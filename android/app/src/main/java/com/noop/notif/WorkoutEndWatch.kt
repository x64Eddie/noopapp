package com.noop.notif

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noop.R
import com.noop.analytics.RouteMath
import com.noop.analytics.WorkoutEndDetector
import com.noop.data.HrSample
import com.noop.location.GpsSession
import com.noop.ui.NoopPrefs
import com.noop.ui.appLaunchIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date
import java.util.TreeMap

/**
 * Watches a running MANUAL workout for the moment it actually ended, and prompts or closes it
 * ([WorkoutEndDetector] holds the rules). Process-level on purpose: the foreground service feeds it live HR
 * while the UI is gone, and the ViewModel feeds it too while alive — the same second is only counted once.
 *
 * It never saves a workout itself; the ViewModel owns that. It records a [PendingEnd] (persisted, so it
 * survives a process death) that the ViewModel finalizes as soon as it is alive. For a GPS session it also
 * stops the route right away — the GPS radio is the expensive part of a forgotten session — freezing the
 * track at the detected end so the saved route stops where the workout did.
 *
 * Gated on [NoopPrefs.autoEndWorkouts]. Nothing leaves the phone.
 */
object WorkoutEndWatch {

    private const val PREFS = "noop_workout_end_watch"
    private const val CHANNEL_ID = "noop_workout_end"
    private const val NOTIF_ID = 4221
    private const val TICK_MS = 60_000L
    private const val SNOOZE_MS = 30 * 60_000L
    /** Minutes of history kept; beyond this the detector reads the gap as absent HR, which only matters for
     *  a session that has already run more than a day. */
    private const val MAX_BUCKETS = 36 * 60

    const val ACTION_END = "com.noop.action.WORKOUT_END"
    const val ACTION_KEEP = "com.noop.action.WORKOUT_KEEP"
    const val EXTRA_END_MS = "endMs"

    data class Session(val startMs: Long, val gps: Boolean, val restingHr: Double, val maxHr: Double)

    /** Finish the running session at [endMs]. [polyline] is the GPS route frozen when the end was requested
     *  (null for a non-GPS session). [auto] marks an end the detector made on its own. */
    data class PendingEnd(val endMs: Long, val auto: Boolean, val polyline: String?)

    private class Bucket(val distanceAtStart: Double, val trackSizeAtStart: Int) {
        var sum = 0L
        var n = 0
    }

    private val lock = Any()
    private val buckets = TreeMap<Long, Bucket>()
    private var session: Session? = null
    private var loaded = false
    private var lastTickMs = 0L
    private var lastIngestSec = -1L
    private var promptedForEndSec = -1L

    private val _pendingEnd = MutableStateFlow<PendingEnd?>(null)
    /** Collected by the ViewModel, which finalizes and then calls [clear]. */
    val pendingEnd: StateFlow<PendingEnd?> = _pendingEnd.asStateFlow()

    // ── Session lifecycle (called by the ViewModel) ─────────────────────────

    /** A workout started (or was rehydrated). Idempotent for the same start. */
    fun begin(context: Context, startMs: Long, gps: Boolean, restingHr: Double, maxHr: Double) {
        ensureLoaded(context)
        synchronized(lock) {
            if (session?.startMs == startMs) return
            session = Session(startMs, gps, restingHr, maxHr)
            buckets.clear()
            lastTickMs = 0L
            lastIngestSec = -1L
            promptedForEndSec = -1L
        }
        prefs(context).edit()
            .putLong("startMs", startMs).putBoolean("gps", gps)
            .putFloat("restingHr", restingHr.toFloat()).putFloat("maxHr", maxHr.toFloat())
            .remove("snoozeUntil").remove("pendingEndMs").remove("pendingAuto").remove("pendingPolyline")
            .apply()
    }

    /** Fold already-captured samples in (a rehydrated session), so the detector sees its history. */
    fun seed(samples: List<HrSample>) {
        synchronized(lock) {
            for (s in samples) bucketFor(s.ts).apply { sum += s.bpm; n++ }
            lastIngestSec = maxOf(lastIngestSec, samples.lastOrNull()?.ts ?: -1L)
        }
    }

