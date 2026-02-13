package dev.flomik

import dev.flomik.race.application.RaceService
import dev.flomik.race.domain.DomainException
import dev.flomik.race.domain.LeaveReason
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
import dev.flomik.race.transport.isIgnorableAdvancementId
import dev.flomik.race.transport.toDto
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun Application.configureSockets(
    raceService: RaceService,
    json: Json,
    reconnectGraceMs: Long,
    pingTimeoutMs: Long,
) {
    val logger = LoggerFactory.getLogger("RaceSockets")
    val effectivePingTimeoutMs = pingTimeoutMs.coerceAtLeast(1_000L)
    val socketsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val socketsByPlayerId = ConcurrentHashMap<PlayerId, WebSocketServerSession>()
    val sessionsByPlayerId = ConcurrentHashMap<PlayerId, SessionId>()
    val lastHeartbeatByPlayerId = ConcurrentHashMap<PlayerId, Long>()

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

    monitor.subscribe(ApplicationStopped) {
        socketsScope.cancel("Application stopped")
    }

    socketsScope.launch {
        while (isActive) {
            delay(10_000L)

            val nowMs = System.currentTimeMillis()
            val candidatePlayerIds = sessionsByPlayerId.keys.toList()

            for (playerId in candidatePlayerIds) {
                val sessionId = sessionsByPlayerId[playerId] ?: continue
                val lastHeartbeatAt = lastHeartbeatByPlayerId[playerId] ?: continue
                if (nowMs - lastHeartbeatAt < effectivePingTimeoutMs) {
                    continue
                }

                val latestSessionId = sessionsByPlayerId[playerId] ?: continue
                if (latestSessionId != sessionId) {
                    continue
                }

                logger.info(
                    "No heartbeat from player {} for {}ms, forcing room leave",
                    playerId,
                    nowMs - lastHeartbeatAt,
                )

                val leaveAffected = try {
                    raceService.leaveRoom(
                        playerId = playerId,
                        reason = LeaveReason.RECONNECT_TIMEOUT,
                    )
                } catch (e: DomainException) {
                    if (e.code != "PLAYER_NOT_IN_ROOM") {
                        logger.warn("Failed to apply timeout leave for player {}", playerId, e)
                    }
                    emptySet()
                } catch (e: Exception) {
                    logger.warn("Failed to apply timeout leave for player {}", playerId, e)
                    emptySet()
                }
                broadcastStateSnapshots(leaveAffected)

                val disconnectAffected = runCatching {
                    raceService.disconnect(playerId, sessionId)
                }.onFailure {
                    logger.debug("Failed to disconnect timed out player {}", playerId, it)
                }.getOrDefault(emptySet())
                broadcastStateSnapshots(disconnectAffected)

                val socket = socketsByPlayerId[playerId]
                if (socket != null) {
                    socketsByPlayerId.remove(playerId, socket)
                } else {
                    socketsByPlayerId.remove(playerId)
                }
                if (sessionsByPlayerId.remove(playerId, sessionId)) {
                    lastHeartbeatByPlayerId.remove(playerId)
                }

                if (socket != null) {
                    runCatching {
                        socket.close(
                            CloseReason(
                                code = CloseReason.Codes.NORMAL,
                                message = "Ping timeout",
                            ),
                        )
                    }
                }

                launch {
                    delay(reconnectGraceMs)
                    val timeoutAffected = raceService.handleReconnectTimeout(
                        playerId = playerId,
                        sessionId = sessionId,
                    )
                    broadcastStateSnapshots(timeoutAffected)
                }
            }

            val staleDisconnectedAffected = runCatching {
                raceService.evictInactiveDisconnectedPlayers(effectivePingTimeoutMs)
            }.onFailure {
                logger.warn("Failed to evict stale disconnected players", it)
            }.getOrDefault(emptySet())
            broadcastStateSnapshots(staleDisconnectedAffected)
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
                        val actor = currentPlayerId
                        val sessionId = currentSessionId
                        if (actor != null && sessionId != null) {
                            lastHeartbeatByPlayerId[actor] = System.currentTimeMillis()
                            raceService.touchHeartbeat(actor, sessionId)
                        }

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
                                sessionsByPlayerId[parsed.playerId] = result.sessionId
                                lastHeartbeatByPlayerId[parsed.playerId] = System.currentTimeMillis()

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

                            is ClientMessage.ReadyCheck -> handleAction("ready_check") { actor ->
                                raceService.startReadyCheck(actor)
                            }

                            is ClientMessage.ReadyCheckResponse -> handleAction("ready_check_response") { actor ->
                                raceService.respondReadyCheck(actor, parsed.ready)
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
                    val removedCurrentSocket = socketsByPlayerId.remove(playerId, this)
                    if (removedCurrentSocket) {
                        if (sessionsByPlayerId.remove(playerId, sessionId)) {
                            lastHeartbeatByPlayerId.remove(playerId)
                        }

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
