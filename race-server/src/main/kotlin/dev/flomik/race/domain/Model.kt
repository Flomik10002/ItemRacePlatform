package dev.flomik.race.domain

import java.time.Instant
import kotlinx.serialization.Serializable

typealias RoomId = String
typealias MatchId = String
typealias PlayerId = String
typealias SessionId = String

@Serializable
enum class PlayerStatus {
    RUNNING,
    FINISHED,
    DEATH,
    LEAVE,
}

@Serializable
enum class LeaveReason {
    MANUAL,
    RECONNECT_TIMEOUT,
    KICK,
}

@Serializable
enum class ConnectionState {
    CONNECTED,
    DISCONNECTED,
}

@Serializable
enum class MatchLifecycleStatus {
    ACTIVE,
    COMPLETED,
}

@Serializable
enum class ReadyCheckStatus {
    READY,
    NOT_READY,
}

data class Room(
    val id: RoomId,
    val code: String,
    val players: LinkedHashSet<PlayerId>,
    var leaderId: PlayerId,
    var currentMatchId: MatchId? = null,
    var pendingMatch: PendingMatchConfig? = null,
    var readyCheck: ReadyCheck? = null,
    var revisionCounter: Int = 0,
    val pendingRemovals: LinkedHashSet<PlayerId> = linkedSetOf(),
)

data class PendingMatchConfig(
    val targetItem: String,
    val seed: Long,
    val rolledAt: Instant,
    val revision: Int,
)

data class ReadyCheck(
    val initiatedBy: PlayerId,
    val startedAt: Instant,
    val expiresAt: Instant,
    val responses: MutableMap<PlayerId, ReadyCheckResponse>,
)

data class ReadyCheckResponse(
    var status: ReadyCheckStatus,
    var respondedAt: Instant,
)

data class Match(
    val id: MatchId,
    val roomId: RoomId,
    val revision: Int,
    val targetItem: String,
    val seed: Long,
    var lifecycleStatus: MatchLifecycleStatus,
    val players: MutableMap<PlayerId, PlayerState>,
    val createdAt: Instant,
    var updatedAt: Instant,
    var completedAt: Instant? = null,
)

data class PlayerState(
    var status: PlayerStatus,
    var result: PlayerResult? = null,
    var leaveReason: LeaveReason? = null,
    var leftAt: Instant? = null,
)

@Serializable
data class PlayerResult(
    val rttMs: Long,
    val igtMs: Long,
)

data class PlayerSession(
    val sessionId: SessionId,
    val playerId: PlayerId,
    var connectionState: ConnectionState,
    var lastSeenAt: Instant,
    var disconnectedAt: Instant? = null,
)

data class PlayerProfile(
    val id: PlayerId,
    var name: String,
    val createdAt: Instant,
    var lastSeenAt: Instant,
)

fun PlayerState.isTerminal(): Boolean = status != PlayerStatus.RUNNING

fun PlayerState.finish(result: PlayerResult) {
    if (status != PlayerStatus.RUNNING) {
        throw DomainException("INVALID_TRANSITION", "Only RUNNING player can finish")
    }
    if (result.rttMs < 0 || result.igtMs < 0) {
        throw DomainException("INVALID_RESULT", "rttMs and igtMs must be >= 0")
    }
    status = PlayerStatus.FINISHED
    this.result = result
    leaveReason = null
    leftAt = null
}

fun PlayerState.die() {
    if (status != PlayerStatus.RUNNING) {
        throw DomainException("INVALID_TRANSITION", "Only RUNNING player can die")
    }
    status = PlayerStatus.DEATH
    result = null
    leaveReason = null
    leftAt = null
}

fun PlayerState.leave(reason: LeaveReason, at: Instant) {
    if (status != PlayerStatus.RUNNING) {
        throw DomainException("INVALID_TRANSITION", "Only RUNNING player can leave")
    }
    status = PlayerStatus.LEAVE
    result = null
    leaveReason = reason
    leftAt = at
}

val Match.isActive: Boolean
    get() = lifecycleStatus == MatchLifecycleStatus.ACTIVE

fun Match.complete(at: Instant) {
    lifecycleStatus = MatchLifecycleStatus.COMPLETED
    updatedAt = at
    completedAt = at
}
