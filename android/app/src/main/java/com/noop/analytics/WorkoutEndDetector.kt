package com.noop.analytics

/**
 * WorkoutEndDetector — decides whether a MANUALLY-STARTED workout that is still running has actually
 * finished, so a session left open by mistake (the "20-hour workout") can be prompted or closed.
 *
 * Pure and Context-free: callers fold live HR (and, for GPS sessions, route distance) into per-minute
 * [Minute] summaries and ask [evaluate] once a minute. Nothing here posts, saves or stops anything.
 *
 * A minute is QUIET when its mean HR sits below the same "active" line the calorie model uses —
 * resting + [Calories.activeHRRFraction] (30 %) of heart-rate reserve, the lower bound of ACSM's
 * light-intensity band (Garber et al. 2011, MSSE 43(7):1334–59, Table 4) — AND, for a GPS session, the
 * route moved less than [STILL_METERS_PER_MIN] in it. Movement overrides HR on purpose: coasting a descent
 * or an easy walk is still the workout. A minute with NO HR at all (strap off-wrist or out of range) is
 * ABSENT, not quiet: the strap may be buffering data that a later sync fills in.
 *
 * Verdicts, over the TRAILING run of non-active minutes (quiet or absent):
 *  - [Verdict.Prompt]  after [PROMPT_AFTER_MIN] minutes. Rest intervals in strength training run 2–5 min
 *    (ACSM 2009 progression stand, MSSE 41(3):687–708), so 10 min clears a long set rest with margin.
 *  - [Verdict.AutoEnd] after [AUTO_END_AFTER_MIN] QUIET minutes — three quarters of an hour at a resting
 *    heart rate is not a workout — or, past [LONG_SESSION_HOURS], after [LONG_SESSION_QUIET_MIN].
 *    A run made only of ABSENT minutes never auto-ends before [ABSENT_AUTO_END_MIN]; a gap is weaker
 *    evidence than a measured resting heart rate. A session with no active minute at all is only ever
 *    prompted: auto-ending it would trim it to its own start.
 *
 * The suggested end is the first second of the trailing non-active run, so the saved session is trimmed to
 * when activity stopped instead of carrying hours of sitting into its averages, Effort and calories.
 */
object WorkoutEndDetector {

    const val PROMPT_AFTER_MIN = 10
    const val AUTO_END_AFTER_MIN = 45
    const val LONG_SESSION_HOURS = 12
    const val LONG_SESSION_QUIET_MIN = 20
    const val ABSENT_AUTO_END_MIN = 180

    /** Below this many route metres in a minute a GPS session counts as standing still (~0.8 m/s would be
     *  a slow walk; 50 m/min is about a third of that, which also absorbs GPS jitter at rest). */
    const val STILL_METERS_PER_MIN = 50.0

    /** One minute of the running session. [startSec] is the minute's first unix second. [meanBpm] is null
     *  when no HR arrived in the minute. [movedM] is the route distance added in the minute, null when the
     *  session has no GPS. */
    data class Minute(val startSec: Long, val meanBpm: Double?, val movedM: Double? = null)

    sealed interface Verdict {
        data object Continue : Verdict
        data class Prompt(val endSec: Long, val idleMinutes: Int) : Verdict
        data class AutoEnd(val endSec: Long, val idleMinutes: Int, val reason: Reason) : Verdict
    }

    enum class Reason { QUIET, LONG_SESSION, NO_SIGNAL }

    /** The HR below which a minute is not exercise, for this wearer. */
    fun activeThreshold(restingHr: Double, maxHr: Double): Double =
        restingHr + Calories.activeHRRFraction * (maxHr - restingHr).coerceAtLeast(0.0)

    /**
     * Evaluate the session that started at [startSec], given its per-minute summaries in time order.
     * Minutes before [startSec] are ignored. Returns [Verdict.Continue] until the trailing idle run is long
     * enough, and never ends a session inside its own first [PROMPT_AFTER_MIN] minutes.
     */
    fun evaluate(
        startSec: Long,
        nowSec: Long,
        minutes: List<Minute>,
        restingHr: Double,
        maxHr: Double,
    ): Verdict {
        if (nowSec - startSec < PROMPT_AFTER_MIN * 60L) return Verdict.Continue
        val threshold = activeThreshold(restingHr, maxHr)
        val inSession = minutes.filter { it.startSec >= startSec && it.startSec <= nowSec }

        // Walk back from the newest minute while it is not active. Minutes with no summary at all (the
        // collector was not running) count as absent, so the run is measured in wall-clock minutes.
        var idleStartSec = nowSec - nowSec % 60
        var quiet = 0
        var absent = 0
        val bySec = inSession.associateBy { it.startSec - it.startSec % 60 }
        var cursor = idleStartSec - 60
        var sawActive = false
        while (cursor >= startSec - startSec % 60) {
            val m = bySec[cursor]
            val state = classify(m, threshold)
            if (state == State.ACTIVE) { sawActive = true; break }
            if (state == State.QUIET) quiet++ else absent++
            idleStartSec = cursor
            cursor -= 60
        }
        val idle = quiet + absent
        if (idle < PROMPT_AFTER_MIN) return Verdict.Continue
        val endSec = maxOf(idleStartSec, startSec)
        val elapsedHours = (nowSec - startSec) / 3600.0

        // No active minute anywhere in the session: there is no real end to trim to, and auto-ending would
        // save a zero-length workout. Ask instead.
        if (!sawActive) return Verdict.Prompt(endSec, idle)
        return when {
            quiet >= AUTO_END_AFTER_MIN -> Verdict.AutoEnd(endSec, idle, Reason.QUIET)
            elapsedHours >= LONG_SESSION_HOURS && quiet >= LONG_SESSION_QUIET_MIN ->
                Verdict.AutoEnd(endSec, idle, Reason.LONG_SESSION)
            absent >= ABSENT_AUTO_END_MIN -> Verdict.AutoEnd(endSec, idle, Reason.NO_SIGNAL)
            else -> Verdict.Prompt(endSec, idle)
        }
    }

    private enum class State { ACTIVE, QUIET, ABSENT }

    private fun classify(m: Minute?, threshold: Double): State {
        if (m == null) return State.ABSENT
        val moving = m.movedM != null && m.movedM >= STILL_METERS_PER_MIN
        if (moving) return State.ACTIVE
        val bpm = m.meanBpm ?: return State.ABSENT
        return if (bpm >= threshold) State.ACTIVE else State.QUIET
    }
}
