package com.noop.analytics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the one-shot full-history Effort / sleep-wear repair ([IntelligenceEngine.runEffortRescoreIfNeeded]) for
 * the life of the PROCESS, not of a screen, and only while the phone is on external power.
 *
 * The repair re-scores up to [IntelligenceEngine.EFFORT_RESCORE_HISTORY_DAYS] days in ONE pass under the same
 * lock every scoring pass takes, and is marked done only when the whole pass returns. Launched from
 * viewModelScope before the 15-minute loop, as it was, two things went wrong on a long history:
 *  - every other pass queued behind it, so last night stayed unscored and Sleep sat on "Calculating last
 *    night's sleep" for as long as the repair ran, with the post-offload pass waiting on the lock;
 *  - leaving the app cancelled it with the ViewModel, so on two years of data it could restart from nothing on
 *    every launch and never finish, holding the lock each time.
 *
 * The caller now runs the recent-window pass first and hands the repair here. This scope is not cancelled by a
 * screen, so the repair keeps going while the process is alive. Process death still interrupts it; the done
 * flags are untouched then, so it retries on the next launch. [inFlight] keeps a recreated ViewModel from
 * queueing a second full pass behind the first: the pending check happens before the lock, so without it both
 * would run.
 *
 * Moving it off the main thread was not enough. A normal 21-night pass takes 75-100 s on a Pixel, so the full
 * history is a 30-60 minute pass; started on battery it was still holding the lock when the morning offload
 * landed, and Android commonly killed it first, so it began again from day one next launch. [onPower] holds it
 * back until the phone is plugged in, typically overnight, when Doze does not apply and no one is waiting on
 * last night's score. The pass has no cancellation points, so unplugging does not stop a run that started; the
 * caller asks again on each loop tick so the repair starts within a tick of the phone being plugged in.
 *
 * A failed run is not retried for [FAILURE_BACKOFF_MS], since a repair that fails every time would otherwise
 * re-run a full pass on every tick. Start, finish, failure and the wait for power are logged.
 */
object HistoryRepairRunner {

    /** How long a failed run waits before the next attempt in the same process. */
    const val FAILURE_BACKOFF_MS: Long = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = AtomicBoolean(false)
    private val waitLogged = AtomicBoolean(false)

    @Volatile
    private var retryNotBeforeMs = 0L

    /**
     * Launch [repair] once in the process scope when [isPending] and the phone is [onPower]. Returns false when
     * it was not launched.
     */
    fun launchIfPending(
        isPending: () -> Boolean,
        log: (String) -> Unit,
        onDone: () -> Unit,
        onPower: () -> Boolean = { true },
        nowMs: () -> Long = System::currentTimeMillis,
        repair: suspend () -> Unit,
    ): Boolean {
        if (!isPending() || nowMs() < retryNotBeforeMs) return false
        if (!onPower()) {
            if (waitLogged.compareAndSet(false, true)) {
                log("History repair: pending; waits until the phone is on power")
            }
            return false
        }
        if (!inFlight.compareAndSet(false, true)) return false
        waitLogged.set(false)
        scope.launch {
            runClaimed(log, onDone, repair, onFailed = { retryNotBeforeMs = nowMs() + FAILURE_BACKOFF_MS })
        }
        return true
    }

    /**
     * The body of one run, for a caller that has already claimed [inFlight], which it releases. Internal so
     * the test drives it without the process scope. [onDone] runs only when [repair] returns; [onFailed] only
     * when it throws something other than a cancellation, before the latch is released.
     */
    internal suspend fun runClaimed(
        log: (String) -> Unit,
        onDone: () -> Unit,
        repair: suspend () -> Unit,
        nanoTime: () -> Long = System::nanoTime,
        onFailed: () -> Unit = {},
    ) {
        val start = nanoTime()
        log("History repair: started (full-history re-score, runs once after an upgrade)")
        try {
            repair()
            log("History repair: done in ${(nanoTime() - start) / 1_000_000_000} s")
            onDone()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            log("History repair: interrupted after ${(nanoTime() - start) / 1_000_000_000} s; retries next launch")
            throw e
        } catch (e: Throwable) {
            onFailed()
            log("History repair: failed after ${(nanoTime() - start) / 1_000_000_000} s " +
                "(${e.javaClass.simpleName}); retries in ${FAILURE_BACKOFF_MS / 3_600_000} h")
        } finally {
            inFlight.set(false)
        }
    }
}
