package com.noop.ui.fork.lift

import com.noop.data.LiftProgramItemRow
import com.noop.data.LiftProgramRow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Runs [LiftStore] against plain SQLite built from Room's OWN exported schema for the five lift tables,
 * so the store's SQL is checked against the real columns, keys and unique indexes upstream ships.
 */
class LiftStoreTest {
    private lateinit var conn: Connection
    private lateinit var store: LiftStore
    private var ids = 0
    private val dev = LiftLog.DEVICE_ID

    @Before
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val dir = System.getProperty("room.schemaLocation") ?: error("room.schemaLocation not set")
        val schema = File(dir, "com.noop.data.WhoopDatabase").listFiles()!!.maxByOrNull { it.name.removeSuffix(".json").toInt() }!!
        val entities = JSONObject(schema.readText()).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            if (!table.startsWith("lift")) continue
            conn.createStatement().use { it.execute(e.getString("createSql").replace("\${TABLE_NAME}", table)) }
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                conn.createStatement().use {
                    it.execute(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
        }
        store = LiftStore(JdbcLiftSql(conn)) { "id-${++ids}" }
    }

    @After
    fun tearDown() = conn.close()

    @Test
    fun sessionLifecycleOrdersSetsMeasuresRestAndFinishes() {
        val s = store.startSession(dev, 1_000, programName = "Upper A")
        assertEquals(s.id, store.openSession(dev)?.id)
        store.logSet(s, "Bench Press", 60.0, 5, null, isWarmup = true, endTs = 1_100)
        store.logSet(s, "Bench Press", 80.0, 8, 8.0, isWarmup = false, endTs = 1_300)
        store.logSet(s, "Row", 70.0, 10, null, isWarmup = false, endTs = 1_500)
        val sets = store.sets(s.id)
        assertEquals(listOf(0, 1, 2), sets.map { it.ord })
        assertEquals(listOf(1, 2, 1), sets.map { it.setIndex })
        assertEquals(listOf(200, 200, null), sets.map { it.restSec }) // rest measured from the taps
        assertEquals(1_100L, sets[1].startTs)
        assertTrue(sets[0].isWarmup)

        val done = store.finishSession(s.id, 2_000, 7.0, "  ")
        assertEquals(2_000L, done?.endTs)
        assertNull(done?.note) // blank note is not stored
        assertNull(store.openSession(dev))
        assertEquals(listOf(s.id), store.recentSessions(dev).map { it.id })
    }

    @Test
    fun startsInTheSameSecondDoNotCollideOnTheNaturalKey() {
        val a = store.startSession(dev, 5_000)
        val b = store.startSession(dev, 5_000)
        assertEquals(5_001L, b.startTs)
        assertTrue(a.id != b.id)
    }

    @Test
    fun deletingASetKeepsOrderAndIndexesDense() {
        val s = store.startSession(dev, 1_000)
        val first = store.logSet(s, "Squat", 100.0, 5, null, false, 1_100)
        store.logSet(s, "Squat", 100.0, 5, null, false, 1_200)
        store.logSet(s, "Squat", 100.0, 4, null, false, 1_300)
        store.deleteSet(first)
        val sets = store.sets(s.id)
        assertEquals(listOf(0, 1), sets.map { it.ord })
        assertEquals(listOf(1, 2), sets.map { it.setIndex })
    }

    @Test
    fun deletingASessionTakesItsSets() {
        val s = store.startSession(dev, 1_000)
        store.logSet(s, "Squat", 100.0, 5, null, false, 1_100)
        store.deleteSession(s.id)
        assertEquals(0, count("liftSet"))
        assertEquals(0, count("liftSession"))
    }

    @Test
    fun lastPerformanceSkipsTheCurrentAndUnfinishedSessions() {
        val old = store.startSession(dev, 1_000)
        store.logSet(old, "Bench Press", 80.0, 8, null, false, 1_100)
        store.finishSession(old.id, 1_500, null, null)
        val newer = store.startSession(dev, 10_000)
        store.logSet(newer, "Bench Press", 82.5, 6, null, false, 10_100)
        val last = store.lastPerformance(dev, "Bench Press", excludingSessionId = newer.id)
        assertEquals(listOf(80.0), last.map { it.weightKg })
        assertEquals(1, store.allFinishedSets(dev).size) // the open session is not history yet
    }

    @Test
    fun touchExerciseIsUniquePerNameAndTracksRecency() {
        val a = store.touchExercise(dev, " Bench Press ", now = 100)
        val b = store.touchExercise(dev, "Bench Press", now = 200)
        store.touchExercise(dev, "Squat", now = 150)
        assertEquals(a.id, b.id)
        assertEquals(listOf("Bench Press", "Squat"), store.exercises(dev).map { it.name })
    }

    @Test
    fun saveProgramReplacesItemsAndArchiveHidesIt() {
        val p = LiftProgramRow("p1", dev, "Upper A", null, createdAt = 1, updatedAt = 1)
        val item = { ex: String -> LiftProgramItemRow("x", dev, "p1", 0, ex, 3, 8, 10, null, 80.0, 120, null) }
        store.saveProgram(p, listOf(item("Bench Press"), item("Row")))
        store.saveProgram(p.copy(name = "Upper A2", updatedAt = 2), listOf(item("Row")))
        assertEquals(listOf("Row"), store.programItems("p1").map { it.exercise })
        assertEquals("Upper A2", store.programs(dev).single().name)
        store.archiveProgram("p1", now = 3)
        assertTrue(store.programs(dev).isEmpty())
        assertEquals(1, store.programs(dev, includeArchived = true).size)
    }

    private fun count(table: String): Int =
        conn.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM $table").use { it.next(); it.getInt(1) } }
}

/** [LiftSql] over a JDBC connection, for JVM tests only. */
private class JdbcLiftSql(private val c: Connection) : LiftSql {
    override fun <T> query(sql: String, args: Array<Any?>, map: (LiftRow) -> T): List<T> =
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                val row = RsRow(rs)
                buildList { while (rs.next()) add(map(row)) }
            }
        }

    override fun exec(sql: String, args: Array<Any?>) {
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.execute()
        }
    }

    private var depth = 0
    override fun <T> transaction(block: () -> T): T {
        if (depth++ == 0) c.autoCommit = false
        try {
            val r = block()
            if (depth == 1) c.commit()
            return r
        } catch (t: Throwable) {
            if (depth == 1) c.rollback()
            throw t
        } finally {
            if (--depth == 0) c.autoCommit = true
        }
    }

    private class RsRow(private val rs: ResultSet) : LiftRow {
        // JDBC is 1-based; LiftRow is 0-based like Android's Cursor.
        override fun string(index: Int): String? = rs.getString(index + 1)
        override fun long(index: Int): Long? = rs.getLong(index + 1).takeUnless { rs.wasNull() }
        override fun double(index: Int): Double? = rs.getDouble(index + 1).takeUnless { rs.wasNull() }
    }
}