    /** The workout ended or was discarded by any path: forget it and withdraw any prompt. */
    fun clear(context: Context) {
        synchronized(lock) {
            session = null
            buckets.clear()
            promptedForEndSec = -1L
        }
        _pendingEnd.value = null
        prefs(context).edit().clear().apply()
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }

    /** Route points recorded up to [endMs], for trimming a GPS track the user ended from a prompt. */
    fun trackSizeAt(endMs: Long): Int? = synchronized(lock) {
        buckets.ceilingEntry(endMs / 1000 - (endMs / 1000) % 60)?.value?.trackSizeAtStart
    }

    // ── Feed + evaluate (service and ViewModel) ─────────────────────────────

    /** One live HR reading. Cheap; repeated readings in the same second count once. */
    fun ingestHr(context: Context, bpm: Int, nowMs: Long) {
        if (bpm <= 0) return
        ensureLoaded(context)
        synchronized(lock) {
            if (session == null) return
            val sec = nowMs / 1000
            if (sec == lastIngestSec) return
            lastIngestSec = sec
            bucketFor(sec).apply { sum += bpm; n++ }
        }
    }

    /** Evaluate at most once a minute. [force] skips the throttle (a rehydrate wants an answer now). */
    fun tick(context: Context, nowMs: Long, force: Boolean = false) {
        ensureLoaded(context)
        if (_pendingEnd.value != null) return
        if (!NoopPrefs.autoEndWorkouts(context)) return
        val s: Session
        val minutes: List<WorkoutEndDetector.Minute>
        synchronized(lock) {
            s = session ?: return
            if (!force && nowMs - lastTickMs < TICK_MS) return
            lastTickMs = nowMs
            bucketFor(nowMs / 1000)
            minutes = summarize(s)
        }
        if (nowMs < prefs(context).getLong("snoozeUntil", 0L)) return

        when (val v = WorkoutEndDetector.evaluate(s.startMs / 1000, nowMs / 1000, minutes, s.restingHr, s.maxHr)) {
            WorkoutEndDetector.Verdict.Continue -> if (promptedForEndSec >= 0) {
                // Activity picked back up after a prompt: the question no longer applies.
                promptedForEndSec = -1L
                runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
            }
            is WorkoutEndDetector.Verdict.Prompt -> if (promptedForEndSec != v.endSec) {
                promptedForEndSec = v.endSec
                postPrompt(context, v.endSec * 1000, v.idleMinutes)
            }
            is WorkoutEndDetector.Verdict.AutoEnd -> requestEnd(context, v.endSec * 1000, auto = true)
        }
    }

    /** Record an end at [endMs]; stop GPS now; the ViewModel saves when it is alive. */
    fun requestEnd(context: Context, endMs: Long, auto: Boolean) {
        ensureLoaded(context)
        val s = synchronized(lock) { session } ?: return
        val polyline = if (s.gps && GpsSession.state.value.active) {
            val full = GpsSession.state.value.track
            val keep = trackSizeAt(endMs)?.coerceAtMost(full.size) ?: full.size
            GpsSession.stop()
            RouteMath.encode(full.take(keep))
        } else null
        val p = PendingEnd(maxOf(endMs, s.startMs), auto, polyline)
        prefs(context).edit()
            .putLong("pendingEndMs", p.endMs).putBoolean("pendingAuto", auto)
            .putString("pendingPolyline", polyline)
            .apply()
        _pendingEnd.value = p
        postEnded(context, p.endMs, auto)
    }

