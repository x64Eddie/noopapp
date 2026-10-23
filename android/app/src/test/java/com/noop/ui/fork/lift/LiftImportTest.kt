package com.noop.ui.fork.lift

import com.noop.ingest.LiftingImporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.time.ZoneOffset

class LiftImportTest {
    private lateinit var conn: Connection
    private lateinit var store: LiftStore
    private val utc = ZoneOffset.UTC

    private val liftosaur = """
        {"history":[{"startTime":1760000000000,"endTime":1760003600000,"programName":"GZCLP","entries":[
          {"exercise":{"id":"bench_press"},"unit":"lb",
           "warmupSets":[{"completedReps":5,"weight":{"value":95,"unit":"lb"}}],
           "sets":[{"completedReps":5,"completedRpe":8,"weight":{"value":185,"unit":"lb"},"timestamp":1760000600000},
                   {"completedReps":4,"weight":{"value":185,"unit":"lb"}},
                   {"reps":5,"weight":{"value":185,"unit":"lb"}}]}]},
         {"startTime":1760100000000,"entries":[{"exercise":{"id":"squat"},"sets":[{"reps":5}]}]}]}
    """.trimIndent()

    private val hevy = """
        title,start_time,end_time,exercise_title,set_index,set_type,weight_kg,reps,rpe
        Push,"12 Jun 2026, 18:30","12 Jun 2026, 19:30",Bench Press (Barbell),0,warmup,40,10,
        Push,"12 Jun 2026, 18:30","12 Jun 2026, 19:30",Bench Press (Barbell),1,normal,80,8,8.5
        Push,"12 Jun 2026, 18:30","12 Jun 2026, 19:30",Overhead Press,0,normal,50,6,
    """.trimIndent() + "\n"

    @Before
    fun setUp() {
        conn = liftSchemaConnection()
        store = LiftStore(JdbcLiftSql(conn))
    }

    @After
    fun tearDown() = conn.close()

    @Test
    fun liftosaurKeepsWarmupsRpeAndConvertsPounds() {
        val sessions = LiftImport.parse(liftosaur.toByteArray(), utc)
        assertEquals(1, sessions.size) // the second record has no completed set
        val s = sessions.single()
        assertEquals(1_760_000_000L, s.startTs)
        assertEquals(1_760_003_600L, s.endTs)
        assertEquals("GZCLP", s.title)
        assertEquals(listOf(true, false, false), s.sets.map { it.isWarmup })
        assertEquals("Bench Press", s.sets[1].exercise)
        assertEquals(185 * LiftingImporter.LB_TO_KG, s.sets[1].weightKg!!, 1e-9)
        assertEquals(8.0, s.sets[1].rpe!!, 1e-9)
        assertEquals(1_760_000_600L, s.sets[1].endTs)
        assertNull(s.sets[2].rpe)
    }

    @Test
    fun hevyCsvGroupsBySessionAndKeepsSetTypes() {
        val s = LiftImport.parse(hevy.toByteArray(), utc).single()
        assertEquals("Push", s.title)
        assertEquals(3, s.sets.size)
        assertTrue(s.sets[0].isWarmup)
        assertEquals(8.5, s.sets[1].rpe!!, 1e-9)
        assertEquals(3_600L, s.endTs - s.startTs)
        // The importer's own summary of the same bytes still sees the same session.
        assertEquals(s.startTs, LiftingImporter.parse(hevy.toByteArray(), utc).sessions.single().startTs)
    }

    @Test
    fun importLandsOnceAndNeverOverwritesALoggedSession() {
        val sessions = LiftImport.parse(liftosaur.toByteArray(), utc)
        assertEquals(1, LiftImport.persist(store, sessions, now = 1))
        assertEquals(0, LiftImport.persist(store, sessions, now = 2)) // re-import is a no-op
        val imported = store.recentSessions(LiftLog.DEVICE_ID).single()
        val sets = store.sets(imported.id)
        assertEquals(listOf(1, 2, 3), sets.map { it.setIndex }) // numbered within the exercise, warm-up first
        assertEquals(listOf(true, false, false), sets.map { it.isWarmup })
        assertEquals(listOf("Bench Press"), store.exercises(LiftLog.DEVICE_ID).map { it.name })

        // A session the user logged at the same instant wins over a later import of a different file.
        val hevySessions = LiftImport.parse(hevy.toByteArray(), utc)
        val logged = store.startSession(LiftLog.DEVICE_ID, hevySessions.single().startTs)
        assertEquals(0, LiftImport.persist(store, hevySessions, now = 3))
        assertFalse(store.sets(logged.id).isNotEmpty())
    }
}
