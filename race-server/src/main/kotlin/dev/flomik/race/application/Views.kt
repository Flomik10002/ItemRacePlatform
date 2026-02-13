package dev.flomik.race.application

import dev.flomik.race.domain.ConnectionState
import dev.flomik.race.domain.LeaveReason
import dev.flomik.race.domain.PlayerResult
import dev.flomik.race.domain.PlayerStatus
import dev.flomik.race.domain.ReadyCheckStatus
import kotlinx.serialization.Serializable

@Serializable
data class RaceSnapshot(
    val serverTimeMs: Long,
    val reconnectGraceMs: Long,
    val self: SelfView,
    val room: RoomView? = null,
)

@Serializable
data class SelfView(
    val playerId: String,
    val name: String,
    val connectionState: ConnectionState,
    val roomCode: String? = null,
)

@Serializable
data class RoomView(
    val code: String,
    val leaderId: String,
    val players: List<RoomPlayerView>,
    val pendingMatch: PendingMatchView? = null,
    val currentMatch: MatchView? = null,
    val readyCheck: ReadyCheckView? = null,
)

@Serializable
data class RoomPlayerView(
    val playerId: String,
    val name: String,
    val connectionState: ConnectionState,
    val pendingRemoval: Boolean,
)

@Serializable
data class PendingMatchView(
    val revision: Int,
    val targetItem: String,
    val seed: Long,
    val rolledAtMs: Long,
)

@Serializable
data class ReadyCheckView(
    val initiatedBy: String,
    val startedAtMs: Long,
    val expiresAtMs: Long,
    val responses: List<ReadyCheckResponseView>,
)

@Serializable
data class ReadyCheckResponseView(
    val playerId: String,
    val status: ReadyCheckStatus,
    val respondedAtMs: Long,
)

@Serializable
data class MatchView(
    val id: String,
    val revision: Int,
    val targetItem: String,
    val seed: Long,
    val isActive: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val completedAtMs: Long? = null,
    val players: List<MatchPlayerView>,
)

@Serializable
data class MatchPlayerView(
    val playerId: String,
    val status: PlayerStatus,
    val result: PlayerResult? = null,
    val leaveReason: LeaveReason? = null,
    val leftAtMs: Long? = null,
)

@Serializable
data class AdminOverviewView(
    val generatedAtMs: Long,
    val reconnectGraceMs: Long,
    val rooms: List<RoomView>,
    val detachedPlayers: List<AdminDetachedPlayerView>,
)

@Serializable
data class AdminDetachedPlayerView(
    val playerId: String,
    val name: String,
    val connectionState: ConnectionState,
    val lastSeenAtMs: Long,
)
