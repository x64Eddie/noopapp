package com.noop.screen

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The screen-state log records only a timestamp and an event type. These tests pin the mapping from the
 * system broadcasts, the stable numeric codes the stored rows depend on, and the real SQLite behaviour of
 * the store (window reads, ordering, pruning, and a row it does not recognise).
 */
@RunWith(RobolectricTestRunner::class)
class ScreenStateLogTest {

    private fun ctx(): Context = RuntimeEnvironment.getApplication()

    @Test
    fun `the three screen broadcasts map to their kinds and nothing else does`() {
        assertEquals(ScreenEventKind.SCREEN_ON, ScreenEventKind.fromAction(Intent.ACTION_SCREEN_ON))
        assertEquals(ScreenEventKind.SCREEN_OFF, ScreenEventKind.fromAction(Intent.ACTION_SCREEN_OFF))
        assertEquals(ScreenEventKind.UNLOCK, ScreenEventKind.fromAction(Intent.ACTION_USER_PRESENT))
        assertNull(ScreenEventKind.fromAction(Intent.ACTION_BATTERY_LOW))
        assertNull(ScreenEventKind.fromAction(null))
    }

    @Test
    fun `codes round trip and are never reused`() {
        val kinds = ScreenEventKind.values()
        assertEquals("codes are persisted, so they must be unique", kinds.size, kinds.map { it.code }.toSet().size)
        assertEquals("labels are exported, so they must be unique", kinds.size, kinds.map { it.label }.toSet().size)
        for (k in kinds) assertEquals(k, ScreenEventKind.fromCode(k.code))
        assertNull(ScreenEventKind.fromCode(99))
        // The persisted codes are a contract: renumbering would silently reinterpret every stored row.
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            listOf(
                ScreenEventKind.SCREEN_ON, ScreenEventKind.SCREEN_OFF, ScreenEventKind.UNLOCK,
                ScreenEventKind.START_ON, ScreenEventKind.START_OFF,
            ).map { it.code },
        )
    }

    @Test
    fun `retention cutoff is the retention window before now`() {
        assertEquals(1_000_000_000L - 120L * 86_400L, ScreenStateLog.retentionCutoff(1_000_000_000L))
        assertEquals(1_000_000_000L - 7L * 86_400L, ScreenStateLog.retentionCutoff(1_000_000_000L, retentionDays = 7))
    }

    @Test
    fun `store returns the window inclusive, oldest first, in insert order for equal timestamps`() {
        val s = ScreenEventStore(ctx())
        s.insert(ScreenEvent(300, ScreenEventKind.SCREEN_OFF))
        s.insert(ScreenEvent(100, ScreenEventKind.SCREEN_ON))
        s.insert(ScreenEvent(200, ScreenEventKind.SCREEN_ON))
        s.insert(ScreenEvent(200, ScreenEventKind.UNLOCK))
        s.insert(ScreenEvent(400, ScreenEventKind.SCREEN_ON))
        assertEquals(
            listOf(
                ScreenEvent(200, ScreenEventKind.SCREEN_ON),
                ScreenEvent(200, ScreenEventKind.UNLOCK),
                ScreenEvent(300, ScreenEventKind.SCREEN_OFF),
            ),
            s.between(200, 300),
        )
        assertTrue(s.between(500, 600).isEmpty())
        s.close()
    }

    @Test
    fun `prune removes only what is older than the cutoff`() {
        val s = ScreenEventStore(ctx())
        s.insert(ScreenEvent(10, ScreenEventKind.SCREEN_ON))
        s.insert(ScreenEvent(20, ScreenEventKind.SCREEN_OFF))
        s.insert(ScreenEvent(30, ScreenEventKind.SCREEN_ON))
        assertEquals(2, s.pruneBefore(30))
        assertEquals(listOf(ScreenEvent(30, ScreenEventKind.SCREEN_ON)), s.between(0, 100))
        s.close()
    }

    @Test
    fun `a stored row with an unknown kind code is skipped, not fatal`() {
        val s = ScreenEventStore(ctx())
        s.writableDatabase.execSQL("INSERT INTO screen_event (ts, kind) VALUES (50, 99)")
        s.insert(ScreenEvent(60, ScreenEventKind.UNLOCK))
        assertEquals(listOf(ScreenEvent(60, ScreenEventKind.UNLOCK)), s.between(0, 100))
        s.close()
    }
}
