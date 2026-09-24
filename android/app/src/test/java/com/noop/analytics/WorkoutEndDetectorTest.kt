package com.noop.analytics

import com.noop.analytics.WorkoutEndDetector.Minute
import com.noop.analytics.WorkoutEndDetector.Reason
import com.noop.analytics.WorkoutEndDetector.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutEndDetectorTest {
    // resting 60, max 190 → active line = 60 + 0.3 × 130 = 99 bpm.
    private val resting = 60.0
    private val max = 190.0
    private val start = 1_700_000_000L - 1_700_000_000L % 60

    /** [active] minutes at 140 bpm, then [quiet] at 72 bpm, then [absent] with no HR. */
    private fun session(active: Int, quiet: Int = 0, absent: Int = 0, movedDuringQuiet: Double? = null): List<Minute> {
        val out = ArrayList<Minute>()
        var t = start
        repeat(active) { out += Minute(t, 140.0, movedDuringQuiet?.let { 400.0 }); t += 60 }
        repeat(quiet) { out += Minute(t, 72.0, movedDuringQuiet); t += 60 }
        repeat(absent) { out += Minute(t, null, null); t += 60 }
        return out
    }

    private fun eval(minutes: List<Minute>) =
        WorkoutEndDetector.evaluate(start, start + minutes.size * 60L, minutes, resting, max)

    @Test fun activeThreshold_is30PercentOfReserve() {
        assertEquals(99.0, WorkoutEndDetector.activeThreshold(resting, max), 1e-9)
    }

    @Test fun ongoingEffort_continues() {
        assertEquals(Verdict.Continue, eval(session(active = 90)))
    }

    @Test fun shortRest_betweenSets_continues() {
        assertEquals(Verdict.Continue, eval(session(active = 30, quiet = 5) + session(active = 1).map {
            it.copy(startSec = start + 35 * 60)
        }))
        assertEquals(Verdict.Continue, eval(session(active = 30, quiet = 9)))
    }

    @Test fun tenQuietMinutes_prompts_withEndAtFirstQuietMinute() {
        val v = eval(session(active = 30, quiet = 10))
        assertEquals(Verdict.Prompt(endSec = start + 30 * 60, idleMinutes = 10), v)
    }

    @Test fun fortyFiveQuietMinutes_autoEnds() {
        val v = eval(session(active = 40, quiet = 45))
        assertTrue(v is Verdict.AutoEnd)
        v as Verdict.AutoEnd
        assertEquals(start + 40 * 60, v.endSec)
        assertEquals(Reason.QUIET, v.reason)
    }

    @Test fun forgottenForTwentyHours_autoEndsTrimmedToRealEnd() {
        val v = eval(session(active = 50, quiet = 20 * 60))
        assertEquals(start + 50 * 60, (v as Verdict.AutoEnd).endSec)
    }

    @Test fun longSession_autoEndsSooner() {
        // 12 h of genuine effort then 20 quiet minutes: ends under the long-session rule, not the 45-min one.
        val v = eval(session(active = 12 * 60, quiet = 20))
        assertEquals(Reason.LONG_SESSION, (v as Verdict.AutoEnd).reason)
    }

    @Test fun missingHr_onlyPrompts_untilThreeHours() {
        assertTrue(eval(session(active = 30, absent = 60)) is Verdict.Prompt)
        val v = eval(session(active = 30, absent = 180))
        assertEquals(Reason.NO_SIGNAL, (v as Verdict.AutoEnd).reason)
    }

    @Test fun gpsMovement_keepsLowHrSessionAlive() {
        // Easy walk: HR under the line but covering ground every minute — still the workout.
        assertEquals(Verdict.Continue, eval(session(active = 10, quiet = 60, movedDuringQuiet = 80.0)))
        // Same HR standing still: the session is over.
        assertTrue(eval(session(active = 10, quiet = 60, movedDuringQuiet = 5.0)) is Verdict.AutoEnd)
    }

    @Test fun neverEndsInsideTheFirstTenMinutes() {
        val quietStart = (0 until 9).map { Minute(start + it * 60L, 70.0) }
        assertEquals(Verdict.Continue, WorkoutEndDetector.evaluate(start, start + 9 * 60, quietStart, resting, max))
    }

    @Test fun uncollectedMinutes_countAsAbsent() {
        // Collector died after 20 active minutes; relaunch 4 h later with no summaries in between.
        val v = WorkoutEndDetector.evaluate(start, start + (20 + 240) * 60L, session(active = 20), resting, max)
        assertEquals(start + 20 * 60, (v as Verdict.AutoEnd).endSec)
        assertEquals(Reason.NO_SIGNAL, v.reason)
    }

    @Test fun noActiveMinuteEver_onlyPrompts() {
        // Started by mistake and never exercised: nothing to trim to, so never close it silently.
        assertTrue(eval(session(active = 0, quiet = 120)) is Verdict.Prompt)
        assertTrue(WorkoutEndDetector.evaluate(start, start + 5 * 3600L, emptyList(), resting, max) is Verdict.Prompt)
    }
}
