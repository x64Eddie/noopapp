import Foundation
import WhoopProtocol   // DeviceFamily, GravitySample, HRSample, RRInterval, SleepStateSample

// WakeEvidenceTrace.swift — per-night diagnostic (Test Centre domain .sleep) printing, for a
// user-supplied interval, the epoch HR / HR-variability z-scores, motion and jerk, gravity-sample
// density and band sleep_state, so a reported awakening becomes a labelled case. Instrumentation only;
// no scoring/staging change. Pure and side-effect-free. One presence resolved once, threaded through
// header + per-epoch loop, never re-derived. An absent channel renders as explicit "not available",
// never a numeric placeholder or an inferred "no wake".

// MARK: - Per-epoch jerk accessor

/// One epoch's motion quantities, computed from per-second gravity samples on a 30 s grid. Pure;
/// reuses SleepStagerV2's exact per-second jerk formula (median of consecutive gravity-magnitude
/// differences) without reaching into the stager's internals.
public struct EpochMotion: Equatable, Sendable {
    public let start: Int           // epoch start (unix seconds, multiple of 30)
    public let jerkMax: Double      // peak per-second jerk (g) in this epoch
    public let gravityDensity: Int  // count of gravity samples whose ts falls in [start, start+30)
    public let moveFrac: Double     // fraction of per-second jerks above jerkScale * jerkFloorMoveMult
    public let jerkScale: Double    // night quiescent jerk floor (median of all per-second jerks)

    public init(start: Int, jerkMax: Double, gravityDensity: Int, moveFrac: Double, jerkScale: Double) {
        self.start = start; self.jerkMax = jerkMax; self.gravityDensity = gravityDensity
        self.moveFrac = moveFrac; self.jerkScale = jerkScale
    }
}

/// Build per-epoch motion quantities for every 30 s epoch boundary in [start, end), reusing
/// SleepStagerV2's exact jerk formula. `start` and `end` are unix seconds; epochs are aligned to
/// multiples of 30. Returns an empty array when no gravity samples are present. Epochs with no
/// gravity data are INCLUDED (with zero density) so callers can iterate in lockstep with the
/// epoch grid.
public func perEpochMotion(start: Int, end: Int, grav: [GravitySample]) -> [EpochMotion] {
    if end <= start { return [] }

    // Per-second gravity aggregation (mean when multiple samples land on the same second).
    var gxSum = [Int: Double](), gySum = [Int: Double](), gzSum = [Int: Double](), gCnt = [Int: Int]()
    for g in grav {
        gxSum[g.ts, default: 0] += g.x; gySum[g.ts, default: 0] += g.y
        gzSum[g.ts, default: 0] += g.z; gCnt[g.ts, default: 0] += 1
    }
    var secG = [Int: (Double, Double, Double)]()
    for (k, c) in gCnt { let d = Double(c); secG[k] = (gxSum[k]! / d, gySum[k]! / d, gzSum[k]! / d) }

    // Gather all per-second jerks for the night-wide jerkScale (median).
    var allJerks: [Double] = []
    let secs = grav.isEmpty ? [] : secG.keys.sorted()
    if secs.count > 1 {
        for i in 1..<secs.count {
            let a = secG[secs[i - 1]]!, b = secG[secs[i]]!
            let dx = a.0 - b.0, dy = a.1 - b.1, dz = a.2 - b.2
            allJerks.append((dx * dx + dy * dy + dz * dz).squareRoot())
        }
    }

    let jerkFloorMoveMult = 38.0

    let jerkScale: Double = {
        if allJerks.isEmpty { return 1e-6 }
        let s = allJerks.sorted(); let n = s.count
        return n % 2 == 1 ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2])
    }()
    let moveThr = jerkScale * jerkFloorMoveMult

    // Build a lookup from second → jerk for the per-epoch pass. jerk[i] is between secs[i] and secs[i+1].
    var secondToJerk = [Int: Double]()
    for i in 0..<allJerks.count { secondToJerk[secs[i + 1]] = allJerks[i] }

    let firstE = ((start + 29) / 30) * 30
    var result: [EpochMotion] = []
    var e = firstE
    while e < end {
        var epochJerks: [Double] = []
        var density = 0
        for s in e..<(e + 30) {
            if let j = secondToJerk[s] { epochJerks.append(j) }
            if secG[s] != nil { density += 1 }
        }
        let jerkMax = epochJerks.max() ?? 0.0
        let moves = epochJerks.reduce(0) { $0 + ($1 > moveThr ? 1 : 0) }
        let moveFrac = epochJerks.isEmpty ? 0.0 : Double(moves) / Double(epochJerks.count)
        result.append(EpochMotion(start: e, jerkMax: jerkMax, gravityDensity: density,
                                   moveFrac: moveFrac, jerkScale: jerkScale))
        e += 30
    }
    return result
}

