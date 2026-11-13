package com.noop.ui.fork.lift

import com.noop.analytics.LiftMetrics
import com.noop.data.LiftMuscle
import com.noop.data.LiftProgramItemRow
import com.noop.data.LiftSessionRow
import com.noop.data.LiftSetEntity
import com.noop.data.WorkoutRow
import com.noop.ingest.LiftingImporter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Fork-only Lift Log constants and pure helpers. Nothing here touches Android or the database. */
object LiftLog {
    /** The manual-workout namespace: a session pairs with the manual `workout` row saved for it. */
    const val DEVICE_ID = "my-whoop"

    /** Same sport token the lifting importer files sessions under, so both land in one Workouts bucket. */
    const val SPORT = LiftingImporter.SPORT

    /** `workout.source` for a session logged here. "manual" keeps it editable and rescored from the strap. */
    const val WORKOUT_SOURCE = "manual"

    const val LB_PER_KG = 1.0 / LiftingImporter.LB_TO_KG
}

/** Display unit for weights. Storage is always kilograms. */
enum class WeightUnit(val step: Double, val barWeight: Double, val plates: List<Double>) {
    KG(2.5, 20.0, listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)),
    LB(5.0, 45.0, listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5));

    fun fromKg(kg: Double): Double = if (this == KG) kg else kg * LiftLog.LB_PER_KG
    fun toKg(value: Double): Double = if (this == KG) value else value * LiftingImporter.LB_TO_KG

    /** Rounds a displayed weight to the unit's smallest sensible step (a quarter of [step]). */
    fun roundDisplay(value: Double): Double {
        val q = step / 4.0
        return (value / q).roundToInt() * q
    }

    fun format(kg: Double?): String {
        if (kg == null) return "-"
        val v = roundDisplay(fromKg(kg))
        return if (abs(v - v.roundToInt()) < 1e-9) v.roundToInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
    }
}

object LiftLogic {

    fun metricsRow(s: LiftSetEntity): LiftMetrics.Row = LiftMetrics.Row(
        ord = s.ord,
        exercise = s.exercise,
        isWarmup = s.isWarmup,
        weightKg = s.weightKg,
        reps = s.reps,
        rpe = s.rpe,
        primaryMuscle = s.primaryMuscle?.let { raw -> LiftMuscle.entries.firstOrNull { it.name == raw } },
        secondaryMuscles = LiftMuscle.decodeList(s.secondaryMuscles),
    )

    /** Working sets: not a warm-up, and performed (zero reps means skipped, per [LiftMetrics.isPerformed]). */
    fun workingSets(sets: List<LiftSetEntity>): List<LiftSetEntity> =
        sets.filter { !it.isWarmup && LiftMetrics.isPerformed(it.reps) }

    data class SessionSummary(
        val workingSets: Int,
        val exercises: Int,
        val volumeKg: Double?,
        val durationSec: Int?,
        val sessionLoad: Double?,
    )

    fun summarize(session: LiftSessionRow, sets: List<LiftSetEntity>): SessionSummary {
        val duration = session.endTs?.let { (it - session.startTs).toInt() }?.takeIf { it > 0 }
        return SessionSummary(
            workingSets = workingSets(sets).size,
            exercises = sets.map { it.exercise }.distinct().size,
            volumeKg = LiftMetrics.volumeLoadKg(sets.map(::metricsRow)),
            durationSec = duration,
            sessionLoad = duration?.let { LiftMetrics.sessionLoad(session.sessionRpe, it) },
        )
    }

    /** Best estimated 1RM per exercise across [sets], strongest first. PRs are derived, never stored. */
    data class PersonalBest(val exercise: String, val e1rmKg: Double, val weightKg: Double?, val reps: Int?)

    // Not LiftMetrics.perExercise: that assumes one session's sets (unique `ord`), and this spans history.
    // The per-set estimate is still LiftMetrics' own, so the number cannot disagree with the app's.
    fun personalBests(sets: List<LiftSetEntity>): List<PersonalBest> =
        workingSets(sets)
            .mapNotNull { s -> LiftMetrics.estimatedOneRepMaxKg(s.weightKg, s.reps)?.let { s to it } }
            .groupBy { it.first.exercise }
            .map { (exercise, scored) ->
                val (best, e1) = scored.maxByOrNull { it.second }!!
                PersonalBest(exercise, e1, best.weightKg, best.reps)
            }
            .sortedByDescending { it.e1rmKg }

