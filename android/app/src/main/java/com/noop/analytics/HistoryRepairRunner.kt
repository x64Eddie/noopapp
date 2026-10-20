package com.noop.analytics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the one-shot full-history Effort / sleep-wear repair ([IntelligenceEngine.runEffortRescoreIfNeeded]) for
 * the life of the PROCESS, not of a screen.
 *
 * The repair re-scores up to [IntelligenceEngine.EFFORT_RESCORE_HISTORY_DAYS] days under the same lock every
 * scoring pass takes, and is marked done only when the whole pass returns. Launched from viewModelScope before
 * the 15-minute loop, as it was, two things went wrong on a long history:
 *  - every other pass queued behind it, so last night stayed unscored and Sleep sat on "Calculating last
 *    night's sleep" for as long as the repair ran, with the post-offload pass waiting on the lock;
 *  - leaving the app cancelled it with the ViewModel, so on two years of data it could restart from nothing on
 *    every launch and never finish, holding the lock each time.
 *
 * The caller now runs the recent-window pass first and hands the repair here. This scope is not cancelled by a
 * screen, so the repair keeps going while the process is alive (the connection service keeps it alive while a
 * strap is connected). Process death still interrupts it; the done flags are untouched then, so it retries on
 * the next launch exactly as before. [inFlight] keeps a recreated ViewModel from queueing a second full pass
 * behind the first: the pending check happens before the lock, so without it both would run.
 *
 * Start, finish and failure are logged. Nothing recorded the repair before, so a log showed the post-offload
 * pass's trigger line and then silence, and nothing named what the pass was waiting behind.
 */
object HistoryRepairRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = AtomicBoolean(false)

    /** Launch [repair] once in the process scope when [isPending]. Returns false when it was not launched. */
    fun launchIfPending(
        isPending: () -> Boolean,
        log: (String) -> Unit,
        onDone: () -> Unit,
        repair: suspend () -> Unit,
    ): Boolean {
        if (!isPending() || !inFlight.compareAndSet(false, true)) return false
        scope.launch { runClaimed(log, onDone, repair) }
        return true
    }

    /**
     * The body of one run, for a caller that has already claimed [inFlight], which it releases. Internal so
     * the test drives it without the process scope. [onDone] runs only when [repair] returns.
     */
    internal suspend fun runClaimed(
        log: (String) -> Unit,
        onDone: () -> Unit,
        repair: suspend () -> Unit,
        nanoTime: () -> Long = System::nanoTime,
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
            log("History repair: failed after ${(nanoTime() - start) / 1_000_000_000} s " +
                "(${e.javaClass.simpleName}); retries next launch")
        } finally {
            inFlight.set(false)
        }
    }
}