// MARK: - Per-epoch HR/HRV z-score helper

/// Z-score of an epoch's value against a per-night baseline. Uses RecoveryScorer.zScore's math
/// (value - mean) / (1.253 * spread) but against an instrumentation-local per-night mean and stddev,
/// not the recovery scorer's day/rolling baseline. Returns nil when value is nil.
public func perEpochZScore(_ value: Double?, mean: Double, spread: Double) -> Double? {
    guard let v = value else { return nil }
    let sigma = max(1.253 * spread, 1e-9)
    return (v - mean) / sigma
}

/// Per-night baseline from a list of optional epoch values. Returns (mean, spread) or nil when fewer
/// than 2 non-nil values are present. Spread is population stddev.
public func perNightBaseline(_ values: [Double?]) -> (mean: Double, spread: Double)? {
    let present = values.compactMap { $0 }
    if present.count < 2 { return nil }
    let n = Double(present.count)
    let mean = present.reduce(0, +) / n
    let variance = present.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / n
    return (mean: mean, spread: variance.squareRoot())
}

// MARK: - Trace function

/// Parsed channel presence (resolved once, never re-derived). Threaded through the trace function so
/// the header and every epoch line agree on which channels exist. The caller resolves this from
/// `WhoopStore.streamPresence` and passes it in.
public struct WakeEvidencePresence: Equatable, Sendable {
    public let hr: Bool, rr: Bool, gravity: Bool, steps: Bool, sleepState: Bool
    public init(hr: Bool, rr: Bool, gravity: Bool, steps: Bool, sleepState: Bool) {
        self.hr = hr; self.rr = rr; self.gravity = gravity; self.steps = steps
        self.sleepState = sleepState
    }
}

