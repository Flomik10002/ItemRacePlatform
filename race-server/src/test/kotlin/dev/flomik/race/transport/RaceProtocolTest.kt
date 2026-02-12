package dev.flomik.race.transport

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RaceProtocolTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun parseHelloMessage() {
        val parsed = ClientMessageParser.parse(
            json,
            """{"type":"hello","playerId":"p1","name":"Alice","sessionId":"s1"}""",
        )

        assertTrue(parsed is ClientMessage.Hello)
        assertEquals("p1", parsed.playerId)
        assertEquals("Alice", parsed.name)
        assertEquals("s1", parsed.sessionId)
    }

    @Test
    fun parseFinishWithNumericStrings() {
        val parsed = ClientMessageParser.parse(
            json,
            """{"type":"finish","rttMs":"1234","igtMs":"1200"}""",
        )

        assertTrue(parsed is ClientMessage.Finish)
        assertEquals(1234L, parsed.rttMs)
        assertEquals(1200L, parsed.igtMs)
    }

    @Test
    fun parseSyncState() {
        val parsed = ClientMessageParser.parse(
            json,
            """{"type":"sync_state"}""",
        )

        assertTrue(parsed is ClientMessage.SyncState)
    }

    @Test
    fun parseCancelStart() {
        val parsed = ClientMessageParser.parse(
            json,
            """{"type":"cancel_start"}""",
        )

        assertTrue(parsed is ClientMessage.CancelStart)
    }

    @Test
    fun parseLeaveMatch() {
        val parsed = ClientMessageParser.parse(
            json,
            """{"type":"leave_match"}""",
        )

        assertTrue(parsed is ClientMessage.LeaveMatch)
    }

    @Test
    fun parseAdvancement() {
        val parsed = ClientMessageParser.parse(
            json,
            """{"type":"advancement","id":"minecraft:story/mine_stone"}""",
        )

        assertTrue(parsed is ClientMessage.Advancement)
        assertEquals("minecraft:story/mine_stone", parsed.id)
    }

    @Test
    fun missingTypeProducesError() {
        val error = assertFailsWith<ProtocolException> {
            ClientMessageParser.parse(json, """{"playerId":"p1"}""")
        }

        assertEquals("Missing or invalid 'type'", error.message)
    }

    @Test
    fun unknownTypeProducesError() {
        val error = assertFailsWith<ProtocolException> {
            ClientMessageParser.parse(json, """{"type":"legacy_command"}""")
        }

        assertEquals("Unknown message type", error.message)
    }
}
