package dev.flomik

import dev.flomik.race.application.RaceService
import dev.flomik.race.domain.DomainException
import dev.flomik.race.domain.PlayerId
import dev.flomik.race.domain.SessionId
import dev.flomik.race.transport.AckMessage
import dev.flomik.race.transport.AdvancementMessage
import dev.flomik.race.transport.ClientMessage
import dev.flomik.race.transport.ClientMessageParser
import dev.flomik.race.transport.ErrorMessage
import dev.flomik.race.transport.PongMessage
import dev.flomik.race.transport.ProtocolException
import dev.flomik.race.transport.ServerMessage
import dev.flomik.race.transport.StateMessage
import dev.flomik.race.transport.WelcomeMessage
import dev.flomik.race.transport.toDto
import dev.flomik.race.transport.isIgnorableAdvancementId
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun Application.configureSockets(
    raceService: RaceService,
    json: Json,
    reconnectGraceMs: Long,
) {
    val logger = LoggerFactory.getLogger("RaceSockets")
    val socketsByPlayerId = ConcurrentHashMap<PlayerId, WebSocketServerSession>()

    suspend fun broadcastStateSnapshots(playerIds: Set<PlayerId>) {
        for (targetPlayerId in playerIds) {
            val socket = socketsByPlayerId[targetPlayerId] ?: continue
            val snapshot = runCatching {
                raceService.snapshotFor(targetPlayerId)
            }.getOrNull() ?: continue

            val sent = runCatching {
                socket.sendServerMessage(json, StateMessage(snapshot = snapshot.toDto()))
            }

            if (sent.isFailure) {
                logger.debug("Failed to push state to player {}", targetPlayerId)
            }
        }
    }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        webSocket("/race") {
            var currentPlayerId: PlayerId? = null
            var currentSessionId: SessionId? = null

            suspend fun sendError(code: String, message: String) {
                sendServerMessage(json, ErrorMessage(code = code, message = message))
            }

            suspend fun handleAction(
                action: String,
                block: suspend (PlayerId) -> Set<PlayerId>,
            ) {
                val actor = currentPlayerId
                if (actor == null) {
                    sendError("NOT_AUTHENTICATED", "Send 'hello' first")
                    return
                }

                val affected = block(actor)
                sendServerMessage(json, AckMessage(action = action))
                broadcastStateSnapshots(affected + actor)
            }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) {
                        continue
                    }

                    val rawText = frame.readText()
                    val parsed = try {
                        ClientMessageParser.parse(json, rawText)
                    } catch (e: ProtocolException) {
                        sendError("BAD_REQUEST", e.message ?: "Invalid request")
                        continue
                    }

                    try {
                        when (parsed) {
                            is ClientMessage.Ping -> {
                                sendServerMessage(
                                    json,
                                    PongMessage(serverTimeMs = System.currentTimeMillis()),
                                )
                            }

                            is ClientMessage.Hello -> {
                                if (currentPlayerId != null) {
                                    sendError("ALREADY_AUTHENTICATED", "hello already completed for this connection")
                                    continue
                                }

                                val result = raceService.connect(
                                    playerId = parsed.playerId,
                                    name = parsed.name,
                                    requestedSessionId = parsed.sessionId,
                                )

                                currentPlayerId = parsed.playerId
                                currentSessionId = result.sessionId

                                val previousSocket = socketsByPlayerId.put(parsed.playerId, this)
                                if (previousSocket != null && previousSocket != this) {
                                    runCatching {
                                        previousSocket.close(
                                            CloseReason(
                                                code = CloseReason.Codes.NORMAL,
                                                message = "Replaced by newer session",
                                            ),
                                        )
                                    }
                                }

                                sendServerMessage(
                                    json,
                                    WelcomeMessage(
                                        playerId = parsed.playerId,
                                        name = parsed.name,
                                        sessionId = result.sessionId,
                                        resumed = result.resumed,
                                        reconnectGraceMs = reconnectGraceMs,
                                    ),
                                )
                                broadcastStateSnapshots(result.affectedPlayerIds + parsed.playerId)
                            }

                            is ClientMessage.SyncState -> {
                                val actor = currentPlayerId
                                if (actor == null) {
                                    sendError("NOT_AUTHENTICATED", "Send 'hello' first")
                                } else {
                                    val snapshot = raceService.snapshotFor(actor)
                                    sendServerMessage(json, StateMessage(snapshot = snapshot.toDto()))
                                }
                            }

                            is ClientMessage.CreateRoom -> handleAction("create_room") { actor ->
                                raceService.createRoom(actor)
                            }

                            is ClientMessage.JoinRoom -> handleAction("join_room") { actor ->
                                raceService.joinRoom(actor, parsed.roomCode)
                            }

                            is ClientMessage.LeaveRoom -> handleAction("leave_room") { actor ->
                                raceService.leaveRoom(actor)
                            }

                            is ClientMessage.LeaveMatch -> handleAction("leave_match") { actor ->
                                raceService.leaveMatch(actor)
                            }

                            is ClientMessage.RollMatch -> handleAction("roll_match") { actor ->
                                raceService.rollMatch(actor)
                            }

                            is ClientMessage.StartMatch -> handleAction("start_match") { actor ->
                                raceService.startMatch(actor)
                            }

                            is ClientMessage.CancelStart -> handleAction("cancel_start") { actor ->
                                raceService.cancelStart(actor)
                            }

                            is ClientMessage.Finish -> handleAction("finish") { actor ->
                                raceService.finish(
                                    playerId = actor,
                                    rttMs = parsed.rttMs,
                                    igtMs = parsed.igtMs,
                                )
                            }

                            is ClientMessage.Death -> handleAction("death") { actor ->
                                raceService.reportDeath(actor)
                            }

                            is ClientMessage.Advancement -> {
                                val actor = currentPlayerId
                                if (actor == null) {
                                    sendError("NOT_AUTHENTICATED", "Send 'hello' first")
                                } else if (isIgnorableAdvancementId(parsed.id)) {
                                    // Ignore root advancements like minecraft:story/root.
                                } else {
                                    val event = raceService.reportAdvancement(actor, parsed.id)
                                    for (targetPlayerId in event.recipientPlayerIds) {
                                        val socket = socketsByPlayerId[targetPlayerId] ?: continue
                                        runCatching {
                                            socket.sendServerMessage(
                                                json,
                                                AdvancementMessage(
                                                    playerId = event.playerId,
                                                    playerName = event.playerName,
                                                    advancementId = event.advancementId,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: DomainException) {
                        sendError(e.code, e.message)
                    } catch (e: Exception) {
                        logger.error("Unexpected websocket command failure", e)
                        sendError("INTERNAL_ERROR", "Unexpected server error")
                    }
                }
            } finally {
                val playerId = currentPlayerId
                val sessionId = currentSessionId
                if (playerId != null && sessionId != null) {
                    socketsByPlayerId.remove(playerId, this)

                    val affected = raceService.disconnect(
                        playerId = playerId,
                        sessionId = sessionId,
                    )
                    broadcastStateSnapshots(affected)

                    launch {
                        delay(reconnectGraceMs)
                        val timeoutAffected = raceService.handleReconnectTimeout(
                            playerId = playerId,
                            sessionId = sessionId,
                        )
                        broadcastStateSnapshots(timeoutAffected)
                    }
                }
            }
        }
    }
}

private suspend fun WebSocketServerSession.sendServerMessage(
    json: Json,
    message: ServerMessage,
) {
    val text = when (message) {
        is WelcomeMessage -> json.encodeToString(message)
        is AckMessage -> json.encodeToString(message)
        is ErrorMessage -> json.encodeToString(message)
        is PongMessage -> json.encodeToString(message)
        is StateMessage -> json.encodeToString(message)
        is AdvancementMessage -> json.encodeToString(message)
    }

    send(Frame.Text(text))
}
