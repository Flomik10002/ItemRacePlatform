package dev.flomik.race.persistence

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileRaceStateStoreTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun saveAndLoadRoundtrip() = runTest {
        val dir = Files.createTempDirectory("race-store-test-")
        val filePath = dir.resolve("state.json")

        val store = FileRaceStateStore(filePath = filePath, json = json)
        val state = PersistedState(
            schemaVersion = 1,
            savedAtMs = 123456789L,
            players = listOf(
                PersistedPlayer(
                    id = "p1",
                    name = "Alice",
                    createdAtMs = 100L,
                    lastSeenAtMs = 200L,
                ),
            ),
            rooms = listOf(
                PersistedRoom(
                    id = "room-1",
                    code = "ABCD12",
                    players = listOf("p1"),
                    leaderId = "p1",
                    currentMatchId = null,
                    pendingMatch = null,
                    revisionCounter = 0,
                    pendingRemovals = emptyList(),
                ),
            ),
            matches = emptyList(),
        )

        store.save(state)
        assertTrue(filePath.exists())

        val loaded = store.load()
        assertEquals(state, loaded)

        filePath.deleteIfExists()
        Files.deleteIfExists(dir)
    }
}
