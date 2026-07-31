package com.noop.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.data.HrSample
import com.noop.data.RrInterval
import com.noop.data.WhoopDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Phase 1 of the WHOOP -> open-wearables bridge (see
 * `docs/specs/2026-07-04-whoop-android-collector-design.md`). Reads decoded HR + R-R interval
 * rows out of the local Room store (already collected by the BLE pipeline, unchanged) since a
 * per-stream cursor, batches them into the shape open-wearables' generic mobile-SDK sync endpoint
 * expects (`POST {base}/api/v1/sdk/users/{userId}/sync`, provider "whoop"), and POSTs them over
 * Tailscale. Mirrors [com.noop.ui.BackupSync]'s WorkManager scheduling shape.
 *
 * ONLY raw measured streams are synced (heart_rate, rr_interval) — never NOOP's own on-device
 * derived scores (recovery/strain/sleep stages). open-wearables' own `algorithms/` layer computes
 * those independently from the raw data, so re-uploading NOOP's derived numbers would just create
 * two disagreeing sources of truth for the same metric.
 *
 * Idempotent by design: the backend upserts on (data_source, series_type, timestamp), so
 * resending an overlapping window (which the cursor deliberately does at each boundary, see
 * [SyncPrefs]) is a no-op server-side rather than a duplicate.
 */
object WhoopSync {

    private const val WORK_PERIODIC = "noop_whoop_sync_periodic"
    private const val WORK_MANUAL = "noop_whoop_sync_manual"

    /** Samples read per stream per HTTP request. Keeps request bodies + retry cost bounded. */
    private const val BATCH_SIZE = 500

    /** Safety cap on batches drained in one worker run, so a large first backfill can't run forever
     *  in a single execution — remaining backlog is picked up by the next periodic/manual run. */
    private const val MAX_BATCHES_PER_RUN = 20

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ── Scheduling ───────────────────────────────────────────────────────────

