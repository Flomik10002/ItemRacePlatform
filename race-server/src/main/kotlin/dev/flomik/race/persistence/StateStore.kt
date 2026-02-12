package dev.flomik.race.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

interface RaceStateStore {
    suspend fun load(): PersistedState?

    suspend fun save(state: PersistedState)
}

class InMemoryRaceStateStore : RaceStateStore {
    @Volatile
    private var state: PersistedState? = null

    override suspend fun load(): PersistedState? = state

    override suspend fun save(state: PersistedState) {
        this.state = state
    }
}

class FileRaceStateStore(
    private val filePath: Path,
    private val json: Json,
) : RaceStateStore {
    override suspend fun load(): PersistedState? = withContext(Dispatchers.IO) {
        if (!Files.exists(filePath)) {
            return@withContext null
        }

        val raw = Files.readString(filePath)
        json.decodeFromString<PersistedState>(raw)
    }

    override suspend fun save(state: PersistedState) = withContext(Dispatchers.IO) {
        val parent = filePath.parent
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent)
        }

        val encoded = json.encodeToString(PersistedState.serializer(), state)

        val tempPath = if (parent != null) {
            Files.createTempFile(parent, "race-state-", ".tmp")
        } else {
            Files.createTempFile("race-state-", ".tmp")
        }

        try {
            Files.writeString(tempPath, encoded)
            Files.move(
                tempPath,
                filePath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            Files.deleteIfExists(tempPath)
        }
        Unit
    }
}

@Serializable
data class PersistedState(
    val schemaVersion: Int = 1,
    val savedAtMs: Long,
    val players: List<PersistedPlayer>,
    val rooms: List<PersistedRoom>,
    val matches: List<PersistedMatch>,
)

@Serializable
data class PersistedPlayer(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val lastSeenAtMs: Long,
)

@Serializable
data class PersistedRoom(
    val id: String,
    val code: String,
    val players: List<String>,
    val leaderId: String,
    val currentMatchId: String? = null,
    val pendingMatch: PersistedPendingMatch? = null,
    val revisionCounter: Int = 0,
    val pendingRemovals: List<String> = emptyList(),
)

@Serializable
data class PersistedPendingMatch(
    val targetItem: String,
    val seed: Long,
    val rolledAtMs: Long,
    val revision: Int,
)

@Serializable
data class PersistedMatch(
    val id: String,
    val roomId: String,
    val revision: Int,
    val targetItem: String,
    val seed: Long,
    val lifecycleStatus: String,
    val players: List<PersistedMatchPlayer>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val completedAtMs: Long? = null,
)

@Serializable
data class PersistedMatchPlayer(
    val playerId: String,
    val status: String,
    val result: PersistedPlayerResult? = null,
    val leaveReason: String? = null,
    val leftAtMs: Long? = null,
)

@Serializable
data class PersistedPlayerResult(
    val rttMs: Long,
    val igtMs: Long,
)
