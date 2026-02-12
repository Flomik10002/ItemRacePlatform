package dev.flomik.race.persistence

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PostgresRaceStateStoreTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun saveAndLoadRoundTrip() = runTest {
        val store = PostgresRaceStateStore(
            jdbcUrl = "jdbc:h2:mem:race_store_roundtrip;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            user = "sa",
            password = "",
            json = json,
        )

        val state = PersistedState(
            savedAtMs = 1_700_000_000_000L,
            players = listOf(
                PersistedPlayer(
                    id = "p1",
                    name = "Alice",
                    createdAtMs = 1_700_000_000_000L,
                    lastSeenAtMs = 1_700_000_000_001L,
                ),
            ),
            rooms = listOf(
                PersistedRoom(
                    id = "room-1",
                    code = "ABC123",
                    players = listOf("p1"),
                    leaderId = "p1",
                ),
            ),
            matches = emptyList(),
        )

        store.save(state)
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(state, loaded)
    }

    @Test
    fun saveOverwritesPreviousSnapshot() = runTest {
        val store = PostgresRaceStateStore(
            jdbcUrl = "jdbc:h2:mem:race_store_overwrite;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            user = "sa",
            password = "",
            json = json,
        )

        val first = PersistedState(
            savedAtMs = 10L,
            players = emptyList(),
            rooms = emptyList(),
            matches = emptyList(),
        )
        val second = PersistedState(
            savedAtMs = 20L,
            players = listOf(
                PersistedPlayer(
                    id = "p2",
                    name = "Bob",
                    createdAtMs = 20L,
                    lastSeenAtMs = 21L,
                ),
            ),
            rooms = emptyList(),
            matches = emptyList(),
        )

        store.save(first)
        store.save(second)
        assertEquals(second, store.load())
    }
}

