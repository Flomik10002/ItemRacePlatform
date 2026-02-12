package dev.flomik.race.application

import dev.flomik.race.domain.ConnectionState
import dev.flomik.race.domain.DomainException
import dev.flomik.race.domain.LeaveReason
import dev.flomik.race.domain.Match
import dev.flomik.race.domain.MatchLifecycleStatus
import dev.flomik.race.domain.MatchId
import dev.flomik.race.domain.PendingMatchConfig
import dev.flomik.race.domain.PlayerId
import dev.flomik.race.domain.PlayerProfile
import dev.flomik.race.domain.PlayerResult
import dev.flomik.race.domain.PlayerSession
import dev.flomik.race.domain.PlayerState
import dev.flomik.race.domain.PlayerStatus
import dev.flomik.race.domain.ReadyCheck
import dev.flomik.race.domain.ReadyCheckResponse
import dev.flomik.race.domain.ReadyCheckStatus
import dev.flomik.race.domain.Room
import dev.flomik.race.domain.RoomId
import dev.flomik.race.domain.SessionId
import dev.flomik.race.domain.complete
import dev.flomik.race.domain.die
import dev.flomik.race.domain.finish
import dev.flomik.race.domain.isActive
import dev.flomik.race.domain.isTerminal
import dev.flomik.race.domain.leave
import dev.flomik.race.persistence.PersistedMatch
import dev.flomik.race.persistence.PersistedMatchPlayer
import dev.flomik.race.persistence.PersistedPendingMatch
import dev.flomik.race.persistence.PersistedPlayer
import dev.flomik.race.persistence.PersistedPlayerResult
import dev.flomik.race.persistence.PersistedReadyCheck
import dev.flomik.race.persistence.PersistedReadyCheckResponse
import dev.flomik.race.persistence.PersistedRoom
import dev.flomik.race.persistence.PersistedState
import dev.flomik.race.persistence.RaceStateStore
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

data class ConnectResult(
    val sessionId: SessionId,
    val resumed: Boolean,
    val affectedPlayerIds: Set<PlayerId>,
)

data class AdvancementBroadcast(
    val playerId: PlayerId,
    val playerName: String,
    val advancementId: String,
    val recipientPlayerIds: Set<PlayerId>,
)

