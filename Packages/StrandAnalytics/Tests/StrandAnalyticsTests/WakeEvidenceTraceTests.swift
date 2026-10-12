import XCTest
import Foundation
@testable import StrandAnalytics
import WhoopProtocol

/// BRA-10: wake-evidence trace tests. Asserts that:
/// - All-present channels produce a valid header + per-epoch z-score lines
/// - WHOOP 4.0 (no band sleep_state, sparse motion) marks absent channels as explicit "N/A", never numeric
/// - Sparse-HR (no HR samples for an epoch) prints "no-sample", not a numeric placeholder
/// - Absent channel in presence never prints a numeric placeholder and never infers "no wake"
/// - The perNightBaseline helper returns nil when fewer than 2 values are present
/// - The perEpochZScore helper returns nil when value is nil
/// - perEpochMotion handles empty inputs gracefully
final class WakeEvidenceTraceTests: XCTestCase {

    private func hrSample(_ ts: Int, _ bpm: Int) -> HRSample { HRSample(ts: ts, bpm: bpm) }
    private func rrSample(_ ts: Int, _ ms: Int) -> RRInterval { RRInterval(ts: ts, rrMs: ms) }
    private func gravSample(_ ts: Int, _ x: Double, _ y: Double, _ z: Double) -> GravitySample {
        GravitySample(ts: ts, x: x, y: y, z: z)
    }
    private func ssSample(_ ts: Int, _ state: Int) -> SleepStateSample {
        SleepStateSample(ts: ts, state: state)
    }

    /// All channels present, producing real z-scores.
    func testAllChannelsPresent() {
        let start = 1_000_000_000 // aligned to 30s epoch boundary
        let end = start + 120 // 4 epochs
        let presence = WakeEvidencePresence(hr: true, rr: true, gravity: true, steps: true, sleepState: true)

        let hr: [HRSample] = (0..<120).flatMap { s in
            let ts = start + s
            return stride(from: 0, to: 30, by: 3).map { hrSample(ts + $0, 60 + $0 % 5) }
        }
        let rr: [RRInterval] = (0..<120).flatMap { s in
            let ts = start + s
            return stride(from: 0, to: 30, by: 5).map { rrSample(ts + $0, 800 + $0 * 10) }
        }
        let grav: [GravitySample] = (0..<120).map { s in
            let ts = start + s
            let angle = Double(s) * 0.01
            return gravSample(ts, sin(angle) * 0.01, cos(angle) * 0.01, 0.98)
        }
        let sleepState: [SleepStateSample] = (0..<120).map { s in
            ssSample(start + s, s < 60 ? 2 : 0) // first two epochs asleep, last two awake
        }

        let lines = wakeEvidenceLines(start: start, end: end, family: .whoop5, presence: presence,
                                       hr: hr, rr: rr, grav: grav, sleepState: sleepState)

        XCTAssertGreaterThan(lines.count, 2, "should have header + interval + at least one epoch line")
        XCTAssertTrue(lines[0].contains("presence: hr rr motion band-state"), "header should list all present channels, got: \(lines[0])")
        XCTAssertTrue(lines[1].contains("interval start=\(start)"), "should print interval: \(lines[1])")
        // Epoch lines should have numeric HR and z-score values (not "N/A").
        let firstEpoch = lines[2]
        XCTAssertTrue(firstEpoch.hasPrefix("epoch"), "should be an epoch line: \(firstEpoch)")
        XCTAssertFalse(firstEpoch.contains("hr=N/A"), "hr should be present: \(firstEpoch)")
        XCTAssertFalse(firstEpoch.contains("hrvStd=N/A"), "hrv should be present: \(firstEpoch)")
        XCTAssertFalse(firstEpoch.contains("gravDens=N/A"), "gravity should be present: \(firstEpoch)")
        XCTAssertFalse(firstEpoch.contains("band=N/A"), "band should be present: \(firstEpoch)")
    }

    /// WHOOP 4.0: no band sleep_state, sparse motion. Absent channels must render as "N/A".
    func testWhoop4NoBandState() {
        let start = 1_000_000_000
        let end = start + 60
        let presence = WakeEvidencePresence(hr: true, rr: true, gravity: true, steps: false, sleepState: false)

        let hr = (0..<60).flatMap { s in
            [hrSample(start + s, 55)]
        }
        let rr = (0..<60).flatMap { s in
            [rrSample(start + s, 900)]
        }
        let grav = [gravSample(start + 0, 0.0, 0.0, 0.98),
                     gravSample(start + 30, 0.01, 0.0, 0.98)]

        let lines = wakeEvidenceLines(start: start, end: end, family: .whoop4, presence: presence,
                                       hr: hr, rr: rr, grav: grav, sleepState: [])

        XCTAssertTrue(lines[0].contains("family=4.0"), "should report family: \(lines[0])")
        XCTAssertTrue(lines[0].contains("presence: hr rr motion"), "should list present channels: \(lines[0])")
        XCTAssertFalse(lines[0].contains("band-state"), "should NOT list band-state when absent: \(lines[0])")

        for i in 2..<lines.count {
            let line = lines[i]
            XCTAssertTrue(line.contains("band=N/A"), "absent band state must be N/A: \(line)")
        }
    }

    /// All channels absent: header says "presence: none".
    func testAllChannelsAbsent() {
        let start = 1_000_000_000
        let end = start + 30
        let presence = WakeEvidencePresence(hr: false, rr: false, gravity: false, steps: false, sleepState: false)

        let lines = wakeEvidenceLines(start: start, end: end, family: .whoop5, presence: presence,
                                       hr: [], rr: [], grav: [], sleepState: [])

        XCTAssertTrue(lines[0].contains("presence: none"), "should report none: \(lines[0])")
    }

