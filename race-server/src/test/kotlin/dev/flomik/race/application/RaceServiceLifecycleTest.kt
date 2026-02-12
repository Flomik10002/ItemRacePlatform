package dev.flomik.race.application

import dev.flomik.race.domain.DomainException
import dev.flomik.race.domain.LeaveReason
import dev.flomik.race.domain.PlayerStatus
import dev.flomik.race.support.DeterministicRandomSource
import dev.flomik.race.support.MutableClock
import dev.flomik.race.support.SequentialIdGenerator
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RaceServiceLifecycleTest {
    private fun createService(clock: MutableClock): RaceService {
        return RaceService(
            reconnectGraceMs = 45_000L,
            clock = clock,
            idGenerator = SequentialIdGenerator(),
            randomSource = DeterministicRandomSource(longs = mutableListOf(1234L, 5678L)),
        )
    }

    @Test
    fun roomLifecycleAndMatchCompletion() = runTest {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val service = createService(clock)

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)

        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)

        service.joinRoom("p2", roomCode)
        service.rollMatch("p1")
        service.startMatch("p1")

        val running = service.snapshotFor("p2").room?.currentMatch
        assertNotNull(running)
        assertEquals(true, running.isActive)
        assertEquals(2, running.players.size)
        assertEquals(
            setOf(PlayerStatus.RUNNING),
            running.players.map { it.status }.toSet(),
        )

        service.finish("p1", rttMs = 1500, igtMs = 1480)
        service.reportDeath("p2")

        val completed = service.snapshotFor("p1").room
        assertNotNull(completed)
        assertNull(completed.currentMatch)
        assertEquals(2, completed.players.size)
    }

    @Test
    fun nonLeaderCannotRollOrStartMatch() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)
        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)

        val rollError = assertFailsWith<DomainException> {
            service.rollMatch("p2")
        }
        assertEquals("NOT_ROOM_LEADER", rollError.code)

        service.rollMatch("p1")
        val startError = assertFailsWith<DomainException> {
            service.startMatch("p2")
        }
        assertEquals("NOT_ROOM_LEADER", startError.code)
    }

    @Test
    fun playerCannotJoinSecondRoom() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)

        service.createRoom("p1")
        service.createRoom("p2")

        val roomCode = service.snapshotFor("p2").room?.code
        assertNotNull(roomCode)

        val error = assertFailsWith<DomainException> {
            service.joinRoom("p1", roomCode)
        }
        assertEquals("PLAYER_ALREADY_IN_ROOM", error.code)
    }

    @Test
    fun leaveDuringMatchBecomesPendingRemovalAndAppliedOnCompletion() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)

        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)
        service.rollMatch("p1")
        service.startMatch("p1")

        service.leaveRoom("p2")
        val inMatch = service.snapshotFor("p1").room
        assertNotNull(inMatch)
        val p2State = inMatch.currentMatch?.players?.firstOrNull { it.playerId == "p2" }
        assertNotNull(p2State)
        assertEquals(PlayerStatus.LEAVE, p2State.status)
        assertEquals(LeaveReason.MANUAL, p2State.leaveReason)

        service.finish("p1", rttMs = 1000, igtMs = 980)

        val after = service.snapshotFor("p1").room
        assertNotNull(after)
        assertNull(after.currentMatch)
        assertEquals(listOf("p1"), after.players.map { it.playerId })
    }
}