    /** The "Keep going" action: stay quiet for half an hour. */
    fun snooze(context: Context, nowMs: Long) {
        prefs(context).edit().putLong("snoozeUntil", nowMs + SNOOZE_MS).apply()
        promptedForEndSec = -1L
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Restore the session + any unfinished end after a process restart. */
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val p = prefs(context)
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val startMs = p.getLong("startMs", 0L)
            if (startMs > 0 && session == null) {
                session = Session(
                    startMs, p.getBoolean("gps", false),
                    p.getFloat("restingHr", 60f).toDouble(), p.getFloat("maxHr", 190f).toDouble(),
                )
            }
        }
        val endMs = p.getLong("pendingEndMs", 0L)
        if (endMs > 0 && _pendingEnd.value == null) {
            _pendingEnd.value = PendingEnd(endMs, p.getBoolean("pendingAuto", false), p.getString("pendingPolyline", null))
        }
    }

    /** Caller holds [lock]. */
    private fun bucketFor(sec: Long): Bucket {
        val minute = sec - sec % 60
        buckets[minute]?.let { return it }
        val g = GpsSession.state.value
        val b = Bucket(if (g.active) g.distanceM else 0.0, if (g.active) g.track.size else 0)
        buckets[minute] = b
        while (buckets.size > MAX_BUCKETS) buckets.pollFirstEntry()
        return b
    }

    /** Caller holds [lock]. Movement for a minute = route distance at the next bucket minus this one. */
    private fun summarize(s: Session): List<WorkoutEndDetector.Minute> {
        val entries = buckets.entries.toList()
        val nowDistance = GpsSession.state.value.let { if (it.active) it.distanceM else null }
        return entries.mapIndexed { i, (minute, b) ->
            val moved = if (!s.gps) null else {
                val next = entries.getOrNull(i + 1)?.value?.distanceAtStart ?: nowDistance
                next?.let { (it - b.distanceAtStart).coerceAtLeast(0.0) }
            }
            WorkoutEndDetector.Minute(minute, if (b.n > 0) b.sum.toDouble() / b.n else null, moved)
        }
    }

    private fun timeLabel(context: Context, ms: Long): String =
        android.text.format.DateFormat.getTimeFormat(context).format(Date(ms))

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() + runCatching
    private fun postPrompt(context: Context, endMs: Long, idleMinutes: Int) {
        val at = timeLabel(context, endMs)
        post(context) { b ->
            b.setContentTitle("Still working out?")
                .setContentText("Your heart rate has been at rest for $idleMinutes min, since $at.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Your heart rate has been at rest for $idleMinutes min, since $at. End the workout there, " +
                        "or keep it running. If it stays at rest, NOOP ends it for you after " +
                        "${WorkoutEndDetector.AUTO_END_AFTER_MIN} min.",
                ))
                .addAction(0, "End at $at", actionIntent(context, ACTION_END, endMs, 1))
                .addAction(0, "Keep going", actionIntent(context, ACTION_KEEP, endMs, 2))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
        }
    }

    private fun postEnded(context: Context, endMs: Long, auto: Boolean) {
        val at = timeLabel(context, endMs)
        post(context) { b ->
            b.setContentTitle(if (auto) "Workout ended automatically" else "Workout ended")
                .setContentText(
                    if (auto) "Your heart rate settled back to rest at $at, so the workout was closed there. Tap to review it."
                    else "Saved as ending at $at. Tap to review it.",
                )
                .setStyle(NotificationCompat.BigTextStyle())
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, build: (NotificationCompat.Builder) -> Unit) {
        runCatching {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context, 21, appLaunchIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentIntent(open)
                .setAutoCancel(true)
            build(b)
            NotificationManagerCompat.from(context).notify(NOTIF_ID, b.build())
        }
    }

    private fun actionIntent(context: Context, action: String, endMs: Long, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, WorkoutEndActionReceiver::class.java).setAction(action).putExtra(EXTRA_END_MS, endMs),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Workout end", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Asks whether a workout you left running has ended, or says it was ended for you."
                },
            )
        }
    }
}

/** Handles the End / Keep going buttons on the workout-end prompt. Not exported. */
class WorkoutEndActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WorkoutEndWatch.ACTION_END -> {
                val endMs = intent.getLongExtra(WorkoutEndWatch.EXTRA_END_MS, System.currentTimeMillis())
                WorkoutEndWatch.requestEnd(context, endMs, auto = false)
            }
            WorkoutEndWatch.ACTION_KEEP -> WorkoutEndWatch.snooze(context, System.currentTimeMillis())
        }
    }
}
