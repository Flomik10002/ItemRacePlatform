package dev.flomik.race.application

import dev.flomik.race.domain.ConnectionState
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaceServiceAdminTest {
    private fun createService(clock: MutableClock): RaceService {
        return RaceService(
            reconnectGraceMs = 45_000L,
            clock = clock,
            idGenerator = SequentialIdGenerator(),
            randomSource = DeterministicRandomSource(longs = mutableListOf(111L, 222L, 333L)),
        )
    }

    @Test
    fun adminKickRemovesPlayerFromRoom() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)
        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)

        val affected = service.adminKickPlayer(roomCode, "p2")
        assertTrue("p1" in affected)
        assertTrue("p2" in affected)

        val room = service.snapshotFor("p1").room
        assertNotNull(room)
        assertEquals(listOf("p1"), room.players.map { it.playerId })
    }

    @Test
    fun adminForceLeaveMatchUnsticksSoloMatch() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.rollMatch("p1")
        service.startMatch("p1")

        service.adminForceLeaveMatch(roomCode, "p1")

        val room = service.snapshotFor("p1").room
        assertNotNull(room)
        assertNull(room.currentMatch)
        assertEquals(listOf("p1"), room.players.map { it.playerId })
    }

    @Test
    fun adminAbortMatchRestoresPending() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)
        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)
        service.rollMatch("p1")
        service.startMatch("p1")

        val affected = service.adminAbortMatch(roomCode)
        assertTrue("p1" in affected)
        assertTrue("p2" in affected)

        val room = service.snapshotFor("p1").room
        assertNotNull(room)
        assertNull(room.currentMatch)
        assertNotNull(room.pendingMatch)
    }

    @Test
    fun adminOverviewIncludesDetachedDisconnectedPlayers() = runTest {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val service = createService(clock)

        val session = service.connect("p1", "Alice", null)
        service.disconnect("p1", session.sessionId)
        clock.advanceMillis(5_000L)

        val overview = service.adminOverview()
        assertEquals(0, overview.rooms.size)
        assertEquals(1, overview.detachedPlayers.size)
        assertEquals("p1", overview.detachedPlayers.single().playerId)
        assertEquals(ConnectionState.DISCONNECTED, overview.detachedPlayers.single().connectionState)
    }

    @Test
    fun adminRemoveDisconnectedPlayersUsesReconnectTimeoutReason() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        val p2 = service.connect("p2", "Bob", null)

        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)
        service.rollMatch("p1")
        service.startMatch("p1")
        service.disconnect("p2", p2.sessionId)

        service.adminRemoveDisconnectedPlayers(roomCode)
        val room = service.snapshotFor("p1").room
        assertNotNull(room)
        val match = room.currentMatch
        assertNotNull(match)
        val p2State = match.players.firstOrNull { it.playerId == "p2" }
        assertNotNull(p2State)
        assertEquals(PlayerStatus.LEAVE, p2State.status)
        assertEquals(LeaveReason.RECONNECT_TIMEOUT, p2State.leaveReason)
    }
}
