package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The FTMS capability-probe log contract — the JVM twin of WhoopProtocol's `FTMSProbeTests`.
 *
 * The six frame fixtures here are the SAME bytes as the Swift test's, and the expected strings are the
 * SAME literals, because a probe line is a cross-platform contract: a field log from an Android phone and
 * one from a Mac pointed at the same treadmill must be directly comparable, and they are not comparable if
 * the two platforms word or order the fields differently. Any edit to [FitnessMachineProbe.frameSummary]
 * that changes a string has to be made in `FTMSProbe` and in both tests together.
 *
 * Fixtures are built byte-by-byte from the Bluetooth SIG Fitness Machine Service field tables, not from a
 * capture, so each asserts a specific flag→field mapping rather than one machine's habits.
 */
class FitnessMachineProbeTest {

    private fun le16(v: Int): IntArray = intArrayOf(v and 0xFF, (v shr 8) and 0xFF)
    private fun le24(v: Int): IntArray = intArrayOf(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF)
    private fun pack(vararg parts: IntArray): ByteArray {
        val all = parts.flatMap { it.toList() }
        return ByteArray(all.size) { (all[it] and 0xFF).toByte() }
    }

    // MARK: - Shared fixtures (byte-identical to the Swift FTMSProbeTests fixtures)

    /** Treadmill, flags 0x0484: speed (bit0 clear) + total distance + total energy block + elapsed. */
    private val treadmillFull = pack(
        le16(0x0484), le16(531), le24(1203), le16(42), le16(50), intArrayOf(3), le16(420),
    )

    /** Treadmill, flags 0x0100: speed + heart rate only — the shape that matters for a gym machine. */
    private val treadmillHrOnly = pack(le16(0x0100), le16(0), intArrayOf(0x80))

    /**
     * Treadmill, flags 0x0001: "More Data" set, which SUPPRESSES instantaneous speed, and no other bit
     * set. A machine that is powered but idle can legitimately send this, so the probe must report a frame
     * carrying nothing rather than treat it as a decode failure.
     */
    private val treadmillEmpty = pack(le16(0x0001))

    /** Indoor bike, flags 0x0044: speed + instantaneous cadence + instantaneous power. */
    private val indoorBike = pack(le16(0x0044), le16(2500), le16(160), le16(200))

    /** Cross trainer, flags 0x000808 (24-bit): speed + step-count block + heart rate. */
    private val crossTrainer = pack(
        le16(0x0808), intArrayOf(0x00), le16(1000), le16(120), le16(118), intArrayOf(0x8C),
    )

    /** Rower, flags 0x0804: stroke-rate block (bit0 clear) + total distance + elapsed. */
    private val rower = pack(le16(0x0804), intArrayOf(60), le16(150), le24(500), le16(300))

    // MARK: - frameSummary

    @Test
    fun `treadmill frame reports length flags and the fields it carried`() {
        assertEquals(
            "2ACD 14B flags=0x0484 decoded: speed, distance, energy, elapsed | not in frame: cadence, power, HR",
            FitnessMachineProbe.frameSummary("2ACD", treadmillFull),
        )
    }

    @Test
    fun `treadmill frame carrying only speed and HR says so`() {
        assertEquals(
            "2ACD 5B flags=0x0100 decoded: speed, HR | not in frame: cadence, power, distance, energy, elapsed",
            FitnessMachineProbe.frameSummary("2ACD", treadmillHrOnly),
        )
    }

    @Test
    fun `a frame with no decodable field is reported, not silently dropped`() {
        assertEquals(
            "2ACD 2B flags=0x0001 no fields decoded | not in frame: speed, cadence, power, distance, energy, HR, elapsed",
            FitnessMachineProbe.frameSummary("2ACD", treadmillEmpty),
        )
    }

    @Test
    fun `indoor bike frame reports cadence and power`() {
        assertEquals(
            "2AD2 8B flags=0x0044 decoded: speed, cadence, power | not in frame: distance, energy, HR, elapsed",
            FitnessMachineProbe.frameSummary("2AD2", indoorBike),
        )
    }