    /** (Re)schedule (or cancel) the periodic sync from the persisted enable flag. Safe to call
     *  repeatedly (KEEP policy) so re-enabling or a reboot doesn't stack duplicate jobs. */
    fun reschedule(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!SyncPrefs.enabled(context) || !SyncKeyStore.isConfigured(context)) {
            wm.cancelUniqueWork(WORK_PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<WhoopSyncWorker>(
            SyncPrefs.intervalMinutes(context).toLong(), TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Manual "Sync now" — runs once, immediately, regardless of the periodic schedule. */
    fun syncNow(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<WhoopSyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_MANUAL, ExistingWorkPolicy.KEEP, req)
    }

    // ── The sync itself ─────────────────────────────────────────────────────

    /** Result of one [runOnce] call, surfaced to the settings screen's "last sync" status line. */
    data class SyncResult(
        val success: Boolean,
        val hrSent: Int,
        val rrSent: Int,
        val message: String,
    )

    /** Drain up to [MAX_BATCHES_PER_RUN] batches of unsynced HR + R-R rows. Returns success only
     *  if every batch it attempted was accepted (a mid-run failure stops the drain but keeps the
     *  cursor at the last successfully-sent point, so the next run resumes cleanly). */
    suspend fun runOnce(context: Context): SyncResult = withContext(Dispatchers.IO) {
        if (!SyncKeyStore.isConfigured(context)) {
            return@withContext SyncResult(false, 0, 0, "Not configured: set backend URL, user id, and API key first.")
        }
        val dao = WhoopDatabase.get(context).whoopDao()
        val deviceId = dao.activeDeviceId()
            ?: return@withContext SyncResult(false, 0, 0, "No paired WHOOP strap found.")

        var totalHr = 0
        var totalRr = 0

        for (batch in 1..MAX_BATCHES_PER_RUN) {
            val hrFrom = SyncPrefs.hrCursor(context)
            val rrFrom = SyncPrefs.rrCursor(context)
            val now = System.currentTimeMillis() / 1000

            val hrRows = dao.rawHrSamples(deviceId, hrFrom, now, BATCH_SIZE)
            val rrRows = dao.rrIntervals(deviceId, rrFrom, now, BATCH_SIZE)

            if (hrRows.isEmpty() && rrRows.isEmpty()) {
                return@withContext SyncResult(true, totalHr, totalRr, "Up to date.")
            }

            val payload = buildPayload(deviceId, hrRows, rrRows)
            val ok = post(context, payload)
            if (!ok) {
                return@withContext SyncResult(
                    totalHr > 0 || totalRr > 0, totalHr, totalRr,
                    "Sync failed on batch $batch (${hrRows.size} HR, ${rrRows.size} RR pending retry).",
                )
            }

            hrRows.maxOfOrNull { it.ts }?.let { SyncPrefs.setHrCursor(context, it) }
            rrRows.maxOfOrNull { it.ts }?.let { SyncPrefs.setRrCursor(context, it) }
            totalHr += hrRows.size
            totalRr += rrRows.size

            // Fewer rows than the batch size means this stream is drained for now.
            if (hrRows.size < BATCH_SIZE && rrRows.size < BATCH_SIZE) {
                SyncPrefs.setLastSyncMs(context, System.currentTimeMillis())
                return@withContext SyncResult(true, totalHr, totalRr, "Synced $totalHr HR + $totalRr RR samples.")
            }
        }

        SyncPrefs.setLastSyncMs(context, System.currentTimeMillis())
        SyncResult(true, totalHr, totalRr, "Synced $totalHr HR + $totalRr RR samples (backlog remains, will continue next run).")
    }

    private fun buildPayload(deviceId: String, hrRows: List<HrSample>, rrRows: List<RrInterval>): String {
        val source = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceManufacturer", "WHOOP")
            put("deviceType", "fitness_band")
        }

        val records = JSONArray()
        for (row in hrRows) {
            val ts = Instant.ofEpochSecond(row.ts).toString()
            records.put(
                JSONObject().apply {
                    put("type", "HEART_RATE")
                    put("startDate", ts)
                    put("endDate", ts)
                    put("value", row.bpm)
                    put("unit", "bpm")
                    put("source", source)
                },
            )
        }
        for (row in rrRows) {
            val ts = Instant.ofEpochSecond(row.ts).toString()
            records.put(
                JSONObject().apply {
                    put("type", "RR_INTERVAL")
                    put("startDate", ts)
                    put("endDate", ts)
                    put("value", row.rrMs)
                    put("unit", "ms")
                    put("source", source)
                },
            )
        }

        val data = JSONObject().apply {
            put("records", records)
            put("sleep", JSONArray())
            put("workouts", JSONArray())
        }

        return JSONObject().apply {
            put("provider", "whoop")
            put("sdkVersion", "noop-sync-0.1.0")
            put("syncTimestamp", Instant.now().toString())
            put("data", data)
        }.toString()
    }

    private fun post(context: Context, jsonBody: String): Boolean {
        val baseUrl = SyncKeyStore.readBaseUrl(context)
        val userId = SyncKeyStore.readUserId(context)
        val apiKey = SyncKeyStore.readApiKey(context)

        val request = Request.Builder()
            .url("$baseUrl/api/v1/sdk/users/$userId/sync")
            .addHeader("X-Open-Wearables-API-Key", apiKey)
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()

        return runCatching {
            http.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}

/** The worker WorkManager actually invokes, both on the periodic schedule and for manual syncs. */
class WhoopSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val result = WhoopSync.runOnce(applicationContext)
        return if (result.success) Result.success() else Result.retry()
    }
}

/**
 * Cursor + schedule state for the WHOOP sync. Cursors are the last-successfully-sent unix-second
 * timestamp PER STREAM; the next read is inclusive of that value (not +1), so a batch boundary is
 * deliberately re-sent and deduplicated server-side rather than risking a skipped sample from an
 * off-by-one. Non-secret (unlike [SyncKeyStore]) — plain SharedPreferences is fine here.
 */
object SyncPrefs {
    private const val FILE = "whoop_sync_prefs"
    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean("enabled", on).apply()

    fun intervalMinutes(c: Context): Int = p(c).getInt("interval_min", 30).coerceIn(15, 24 * 60)
    fun setIntervalMinutes(c: Context, minutes: Int) =
        p(c).edit().putInt("interval_min", minutes.coerceIn(15, 24 * 60)).apply()

    fun hrCursor(c: Context): Long = p(c).getLong("hr_cursor", 0L)
    fun setHrCursor(c: Context, ts: Long) = p(c).edit().putLong("hr_cursor", ts).apply()

    fun rrCursor(c: Context): Long = p(c).getLong("rr_cursor", 0L)
    fun setRrCursor(c: Context, ts: Long) = p(c).edit().putLong("rr_cursor", ts).apply()

    fun lastSyncMs(c: Context): Long = p(c).getLong("last_sync_ms", 0L)
    fun setLastSyncMs(c: Context, ms: Long) = p(c).edit().putLong("last_sync_ms", ms).apply()

    /** Reset both cursors to 0 so the next sync re-walks all local history (idempotent upserts on
     *  the backend make this safe — just slower). Used by a "Resync all" action if ever needed. */
    fun resetCursors(c: Context) {
        p(c).edit().putLong("hr_cursor", 0L).putLong("rr_cursor", 0L).apply()
    }
}
