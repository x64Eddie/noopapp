package com.noop.ingest

import com.noop.screen.ScreenEvent
import com.noop.screen.ScreenEventKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The per-night export cuts the strap's data into local noon-to-noon windows, so a sleep is never split in
 * half the way a rolling 24 h window ending at an arbitrary time can split it. These pin the windows,
 * including the day a clock change makes a night 25 hours long, and the exact row the phone-screen events
 * add to the CSV.
 */
class RawSensorExportNightsTest {

    private val denver = ZoneId.of("America/Denver")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, denver).toEpochSecond()

    @Test
    fun `evening export gives the night that ended this morning first`() {
        val now = at(2026, 10, 18, 18)
        val w = RawSensorExport.nightWindows(now, nights = 3, zone = denver)
        assertEquals(listOf("night-ending-2026-10-18", "night-ending-2026-10-17", "night-ending-2026-10-16"), w.map { it.label })
        assertEquals(at(2026, 10, 17, 12), w[0].from)
        assertEquals(at(2026, 10, 18, 12), w[0].to)
        assertEquals(at(2026, 10, 16, 12), w[1].from)
        assertEquals(at(2026, 10, 17, 12), w[1].to)
    }

    @Test
    fun `a morning export ends the newest window at now, not at a noon in the future`() {
        val now = at(2026, 10, 18, 8, 30)
        val w = RawSensorExport.nightWindows(now, nights = 2, zone = denver)
        assertEquals(at(2026, 10, 17, 12), w[0].from)
        assertEquals(now, w[0].to)
        assertEquals(at(2026, 10, 17, 12), w[1].to)
    }

    @Test
    fun `windows tile with no gap and no overlap`() {
        val w = RawSensorExport.nightWindows(at(2026, 10, 18, 18), nights = 7, zone = denver)
        for (i in 0 until w.size - 1) assertEquals("window ${i + 1} must end where window $i begins", w[i].from, w[i + 1].to)
    }

    @Test
    fun `the night a clock change falls in is 25 hours long`() {
        // Denver ends daylight saving on 2026-11-01: the window ending at noon that day spans 25 hours.
        val w = RawSensorExport.nightWindows(at(2026, 11, 1, 18), nights = 1, zone = denver)
        assertEquals("night-ending-2026-11-01", w[0].label)
        assertEquals(25L * 3600L, w[0].to - w[0].from)
    }

    @Test
    fun `a phone-screen event is one event row with the label in event_kind and no payload`() {
        val row = RawSensorExport.screenEventLine(ScreenEvent(1_792_281_699L, ScreenEventKind.SCREEN_ON))
        // 18 columns: unix_s,iso_utc,stream, then 15 cells of which index 13 (event_kind) is filled.
        assertEquals("1792281699,2026-10-18T00:01:39Z,event" + ",".repeat(14) + "PHONE_SCREEN_ON,", row)
        assertEquals(18, row.split(",").size)
    }
}