    @Test
    fun `cross trainer flags print as six hex digits because the field is 24-bit`() {
        assertEquals(
            "2ACE 10B flags=0x000808 decoded: speed, cadence, HR | not in frame: power, distance, energy, elapsed",
            FitnessMachineProbe.frameSummary("2ACE", crossTrainer),
        )
    }

    @Test
    fun `rower frame reports stroke rate as cadence`() {
        assertEquals(
            "2AD1 10B flags=0x0804 decoded: cadence, distance, elapsed | not in frame: speed, power, energy, HR",
            FitnessMachineProbe.frameSummary("2AD1", rower),
        )
    }

    @Test
    fun `an unknown characteristic produces no line rather than a guess`() {
        assertNull(FitnessMachineProbe.frameSummary("180D", treadmillFull))
        assertNull(FitnessMachineProbe.flags("180D", treadmillFull))
        assertNull(FitnessMachineProbe.kindForUuid16("180D"))
    }

    @Test
    fun `a frame too short to hold its own flags field produces no line`() {
        // One byte cannot hold a 16-bit flags word, so there is nothing attributable to report.
        assertNull(FitnessMachineProbe.frameSummary("2ACD", pack(intArrayOf(0x00))))
        assertNull(FitnessMachineProbe.flags("2ACD", pack(intArrayOf(0x00))))
        // A cross trainer needs THREE bytes: two is enough for a treadmill and not for this.
        assertNull(FitnessMachineProbe.frameSummary("2ACE", pack(le16(0x0000))))
        assertNull(FitnessMachineProbe.flags("2ACE", pack(le16(0x0000))))
    }

    // MARK: - flags

    @Test
    fun `flags reads the raw little-endian word for both widths`() {
        assertEquals(0x0484, FitnessMachineProbe.flags("2ACD", treadmillFull))
        assertEquals(0x0044, FitnessMachineProbe.flags("2AD2", indoorBike))
        assertEquals(0x0804, FitnessMachineProbe.flags("2AD1", rower))
        assertEquals(0x000808, FitnessMachineProbe.flags("2ACE", crossTrainer))
    }

    @Test
    fun `flags width is 3 bytes only for the cross trainer`() {
        assertEquals(3, FitnessMachineProbe.flagsWidth(FitnessMachine.MachineKind.CROSS_TRAINER))
        assertEquals(2, FitnessMachineProbe.flagsWidth(FitnessMachine.MachineKind.TREADMILL))
        assertEquals(2, FitnessMachineProbe.flagsWidth(FitnessMachine.MachineKind.INDOOR_BIKE))
        assertEquals(2, FitnessMachineProbe.flagsWidth(FitnessMachine.MachineKind.ROWER))
    }

    // MARK: - shortLabel

    @Test
    fun `a base-UUID service prints as its 16-bit short form`() {
        assertEquals("0x1826", FitnessMachineProbe.shortLabel("00001826-0000-1000-8000-00805f9b34fb"))
        assertEquals("0x180D", FitnessMachineProbe.shortLabel("0000180d-0000-1000-8000-00805f9b34fb"))
        assertEquals("0x2ACD", FitnessMachineProbe.shortLabel("00002ACD-0000-1000-8000-00805F9B34FB"))
    }

    @Test
    fun `a vendor UUID is printed in full because it is the finding`() {
        // On a machine serving no standard fitness service, the proprietary UUIDs are the whole diagnosis.
        val vendor = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        assertEquals(vendor, FitnessMachineProbe.shortLabel(vendor))
        // A 32-bit assigned number does NOT sit on the base UUID in the 0000xxxx slot; it must not be
        // truncated to its high half, which would print two different services identically.
        assertEquals(
            "12341826-0000-1000-8000-00805f9b34fb",
            FitnessMachineProbe.shortLabel("12341826-0000-1000-8000-00805f9b34fb"),
        )
    }
}
