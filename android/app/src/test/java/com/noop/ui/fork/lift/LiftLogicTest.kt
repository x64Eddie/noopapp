package com.noop.ui.fork.lift

import com.noop.data.LiftProgramItemRow
import com.noop.data.LiftSessionRow
import com.noop.data.LiftSetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiftLogicTest {
    private var n = 0
    private fun set(ex: String, w: Double?, reps: Int?, warm: Boolean = false, session: String = "s") =
        LiftSetEntity("t${n++}", "d", session, n, ex, setIndex = 1, weightKg = w, reps = reps, isWarmup = warm)

    private fun item(low: Int?, high: Int?, sets: Int? = 3, weight: Double? = null) =
        LiftProgramItemRow("i", "d", "p", 0, "Bench Press", sets, low, high, null, weight, 120, null)

    @Test
    fun unitsRoundTripAndFormat() {
        assertEquals(100.0, WeightUnit.KG.fromKg(100.0), 1e-9)
        assertEquals(225.0, WeightUnit.LB.roundDisplay(WeightUnit.LB.fromKg(WeightUnit.LB.toKg(225.0))), 1e-9)
        assertEquals("225", WeightUnit.LB.format(WeightUnit.LB.toKg(225.0)))
        assertEquals("82.5", WeightUnit.KG.format(82.5))
        assertEquals("-", WeightUnit.KG.format(null))
    }

    @Test
    fun platesLoadGreedilyPerSide() {
        val p = LiftLogic.plates(225.0, WeightUnit.LB)
        assertEquals(listOf(45.0, 45.0), p.perSide)
        assertEquals(0.0, p.remainder, 1e-9)
        val k = LiftLogic.plates(102.5, WeightUnit.KG)
        assertEquals(listOf(25.0, 15.0, 1.25), k.perSide)
        assertTrue(LiftLogic.plates(30.0, WeightUnit.LB).belowBar)
        assertEquals(2.0, LiftLogic.plates(137.0, WeightUnit.LB).remainder, 1e-9) // 135 + 2 unreachable
    }

    @Test
    fun doubleProgressionAddsWeightOnlyWhenEveryTopSetHitsTheCeiling() {
        val unit = WeightUnit.KG
        val hit = listOf(set("Bench Press", 80.0, 10), set("Bench Press", 80.0, 10), set("Bench Press", 80.0, 10))
        val s1 = LiftLogic.suggest(item(8, 10), hit, unit)
        assertEquals(LiftLogic.Reason.ADD_WEIGHT, s1.reason)
        assertEquals(82.5, s1.weightKg!!, 1e-9)
        assertEquals(8, s1.reps)

        val miss = listOf(set("Bench Press", 80.0, 10), set("Bench Press", 80.0, 9), set("Bench Press", 80.0, 8))
        val s2 = LiftLogic.suggest(item(8, 10), miss, unit)
        assertEquals(LiftLogic.Reason.ADD_REPS, s2.reason)
        assertEquals(80.0, s2.weightKg!!, 1e-9)
        assertEquals(10, s2.reps)

        // Too few sets at the top weight: hold the weight.
        val short = listOf(set("Bench Press", 80.0, 10))
        assertEquals(LiftLogic.Reason.ADD_REPS, LiftLogic.suggest(item(8, 10, sets = 3), short, unit).reason)
    }

    @Test
    fun noHistoryFallsBackToTheProgramTarget() {
        val s = LiftLogic.suggest(item(5, 5, weight = 100.0), emptyList(), WeightUnit.KG)
        assertEquals(LiftLogic.Reason.NO_HISTORY, s.reason)
        assertEquals(100.0, s.weightKg!!, 1e-9)
        val warmOnly = LiftLogic.suggest(null, listOf(set("Bench Press", 40.0, 10, warm = true)), WeightUnit.KG)
        assertEquals(LiftLogic.Reason.NO_HISTORY, warmOnly.reason)
    }

    @Test
    fun personalBestsUseEstimatedOneRepMaxAcrossSessions() {
        val history = listOf(
            set("Squat", 100.0, 5, session = "a"), // 116.67
            set("Squat", 110.0, 1, session = "b"), // 110
            set("Squat", 90.0, 15, session = "b"), // over the rep ceiling: no estimate
            set("Squat", 140.0, 3, warm = true, session = "b"), // warm-up never counts
            set("Bench Press", 80.0, 8, session = "a"),
        )
        val bests = LiftLogic.personalBests(history)
        assertEquals(listOf("Squat", "Bench Press"), bests.map { it.exercise })
        assertEquals(116.6667, bests[0].e1rmKg, 1e-3)
        assertEquals(5, bests[0].reps)
    }

    @Test
    fun newBestIsFlaggedOnlyAgainstEarlierEstimates() {
        val history = listOf(set("Squat", 100.0, 5))
        assertTrue(LiftLogic.isNewBest(set("Squat", 105.0, 5), history))
        assertFalse(LiftLogic.isNewBest(set("Squat", 100.0, 5), history))
        assertFalse(LiftLogic.isNewBest(set("Deadlift", 200.0, 1), history)) // first ever: nothing to beat
        assertFalse(LiftLogic.isNewBest(set("Squat", 200.0, 1, warm = true), history))
    }

    @Test
    fun finishedSessionBecomesAPairedManualWorkout() {
        val session = LiftSessionRow("s", LiftLog.DEVICE_ID, 1_000, 4_600, LiftLog.SPORT, null, "Upper A", 8.0, null)
        val sets = listOf(set("Bench Press", 60.0, 5, warm = true), set("Bench Press", 80.0, 8), set("Row", 70.0, 10))
        val row = LiftLogic.workoutRow(session, sets, WeightUnit.KG)!!
        assertEquals(Triple(LiftLog.DEVICE_ID, 1_000L, LiftLog.SPORT), Triple(row.deviceId, row.startTs, row.sport))
        assertEquals("manual", row.source)
        assertEquals(3_600.0, row.durationS!!, 1e-9)
        assertNull(row.strain) // rescored from the strap by the manual-save path, never invented
        assertEquals("Upper A · 2 sets · 2 exercises · volume 1340 kg · sRPE 8", row.notes)
        assertNull(LiftLogic.workoutRow(session.copy(endTs = null), sets, WeightUnit.KG))

        val summary = LiftLogic.summarize(session, sets)
        assertEquals(480.0, summary.sessionLoad!!, 1e-9) // 8 x 60 min
    }

    @Test
    fun clockFormatsRest() {
        assertEquals("0:00", LiftLogic.clock(-3))
        assertEquals("2:05", LiftLogic.clock(125))
    }
}
