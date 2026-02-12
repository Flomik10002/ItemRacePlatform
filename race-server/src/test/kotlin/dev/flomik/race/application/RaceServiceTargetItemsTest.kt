package dev.flomik.race.application

import dev.flomik.race.support.DeterministicRandomSource
import dev.flomik.race.support.MutableClock
import dev.flomik.race.support.SequentialIdGenerator
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class RaceServiceTargetItemsTest {
    @Test
    fun rollMatchUsesConfiguredTargetItemsPool() = runTest {
        val service = RaceService(
            reconnectGraceMs = 45_000L,
            clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z")),
            idGenerator = SequentialIdGenerator(),
            randomSource = DeterministicRandomSource(longs = mutableListOf(123L)),
            targetItems = listOf("minecraft:apple"),
        )

        service.connect("p1", "Alice", null)
        service.createRoom("p1")
        service.rollMatch("p1")

        val pending = service.snapshotFor("p1").room?.pendingMatch
        assertNotNull(pending)
        assertEquals("minecraft:apple", pending.targetItem)
    }

    @Test
    fun raceServiceRejectsEmptyTargetItemsPool() {
        assertFailsWith<IllegalArgumentException> {
            RaceService(targetItems = emptyList())
        }
    }
}

