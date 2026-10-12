package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.protocol.HrSample
import com.noop.protocol.RrInterval
import com.noop.data.SleepStateSampleEntity as SleepStateSample
import com.noop.protocol.DeviceFamily
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// WakeEvidenceTrace.kt — per-night diagnostic (Test Centre domain .sleep) printing, for a
// user-supplied interval, the epoch HR / HR-variability z-scores, motion and jerk, gravity-sample
// density and band sleep_state, so a reported awakening becomes a labelled case. Instrumentation only;
// no scoring/staging change. Pure and side-effect-free. One presence resolved once, threaded through
// header + per-epoch loop, never re-derived. An absent channel renders as explicit "not available",
// never a numeric placeholder or an inferred "no wake".
// Kotlin twin of the Swift WakeEvidenceTrace, byte-identical by oracle verification.

// MARK: - Per-epoch jerk accessor

data class EpochMotion(
    val start: Int,
    val jerkMax: Double,
    val gravityDensity: Int,
    val moveFrac: Double,
    val jerkScale: Double,
)

fun perEpochMotion(start: Int, end: Int, grav: List<GravitySample>): List<EpochMotion> {
    if (end <= start || grav.isEmpty()) return emptyList()

    val gxSum = mutableMapOf<Int, Double>()
    val gySum = mutableMapOf<Int, Double>()
    val gzSum = mutableMapOf<Int, Double>()
    val gCnt = mutableMapOf<Int, Int>()
    for (g in grav) {
        val t = g.ts.toInt()
        gxSum[t] = (gxSum[t] ?: 0.0) + g.x
        gySum[t] = (gySum[t] ?: 0.0) + g.y
        gzSum[t] = (gzSum[t] ?: 0.0) + g.z
        gCnt[t] = (gCnt[t] ?: 0) + 1
    }

    val secG = mutableMapOf<Int, Triple<Double, Double, Double>>()
    for ((k, c) in gCnt) {
        val d = c.toDouble()
        secG[k] = Triple((gxSum[k] ?: 0.0) / d, (gySum[k] ?: 0.0) / d, (gzSum[k] ?: 0.0) / d)
    }

    val secs = secG.keys.sorted()
    val allJerks = mutableListOf<Double>()
    if (secs.size > 1) {
        for (i in 1 until secs.size) {
            val a = secG[secs[i - 1]]!!
            val b = secG[secs[i]]!!
            val dx = a.first - b.first
            val dy = a.second - b.second
            val dz = a.third - b.third
            allJerks.add(sqrt(dx * dx + dy * dy + dz * dz))
        }
    }

    val jerkFloorMoveMult = 38.0

    val jerkScale = if (allJerks.isEmpty()) 1e-6 else {
        val s = allJerks.sorted(); val n = s.size
        if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }
    val moveThr = jerkScale * jerkFloorMoveMult

    val secondToJerk = mutableMapOf<Int, Double>()
    for (i in allJerks.indices) secondToJerk[secs[i + 1]] = allJerks[i]

    val firstE = ((start + 29) / 30) * 30
    val result = mutableListOf<EpochMotion>()
    var e = firstE
    while (e < end) {
        val epochJerks = mutableListOf<Double>()
        var density = 0
        for (s in e until (e + 30)) {
            secondToJerk[s]?.let { epochJerks.add(it) }
            if (secG[s] != null) density++
        }
        val jerkMax = epochJerks.maxOrNull() ?: 0.0
        val moves = epochJerks.count { it > moveThr }
        val moveFrac = if (epochJerks.isEmpty()) 0.0 else moves.toDouble() / epochJerks.size
        result.add(EpochMotion(e, jerkMax, density, moveFrac, jerkScale))
        e += 30
    }
    return result
}

// MARK: - Per-epoch HR/HRV z-score helper

fun perEpochZScore(value: Double?, mean: Double, spread: Double): Double? {
    if (value == null) return null
    val sigma = max(1.253 * spread, 1e-9)
    return (value - mean) / sigma
}

data class NightBaseline(val mean: Double, val spread: Double)

fun perNightBaseline(values: List<Double?>): NightBaseline? {
    val present = values.filterNotNull()
    if (present.size < 2) return null
    val n = present.size.toDouble()
    val mean = present.sum() / n
    val variance = present.sumOf { (it - mean) * (it - mean) } / n
    return NightBaseline(mean, sqrt(variance))
}

// MARK: - Trace function

data class WakeEvidencePresence(
    val hr: Boolean, val rr: Boolean, val gravity: Boolean, val steps: Boolean, val sleepState: Boolean,
)

