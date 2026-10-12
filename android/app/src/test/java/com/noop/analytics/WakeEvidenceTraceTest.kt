package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.protocol.HrSample
import com.noop.protocol.RrInterval
import com.noop.data.SleepStateSampleEntity
import com.noop.protocol.DeviceFamily
import org.junit.Assert.*
import org.junit.Test

class WakeEvidenceTraceTest {

    private fun hrSample(ts: Int, bpm: Int) = HrSample(ts, bpm)
    private fun rrSample(ts: Int, ms: Int) = RrInterval(ts, ms)
    private fun gravSample(ts: Int, x: Double, y: Double, z: Double) = GravitySample("test", ts.toLong(), x, y, z)
    private fun ssSample(ts: Int, state: Int) = SleepStateSampleEntity("test", ts.toLong(), state)

    @Test
    fun testAllChannelsPresent() {
        val start = 1_000_000_000
        val end = start + 120
        val presence = WakeEvidencePresence(true, true, true, true, true)

        val hr = (0 until 120).flatMap { s ->
            val ts = start + s
            (0 until 30 step 3).map { hrSample(ts + it, 60 + it % 5) }
        }
        val rr = (0 until 120).flatMap { s ->
            val ts = start + s
            (0 until 30 step 5).map { rrSample(ts + it, 800 + it * 10) }
        }
        val grav = (0 until 120).map { s ->
            val ts = start + s
            val angle = s * 0.01
            gravSample(ts, Math.sin(angle) * 0.01, Math.cos(angle) * 0.01, 0.98)
        }
        val sleepState = (0 until 120).map { s ->
            ssSample(start + s, if (s < 60) 2 else 0)
        }

        val lines = wakeEvidenceLines(start, end, DeviceFamily.WHOOP5, presence, hr, rr, grav, sleepState)

        assertTrue("should have header + interval + at least one epoch line, got ${lines.size}", lines.size > 2)
        assertTrue("header: ${lines[0]}", lines[0].contains("presence: hr rr motion band-state"))
        assertTrue("interval: ${lines[1]}", lines[1].contains("interval start=$start"))
        val firstEpoch = lines[2]
        assertTrue("should be an epoch line: $firstEpoch", firstEpoch.startsWith("epoch"))
        assertFalse("hr should be present", firstEpoch.contains("hr=N/A"))
        assertFalse("hrv should be present", firstEpoch.contains("hrvStd=N/A"))
        assertFalse("gravity should be present", firstEpoch.contains("gravDens=N/A"))
        assertFalse("band should be present", firstEpoch.contains("band=N/A"))
    }

    @Test
    fun testWhoop4NoBandState() {
        val start = 1_000_000_000
        val end = start + 60
        val presence = WakeEvidencePresence(true, true, true, false, false)

        val hr = (0 until 60).map { hrSample(start + it, 55) }
        val rr = (0 until 60).map { rrSample(start + it, 900) }
        val grav = listOf(
            gravSample(start + 0, 0.0, 0.0, 0.98),
            gravSample(start + 30, 0.01, 0.0, 0.98),
        )

        val lines = wakeEvidenceLines(start, end, DeviceFamily.WHOOP4, presence, hr, rr, grav, emptyList())

        assertTrue("family: ${lines[0]}", lines[0].contains("family=4.0"))
        assertTrue("presence: ${lines[0]}", lines[0].contains("presence: hr rr motion"))
        assertFalse("no band-state: ${lines[0]}", lines[0].contains("band-state"))

        for (i in 2 until lines.size) {
            assertTrue("absent band state N/A: ${lines[i]}", lines[i].contains("band=N/A"))
        }
    }