    /** True when [candidate] beats every earlier estimate for its exercise (a new PR on this set). */
    fun isNewBest(candidate: LiftSetEntity, history: List<LiftSetEntity>): Boolean {
        if (candidate.isWarmup) return false
        val e1 = LiftMetrics.estimatedOneRepMaxKg(candidate.weightKg, candidate.reps) ?: return false
        val previous = history.asSequence()
            .filter { it.exercise == candidate.exercise && !it.isWarmup && it.id != candidate.id }
            .mapNotNull { LiftMetrics.estimatedOneRepMaxKg(it.weightKg, it.reps) }
            .maxOrNull() ?: return false // a first-ever estimate has nothing to beat
        return e1 > previous + 1e-9
    }

    /** What to put on the bar next time: double progression within the program line's rep range. */
    data class Suggestion(val weightKg: Double?, val reps: Int?, val reason: Reason)

    enum class Reason { NO_HISTORY, ADD_WEIGHT, ADD_REPS, REPEAT }

    fun suggest(item: LiftProgramItemRow?, last: List<LiftSetEntity>, unit: WeightUnit): Suggestion {
        val working = workingSets(last)
        val low = item?.targetRepsLow
        val high = item?.targetRepsHigh ?: low
        if (working.isEmpty()) {
            return Suggestion(item?.targetWeightKg, low, Reason.NO_HISTORY)
        }
        val top = working.maxByOrNull { it.weightKg ?: 0.0 }!!
        val topWeight = top.weightKg
        val atTop = working.filter { it.weightKg == topWeight }
        if (high == null || topWeight == null) {
            return Suggestion(topWeight, top.reps, Reason.REPEAT)
        }
        val allHitTop = atTop.all { (it.reps ?: 0) >= high }
        val targetSets = item?.targetSets
        val enoughSets = targetSets == null || atTop.size >= targetSets
        return if (allHitTop && enoughSets) {
            Suggestion(topWeight + unit.toKg(unit.step), low ?: top.reps, Reason.ADD_WEIGHT)
        } else {
            val bestReps = atTop.maxOf { it.reps ?: 0 }
            Suggestion(topWeight, (bestReps + 1).coerceAtMost(high), Reason.ADD_REPS)
        }
    }

    /**
     * Plates per side for [targetDisplay] (in [unit]) on a bar of [bar]. Greedy over the unit's plate set;
     * [remainder] is what could not be loaded (non-zero when the target is not reachable exactly).
     */
    data class PlateLoad(val perSide: List<Double>, val remainder: Double, val belowBar: Boolean)

    fun plates(targetDisplay: Double, unit: WeightUnit, bar: Double = unit.barWeight): PlateLoad {
        if (targetDisplay < bar - 1e-9) return PlateLoad(emptyList(), 0.0, belowBar = true)
        var side = (targetDisplay - bar) / 2.0
        val out = mutableListOf<Double>()
        for (p in unit.plates) {
            val n = floor((side + 1e-9) / p).toInt()
            repeat(n) { out += p }
            side -= n * p
        }
        return PlateLoad(out, (side * 2).let { if (abs(it) < 1e-9) 0.0 else it }, belowBar = false)
    }

    /** `m:ss` for a rest timer. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * The manual `workout` row a finished session is saved as. Same natural key as the session
     * (`deviceId`, `startTs`, `sport`), so the two pair 1:1. HR, strain and calories are left empty: the
     * app's manual-save path rescores them from the strap for this window.
     */
    fun workoutRow(session: LiftSessionRow, sets: List<LiftSetEntity>, unit: WeightUnit): WorkoutRow? {
        val end = session.endTs ?: return null
        val summary = summarize(session, sets)
        val note = buildString {
            append(session.programName ?: "Strength")
            append(" · ")
            append("${summary.workingSets} set").append(if (summary.workingSets == 1) "" else "s")
            if (summary.exercises > 0) append(" · ${summary.exercises} exercise").append(if (summary.exercises == 1) "" else "s")
            summary.volumeKg?.let { append(" · volume ${unit.format(it)} ${unit.name.lowercase()}") }
            session.sessionRpe?.let { append(" · sRPE ${"%.0f".format(it)}") }
        }
        return WorkoutRow(
            deviceId = session.deviceId,
            startTs = session.startTs,
            endTs = end,
            sport = session.sport,
            source = LiftLog.WORKOUT_SOURCE,
            durationS = (end - session.startTs).takeIf { it > 0 }?.toDouble(),
            notes = note,
        )
    }
}
