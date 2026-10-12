import XCTest
@testable import WhoopProtocol

/// The FTMS capability-probe log contract — the Swift twin of Android's `FitnessMachineProbeTest`.
///
/// The six frame fixtures here are the SAME bytes as the Kotlin test's, and the expected strings are the
/// SAME literals, because a probe line is a cross-platform contract: a field log from an Android phone and
/// one from a Mac pointed at the same treadmill must be directly comparable, and they are not comparable if
/// the two platforms word or order the fields differently. Any edit to `FTMSProbe.frameSummary` that changes
/// a string has to be made in `FitnessMachineProbe` and in both tests together.
///
/// Fixtures are built byte-by-byte from the Bluetooth SIG Fitness Machine Service field tables, not from a
/// capture, so each asserts a specific flag→field mapping rather than one machine's habits.
final class FTMSProbeTests: XCTestCase {

    private func le16(_ v: Int) -> [UInt8] { [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)] }
    private func le24(_ v: Int) -> [UInt8] { [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF)] }

    // MARK: - Shared fixtures (byte-identical to the Kotlin FitnessMachineProbeTest fixtures)

    /// Treadmill, flags 0x0484: speed (bit0 clear) + total distance + total energy block + elapsed.
    private var treadmillFull: [UInt8] {
        le16(0x0484) + le16(531) + le24(1203) + le16(42) + le16(50) + [3] + le16(420)
    }

    /// Treadmill, flags 0x0100: speed + heart rate only — the shape that matters for a gym machine.
    private var treadmillHROnly: [UInt8] { le16(0x0100) + le16(0) + [0x80] }

    /// Treadmill, flags 0x0001: "More Data" set, which SUPPRESSES instantaneous speed, and no other bit
    /// set. A machine that is powered but idle can legitimately send this, so the probe must report a frame
    /// carrying nothing rather than treat it as a decode failure.
    private var treadmillEmpty: [UInt8] { le16(0x0001) }

    /// Indoor bike, flags 0x0044: speed + instantaneous cadence + instantaneous power.
    private var indoorBike: [UInt8] { le16(0x0044) + le16(2500) + le16(160) + le16(200) }

    /// Cross trainer, flags 0x000808 (24-bit): speed + step-count block + heart rate.
    private var crossTrainer: [UInt8] {
        le16(0x0808) + [0x00] + le16(1000) + le16(120) + le16(118) + [0x8C]
    }

    /// Rower, flags 0x0804: stroke-rate block (bit0 clear) + total distance + elapsed.
    private var rower: [UInt8] { le16(0x0804) + [60] + le16(150) + le24(500) + le16(300) }

    // MARK: - frameSummary

    func testTreadmillFrameReportsLengthFlagsAndFieldsCarried() {
        XCTAssertEqual(
            FTMSProbe.frameSummary(uuid16: "2ACD", treadmillFull),
            "2ACD 14B flags=0x0484 decoded: speed, distance, energy, elapsed | not in frame: cadence, power, HR"
        )
    }

    func testTreadmillFrameCarryingOnlySpeedAndHR() {
        XCTAssertEqual(
            FTMSProbe.frameSummary(uuid16: "2ACD", treadmillHROnly),
            "2ACD 5B flags=0x0100 decoded: speed, HR | not in frame: cadence, power, distance, energy, elapsed"
        )
    }

    func testFrameWithNoDecodableFieldIsReportedNotDropped() {
        XCTAssertEqual(
            FTMSProbe.frameSummary(uuid16: "2ACD", treadmillEmpty),
            "2ACD 2B flags=0x0001 no fields decoded | not in frame: speed, cadence, power, distance, energy, HR, elapsed"
        )
    }

    func testIndoorBikeFrameReportsCadenceAndPower() {
        XCTAssertEqual(
            FTMSProbe.frameSummary(uuid16: "2AD2", indoorBike),
            "2AD2 8B flags=0x0044 decoded: speed, cadence, power | not in frame: distance, energy, HR, elapsed"
        )
    }

    func testCrossTrainerFlagsPrintAsSixHexDigits() {
        XCTAssertEqual(
            FTMSProbe.frameSummary(uuid16: "2ACE", crossTrainer),
            "2ACE 10B flags=0x000808 decoded: speed, cadence, HR | not in frame: power, distance, energy, elapsed"
        )
    }

    func testRowerFrameReportsStrokeRateAsCadence() {
        XCTAssertEqual(
            FTMSProbe.frameSummary(uuid16: "2AD1", rower),
            "2AD1 10B flags=0x0804 decoded: cadence, distance, elapsed | not in frame: speed, power, energy, HR"
        )
    }

    func testUnknownCharacteristicProducesNoLineRatherThanAGuess() {
        XCTAssertNil(FTMSProbe.frameSummary(uuid16: "180D", treadmillFull))
        XCTAssertNil(FTMSProbe.flags(uuid16: "180D", treadmillFull))
        XCTAssertNil(FTMSProbe.kind(forUUID16: "180D"))
    }

    func testFrameTooShortForItsFlagsFieldProducesNoLine() {
        // One byte cannot hold a 16-bit flags word, so there is nothing attributable to report.
        XCTAssertNil(FTMSProbe.frameSummary(uuid16: "2ACD", [0x00]))
        XCTAssertNil(FTMSProbe.flags(uuid16: "2ACD", [0x00]))
        // A cross trainer needs THREE bytes: two is enough for a treadmill and not for this.
        XCTAssertNil(FTMSProbe.frameSummary(uuid16: "2ACE", [0x00, 0x00]))
        XCTAssertNil(FTMSProbe.flags(uuid16: "2ACE", [0x00, 0x00]))
    }

    // MARK: - flags

    func testFlagsReadsTheRawLittleEndianWordForBothWidths() {
        XCTAssertEqual(FTMSProbe.flags(uuid16: "2ACD", treadmillFull), 0x0484)
        XCTAssertEqual(FTMSProbe.flags(uuid16: "2AD2", indoorBike), 0x0044)
        XCTAssertEqual(FTMSProbe.flags(uuid16: "2AD1", rower), 0x0804)
        XCTAssertEqual(FTMSProbe.flags(uuid16: "2ACE", crossTrainer), 0x000808)
    }

    func testFlagsWidthIsThreeBytesOnlyForTheCrossTrainer() {
        XCTAssertEqual(FTMSProbe.flagsWidth(.crossTrainer), 3)
        XCTAssertEqual(FTMSProbe.flagsWidth(.treadmill), 2)
        XCTAssertEqual(FTMSProbe.flagsWidth(.indoorBike), 2)
        XCTAssertEqual(FTMSProbe.flagsWidth(.rower), 2)
    }

    // MARK: - shortLabel

    func testBaseUUIDServicePrintsAsItsShortForm() {
        XCTAssertEqual(FTMSProbe.shortLabel("00001826-0000-1000-8000-00805f9b34fb"), "0x1826")
        XCTAssertEqual(FTMSProbe.shortLabel("0000180d-0000-1000-8000-00805f9b34fb"), "0x180D")
        XCTAssertEqual(FTMSProbe.shortLabel("00002ACD-0000-1000-8000-00805F9B34FB"), "0x2ACD")
    }

    func testVendorUUIDIsPrintedInFullBecauseItIsTheFinding() {
        // On a machine serving no standard fitness service, the proprietary UUIDs are the whole diagnosis.
        let vendor = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        XCTAssertEqual(FTMSProbe.shortLabel(vendor), vendor)
        // A 32-bit assigned number does NOT sit on the base UUID in the 0000xxxx slot; it must not be
        // truncated to its high half, which would print two different services identically.
        XCTAssertEqual(
            FTMSProbe.shortLabel("12341826-0000-1000-8000-00805f9b34fb"),
            "12341826-0000-1000-8000-00805f9b34fb"
        )
    }
}
