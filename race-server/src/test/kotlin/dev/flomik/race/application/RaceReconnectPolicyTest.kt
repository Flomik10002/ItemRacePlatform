package dev.flomik.race.application

import dev.flomik.race.domain.LeaveReason
import dev.flomik.race.domain.PlayerStatus
import dev.flomik.race.support.DeterministicRandomSource
import dev.flomik.race.support.MutableClock
import dev.flomik.race.support.SequentialIdGenerator
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse

class RaceReconnectPolicyTest {
    private fun createService(clock: MutableClock): RaceService {
        return RaceService(
            reconnectGraceMs = 45_000L,
            clock = clock,
            idGenerator = SequentialIdGenerator(),
            randomSource = DeterministicRandomSource(longs = mutableListOf(1234L)),
        )
    }

    @Test
    fun reconnectWithinGraceKeepsRunningState() = runTest {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val service = createService(clock)

        val p1 = service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)

        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)
        service.rollMatch("p1")
        service.startMatch("p1")

        service.disconnect("p1", p1.sessionId)

        clock.advanceMillis(20_000L)
        val resumed = service.connect("p1", "Alice", p1.sessionId)
        assertEquals(true, resumed.resumed)

        clock.advanceMillis(30_000L)
        service.handleReconnectTimeout("p1", p1.sessionId)

        val match = service.snapshotFor("p2").room?.currentMatch
        assertNotNull(match)
        val p1State = match.players.firstOrNull { it.playerId == "p1" }
        assertNotNull(p1State)
        assertEquals(PlayerStatus.RUNNING, p1State.status)
    }

    @Test
    fun timeoutConvertsToLeaveAndRemovedAfterMatchCompletion() = runTest {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val service = createService(clock)

        val p1 = service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)

        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)
        service.rollMatch("p1")
        service.startMatch("p1")

        service.disconnect("p1", p1.sessionId)
        clock.advanceMillis(46_000L)
        service.handleReconnectTimeout("p1", p1.sessionId)

        val running = service.snapshotFor("p2").room?.currentMatch
        assertNotNull(running)
        val p1State = running.players.firstOrNull { it.playerId == "p1" }
        assertNotNull(p1State)
        assertEquals(PlayerStatus.LEAVE, p1State.status)
        assertEquals(LeaveReason.RECONNECT_TIMEOUT, p1State.leaveReason)

        service.finish("p2", rttMs = 900, igtMs = 850)

        val after = service.snapshotFor("p2").room
        assertNotNull(after)
        assertEquals(listOf("p2"), after.players.map { it.playerId })
    }

    @Test
    fun disconnectedPlayerWithoutRoomIsPrunedAfterGraceTimeout() = runTest {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val service = createService(clock)

        val firstConnect = service.connect("p1", "Alice", null)
        service.disconnect("p1", firstConnect.sessionId)

        clock.advanceMillis(46_000L)
        service.handleReconnectTimeout("p1", firstConnect.sessionId)

        val reconnect = service.connect("p1", "Alice", firstConnect.sessionId)
        assertFalse(reconnect.resumed)
    }
}