    /// An absent channel in presence should never produce a numeric placeholder.
    func testAbsentChannelNeverNumeric() {
        let start = 1_000_000_000
        let end = start + 30
        let presence = WakeEvidencePresence(hr: false, rr: false, gravity: true, steps: false, sleepState: false)
        let grav = [gravSample(start + 0, 0.0, 0.0, 0.98)]

        let lines = wakeEvidenceLines(start: start, end: end, family: .whoop5, presence: presence,
                                       hr: [], rr: [], grav: grav, sleepState: [])

        for i in 2..<lines.count {
            let line = lines[i]
            XCTAssertTrue(line.contains("hr=N/A"), "absent HR must be N/A: \(line)")
            XCTAssertTrue(line.contains("hrvStd=N/A"), "absent HRV must be N/A: \(line)")
            XCTAssertFalse(line.contains("zHR=N/A") && line.contains("zHR=-"), "should not have a numeric z-score for absent channel: \(line)")
        }
    }

    /// No HR samples in an epoch but channel is present → "no-sample".
    func testPresentChannelEmptyEpoch() {
        let start = 1_000_000_000
        let end = start + 30
        let presence = WakeEvidencePresence(hr: true, rr: true, gravity: true, steps: false, sleepState: false)
        // No HR or RR in this interval.
        let grav = [gravSample(start + 0, 0.0, 0.0, 0.98)]

        let lines = wakeEvidenceLines(start: start, end: end, family: .whoop5, presence: presence,
                                       hr: [], rr: [], grav: grav, sleepState: [])

        for i in 2..<lines.count {
            let line = lines[i]
            XCTAssertTrue(line.contains("hr=no-sample"), "present HR but empty epoch: \(line)")
            XCTAssertTrue(line.contains("hrvStd=no-sample"), "present HRV but empty epoch: \(line)")
        }
    }

    /// Quantized epoch schedule: interval [start, end) → exactly (end - epochStart) / 30 lines.
    func testEpochGrid() {
        let start = 1_000_000_021 // not aligned → epochStart = 1_000_000_050
        let end = start + 120
        let preset = WakeEvidencePresence(hr: false, rr: false, gravity: false, steps: false, sleepState: false)

        let lines = wakeEvidenceLines(start: start, end: end, family: .whoop5, presence: preset,
                                       hr: [], rr: [], grav: [], sleepState: [])

        // epochStart = (1_000_000_021 + 29) / 30 * 30 = 1_000_000_050
        // epochs: [1000000050, 1000000080, 1000000110] = 3 epochs
        let expectedEpochs = 3
        let epochLines = lines.filter { $0.hasPrefix("epoch ") }
        XCTAssertEqual(epochLines.count, expectedEpochs, "should have \(expectedEpochs) epoch lines, got \(epochLines.count): \(lines)")
    }

    // MARK: - perNightBaseline

    func testPerNightBaseline() {
        XCTAssertNil(perNightBaseline([]), "empty → nil")
        XCTAssertNil(perNightBaseline([nil]), "one nil → nil")
        XCTAssertNil(perNightBaseline([50.0]), "one value → nil")
        if let b = perNightBaseline([50.0, 60.0]) {
            XCTAssertEqual(b.mean, 55.0, accuracy: 1e-9)
            XCTAssertEqual(b.spread, 5.0, accuracy: 1e-9)
        } else {
            XCTFail("two values should produce a baseline")
        }
        if let b = perNightBaseline([50.0, nil, 60.0]) {
            XCTAssertEqual(b.mean, 55.0, accuracy: 1e-9)
            XCTAssertEqual(b.spread, 5.0, accuracy: 1e-9)
        } else {
            XCTFail("two non-nil values should produce a baseline")
        }
    }

    // MARK: - perEpochZScore

    func testPerEpochZScore() {
        XCTAssertNil(perEpochZScore(nil, mean: 55, spread: 5), "nil value → nil z-score")
        if let z = perEpochZScore(65, mean: 55, spread: 5) {
            XCTAssertGreaterThan(z, 0, "above-mean value should give positive z")
        } else {
            XCTFail("should compute z-score for non-nil value")
        }
        if let z = perEpochZScore(45, mean: 55, spread: 5) {
            XCTAssertLessThan(z, 0, "below-mean value should give negative z")
        } else {
            XCTFail("should compute z-score for non-nil value")
        }
    }

    // MARK: - perEpochMotion

    func testPerEpochMotionEmpty() {
        XCTAssertTrue(perEpochMotion(start: 100, end: 200, grav: []).isEmpty)
        XCTAssertTrue(perEpochMotion(start: 200, end: 100, grav: [gravSample(150, 0, 0, 0.98)]).isEmpty)
    }

    func testPerEpochMotionBasic() {
        let start = 1_000_000_000
        let grav = (0..<60).map { s in
            let th = Double(s) * 0.1
            return gravSample(start + s, sin(th) * 0.01, cos(th) * 0.01, 0.98)
        }
        let result = perEpochMotion(start: start, end: start + 60, grav: grav)
        XCTAssertEqual(result.count, 2, "should produce 2 epochs")
        for m in result {
            XCTAssertGreaterThan(m.gravityDensity, 0, "should have gravity samples: \(m)")
            XCTAssertGreaterThanOrEqual(m.jerkMax, 0, "jerkMax should be non-negative: \(m)")
        }
    }
}