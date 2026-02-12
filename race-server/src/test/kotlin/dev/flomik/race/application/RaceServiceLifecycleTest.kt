package dev.flomik.race.application

import dev.flomik.race.domain.DomainException
import dev.flomik.race.domain.LeaveReason
import dev.flomik.race.domain.PlayerStatus
import dev.flomik.race.domain.ReadyCheckStatus
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
import kotlin.test.assertTrue

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

    @Test
    fun leaveMatchDuringActiveRaceKeepsPlayerInRoom() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)

        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)
        service.rollMatch("p1")
        service.startMatch("p1")

        service.leaveMatch("p2")
        val during = service.snapshotFor("p1").room
        assertNotNull(during)
        val p2State = during.currentMatch?.players?.firstOrNull { it.playerId == "p2" }
        assertNotNull(p2State)
        assertEquals(PlayerStatus.LEAVE, p2State.status)
        assertEquals(LeaveReason.MANUAL, p2State.leaveReason)

        service.finish("p1", rttMs = 1000, igtMs = 980)

        val after = service.snapshotFor("p1").room
        assertNotNull(after)
        assertNull(after.currentMatch)
        assertEquals(listOf("p1", "p2"), after.players.map { it.playerId })
    }

    @Test
    fun leaderCanCancelStartAndKeepRoomIntact() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)

        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)
        service.rollMatch("p1")
        service.startMatch("p1")

        service.cancelStart("p1")

        val roomAfterCancel = service.snapshotFor("p1").room
        assertNotNull(roomAfterCancel)
        assertNull(roomAfterCancel.currentMatch)
        assertNotNull(roomAfterCancel.pendingMatch)
        assertEquals(listOf("p1", "p2"), roomAfterCancel.players.map { it.playerId })
    }

    @Test
    fun nonLeaderCannotCancelStart() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)

        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)
        service.rollMatch("p1")
        service.startMatch("p1")

        val error = assertFailsWith<DomainException> {
            service.cancelStart("p2")
        }
        assertEquals("NOT_ROOM_LEADER", error.code)
    }

    @Test
    fun runningPlayerCanReportAdvancement() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)

        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)
        service.rollMatch("p1")
        service.startMatch("p1")

        val event = service.reportAdvancement("p1", "minecraft:story/mine_stone")
        assertEquals("p1", event.playerId)
        assertEquals("Alice", event.playerName)
        assertEquals("minecraft:story/mine_stone", event.advancementId)
        assertEquals(setOf("p1", "p2"), event.recipientPlayerIds)
    }

    @Test
    fun finishedPlayerCannotReportAdvancement() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.createRoom("p1")
        service.rollMatch("p1")
        service.startMatch("p1")
        service.finish("p1", rttMs = 1000, igtMs = 900)

        val error = assertFailsWith<DomainException> {
            service.reportAdvancement("p1", "minecraft:story/mine_stone")
        }
        assertEquals("NO_ACTIVE_MATCH", error.code)
    }

    @Test
    fun readyCheckLifecycleAndResponses() = runTest {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val service = createService(clock)

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)
        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)

        service.startReadyCheck("p1")
        service.respondReadyCheck("p1", ready = true)
        service.respondReadyCheck("p2", ready = false)

        val room = service.snapshotFor("p1").room
        assertNotNull(room)
        val readyCheck = room.readyCheck
        assertNotNull(readyCheck)
        assertEquals("p1", readyCheck.initiatedBy)
        assertEquals(2, readyCheck.responses.size)
        assertEquals(
            ReadyCheckStatus.READY,
            readyCheck.responses.first { it.playerId == "p1" }.status,
        )
        assertEquals(
            ReadyCheckStatus.NOT_READY,
            readyCheck.responses.first { it.playerId == "p2" }.status,
        )

        clock.advanceMillis(10_001L)
        val expired = service.snapshotFor("p1").room
        assertNotNull(expired)
        assertNull(expired.readyCheck)
    }

    @Test
    fun nonLeaderCannotStartReadyCheck() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)
        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)

        val error = assertFailsWith<DomainException> {
            service.startReadyCheck("p2")
        }
        assertEquals("NOT_ROOM_LEADER", error.code)
    }

    @Test
    fun readyCheckCannotStartDuringActiveMatch() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.createRoom("p1")
        service.rollMatch("p1")
        service.startMatch("p1")

        val error = assertFailsWith<DomainException> {
            service.startReadyCheck("p1")
        }
        assertEquals("MATCH_ALREADY_ACTIVE", error.code)
    }

    @Test
    fun respondReadyCheckFailsWhenNoReadyCheckActive() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.createRoom("p1")

        val error = assertFailsWith<DomainException> {
            service.respondReadyCheck("p1", ready = true)
        }
        assertEquals("READY_CHECK_NOT_ACTIVE", error.code)
    }

    @Test
    fun readyCheckIsClearedOnRosterChange() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)
        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)

        service.startReadyCheck("p1")
        service.leaveRoom("p2")

        val room = service.snapshotFor("p1").room
        assertNotNull(room)
        assertEquals(listOf("p1"), room.players.map { it.playerId })
        assertNull(room.readyCheck)
        assertTrue(room.pendingMatch == null)
    }

    @Test
    fun soloLeaveMatchCompletesMatchAndAllowsRollingNextOne() = runTest {
        val service = createService(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))

        service.connect("p1", "Alice", null)
        service.createRoom("p1")
        service.rollMatch("p1")
        service.startMatch("p1")

        service.leaveMatch("p1")

        val roomAfterLeave = service.snapshotFor("p1").room
        assertNotNull(roomAfterLeave)
        assertNull(roomAfterLeave.currentMatch)
        assertEquals(listOf("p1"), roomAfterLeave.players.map { it.playerId })

        service.rollMatch("p1")
        val roomAfterRoll = service.snapshotFor("p1").room
        assertNotNull(roomAfterRoll)
        assertNotNull(roomAfterRoll.pendingMatch)
    }
}
