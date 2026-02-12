package dev.flomik.race.application

import dev.flomik.race.domain.ConnectionState
import dev.flomik.race.persistence.InMemoryRaceStateStore
import dev.flomik.race.support.DeterministicRandomSource
import dev.flomik.race.support.MutableClock
import dev.flomik.race.support.SequentialIdGenerator
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RaceServicePersistenceTest {
    @Test
    fun warmupRestoresRoomsAndActiveMatchFromStore() = runTest {
        val store = InMemoryRaceStateStore()
        val initialClock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))

        val first = RaceService(
            reconnectGraceMs = 45_000L,
            clock = initialClock,
            idGenerator = SequentialIdGenerator(),
            randomSource = DeterministicRandomSource(longs = mutableListOf(555L)),
            stateStore = store,
        )

        first.warmup()
        val firstConnect = first.connect("p1", "Alice", null)
        first.connect("p2", "Bob", null)

        first.createRoom("p1")
        val roomCode = first.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        first.joinRoom("p2", roomCode)

        first.rollMatch("p1")
        first.startMatch("p1")

        val restoredClock = MutableClock(Instant.parse("2026-01-01T00:10:00Z"))
        val second = RaceService(
            reconnectGraceMs = 45_000L,
            clock = restoredClock,
            idGenerator = SequentialIdGenerator(),
            randomSource = DeterministicRandomSource(longs = mutableListOf(777L)),
            stateStore = store,
        )

        second.warmup()

        val reconnect = second.connect("p1", "Alice", firstConnect.sessionId)
        assertFalse(reconnect.resumed)

        val snapshot = second.snapshotFor("p1")
        assertEquals(roomCode, snapshot.room?.code)

        val room = snapshot.room
        assertNotNull(room)
        val match = room.currentMatch
        assertNotNull(match)
        assertEquals(true, match.isActive)
        assertEquals(setOf("p1", "p2"), match.players.map { it.playerId }.toSet())

        val p2 = room.players.firstOrNull { it.playerId == "p2" }
        assertNotNull(p2)
        assertEquals(ConnectionState.DISCONNECTED, p2.connectionState)
    }

    @Test
    fun warmupIsIdempotent() = runTest {
        val service = RaceService(stateStore = InMemoryRaceStateStore())

        service.warmup()
        service.warmup()

        service.connect("p1", "Alice", null)
        val snapshot = service.snapshotFor("p1")
        assertEquals("p1", snapshot.self.playerId)
    }

    @Test
    fun completedMatchesArePrunedFromPersistedState() = runTest {
        val store = InMemoryRaceStateStore()
        val service = RaceService(
            reconnectGraceMs = 45_000L,
            clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z")),
            idGenerator = SequentialIdGenerator(),
            randomSource = DeterministicRandomSource(longs = mutableListOf(555L)),
            stateStore = store,
        )

        service.warmup()
        service.connect("p1", "Alice", null)
        service.connect("p2", "Bob", null)
        service.createRoom("p1")
        val roomCode = service.snapshotFor("p1").room?.code
        assertNotNull(roomCode)
        service.joinRoom("p2", roomCode)
        service.rollMatch("p1")
        service.startMatch("p1")
        service.finish("p1", rttMs = 1500, igtMs = 1400)
        service.reportDeath("p2")

        val persisted = store.load()
        assertNotNull(persisted)
        assertTrue(persisted.matches.isEmpty())
    }
}
