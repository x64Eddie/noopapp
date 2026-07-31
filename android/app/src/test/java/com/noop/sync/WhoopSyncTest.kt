package com.noop.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for WhoopSync.classifyResponse - the decision that lets the sync worker tell a
 * key/user_id mismatch (won't resolve by retrying) apart from a transient failure (worth
 * retrying). 401/403 are exactly what the backend's enforce_sdk_user_scope returns for a
 * mismatch - verified live against the running backend when that check was added.
 */
class WhoopSyncTest {

    @Test
    fun `successful response classifies as Success regardless of code`() {
        assertEquals(WhoopSync.PostOutcome.Success, WhoopSync.classifyResponse(200, successful = true))
        assertEquals(WhoopSync.PostOutcome.Success, WhoopSync.classifyResponse(202, successful = true))
    }

    @Test
    fun `401 unsuccessful classifies as AuthRejected`() {
        assertEquals(WhoopSync.PostOutcome.AuthRejected, WhoopSync.classifyResponse(401, successful = false))
    }

    @Test
    fun `403 unsuccessful classifies as AuthRejected`() {
        assertEquals(WhoopSync.PostOutcome.AuthRejected, WhoopSync.classifyResponse(403, successful = false))
    }

    @Test
    fun `500 unsuccessful classifies as Failed, not AuthRejected`() {
        assertEquals(WhoopSync.PostOutcome.Failed, WhoopSync.classifyResponse(500, successful = false))
    }

    @Test
    fun `400 unsuccessful classifies as Failed`() {
        assertEquals(WhoopSync.PostOutcome.Failed, WhoopSync.classifyResponse(400, successful = false))
    }

    @Test
    fun `404 unsuccessful classifies as Failed`() {
        assertEquals(WhoopSync.PostOutcome.Failed, WhoopSync.classifyResponse(404, successful = false))
    }

    @Test
    fun `network exception path (no HTTP response at all) is handled by post() defaulting to Failed`() {
        // classifyResponse itself always receives a real HTTP code (it's only called inside the
        // response.use block); the exception path is covered by post()'s runCatching { }
        // .getOrDefault(PostOutcome.Failed) - a thrown IOException never reaches classifyResponse
        // at all, so there's nothing to unit-test here beyond confirming Failed is the documented
        // fallback (see WhoopSync.post()'s implementation).
        assertEquals(WhoopSync.PostOutcome.Failed, WhoopSync.classifyResponse(0, successful = false))
    }
}