fun wakeEvidenceLines(
    start: Int, end: Int,
    family: DeviceFamily,
    presence: WakeEvidencePresence,
    hr: List<HrSample>, rr: List<RrInterval>,
    grav: List<GravitySample>,
    sleepState: List<SleepStateSample>,
): List<String> {
    val legend = when {
        !presence.hr && !presence.gravity && !presence.rr && !presence.sleepState -> "presence: none"
        else -> buildList {
            if (presence.hr) add("hr")
            if (presence.rr) add("rr")
            if (presence.gravity) add("motion")
            if (presence.sleepState) add("band-state")
        }.joinToString(" ", prefix = "presence: ")
    }
    val lines = mutableListOf(
        "wake-evidence family=${family.name.lowercase()} $legend",
        "interval start=$start end=$end durations=${end - start}s",
    )

    val epochStart = ((start + 29) / 30) * 30
    if (epochStart >= end || end <= start) {
        lines.add("wake-evidence: no epochs in interval")
        return lines
    }

    val motions = perEpochMotion(start, end, grav)
    val motionByStart = motions.associateBy { it.start }

    // Per-epoch HR
    val hrByEpoch = mutableMapOf<Int, MutableList<Double>>()
    for (s in hr) {
        val e = (s.ts / 30) * 30
        hrByEpoch.getOrPut(e) { mutableListOf() }.add(s.bpm.toDouble())
    }
    val hrMeanByEpoch = mutableMapOf<Int, Double>()
    for ((e, vals) in hrByEpoch) hrMeanByEpoch[e] = vals.sum() / vals.size

    // Per-epoch HRV
    val rrByEpoch = mutableMapOf<Int, MutableList<Double>>()
    for (r in rr) {
        val e = (r.ts / 30) * 30
        rrByEpoch.getOrPut(e) { mutableListOf() }.add(r.rrMs.toDouble())
    }
    val rrStdByEpoch = mutableMapOf<Int, Double>()
    for ((e, vals) in rrByEpoch) {
        val n = vals.size.toDouble()
        if (n < 2) continue
        val m = vals.sum() / n
        val v = vals.sumOf { (it - m) * (it - m) } / n
        rrStdByEpoch[e] = sqrt(v)
    }

    // Per-epoch band sleep_state
    val sleepStateCounts = mutableMapOf<Int, MutableMap<Int, Int>>()
    for (s in sleepState) {
        val e = (s.ts.toInt() / 30) * 30
        val inner = sleepStateCounts.getOrPut(e) { mutableMapOf() }
        inner[s.state] = (inner[s.state] ?: 0) + 1
    }
    val sleepStateByEpoch = mutableMapOf<Int, Int>()
    for ((e, counts) in sleepStateCounts) {
        val best = counts.maxByOrNull { it.value }
        if (best != null) sleepStateByEpoch[e] = best.key
    }

    // Per-night baselines
    val epochStarts = (epochStart until end step 30).toList()
    val hrVals = epochStarts.map { hrMeanByEpoch[it] }
    val hrvVals = epochStarts.map { rrStdByEpoch[it] }
    val hrBaseline = perNightBaseline(hrVals)
    val hrvBaseline = perNightBaseline(hrvVals)

    for (e in epochStart until end step 30) {
        val hrMean = hrMeanByEpoch[e]
        val rrStd = rrStdByEpoch[e]
        val hrZ = hrBaseline?.let { perEpochZScore(hrMean, it.mean, it.spread) }
        val hrvZ = hrvBaseline?.let { perEpochZScore(rrStd, it.mean, it.spread) }
        val motion = motionByStart[e]
        val band = sleepStateByEpoch[e]

        val hrPart = when {
            !presence.hr -> "hr=N/A"
            hrMean != null -> {
                val zStr = hrZ?.let { String.format("%.2f", it) } ?: "N/A"
                "hr=${hrMean.roundToInt()} zHR=$zStr"
            }
            else -> "hr=no-sample zHR=N/A"
        }

        val hrvPart = when {
            !presence.rr -> "hrvStd=N/A"
            rrStd != null -> {
                val zStr = hrvZ?.let { String.format("%.2f", it) } ?: "N/A"
                "hrvStd=${rrStd.roundToInt()} zHRV=$zStr"
            }
            else -> "hrvStd=no-sample zHRV=N/A"
        }

        val gravPart = when {
            !presence.gravity -> "gravDens=N/A jerkMax=N/A moveFrac=N/A"
            motion != null -> "gravDens=${motion.gravityDensity} jerkMax=${String.format("%.6f", motion.jerkMax)} moveFrac=${String.format("%.3f", motion.moveFrac)}"
            else -> "gravDens=no-sample jerkMax=N/A moveFrac=N/A"
        }

        val bandPart = when {
            !presence.sleepState -> "band=N/A"
            band != null -> {
                val label = when (band) {
                    0 -> "wake"
                    1 -> "still"
                    2 -> "asleep"
                    3 -> "up"
                    else -> "?$band"
                }
                "band=$label"
            }
            else -> "band=no-sample"
        }

        lines.add("epoch $e $hrPart $hrvPart $gravPart $bandPart")
    }

    return lines
}