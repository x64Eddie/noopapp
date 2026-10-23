package com.noop.analytics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The full-history repair runner: it logs start and finish, calls onDone only when the repair returns, never
 * runs two repairs at once, and releases its in-flight latch however a run ends so a later launch can retry.
 */
class HistoryRepairRunnerTest {

    private var clock = 0L
    private val tick: () -> Long = { clock.also { clock += 90_000_000_000L } }

    @Test
    fun successLogsStartAndDurationAndCallsOnDone() = runBlocking {
        val log = mutableListOf<String>()
        var done = 0
        var ran = 0
        HistoryRepairRunner.runClaimed(log::add, { done++ }, { ran++ }, tick)
        assertEquals(1, ran)
        assertEquals(1, done)
        assertEquals(
            listOf(
                "History repair: started (full-history re-score, runs once after an upgrade)",
                "History repair: done in 90 s",
            ),
            log,
        )
    }

    @Test
    fun failureIsLoggedWithoutOnDone() = runBlocking {
        val log = mutableListOf<String>()
        var done = 0
        HistoryRepairRunner.runClaimed(log::add, { done++ }, { throw IllegalStateException("boom") }, tick)
        assertEquals(0, done)
        assertEquals("History repair: failed after 90 s (IllegalStateException); retries in 6 h", log.last())
    }

    @Test
    fun cancellationIsLoggedAndRethrown() = runBlocking {
        val log = mutableListOf<String>()
        var done = 0
        try {
            HistoryRepairRunner.runClaimed(log::add, { done++ }, { throw CancellationException("gone") }, tick)
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertEquals(0, done)
        assertEquals("History repair: interrupted after 90 s; retries next launch", log.last())
    }

    @Test
    fun notLaunchedWhenNothingIsPending() {
        var ran = false
        val launched = HistoryRepairRunner.launchIfPending({ false }, {}, {}) { ran = true }
        assertFalse(launched)
        assertFalse(ran)
    }

    @Test
    fun aSecondLaunchIsRefusedWhileOneRunsAndAFailureBacksOff() = runBlocking {
        // A fake clock of its own: the back-off this sets lies far before real time, so other tests, which use
        // the system clock, are unaffected by it.
        var now = 1_000L
        val clock = { now }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        assertTrue(HistoryRepairRunner.launchIfPending({ true }, {}, {}, nowMs = clock) {
            started.complete(Unit)
            try { release.await(); throw IllegalStateException("first run fails") } finally { ended.complete(Unit) }
        })
        withTimeout(5_000) { started.await() }
        var secondRan = false
        assertFalse("a second full pass must not queue behind the first",
            HistoryRepairRunner.launchIfPending({ true }, {}, {}, nowMs = clock) { secondRan = true })
        release.complete(Unit)
        withTimeout(5_000) { ended.await() }
        // The latch is released in `finally`, just after the repair body returns; give it a moment.
        kotlinx.coroutines.delay(200)
        assertFalse("a failed run must not retry inside the back-off",
            HistoryRepairRunner.launchIfPending({ true }, {}, {}, nowMs = clock) { secondRan = true })
        now += HistoryRepairRunner.FAILURE_BACKOFF_MS
        val retried = CompletableDeferred<Unit>()
        withTimeout(5_000) {
            while (!HistoryRepairRunner.launchIfPending({ true }, {}, {}, nowMs = clock) { retried.complete(Unit) }) {
                kotlinx.coroutines.delay(10)
            }
            retried.await()
        }
        assertFalse(secondRan)
    }

    @Test
    fun waitsForPowerAndLogsTheWaitOnce() = runBlocking {
        // One launch on power resets the once-only wait line, whatever an earlier test logged. The wait is
        // checked before the in-flight latch, so this run need not have finished for the lines below.
        val reset = CompletableDeferred<Unit>()
        withTimeout(5_000) {
            while (!HistoryRepairRunner.launchIfPending({ true }, {}, {}) { reset.complete(Unit) }) {
                kotlinx.coroutines.delay(10)
            }
            reset.await()
        }
        val log = mutableListOf<String>()
        var ran = false
        assertFalse(HistoryRepairRunner.launchIfPending({ true }, log::add, {}, onPower = { false }) { ran = true })
        assertFalse(HistoryRepairRunner.launchIfPending({ true }, log::add, {}, onPower = { false }) { ran = true })
        assertFalse(ran)
        assertEquals(listOf("History repair: pending; waits until the phone is on power"), log)
    }

    @Test
    fun nothingPendingIsSilentOffPower() {
        val log = mutableListOf<String>()
        assertFalse(HistoryRepairRunner.launchIfPending({ false }, log::add, {}, onPower = { false }) {})
        assertTrue(log.isEmpty())
    }
}
