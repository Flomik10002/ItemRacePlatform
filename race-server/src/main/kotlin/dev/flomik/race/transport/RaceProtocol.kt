package dev.flomik.race.transport

import dev.flomik.race.application.MatchPlayerView
import dev.flomik.race.application.MatchView
import dev.flomik.race.application.PendingMatchView
import dev.flomik.race.application.RaceSnapshot
import dev.flomik.race.application.RoomPlayerView
import dev.flomik.race.application.RoomView
import dev.flomik.race.application.SelfView
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed interface ClientMessage {
    data class Hello(
        val playerId: String,
        val name: String,
        val sessionId: String?,
    ) : ClientMessage

    data object Ping : ClientMessage
    data object CreateRoom : ClientMessage
    data class JoinRoom(val roomCode: String) : ClientMessage
    data object LeaveRoom : ClientMessage
    data object RollMatch : ClientMessage
    data object StartMatch : ClientMessage
    data class Finish(val rttMs: Long, val igtMs: Long) : ClientMessage
    data object Death : ClientMessage
    data object SyncState : ClientMessage
}

class ProtocolException(message: String) : RuntimeException(message)

object ClientMessageParser {
    fun parse(json: Json, raw: String): ClientMessage {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            throw ProtocolException("Malformed JSON")
        }

        return when (root.requiredString("type")) {
            "hello" -> ClientMessage.Hello(
                playerId = root.requiredString("playerId"),
                name = root.requiredString("name"),
                sessionId = root.optionalString("sessionId"),
            )

            "ping" -> ClientMessage.Ping
            "create_room" -> ClientMessage.CreateRoom
            "join_room" -> ClientMessage.JoinRoom(roomCode = root.requiredString("roomCode"))
            "leave_room" -> ClientMessage.LeaveRoom
            "roll_match" -> ClientMessage.RollMatch
            "start_match" -> ClientMessage.StartMatch
            "finish" -> ClientMessage.Finish(
                rttMs = root.requiredLong("rttMs"),
                igtMs = root.requiredLong("igtMs"),
            )

            "death" -> ClientMessage.Death
            "sync_state" -> ClientMessage.SyncState
            else -> throw ProtocolException("Unknown message type")
        }
    }
}

private fun JsonObject.requiredString(name: String): String {
    return this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw ProtocolException("Missing or invalid '$name'")
}

private fun JsonObject.optionalString(name: String): String? {
    return this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun JsonObject.requiredLong(name: String): Long {
    val primitive = this[name] as? JsonPrimitive
        ?: throw ProtocolException("Missing or invalid '$name'")

    return primitive.longOrNull
        ?: primitive.doubleOrNull?.toLong()
        ?: primitive.content.toLongOrNull()
        ?: throw ProtocolException("Missing or invalid '$name'")
}

sealed interface ServerMessage

@Serializable
data class WelcomeMessage(
    val type: String = "welcome",
    val playerId: String,
    val name: String,
    val sessionId: String,
    val resumed: Boolean,
    val reconnectGraceMs: Long,
) : ServerMessage

@Serializable
data class AckMessage(
    val type: String = "ack",
    val action: String,
    val message: String? = null,
) : ServerMessage

@Serializable
data class ErrorMessage(
    val type: String = "error",
    val code: String,
    val message: String,
) : ServerMessage

@Serializable
data class PongMessage(
    val type: String = "pong",
    val serverTimeMs: Long,
) : ServerMessage

@Serializable
data class StateMessage(
    val type: String = "state",
    val snapshot: SnapshotDto,
) : ServerMessage

@Serializable
data class SnapshotDto(
    val serverTimeMs: Long,
    val reconnectGraceMs: Long,
    val self: SelfDto,
    val room: RoomDto? = null,
)

@Serializable
data class SelfDto(
    val playerId: String,
    val name: String,
    val connectionState: String,
    val roomCode: String? = null,
)

@Serializable
data class RoomDto(
    val code: String,
    val leaderId: String,
    val players: List<RoomPlayerDto>,
    val pendingMatch: PendingMatchDto? = null,
    val currentMatch: MatchDto? = null,
)

@Serializable
data class RoomPlayerDto(
    val playerId: String,
    val name: String,
    val connectionState: String,
    val pendingRemoval: Boolean,
)

@Serializable
data class PendingMatchDto(
    val revision: Int,
    val targetItem: String,
    val seed: Long,
    val rolledAtMs: Long,
)

@Serializable
data class MatchDto(
    val id: String,
    val revision: Int,
    val targetItem: String,
    val seed: Long,
    val isActive: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val completedAtMs: Long? = null,
    val players: List<MatchPlayerDto>,
)

@Serializable
data class MatchPlayerDto(
    val playerId: String,
    val status: String,
    val result: PlayerResultDto? = null,
    val leaveReason: String? = null,
    val leftAtMs: Long? = null,
)

@Serializable
data class PlayerResultDto(
    val rttMs: Long,
    val igtMs: Long,
)

fun RaceSnapshot.toDto(): SnapshotDto {
    return SnapshotDto(
        serverTimeMs = serverTimeMs,
        reconnectGraceMs = reconnectGraceMs,
        self = self.toDto(),
        room = room?.toDto(),
    )
}

private fun SelfView.toDto(): SelfDto = SelfDto(
    playerId = playerId,
    name = name,
    connectionState = connectionState.name,
    roomCode = roomCode,
)

private fun RoomView.toDto(): RoomDto = RoomDto(
    code = code,
    leaderId = leaderId,
    players = players.map(RoomPlayerView::toDto),
    pendingMatch = pendingMatch?.toDto(),
    currentMatch = currentMatch?.toDto(),
)

private fun RoomPlayerView.toDto(): RoomPlayerDto = RoomPlayerDto(
    playerId = playerId,
    name = name,
    connectionState = connectionState.name,
    pendingRemoval = pendingRemoval,
)

private fun PendingMatchView.toDto(): PendingMatchDto = PendingMatchDto(
    revision = revision,
    targetItem = targetItem,
    seed = seed,
    rolledAtMs = rolledAtMs,
)

private fun MatchView.toDto(): MatchDto = MatchDto(
    id = id,
    revision = revision,
    targetItem = targetItem,
    seed = seed,
    isActive = isActive,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    completedAtMs = completedAtMs,
    players = players.map(MatchPlayerView::toDto),
)

private fun MatchPlayerView.toDto(): MatchPlayerDto = MatchPlayerDto(
    playerId = playerId,
    status = status.name,
    result = result?.let { PlayerResultDto(it.rttMs, it.igtMs) },
    leaveReason = leaveReason?.name,
    leftAtMs = leftAtMs,
)
