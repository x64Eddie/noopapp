package com.noop.screen

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * What the phone's screen did, never what was on it. One of these is a timestamp and an event type, and
 * nothing else: no app name, no content, no location.
 *
 * Why it exists: a wrist sensor cannot tell lying awake in bed from sleeping, so a night spent reading on a
 * phone is staged as sleep from the moment the wrist goes still. A screen that was on is direct evidence the
 * wearer was awake. This is INSTRUMENTATION: it is stored and exported beside the strap streams so a night can
 * be compared against it, and no scoring reads it yet.
 */
enum class ScreenEventKind(val code: Int, val label: String) {
    SCREEN_ON(1, "PHONE_SCREEN_ON"),
    SCREEN_OFF(2, "PHONE_SCREEN_OFF"),

    /** The keyguard was dismissed, i.e. the wearer actively used the phone rather than the screen waking itself. */
    UNLOCK(3, "PHONE_UNLOCK"),

    /** Written once each time logging starts: the screen state before the first broadcast is otherwise unknown. */
    START_ON(4, "PHONE_LOG_START_SCREEN_ON"),
    START_OFF(5, "PHONE_LOG_START_SCREEN_OFF");

    companion object {
        fun fromAction(action: String?): ScreenEventKind? = when (action) {
            Intent.ACTION_SCREEN_ON -> SCREEN_ON
            Intent.ACTION_SCREEN_OFF -> SCREEN_OFF
            Intent.ACTION_USER_PRESENT -> UNLOCK
            else -> null
        }

        fun fromCode(code: Int): ScreenEventKind? = values().firstOrNull { it.code == code }
    }
}

data class ScreenEvent(val ts: Long, val kind: ScreenEventKind)

/**
 * Local store for [ScreenEvent]s. A separate small database file rather than a table in the app's Room
 * database: Room's versioned migrations are shared with upstream, and a fork-only table there is a standing
 * rebase conflict.
 */
class ScreenEventStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE screen_event (ts INTEGER NOT NULL, kind INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_screen_event_ts ON screen_event (ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(event: ScreenEvent) {
        val cv = ContentValues().apply { put("ts", event.ts); put("kind", event.kind.code) }
        writableDatabase.insert("screen_event", null, cv)
    }

    /** Events with [from] <= ts <= [to], oldest first. A row with an unknown kind code is skipped. */
    fun between(from: Long, to: Long): List<ScreenEvent> {
        val out = ArrayList<ScreenEvent>()
        readableDatabase.query(
            "screen_event", arrayOf("ts", "kind"), "ts >= ? AND ts <= ?",
            arrayOf(from.toString(), to.toString()), null, null, "ts ASC, rowid ASC",
        ).use { c ->
            while (c.moveToNext()) {
                val kind = ScreenEventKind.fromCode(c.getInt(1)) ?: continue
                out += ScreenEvent(c.getLong(0), kind)
            }
        }
        return out
    }

    /** Delete everything older than [cutoff] (exclusive). Returns the number of rows removed. */
    fun pruneBefore(cutoff: Long): Int =
        writableDatabase.delete("screen_event", "ts < ?", arrayOf(cutoff.toString()))

    companion object {
        const val DB_NAME = "noop_fork.db"
        const val DB_VERSION = 1
    }
}

object ScreenStateLog {

    /** Events older than this are dropped when logging starts. */
    const val RETENTION_DAYS = 120

    @Volatile private var store: ScreenEventStore? = null
    private var receiver: BroadcastReceiver? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "screen-state-log").apply { isDaemon = true } }

    /** The cutoff (unix seconds) before which events are pruned. Pure so it is tested directly. */
    internal fun retentionCutoff(nowSeconds: Long, retentionDays: Int = RETENTION_DAYS): Long =
        nowSeconds - retentionDays * 86_400L

    /**
     * Start logging for the life of the process. Idempotent. A dynamic receiver is required, since the
     * screen broadcasts cannot be declared in the manifest, so this only sees events while the process is
     * alive; the connection service keeps it alive while a strap is connected, and a gap is a gap, not a
     * guess. Never throws: a logging problem must not reach the strap path.
     */
    @Synchronized
    fun start(context: Context) {
        if (receiver != null) return
        runCatching {
            val app = context.applicationContext
            val s = ScreenEventStore(app)
            store = s
            val now = System.currentTimeMillis() / 1000
            val interactive = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive
            writer.execute {
                runCatching {
                    s.pruneBefore(retentionCutoff(now))
                    when (interactive) {
                        true -> s.insert(ScreenEvent(now, ScreenEventKind.START_ON))
                        false -> s.insert(ScreenEvent(now, ScreenEventKind.START_OFF))
                        null -> Unit
                    }
                }
            }
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val kind = ScreenEventKind.fromAction(intent?.action) ?: return
                    val ts = System.currentTimeMillis() / 1000
                    writer.execute { runCatching { s.insert(ScreenEvent(ts, kind)) } }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }
    }

    /** Events in [from, to] (unix seconds), oldest first; empty when logging never started or failed. */
    fun eventsBetween(from: Long, to: Long): List<ScreenEvent> =
        runCatching { store?.between(from, to) }.getOrNull().orEmpty()
}
