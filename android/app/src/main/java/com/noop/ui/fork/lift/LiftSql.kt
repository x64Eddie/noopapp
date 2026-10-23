package com.noop.ui.fork.lift

import android.database.Cursor
import com.noop.data.WhoopDatabase

/**
 * The narrow SQL seam the Lift Log store runs through.
 *
 * Fork-only: upstream ships the five lift tables (`data/LiftEntities.kt`) with no Android DAO. Adding a
 * Room `@Dao` would need an abstract accessor in `WhoopDatabase`, an upstream file, so the store issues
 * plain SQL against the same database instead. Every identifier is a literal in [LiftStore]; every value
 * is a bind argument. The seam also lets the store run against plain SQLite in JVM tests.
 */
interface LiftSql {
    fun <T> query(sql: String, args: Array<Any?>, map: (LiftRow) -> T): List<T>
    fun exec(sql: String, args: Array<Any?>)
    fun <T> transaction(block: () -> T): T
}

/** Column access by index; null-aware so nullable columns stay null rather than becoming 0. */
interface LiftRow {
    fun string(index: Int): String?
    fun long(index: Int): Long?
    fun double(index: Int): Double?
}

/** Production adapter over Room's open helper. Call from a background dispatcher. */
class RoomLiftSql(private val db: WhoopDatabase) : LiftSql {
    private val sqlite get() = db.openHelper.writableDatabase

    override fun <T> query(sql: String, args: Array<Any?>, map: (LiftRow) -> T): List<T> =
        sqlite.query(sql, args).use { cursor ->
            val row = CursorRow(cursor)
            buildList { while (cursor.moveToNext()) add(map(row)) }
        }

    override fun exec(sql: String, args: Array<Any?>) {
        sqlite.execSQL(sql, args)
    }

    override fun <T> transaction(block: () -> T): T {
        val w = sqlite
        w.beginTransaction()
        try {
            val result = block()
            w.setTransactionSuccessful()
            return result
        } finally {
            w.endTransaction()
            // Room-backed screens (Workouts) observe through the invalidation tracker; tell it.
            db.invalidationTracker.refreshVersionsAsync()
        }
    }

    private class CursorRow(private val c: Cursor) : LiftRow {
        override fun string(index: Int): String? = if (c.isNull(index)) null else c.getString(index)
        override fun long(index: Int): Long? = if (c.isNull(index)) null else c.getLong(index)
        override fun double(index: Int): Double? = if (c.isNull(index)) null else c.getDouble(index)
    }
}