class RaceService(
    private val reconnectGraceMs: Long = 45_000L,
    private val clock: Clock = SystemClock,
    private val idGenerator: IdGenerator = UuidIdGenerator(),
    private val randomSource: RandomSource = KotlinRandomSource(),
    private val stateStore: RaceStateStore? = null,
    targetItems: List<String> = TargetItemCatalog.loadFromClasspath(),
) {
    private val mutex = Mutex()
    private var hydratedFromStore: Boolean = false

    private val roomsById = mutableMapOf<RoomId, Room>()
    private val roomIdByCode = mutableMapOf<String, RoomId>()
    private val matchesById = mutableMapOf<MatchId, Match>()
    private val playersById = mutableMapOf<PlayerId, PlayerProfile>()
    private val sessionsByPlayerId = mutableMapOf<PlayerId, PlayerSession>()
    private val playerRoomId = mutableMapOf<PlayerId, RoomId>()
    private val targetItemPool = targetItems
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
        .also {
            require(it.isNotEmpty()) { "targetItems must not be empty" }
        }

    suspend fun warmup() = mutex.withLock {
        hydrateFromStoreIfNeeded()
        validateGlobalState()
    }

    suspend fun connect(
        playerId: PlayerId,
        name: String,
        requestedSessionId: SessionId?,
    ): ConnectResult = mutex.withLock {
        hydrateFromStoreIfNeeded()
        requirePlayerIdentity(playerId, name)
        val now = now()
        pruneExpiredReadyChecks(now)

        val profile = playersById[playerId]
        if (profile == null) {
            playersById[playerId] = PlayerProfile(
                id = playerId,
                name = name,
                createdAt = now,
                lastSeenAt = now,
            )
        } else {
            profile.name = name
            profile.lastSeenAt = now
        }

        val existingSession = sessionsByPlayerId[playerId]
        val resumed = requestedSessionId != null && existingSession?.sessionId == requestedSessionId
        val sessionId = requestedSessionId?.takeIf { resumed } ?: idGenerator.nextSessionId()

        sessionsByPlayerId[playerId] = PlayerSession(
            sessionId = sessionId,
            playerId = playerId,
            connectionState = ConnectionState.CONNECTED,
            lastSeenAt = now,
            disconnectedAt = null,
        )

        val affected = playersInSameRoom(playerId).ifEmpty { setOf(playerId) }
        validateGlobalState()
        persistStateLocked()
        ConnectResult(
            sessionId = sessionId,
            resumed = resumed,
            affectedPlayerIds = affected,
        )
    }

    suspend fun disconnect(
        playerId: PlayerId,
        sessionId: SessionId,
    ): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        val now = now()
        pruneExpiredReadyChecks(now)
        val session = sessionsByPlayerId[playerId] ?: return@withLock emptySet()
        if (session.sessionId != sessionId) return@withLock emptySet()
        if (session.connectionState == ConnectionState.DISCONNECTED) return@withLock emptySet()
        session.connectionState = ConnectionState.DISCONNECTED
        session.disconnectedAt = now
        session.lastSeenAt = now
        playersById[playerId]?.lastSeenAt = now

        validateGlobalState()
        persistStateLocked()
        playersInSameRoom(playerId).ifEmpty { setOf(playerId) }
    }

    suspend fun handleReconnectTimeout(
        playerId: PlayerId,
        sessionId: SessionId,
    ): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        val now = now()
        pruneExpiredReadyChecks(now)
        val session = sessionsByPlayerId[playerId] ?: return@withLock emptySet()
        if (session.sessionId != sessionId) return@withLock emptySet()
        if (session.connectionState != ConnectionState.DISCONNECTED) return@withLock emptySet()

        val disconnectedAt = session.disconnectedAt ?: return@withLock emptySet()
        val disconnectedForMs = Duration.between(disconnectedAt, now).toMillis()
        if (disconnectedForMs < reconnectGraceMs) return@withLock emptySet()

        val room = findRoomByPlayer(playerId)
        if (room == null) {
            sessionsByPlayerId.remove(playerId)
            playersById.remove(playerId)
            validateGlobalState()
            persistStateLocked()
            return@withLock emptySet()
        }
        val affected = room.players.toMutableSet().apply { add(playerId) }

        val match = activeMatch(room)
        if (match == null) {
            removePlayerFromRoom(room, playerId)
            validateGlobalState()
            persistStateLocked()
            return@withLock affected
        }

        val state = match.players[playerId]
            ?: throw DomainException("INVARIANT_VIOLATION", "Player in room is absent in active match")

        if (state.status == PlayerStatus.RUNNING) {
            state.leave(LeaveReason.RECONNECT_TIMEOUT, now)
            match.updatedAt = now
        }

        room.pendingRemovals.add(playerId)
        ensureLeader(room)
        completeMatchIfNeeded(room, match, now)

        validateGlobalState()
        persistStateLocked()
        affected
    }

    suspend fun createRoom(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        pruneExpiredReadyChecks(now())
        ensurePlayerConnected(playerId)
        if (playerRoomId.containsKey(playerId)) {
            throw DomainException("PLAYER_ALREADY_IN_ROOM", "Player is already in a room")
        }

        val room = Room(
            id = idGenerator.nextRoomId(),
            code = allocateRoomCode(),
            players = linkedSetOf(playerId),
            leaderId = playerId,
        )

        roomsById[room.id] = room
        roomIdByCode[room.code] = room.id
        playerRoomId[playerId] = room.id

        validateGlobalState()
        persistStateLocked()
        setOf(playerId)
    }

    suspend fun joinRoom(
        playerId: PlayerId,
        roomCodeRaw: String,
    ): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        pruneExpiredReadyChecks(now())
        ensurePlayerConnected(playerId)
        if (playerRoomId.containsKey(playerId)) {
            throw DomainException("PLAYER_ALREADY_IN_ROOM", "Player is already in a room")
        }

        val roomCode = normalizeRoomCode(roomCodeRaw)
        val room = findRoomByCode(roomCode)
            ?: throw DomainException("ROOM_NOT_FOUND", "Room '$roomCode' does not exist")

        if (activeMatch(room) != null) {
            throw DomainException("ROOM_MATCH_ACTIVE", "Cannot join while match is active")
        }

        room.players.add(playerId)
        playerRoomId[playerId] = room.id
        room.readyCheck = null
        ensureLeader(room)

        validateGlobalState()
        persistStateLocked()
        room.players.toSet()
    }

    suspend fun leaveMatch(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        pruneExpiredReadyChecks(now())
        val room = findRoomByPlayer(playerId)
            ?: throw DomainException("PLAYER_NOT_IN_ROOM", "Player is not in a room")
        val match = activeMatch(room)
            ?: throw DomainException("NO_ACTIVE_MATCH", "No active match in this room")
        val state = match.players[playerId]
            ?: throw DomainException("INVARIANT_VIOLATION", "Player in room is absent in active match")

        val now = now()
        if (state.status == PlayerStatus.RUNNING) {
            state.leave(LeaveReason.MANUAL, now)
            match.updatedAt = now
        }

        completeMatchIfNeeded(room, match, now)

        validateGlobalState()
        persistStateLocked()
        room.players.toSet().plus(playerId)
    }

    suspend fun leaveRoom(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        pruneExpiredReadyChecks(now())
        val room = findRoomByPlayer(playerId)
            ?: throw DomainException("PLAYER_NOT_IN_ROOM", "Player is not in a room")

        val now = now()
        val affected = room.players.toMutableSet().apply { add(playerId) }
        val match = activeMatch(room)

        if (match == null) {
            removePlayerFromRoom(room, playerId)
            validateGlobalState()
            persistStateLocked()
            return@withLock affected
        }

        val state = match.players[playerId]
            ?: throw DomainException("INVARIANT_VIOLATION", "Player in room is absent in active match")

        if (state.status == PlayerStatus.RUNNING) {
            state.leave(LeaveReason.MANUAL, now)
            match.updatedAt = now
        }

        room.pendingRemovals.add(playerId)
        ensureLeader(room)
        completeMatchIfNeeded(room, match, now)

        validateGlobalState()
        persistStateLocked()
        affected
    }

    suspend fun rollMatch(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        pruneExpiredReadyChecks(now())
        val room = findRoomByPlayer(playerId)
            ?: throw DomainException("PLAYER_NOT_IN_ROOM", "Player is not in a room")

        ensureLeader(room, playerId)
        if (activeMatch(room) != null) {
            throw DomainException("MATCH_ALREADY_ACTIVE", "Current match is still active")
        }

        val now = now()
        val revision = room.revisionCounter + 1
        val seed = randomSource.nextLong()
        room.readyCheck = null
        room.pendingMatch = PendingMatchConfig(
            targetItem = pickTargetItem(seed),
            seed = seed,
            rolledAt = now,
            revision = revision,
        )
        room.revisionCounter = revision

        validateGlobalState()
        persistStateLocked()
        room.players.toSet()
    }

    suspend fun startMatch(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        pruneExpiredReadyChecks(now())
        val room = findRoomByPlayer(playerId)
            ?: throw DomainException("PLAYER_NOT_IN_ROOM", "Player is not in a room")

        ensureLeader(room, playerId)
        if (activeMatch(room) != null) {
            throw DomainException("MATCH_ALREADY_ACTIVE", "Current match is still active")
        }

        val pending = room.pendingMatch
            ?: throw DomainException("PENDING_MATCH_MISSING", "Pending match does not exist")
        if (room.players.isEmpty()) {
            throw DomainException("EMPTY_ROOM", "Cannot start match in empty room")
        }

        val now = now()
        val match = Match(
            id = idGenerator.nextMatchId(),
            roomId = room.id,
            revision = pending.revision,
            targetItem = pending.targetItem,
            seed = pending.seed,
            lifecycleStatus = MatchLifecycleStatus.ACTIVE,
            players = room.players.associateWith {
                PlayerState(status = PlayerStatus.RUNNING)
            }.toMutableMap(),
            createdAt = now,
            updatedAt = now,
        )

        matchesById[match.id] = match
        room.currentMatchId = match.id
        room.pendingMatch = null
        room.readyCheck = null
        room.pendingRemovals.clear()

        validateGlobalState()
        persistStateLocked()
        room.players.toSet()
    }

    suspend fun cancelStart(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        pruneExpiredReadyChecks(now())
        val room = findRoomByPlayer(playerId)
            ?: throw DomainException("PLAYER_NOT_IN_ROOM", "Player is not in a room")

        ensureLeader(room, playerId)
        val match = activeMatch(room)
            ?: throw DomainException("NO_ACTIVE_MATCH", "No active match in this room")

        if (match.players.values.any { it.status != PlayerStatus.RUNNING }) {
            throw DomainException(
                "MATCH_ALREADY_PROGRESSING",
                "Cannot cancel start after match progress",
            )
        }

        val now = now()
        match.complete(now)
        room.currentMatchId = null
        room.readyCheck = null
        room.pendingRemovals.clear()
        room.pendingMatch = PendingMatchConfig(
            targetItem = match.targetItem,
            seed = match.seed,
            rolledAt = now,
            revision = match.revision,
        )
        matchesById.remove(match.id)

        validateGlobalState()
        persistStateLocked()
        room.players.toSet()
    }

    suspend fun startReadyCheck(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        val now = now()
        pruneExpiredReadyChecks(now)

        val room = findRoomByPlayer(playerId)
            ?: throw DomainException("PLAYER_NOT_IN_ROOM", "Player is not in a room")
        ensureLeader(room, playerId)
        if (activeMatch(room) != null) {
            throw DomainException("MATCH_ALREADY_ACTIVE", "Cannot start ready check while match is active")
        }

        room.readyCheck = ReadyCheck(
            initiatedBy = playerId,
            startedAt = now,
            expiresAt = now.plusMillis(READY_CHECK_TTL_MS),
            responses = mutableMapOf(),
        )

        validateGlobalState()
        persistStateLocked()
        room.players.toSet()
    }

    suspend fun respondReadyCheck(
        playerId: PlayerId,
        ready: Boolean,
    ): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        val now = now()
        pruneExpiredReadyChecks(now)

        val room = findRoomByPlayer(playerId)
            ?: throw DomainException("PLAYER_NOT_IN_ROOM", "Player is not in a room")
        if (activeMatch(room) != null) {
            throw DomainException("MATCH_ALREADY_ACTIVE", "Ready check responses are disabled while match is active")
        }

        val readyCheck = room.readyCheck
            ?: throw DomainException("READY_CHECK_NOT_ACTIVE", "No active ready check in this room")
        if (!now.isBefore(readyCheck.expiresAt)) {
            room.readyCheck = null
            throw DomainException("READY_CHECK_NOT_ACTIVE", "No active ready check in this room")
        }

        readyCheck.responses[playerId] = ReadyCheckResponse(
            status = if (ready) ReadyCheckStatus.READY else ReadyCheckStatus.NOT_READY,
            respondedAt = now,
        )

        validateGlobalState()
        persistStateLocked()
        room.players.toSet()
    }

    suspend fun finish(
        playerId: PlayerId,
        rttMs: Long,
        igtMs: Long,
    ): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        pruneExpiredReadyChecks(now())
        val room = findRoomByPlayer(playerId)
            ?: throw DomainException("PLAYER_NOT_IN_ROOM", "Player is not in a room")
        val match = activeMatch(room)
            ?: throw DomainException("NO_ACTIVE_MATCH", "No active match in this room")
        val state = match.players[playerId]
            ?: throw DomainException("INVARIANT_VIOLATION", "Player in room is absent in active match")

        state.finish(PlayerResult(rttMs = rttMs, igtMs = igtMs))
        val now = now()
        match.updatedAt = now
        completeMatchIfNeeded(room, match, now)

        validateGlobalState()
        persistStateLocked()
        room.players.toSet().plus(playerId)
    }

    suspend fun reportDeath(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
        hydrateFromStoreIfNeeded()
        pruneExpiredReadyChecks(now())
        val room = findRoomByPlayer(playerId)
            ?: throw DomainException("PLAYER_NOT_IN_ROOM", "Player is not in a room")
        val match = activeMatch(room)
            ?: throw DomainException("NO_ACTIVE_MATCH", "No active match in this room")
        val state = match.players[playerId]
            ?: throw DomainException("INVARIANT_VIOLATION", "Player in room is absent in active match")

        state.die()
        val now = now()
        match.updatedAt = now
        completeMatchIfNeeded(room, match, now)

        validateGlobalState()
        persistStateLocked()
        room.players.toSet().plus(playerId)
    }

    suspend fun reportAdvancement(
        playerId: PlayerId,
        advancementIdRaw: String,
    ): AdvancementBroadcast = mutex.withLock {
        hydrateFromStoreIfNeeded()
        pruneExpiredReadyChecks(now())
        val advancementId = advancementIdRaw.trim()
        if (advancementId.isBlank()) {
            throw DomainException("INVALID_ADVANCEMENT", "advancement id must be non-empty")
        }
        if (advancementId.length > 256) {
            throw DomainException("INVALID_ADVANCEMENT", "advancement id is too long")
        }

        val room = findRoomByPlayer(playerId)
            ?: throw DomainException("PLAYER_NOT_IN_ROOM", "Player is not in a room")
        val match = activeMatch(room)
            ?: throw DomainException("NO_ACTIVE_MATCH", "No active match in this room")
        val state = match.players[playerId]
            ?: throw DomainException("INVARIANT_VIOLATION", "Player in room is absent in active match")
        if (state.status != PlayerStatus.RUNNING) {
            throw DomainException("PLAYER_NOT_RUNNING", "Only running player can report advancement")
        }

        val profile = playersById[playerId]
            ?: throw DomainException("INVARIANT_VIOLATION", "Player profile missing for '$playerId'")

        AdvancementBroadcast(
            playerId = playerId,
            playerName = profile.name,
            advancementId = advancementId,
            recipientPlayerIds = room.players.toSet(),
        )
    }

    suspend fun snapshotFor(playerId: PlayerId): RaceSnapshot = mutex.withLock {
        hydrateFromStoreIfNeeded()
        pruneExpiredReadyChecks(now())
        val profile = playersById[playerId]
            ?: throw DomainException("PLAYER_NOT_FOUND", "Player not registered")
        val session = sessionsByPlayerId[playerId]

        val room = findRoomByPlayer(playerId)
        RaceSnapshot(
            serverTimeMs = now().toEpochMilli(),
            reconnectGraceMs = reconnectGraceMs,
            self = SelfView(
                playerId = profile.id,
                name = profile.name,
                connectionState = session?.connectionState ?: ConnectionState.DISCONNECTED,
                roomCode = room?.code,
            ),
            room = room?.toView(),
        )
    }

    private suspend fun hydrateFromStoreIfNeeded() {
        if (hydratedFromStore) {
            return
        }

        val persisted = stateStore?.load()
        if (persisted != null) {
            importPersistedState(persisted)
        }
        hydratedFromStore = true
    }

    private suspend fun persistStateLocked() {
        val store = stateStore ?: return
        store.save(exportPersistedState())
    }

    private fun importPersistedState(state: PersistedState) {
        roomsById.clear()
        roomIdByCode.clear()
        matchesById.clear()
        playersById.clear()
        sessionsByPlayerId.clear()
        playerRoomId.clear()

        for (player in state.players) {
            playersById[player.id] = PlayerProfile(
                id = player.id,
                name = player.name,
                createdAt = Instant.ofEpochMilli(player.createdAtMs),
                lastSeenAt = Instant.ofEpochMilli(player.lastSeenAtMs),
            )
        }

        for (persistedRoom in state.rooms) {
            val room = Room(
                id = persistedRoom.id,
                code = persistedRoom.code,
                players = LinkedHashSet(persistedRoom.players),
                leaderId = persistedRoom.leaderId,
                currentMatchId = persistedRoom.currentMatchId,
                pendingMatch = persistedRoom.pendingMatch?.let {
                    PendingMatchConfig(
                        targetItem = it.targetItem,
                        seed = it.seed,
                        rolledAt = Instant.ofEpochMilli(it.rolledAtMs),
                        revision = it.revision,
                    )
                },
                readyCheck = persistedRoom.readyCheck?.let { persistedReady ->
                    ReadyCheck(
                        initiatedBy = persistedReady.initiatedBy,
                        startedAt = Instant.ofEpochMilli(persistedReady.startedAtMs),
                        expiresAt = Instant.ofEpochMilli(persistedReady.expiresAtMs),
                        responses = persistedReady.responses.associate { response ->
                            response.playerId to ReadyCheckResponse(
                                status = parseEnum<ReadyCheckStatus>(
                                    response.status,
                                    "room.readyCheck.responses.status",
                                ),
                                respondedAt = Instant.ofEpochMilli(response.respondedAtMs),
                            )
                        }.toMutableMap(),
                    )
                },
                revisionCounter = persistedRoom.revisionCounter,
                pendingRemovals = LinkedHashSet(persistedRoom.pendingRemovals),
            )

            if (roomIdByCode.containsKey(room.code)) {
                throw DomainException(
                    "PERSISTENCE_CORRUPTED",
                    "Duplicate room code '${room.code}' in persisted state",
                )
            }

            roomsById[room.id] = room
            roomIdByCode[room.code] = room.id

            for (playerId in room.players) {
                val previousRoomId = playerRoomId.put(playerId, room.id)
                if (previousRoomId != null && previousRoomId != room.id) {
                    throw DomainException(
                        "PERSISTENCE_CORRUPTED",
                        "Player '$playerId' belongs to multiple rooms in persisted state",
                    )
                }
            }
        }

        for (persistedMatch in state.matches) {
            val match = Match(
                id = persistedMatch.id,
                roomId = persistedMatch.roomId,
                revision = persistedMatch.revision,
                targetItem = persistedMatch.targetItem,
                seed = persistedMatch.seed,
                lifecycleStatus = parseEnum<MatchLifecycleStatus>(
                    persistedMatch.lifecycleStatus,
                    "match.lifecycleStatus",
                ),
                players = persistedMatch.players.associate { persistedPlayer ->
                    val status = parseEnum<PlayerStatus>(persistedPlayer.status, "match.player.status")
                    persistedPlayer.playerId to PlayerState(
                        status = status,
                        result = persistedPlayer.result?.let {
                            PlayerResult(
                                rttMs = it.rttMs,
                                igtMs = it.igtMs,
                            )
                        },
                        leaveReason = persistedPlayer.leaveReason?.let {
                            parseEnum<LeaveReason>(it, "match.player.leaveReason")
                        },
                        leftAt = persistedPlayer.leftAtMs?.let(Instant::ofEpochMilli),
                    )
                }.toMutableMap(),
                createdAt = Instant.ofEpochMilli(persistedMatch.createdAtMs),
                updatedAt = Instant.ofEpochMilli(persistedMatch.updatedAtMs),
                completedAt = persistedMatch.completedAtMs?.let(Instant::ofEpochMilli),
            )
            matchesById[match.id] = match
        }
    }

    private fun exportPersistedState(): PersistedState {
        return PersistedState(
            savedAtMs = now().toEpochMilli(),
            players = playersById.values
                .sortedBy { it.id }
                .map { player ->
                    PersistedPlayer(
                        id = player.id,
                        name = player.name,
                        createdAtMs = player.createdAt.toEpochMilli(),
                        lastSeenAtMs = player.lastSeenAt.toEpochMilli(),
                    )
                },
            rooms = roomsById.values
                .sortedBy { it.id }
                .map { room ->
                    PersistedRoom(
                        id = room.id,
                        code = room.code,
                        players = room.players.toList(),
                        leaderId = room.leaderId,
                        currentMatchId = room.currentMatchId,
                        pendingMatch = room.pendingMatch?.let {
                            PersistedPendingMatch(
                                targetItem = it.targetItem,
                                seed = it.seed,
                                rolledAtMs = it.rolledAt.toEpochMilli(),
                                revision = it.revision,
                            )
                        },
                        readyCheck = room.readyCheck?.let { readyCheck ->
                            PersistedReadyCheck(
                                initiatedBy = readyCheck.initiatedBy,
                                startedAtMs = readyCheck.startedAt.toEpochMilli(),
                                expiresAtMs = readyCheck.expiresAt.toEpochMilli(),
                                responses = readyCheck.responses
                                    .entries
                                    .sortedBy { it.key }
                                    .map { (playerId, response) ->
                                        PersistedReadyCheckResponse(
                                            playerId = playerId,
                                            status = response.status.name,
                                            respondedAtMs = response.respondedAt.toEpochMilli(),
                                        )
                                    },
                            )
                        },
                        revisionCounter = room.revisionCounter,
                        pendingRemovals = room.pendingRemovals.toList(),
                    )
                },
            matches = matchesById.values
                .sortedBy { it.id }
                .map { match ->
                    PersistedMatch(
                        id = match.id,
                        roomId = match.roomId,
                        revision = match.revision,
                        targetItem = match.targetItem,
                        seed = match.seed,
                        lifecycleStatus = match.lifecycleStatus.name,
                        players = match.players
                            .entries
                            .sortedBy { it.key }
                            .map { (playerId, state) ->
                                PersistedMatchPlayer(
                                    playerId = playerId,
                                    status = state.status.name,
                                    result = state.result?.let {
                                        PersistedPlayerResult(
                                            rttMs = it.rttMs,
                                            igtMs = it.igtMs,
                                        )
                                    },
                                    leaveReason = state.leaveReason?.name,
                                    leftAtMs = state.leftAt?.toEpochMilli(),
                                )
                            },
                        createdAtMs = match.createdAt.toEpochMilli(),
                        updatedAtMs = match.updatedAt.toEpochMilli(),
                        completedAtMs = match.completedAt?.toEpochMilli(),
                    )
                },
        )
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String, fieldName: String): T {
        return try {
            enumValueOf<T>(value)
        } catch (_: IllegalArgumentException) {
            throw DomainException(
                "PERSISTENCE_CORRUPTED",
                "Invalid enum value '$value' for '$fieldName'",
            )
        }
    }

    private fun ensurePlayerConnected(playerId: PlayerId) {
        if (!playersById.containsKey(playerId)) {
            throw DomainException("PLAYER_NOT_CONNECTED", "Player must send 'hello' first")
        }
    }

    private fun requirePlayerIdentity(playerId: String, name: String) {
        if (playerId.isBlank()) {
            throw DomainException("INVALID_PLAYER", "playerId must be non-empty")
        }
        if (name.isBlank()) {
            throw DomainException("INVALID_PLAYER", "name must be non-empty")
        }
    }

    private fun ensureLeader(room: Room, playerId: PlayerId? = null) {
        if (playerId != null) {
            if (room.leaderId != playerId || room.pendingRemovals.contains(playerId)) {
                throw DomainException("NOT_ROOM_LEADER", "Only active room leader can perform this action")
            }
        }

        if (!room.players.contains(room.leaderId) || room.pendingRemovals.contains(room.leaderId)) {
            val candidate = room.players.firstOrNull { it !in room.pendingRemovals }
                ?: room.players.firstOrNull()
            if (candidate != null) {
                room.leaderId = candidate
            }
        }
    }

    private fun removePlayerFromRoom(room: Room, playerId: PlayerId) {
        room.players.remove(playerId)
        room.pendingRemovals.remove(playerId)
        playerRoomId.remove(playerId)
        room.readyCheck = null

        if (room.players.isEmpty()) {
            deleteRoom(room)
            pruneDetachedDisconnectedPlayer(playerId)
            return
        }

        ensureLeader(room)
        pruneDetachedDisconnectedPlayer(playerId)
    }

    private fun deleteRoom(room: Room) {
        roomsById.remove(room.id)
        roomIdByCode.remove(room.code)

        val roomMatchIds = matchesById.values
            .filter { it.roomId == room.id }
            .map { it.id }
        roomMatchIds.forEach(matchesById::remove)

        room.pendingRemovals.forEach(playerRoomId::remove)
        room.pendingRemovals.clear()
    }

    private fun completeMatchIfNeeded(room: Room, match: Match, now: java.time.Instant) {
        if (!match.players.values.all { it.isTerminal() }) return

        match.complete(now)
        room.currentMatchId = null
        applyPendingRemovals(room)
        matchesById.remove(match.id)
    }

    private fun applyPendingRemovals(room: Room) {
        if (room.pendingRemovals.isEmpty()) return

        val removals = room.pendingRemovals.toList()
        room.pendingRemovals.clear()
        removals.forEach { removePlayerFromRoom(room, it) }
    }

    private fun pruneExpiredReadyChecks(now: Instant) {
        for (room in roomsById.values) {
            val readyCheck = room.readyCheck ?: continue
            if (!now.isBefore(readyCheck.expiresAt)) {
                room.readyCheck = null
                continue
            }
            readyCheck.responses.keys.removeIf { playerId -> playerId !in room.players }
        }
    }

    private fun pruneDetachedDisconnectedPlayer(playerId: PlayerId) {
        if (playerRoomId.containsKey(playerId)) return
        val session = sessionsByPlayerId[playerId] ?: return
        if (session.connectionState != ConnectionState.DISCONNECTED) return
        sessionsByPlayerId.remove(playerId)
        playersById.remove(playerId)
    }

    private fun findRoomByPlayer(playerId: PlayerId): Room? {
        val roomId = playerRoomId[playerId] ?: return null
        return roomsById[roomId]
    }

    private fun playersInSameRoom(playerId: PlayerId): Set<PlayerId> {
        val room = findRoomByPlayer(playerId) ?: return emptySet()
        return room.players.toSet()
    }

    private fun findRoomByCode(roomCode: String): Room? {
        val roomId = roomIdByCode[roomCode] ?: return null
        return roomsById[roomId]
    }

    private fun activeMatch(room: Room): Match? {
        val matchId = room.currentMatchId ?: return null
        val match = matchesById[matchId] ?: return null
        return if (match.isActive) match else null
    }

    private fun Room.toView(): RoomView {
        val pending = pendingMatch?.let {
            PendingMatchView(
                revision = it.revision,
                targetItem = it.targetItem,
                seed = it.seed,
                rolledAtMs = it.rolledAt.toEpochMilli(),
            )
        }

        val current = currentMatchId
            ?.let(matchesById::get)
            ?.toView(players)
        val ready = readyCheck?.let { ready ->
            ReadyCheckView(
                initiatedBy = ready.initiatedBy,
                startedAtMs = ready.startedAt.toEpochMilli(),
                expiresAtMs = ready.expiresAt.toEpochMilli(),
                responses = ready.responses
                    .entries
                    .sortedBy { it.key }
                    .map { (playerId, response) ->
                        ReadyCheckResponseView(
                            playerId = playerId,
                            status = response.status,
                            respondedAtMs = response.respondedAt.toEpochMilli(),
                        )
                    },
            )
        }

        return RoomView(
            code = code,
            leaderId = leaderId,
            players = players.map { pid ->
                val profile = playersById[pid]
                    ?: throw DomainException("INVARIANT_VIOLATION", "Player profile missing for '$pid'")
                RoomPlayerView(
                    playerId = pid,
                    name = profile.name,
                    connectionState = sessionsByPlayerId[pid]?.connectionState ?: ConnectionState.DISCONNECTED,
                    pendingRemoval = pid in pendingRemovals,
                )
            },
            pendingMatch = pending,
            currentMatch = current,
            readyCheck = ready,
        )
    }

    private fun Match.toView(roomOrder: LinkedHashSet<PlayerId>): MatchView {
        val byId = players
        val ordered = roomOrder.mapNotNull { pid -> byId[pid]?.let { pid to it } } +
            byId.entries.filter { (pid, _) -> pid !in roomOrder }.map { it.key to it.value }

        return MatchView(
            id = id,
            revision = revision,
            targetItem = targetItem,
            seed = seed,
            isActive = isActive,
            createdAtMs = createdAt.toEpochMilli(),
            updatedAtMs = updatedAt.toEpochMilli(),
            completedAtMs = completedAt?.toEpochMilli(),
            players = ordered.map { (playerId, state) ->
                MatchPlayerView(
                    playerId = playerId,
                    status = state.status,
                    result = state.result,
                    leaveReason = state.leaveReason,
                    leftAtMs = state.leftAt?.toEpochMilli(),
                )
            },
        )
    }

    private fun normalizeRoomCode(raw: String): String {
        val normalized = raw.trim().uppercase()
        if (normalized.length !in 4..12) {
            throw DomainException("INVALID_ROOM_CODE", "roomCode length must be 4..12")
        }
        if (!normalized.all { it.isLetterOrDigit() }) {
            throw DomainException("INVALID_ROOM_CODE", "roomCode must be alphanumeric")
        }
        return normalized
    }

    private fun allocateRoomCode(length: Int = 6): String {
        repeat(1_000) {
            val code = buildString(length) {
                repeat(length) {
                    append(ROOM_CODE_ALPHABET[randomSource.nextInt(ROOM_CODE_ALPHABET.length)])
                }
            }

            if (!roomIdByCode.containsKey(code)) {
                return code
            }
        }

        throw DomainException("ROOM_CODE_EXHAUSTED", "Failed to allocate unique room code")
    }

    private fun pickTargetItem(seed: Long): String {
        val seeded = Random(seed)
        return targetItemPool[seeded.nextInt(targetItemPool.size)]
    }

    private fun validateGlobalState() {
        for ((roomId, room) in roomsById) {
            if (room.players.isEmpty()) {
                throw DomainException("INVARIANT_VIOLATION", "Room '$roomId' has no players")
            }
            if (room.leaderId !in room.players) {
                throw DomainException("INVARIANT_VIOLATION", "Room '$roomId' leader is not in players")
            }
            if (room.currentMatchId != null && room.pendingMatch != null) {
                throw DomainException("INVARIANT_VIOLATION", "Room '$roomId' has both active and pending match")
            }
            if (room.currentMatchId != null && room.readyCheck != null) {
                throw DomainException("INVARIANT_VIOLATION", "Room '$roomId' has both active match and ready check")
            }

            val readyCheck = room.readyCheck
            if (readyCheck != null) {
                if (!readyCheck.expiresAt.isAfter(readyCheck.startedAt)) {
                    throw DomainException("INVARIANT_VIOLATION", "Room '$roomId' ready check expiration is invalid")
                }
                if (readyCheck.initiatedBy !in room.players) {
                    throw DomainException("INVARIANT_VIOLATION", "Room '$roomId' ready check initiator is not in room")
                }
                for ((responsePlayerId, response) in readyCheck.responses) {
                    if (responsePlayerId !in room.players) {
                        throw DomainException(
                            "INVARIANT_VIOLATION",
                            "Room '$roomId' ready check has response from player outside room",
                        )
                    }
                    if (response.respondedAt.isBefore(readyCheck.startedAt) || response.respondedAt.isAfter(readyCheck.expiresAt)) {
                        throw DomainException(
                            "INVARIANT_VIOLATION",
                            "Room '$roomId' ready check response timestamp is out of bounds",
                        )
                    }
                }
            }

            val currentMatchId = room.currentMatchId
            if (currentMatchId != null) {
                val match = matchesById[currentMatchId]
                    ?: throw DomainException("INVARIANT_VIOLATION", "Room '$roomId' points to missing match")

                if (match.roomId != roomId) {
                    throw DomainException("INVARIANT_VIOLATION", "Match '${match.id}' points to wrong room")
                }
                if (!match.isActive) {
                    throw DomainException("INVARIANT_VIOLATION", "Room '$roomId' references inactive match")
                }

                for (playerId in match.players.keys) {
                    if (playerId !in room.players) {
                        throw DomainException("INVARIANT_VIOLATION", "Match '${match.id}' has player outside room")
                    }
                }
            }
        }

        for ((playerId, roomId) in playerRoomId) {
            val room = roomsById[roomId]
                ?: throw DomainException("INVARIANT_VIOLATION", "Player '$playerId' points to missing room")
            if (playerId !in room.players) {
                throw DomainException("INVARIANT_VIOLATION", "Player '$playerId' mapping inconsistent with room")
            }
        }

        val duplicatePlayers = mutableSetOf<PlayerId>()
        val seenPlayers = mutableSetOf<PlayerId>()
        for (room in roomsById.values) {
            for (playerId in room.players) {
                if (!seenPlayers.add(playerId)) {
                    duplicatePlayers.add(playerId)
                }
            }
        }
        if (duplicatePlayers.isNotEmpty()) {
            throw DomainException("INVARIANT_VIOLATION", "Players in multiple rooms: $duplicatePlayers")
        }

        for ((matchId, match) in matchesById) {
            when (match.lifecycleStatus) {
                MatchLifecycleStatus.ACTIVE -> {
                    if (match.completedAt != null) {
                        throw DomainException("INVARIANT_VIOLATION", "Active match '$matchId' has completedAt")
                    }
                }

                MatchLifecycleStatus.COMPLETED -> {
                    if (match.completedAt == null) {
                        throw DomainException("INVARIANT_VIOLATION", "Completed match '$matchId' has no completedAt")
                    }
                }
            }

            for ((playerId, state) in match.players) {
                when (state.status) {
                    PlayerStatus.RUNNING -> {
                        if (state.result != null || state.leaveReason != null || state.leftAt != null) {
                            throw DomainException("INVARIANT_VIOLATION", "RUNNING state corrupted for '$playerId'")
                        }
                    }

                    PlayerStatus.FINISHED -> {
                        if (state.result == null || state.leaveReason != null || state.leftAt != null) {
                            throw DomainException("INVARIANT_VIOLATION", "FINISHED state corrupted for '$playerId'")
                        }
                    }

                    PlayerStatus.DEATH -> {
                        if (state.result != null || state.leaveReason != null || state.leftAt != null) {
                            throw DomainException("INVARIANT_VIOLATION", "DEATH state corrupted for '$playerId'")
                        }
                    }

                    PlayerStatus.LEAVE -> {
                        if (state.leaveReason == null || state.leftAt == null || state.result != null) {
                            throw DomainException("INVARIANT_VIOLATION", "LEAVE state corrupted for '$playerId'")
                        }
                    }
                }
            }
        }

        for ((roomId, room) in roomsById) {
            for (playerId in room.players) {
                if (!playerRoomId.containsKey(playerId)) {
                    throw DomainException("INVARIANT_VIOLATION", "Room '$roomId' player '$playerId' missing reverse map")
                }
                if (!playersById.containsKey(playerId)) {
                    throw DomainException("INVARIANT_VIOLATION", "Room '$roomId' player '$playerId' missing profile")
                }
            }
        }
    }

    private fun now() = clock.now()

    companion object {
        private const val ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val READY_CHECK_TTL_MS = 10_000L
    }
}