/// One line per epoch in the user-supplied interval, plus a header stating which channels were
/// actually present for that night. The header and per-epoch loop both read the one passed-in
/// `presence` — never re-derived from "is this array empty".
public func wakeEvidenceLines(
    start: Int, end: Int,
    family: DeviceFamily,
    presence: WakeEvidencePresence,
    hr: [HRSample], rr: [RRInterval],
    grav: [GravitySample],
    sleepState: [SleepStateSample]
) -> [String] {
    let legend: String = {
        if !presence.hr, !presence.gravity, !presence.rr, !presence.sleepState {
            return "presence: none"
        }
        var parts: [String] = []
        if presence.hr { parts.append("hr") }
        if presence.rr { parts.append("rr") }
        if presence.gravity { parts.append("motion") }
        if presence.sleepState { parts.append("band-state") }
        return "presence: \(parts.joined(separator: " "))"
    }()
    var lines = ["wake-evidence family=\(family.rawValue) \(legend)"]
    lines.append("interval start=\(start) end=\(end) durations=\(end - start)s")

    let epochStart = ((start + 29) / 30) * 30
    if epochStart >= end || end <= start {
        lines.append("wake-evidence: no epochs in interval")
        return lines
    }

    let motions = perEpochMotion(start: start, end: end, grav: grav)
    var motionByStart = [Int: EpochMotion]()
    for m in motions { motionByStart[m.start] = m }

    // Per-epoch HR (mean bpm per 30 s epoch).
    var hrByEpoch = [Int: [Double]]()
    for s in hr { let e = (s.ts / 30) * 30; hrByEpoch[e, default: []].append(Double(s.bpm)) }
    var hrMeanByEpoch = [Int: Double]()
    for (e, vals) in hrByEpoch { hrMeanByEpoch[e] = vals.reduce(0, +) / Double(vals.count) }

    // Per-epoch HRV (std of R-R intervals across the epoch as a proxy for short-term variability).
    var rrByEpoch = [Int: [Double]]()
    for r in rr { let e = (r.ts / 30) * 30; rrByEpoch[e, default: []].append(Double(r.rrMs)) }
    var rrStdByEpoch = [Int: Double]()
    for (e, vals) in rrByEpoch {
        let n = Double(vals.count)
        if n < 2 { continue }
        let m = vals.reduce(0, +) / n
        let v = vals.reduce(0) { $0 + ($1 - m) * ($1 - m) } / n
        rrStdByEpoch[e] = v.squareRoot()
    }

    // Per-epoch band sleep_state (most common state in the epoch or nil when absent).
    var sleepStateByEpoch = [Int: Int]()
    var sleepStateCounts = [Int: [Int: Int]]()
    for s in sleepState {
        let e = (s.ts / 30) * 30
        if sleepStateCounts[e] == nil { sleepStateCounts[e] = [:] }
        sleepStateCounts[e]![s.state, default: 0] += 1
    }
    for (e, counts) in sleepStateCounts {
        if let best = counts.max(by: { $0.value < $1.value }) {
            sleepStateByEpoch[e] = best.key
        }
    }

    // Per-night baselines from present epochs.
    let epochStarts = stride(from: epochStart, to: end, by: 30).map { $0 }
    let hrVals: [Double?] = epochStarts.map { hrMeanByEpoch[$0] }
    let hrvVals: [Double?] = epochStarts.map { rrStdByEpoch[$0] }
    let hrBaseline = perNightBaseline(hrVals)
    let hrvBaseline = perNightBaseline(hrvVals)

    for e in stride(from: epochStart, to: end, by: 30) {
        let hrMean = hrMeanByEpoch[e]
        let rrStd = rrStdByEpoch[e]
        let hrZ = hrBaseline.flatMap { perEpochZScore(hrMean, mean: $0.mean, spread: $0.spread) }
        let hrvZ = hrvBaseline.flatMap { perEpochZScore(rrStd, mean: $0.mean, spread: $0.spread) }
        let motion = motionByStart[e]
        let band = sleepStateByEpoch[e]

        let hrPart: String
        if !presence.hr {
            hrPart = "hr=N/A"
        } else if let v = hrMean {
            let zStr = hrZ.map { String(format: "%.2f", $0) } ?? "N/A"
            hrPart = "hr=\(Int(v.rounded())) zHR=\(zStr)"
        } else {
            hrPart = "hr=no-sample zHR=N/A"
        }

        let hrvPart: String
        if !presence.rr {
            hrvPart = "hrvStd=N/A"
        } else if let v = rrStd {
            let zStr = hrvZ.map { String(format: "%.2f", $0) } ?? "N/A"
            hrvPart = "hrvStd=\(Int(v.rounded())) zHRV=\(zStr)"
        } else {
            hrvPart = "hrvStd=no-sample zHRV=N/A"
        }

        let gravPart: String
        if !presence.gravity {
            gravPart = "gravDens=N/A jerkMax=N/A moveFrac=N/A"
        } else if let m = motion {
            gravPart = "gravDens=\(m.gravityDensity) jerkMax=\(String(format: "%.6f", m.jerkMax)) moveFrac=\(String(format: "%.3f", m.moveFrac))"
        } else {
            gravPart = "gravDens=no-sample jerkMax=N/A moveFrac=N/A"
        }

        let bandPart: String
        if !presence.sleepState {
            bandPart = "band=N/A"
        } else if let b = band {
            let label: String
            switch b {
            case 0: label = "wake"
            case 1: label = "still"
            case 2: label = "asleep"
            case 3: label = "up"
            default: label = "?\(b)"
            }
            bandPart = "band=\(label)"
        } else {
            bandPart = "band=no-sample"
        }

        lines.append("epoch \(e) \(hrPart) \(hrvPart) \(gravPart) \(bandPart)")
    }

    return lines
}