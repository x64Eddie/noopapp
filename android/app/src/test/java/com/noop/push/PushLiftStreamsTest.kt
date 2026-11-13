package com.noop.push

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

/**
 * Fork-only protocol 1.1: the strength log as two replace-window streams.
 *
 * A 1.0 receiver must see exactly the upstream behaviour, so every test here also pins what 1.0 does.
 */
class PushLiftStreamsTest {
    private val source = "3a3486dd-5030-4e17-a00d-a781399890f9"
    private val window = PushWindow.ending(LocalDate.of(2026, 10, 23), ZoneOffset.UTC)

    private fun session(id: String, startTs: Long) = PushMutableRecord(
        mapOf("id" to id),
        mapOf(
            "startTs" to startTs, "endTs" to startTs + 3600, "sport" to "Strength Training",
            "programId" to null, "programName" to "Upper A", "sessionRpe" to 8.0, "note" to null,
        ),
    )

    private fun set(id: String, sessionStartTs: Long) = PushMutableRecord(
        mapOf("id" to id),
        mapOf(
            "sessionId" to "sess-1", "ord" to 0L, "exercise" to "Bench Press", "primaryMuscle" to "chest",
            "secondaryMuscles" to "triceps", "setIndex" to 1L, "weightKg" to 80.0, "reps" to 8L, "rpe" to 8.5,
            "isWarmup" to false, "startTs" to null, "endTs" to null, "restSec" to 120L, "note" to null,
            "sessionStartTs" to sessionStartTs,
        ),
    )

    @Test
    fun liftStreamsBelongOnlyToVersionOnePointOne() {
        assertFalse(PushMutableTable.LIFT_SET.availableIn("1.0"))
        assertTrue(PushMutableTable.LIFT_SET.availableIn("1.1"))
        assertTrue(PushMutableTable.JOURNAL.availableIn("1.1"))
        assertEquals(4, PushCapabilities.ALL.mutableTables.size)
        assertEquals(6, PushCapabilities.forVersion("1.1").mutableTables.size)
        assertEquals(14, PushCapabilities.ALL_KNOWN_WIRE_NAMES.size)
    }

    @Test
    fun capabilitiesNegotiateTheSelectedVersion() {
        val v11 = PushCapabilities.parse(
            """{"type":"capabilities","protocolVersion":"1.1","receiverStateId":"00000000-0000-4000-8000-000000000099","streams":["hrSample","liftSession","liftSet"]}"""
                .toByteArray(),
        )
        assertEquals("1.1", v11.protocolVersion)
        assertEquals(setOf(PushMutableTable.LIFT_SESSION, PushMutableTable.LIFT_SET), v11.mutableTables)

        // A 1.0 document naming a 1.1 stream fails closed, exactly like an unknown name.
        assertThrows(PushProtocolException::class.java) {
            PushCapabilities.parse(
                """{"type":"capabilities","protocolVersion":"1.0","receiverStateId":"00000000-0000-4000-8000-000000000099","streams":["liftSet"]}"""
                    .toByteArray(),
            )
        }
        assertThrows(PushProtocolException::class.java) {
            PushCapabilities.parse(
                """{"type":"capabilities","protocolVersion":"2.0","receiverStateId":"00000000-0000-4000-8000-000000000099","streams":[]}"""
                    .toByteArray(),
            )
        }
    }

    @Test
    fun liftBatchesRefuseToClaimVersionOnePointZero() {
        assertThrows(PushProtocolException::class.java) {
            PushProtocol.mutableBatches(PushMutableTable.LIFT_SESSION, source, "strap-a", window, emptyList())
        }
    }

    @Test
    fun liftSessionAndSetHeadersCarryTheirSelectors() {
        val start = window.startTsInclusive + 3600
        val sess = PushProtocol.mutableBatch(
            PushMutableTable.LIFT_SESSION, source, "strap-a", window, listOf(session("sess-1", start)), "1.1",
        )
        val sessHeader = JSONObject(sess.body.toString(Charsets.UTF_8).lines().first())
        assertEquals("1.1", sessHeader.getString("protocolVersion"))
        assertEquals("startTs", sessHeader.getJSONObject("window").getString("selector"))
        assertEquals("1.1", sess.protocolVersion)

        val sets = PushProtocol.mutableBatch(
            PushMutableTable.LIFT_SET, source, "strap-a", window, listOf(set("set-1", start)), "1.1",
        )
        val lines = sets.body.toString(Charsets.UTF_8).trimEnd().lines()
        assertEquals("sessionStartTs", JSONObject(lines[0]).getJSONObject("window").getString("selector"))
        val record = JSONObject(lines[1])
        assertEquals("set-1", record.getJSONObject("key").getString("id"))
        assertEquals(false, record.getJSONObject("data").getBoolean("isWarmup"))

        // The ack the receiver echoes for a replace-window part carries a null cursor and the 1.1 label.
        assertTrue(PushAck.fromBatch(sets).exactlyMatches(sets))
    }

    @Test
    fun upstreamStreamsStillDefaultToVersionOnePointZero() {
        val batch = PushProtocol.mutableBatch(PushMutableTable.JOURNAL, source, "strap-a", window, emptyList())
        assertEquals("1.0", batch.protocolVersion)
    }

    /**
     * Cross-language oracle for the private receiver: prints the exact NDJSON entity this encoder
     * produces for a fixed set row. The receiver's test suite parses these bytes verbatim.
     */
    @Test
    fun printsOracleFixtureForTheReceiver() {
        val fixed = PushWindow.ending(LocalDate.of(2026, 10, 23), ZoneOffset.UTC)
        val batch = PushProtocol.mutableBatch(
            PushMutableTable.LIFT_SET, source, "strap-a", fixed,
            listOf(set("set-1", fixed.startTsInclusive + 3600)), "1.1",
        )
        println("LIFT_SET_ORACLE_B64=" + Base64.getEncoder().encodeToString(batch.body))
        println("LIFT_SET_ORACLE_BATCH_ID=" + batch.batchId)
    }
}
