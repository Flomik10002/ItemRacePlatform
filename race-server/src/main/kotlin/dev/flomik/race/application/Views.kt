package dev.flomik.race.application

import dev.flomik.race.domain.ConnectionState
import dev.flomik.race.domain.LeaveReason
import dev.flomik.race.domain.PlayerResult
import dev.flomik.race.domain.PlayerStatus

data class RaceSnapshot(
    val serverTimeMs: Long,
    val reconnectGraceMs: Long,
    val self: SelfView,
    val room: RoomView? = null,
)

data class SelfView(
    val playerId: String,
    val name: String,
    val connectionState: ConnectionState,
    val roomCode: String? = null,
)

data class RoomView(
    val code: String,
    val leaderId: String,
    val players: List<RoomPlayerView>,
    val pendingMatch: PendingMatchView? = null,
    val currentMatch: MatchView? = null,
)

data class RoomPlayerView(
    val playerId: String,
    val name: String,
    val connectionState: ConnectionState,
    val pendingRemoval: Boolean,
)

data class PendingMatchView(
    val revision: Int,
    val targetItem: String,
    val seed: Long,
    val rolledAtMs: Long,
)

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

data class MatchPlayerView(
    val playerId: String,
    val status: PlayerStatus,
    val result: PlayerResult? = null,
    val leaveReason: LeaveReason? = null,
    val leftAtMs: Long? = null,
)

