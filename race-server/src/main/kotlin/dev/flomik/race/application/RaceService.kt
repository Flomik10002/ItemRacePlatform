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
import dev.flomik.race.domain.Room
import dev.flomik.race.domain.RoomId
import dev.flomik.race.domain.SessionId
import dev.flomik.race.domain.complete
import dev.flomik.race.domain.die
import dev.flomik.race.domain.finish
import dev.flomik.race.domain.isActive
import dev.flomik.race.domain.isTerminal
import dev.flomik.race.domain.leave
import java.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

data class ConnectResult(
    val sessionId: SessionId,
    val resumed: Boolean,
    val affectedPlayerIds: Set<PlayerId>,
)

class RaceService(
    private val reconnectGraceMs: Long = 45_000L,
    private val clock: Clock = SystemClock,
    private val idGenerator: IdGenerator = UuidIdGenerator(),
    private val randomSource: RandomSource = KotlinRandomSource(),
) {
    private val mutex = Mutex()

    private val roomsById = mutableMapOf<RoomId, Room>()
    private val roomIdByCode = mutableMapOf<String, RoomId>()
    private val matchesById = mutableMapOf<MatchId, Match>()
    private val playersById = mutableMapOf<PlayerId, PlayerProfile>()
    private val sessionsByPlayerId = mutableMapOf<PlayerId, PlayerSession>()
    private val playerRoomId = mutableMapOf<PlayerId, RoomId>()

    suspend fun connect(
        playerId: PlayerId,
        name: String,
        requestedSessionId: SessionId?,
    ): ConnectResult = mutex.withLock {
        requirePlayerIdentity(playerId, name)
        val now = now()

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
        val sessionId = if (resumed) {
            requestedSessionId
        } else {
            idGenerator.nextSessionId()
        }!!

        sessionsByPlayerId[playerId] = PlayerSession(
            sessionId = sessionId,
            playerId = playerId,
            connectionState = ConnectionState.CONNECTED,
            lastSeenAt = now,
            disconnectedAt = null,
        )

        val affected = playersInSameRoom(playerId).ifEmpty { setOf(playerId) }
        validateGlobalState()
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
        val session = sessionsByPlayerId[playerId] ?: return@withLock emptySet()
        if (session.sessionId != sessionId) return@withLock emptySet()
        if (session.connectionState == ConnectionState.DISCONNECTED) return@withLock emptySet()

        val now = now()
        session.connectionState = ConnectionState.DISCONNECTED
        session.disconnectedAt = now
        session.lastSeenAt = now
        playersById[playerId]?.lastSeenAt = now

        validateGlobalState()
        playersInSameRoom(playerId).ifEmpty { setOf(playerId) }
    }

    suspend fun handleReconnectTimeout(
        playerId: PlayerId,
        sessionId: SessionId,
    ): Set<PlayerId> = mutex.withLock {
        val session = sessionsByPlayerId[playerId] ?: return@withLock emptySet()
        if (session.sessionId != sessionId) return@withLock emptySet()
        if (session.connectionState != ConnectionState.DISCONNECTED) return@withLock emptySet()

        val disconnectedAt = session.disconnectedAt ?: return@withLock emptySet()
        val now = now()
        val disconnectedForMs = Duration.between(disconnectedAt, now).toMillis()
        if (disconnectedForMs < reconnectGraceMs) return@withLock emptySet()

        val room = findRoomByPlayer(playerId) ?: return@withLock emptySet()
        val affected = room.players.toMutableSet().apply { add(playerId) }

        val match = activeMatch(room)
        if (match == null) {
            removePlayerFromRoom(room, playerId)
            validateGlobalState()
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
        affected
    }

    suspend fun createRoom(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
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
        setOf(playerId)
    }

    suspend fun joinRoom(
        playerId: PlayerId,
        roomCodeRaw: String,
    ): Set<PlayerId> = mutex.withLock {
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
        ensureLeader(room)

        validateGlobalState()
        room.players.toSet()
    }

    suspend fun leaveRoom(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
        val room = findRoomByPlayer(playerId)
            ?: throw DomainException("PLAYER_NOT_IN_ROOM", "Player is not in a room")

        val now = now()
        val affected = room.players.toMutableSet().apply { add(playerId) }
        val match = activeMatch(room)

        if (match == null) {
            removePlayerFromRoom(room, playerId)
            validateGlobalState()
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
        affected
    }

    suspend fun rollMatch(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
        val room = findRoomByPlayer(playerId)
            ?: throw DomainException("PLAYER_NOT_IN_ROOM", "Player is not in a room")

        ensureLeader(room, playerId)
        if (activeMatch(room) != null) {
            throw DomainException("MATCH_ALREADY_ACTIVE", "Current match is still active")
        }
        if (room.pendingMatch != null) {
            throw DomainException("PENDING_MATCH_EXISTS", "Pending match already exists")
        }

        val now = now()
        val revision = room.revisionCounter + 1
        val seed = randomSource.nextLong()
        room.pendingMatch = PendingMatchConfig(
            targetItem = pickTargetItem(seed),
            seed = seed,
            rolledAt = now,
            revision = revision,
        )
        room.revisionCounter = revision

        validateGlobalState()
        room.players.toSet()
    }

    suspend fun startMatch(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
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
        room.pendingRemovals.clear()

        validateGlobalState()
        room.players.toSet()
    }

    suspend fun finish(
        playerId: PlayerId,
        rttMs: Long,
        igtMs: Long,
    ): Set<PlayerId> = mutex.withLock {
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
        room.players.toSet().plus(playerId)
    }

    suspend fun reportDeath(playerId: PlayerId): Set<PlayerId> = mutex.withLock {
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
        room.players.toSet().plus(playerId)
    }

    suspend fun snapshotFor(playerId: PlayerId): RaceSnapshot = mutex.withLock {
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

        if (room.players.isEmpty()) {
            deleteRoom(room)
            return
        }

        ensureLeader(room)
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
    }

    private fun applyPendingRemovals(room: Room) {
        if (room.pendingRemovals.isEmpty()) return

        val removals = room.pendingRemovals.toList()
        room.pendingRemovals.clear()
        removals.forEach { removePlayerFromRoom(room, it) }
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
        return TARGET_ITEMS[seeded.nextInt(TARGET_ITEMS.size)]
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

        private val TARGET_ITEMS = listOf(
            "minecraft:diamond",
            "minecraft:emerald",
            "minecraft:ender_pearl",
            "minecraft:blaze_rod",
            "minecraft:nether_wart",
            "minecraft:quartz",
            "minecraft:obsidian",
            "minecraft:golden_apple",
            "minecraft:gold_ingot",
            "minecraft:iron_ingot",
            "minecraft:lapis_lazuli",
            "minecraft:redstone",
            "minecraft:amethyst_shard",
            "minecraft:glow_berries",
            "minecraft:slime_ball",
            "minecraft:gunpowder",
            "minecraft:string",
            "minecraft:spider_eye",
            "minecraft:ghast_tear",
            "minecraft:phantom_membrane",
            "minecraft:prismarine_shard",
            "minecraft:nautilus_shell",
            "minecraft:heart_of_the_sea",
            "minecraft:ancient_debris",
            "minecraft:netherite_scrap",
            "minecraft:copper_ingot",
            "minecraft:goat_horn",
            "minecraft:honey_bottle",
            "minecraft:music_disc_13",
            "minecraft:totem_of_undying",
        )
    }
}