    @Test
    fun testAllChannelsAbsent() {
        val presence = WakeEvidencePresence(false, false, false, false, false)
        val lines = wakeEvidenceLines(1_000_000_000, 1_000_000_030, DeviceFamily.WHOOP5, presence,
            emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue("presence none: ${lines[0]}", lines[0].contains("presence: none"))
    }

    @Test
    fun testAbsentChannelNeverNumeric() {
        val start = 1_000_000_000
        val end = start + 30
        val presence = WakeEvidencePresence(false, false, true, false, false)
        val grav = listOf(gravSample(start + 0, 0.0, 0.0, 0.98))

        val lines = wakeEvidenceLines(start, end, DeviceFamily.WHOOP5, presence,
            emptyList(), emptyList(), grav, emptyList())

        for (i in 2 until lines.size) {
            assertTrue("absent HR N/A: ${lines[i]}", lines[i].contains("hr=N/A"))
            assertTrue("absent HRV N/A: ${lines[i]}", lines[i].contains("hrvStd=N/A"))
        }
    }

    @Test
    fun testPresentChannelEmptyEpoch() {
        val start = 1_000_000_000
        val end = start + 30
        val presence = WakeEvidencePresence(true, true, true, false, false)
        val grav = listOf(gravSample(start + 0, 0.0, 0.0, 0.98))

        val lines = wakeEvidenceLines(start, end, DeviceFamily.WHOOP5, presence,
            emptyList(), emptyList(), grav, emptyList())

        for (i in 2 until lines.size) {
            assertTrue("no-sample HR: ${lines[i]}", lines[i].contains("hr=no-sample"))
            assertTrue("no-sample HRV: ${lines[i]}", lines[i].contains("hrvStd=no-sample"))
        }
    }

    @Test
    fun testEpochGrid() {
        val start = 1_000_000_021
        val end = start + 120
        val preset = WakeEvidencePresence(false, false, false, false, false)

        val lines = wakeEvidenceLines(start, end, DeviceFamily.WHOOP5, preset,
            emptyList(), emptyList(), emptyList(), emptyList())

        val epochLines = lines.filter { it.startsWith("epoch ") }
        assertEquals("should have 3 epoch lines, got ${epochLines.size}: $lines", 3, epochLines.size)
    }

    @Test
    fun testPerNightBaseline() {
        assertNull(perNightBaseline(emptyList()))
        assertNull(perNightBaseline(listOf(null)))
        assertNull(perNightBaseline(listOf(50.0)))
        val b = perNightBaseline(listOf(50.0, 60.0))
        assertNotNull(b)
        assertEquals(55.0, b!!.mean, 1e-9)
        assertEquals(5.0, b.spread, 1e-9)
        val b2 = perNightBaseline(listOf(50.0, null, 60.0))
        assertNotNull(b2)
        assertEquals(55.0, b2!!.mean, 1e-9)
        assertEquals(5.0, b2.spread, 1e-9)
    }

    @Test
    fun testPerEpochZScore() {
        assertNull(perEpochZScore(null, 55.0, 5.0))
        val zHi = perEpochZScore(65.0, 55.0, 5.0)
        assertNotNull(zHi)
        assertTrue("positive z", zHi!! > 0)
        val zLo = perEpochZScore(45.0, 55.0, 5.0)
        assertNotNull(zLo)
        assertTrue("negative z", zLo!! < 0)
    }

    @Test
    fun testPerEpochMotionEmpty() {
        assertTrue(perEpochMotion(100, 200, emptyList()).isEmpty())
        assertTrue(perEpochMotion(200, 100, listOf(gravSample(150, 0.0, 0.0, 0.98))).isEmpty())
    }

    @Test
    fun testPerEpochMotionBasic() {
        val start = 1_000_000_000
        val grav = (0 until 60).map { s ->
            val th = s * 0.1
            gravSample(start + s, Math.sin(th) * 0.01, Math.cos(th) * 0.01, 0.98)
        }
        val result = perEpochMotion(start, start + 60, grav)
        assertEquals(2, result.size)
        for (m in result) {
            assertTrue("gravity density > 0: $m", m.gravityDensity > 0)
            assertTrue("jerkMax >= 0: $m", m.jerkMax >= 0.0)
        }
    }
}