package dev.flomik.race.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val DOCS_JSON = Json { prettyPrint = true }

@Serializable
data class ProtocolCatalog(
    val protocol: String,
    val websocketPath: String,
    val clientMessages: List<ProtocolMessageSpec>,
    val serverMessages: List<ProtocolMessageSpec>,
)

@Serializable
data class ProtocolMessageSpec(
    val type: String,
    val direction: String,
    val description: String,
    val fields: List<ProtocolFieldSpec>,
)

@Serializable
data class ProtocolFieldSpec(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String,
)

fun buildProtocolCatalog(): ProtocolCatalog {
    return ProtocolCatalog(
        protocol = "item-race-ws-v1",
        websocketPath = "/race",
        clientMessages = listOf(
            ProtocolMessageSpec(
                type = RaceMessageTypes.HELLO,
                direction = "client_to_server",
                description = "Authenticate websocket session and optionally resume by sessionId.",
                fields = listOf(
                    ProtocolFieldSpec("playerId", "string", true, "Stable player identifier (uuid)."),
                    ProtocolFieldSpec("name", "string", true, "Current player display name."),
                    ProtocolFieldSpec("sessionId", "string", false, "Previous session id to resume."),
                ),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.PING,
                direction = "client_to_server",
                description = "Liveness probe.",
                fields = emptyList(),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.SYNC_STATE,
                direction = "client_to_server",
                description = "Force push of latest state snapshot for this player.",
                fields = emptyList(),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.CREATE_ROOM,
                direction = "client_to_server",
                description = "Create a new room with sender as leader.",
                fields = emptyList(),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.JOIN_ROOM,
                direction = "client_to_server",
                description = "Join existing room by code.",
                fields = listOf(
                    ProtocolFieldSpec("roomCode", "string", true, "Room code (4..12 alnum)."),
                ),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.LEAVE_ROOM,
                direction = "client_to_server",
                description = "Leave current room completely.",
                fields = emptyList(),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.LEAVE_MATCH,
                direction = "client_to_server",
                description = "Mark self as LEAVE in active match without leaving room.",
                fields = emptyList(),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.ROLL_MATCH,
                direction = "client_to_server",
                description = "Leader-only: roll target item and seed into pending match.",
                fields = emptyList(),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.START_MATCH,
                direction = "client_to_server",
                description = "Leader-only: start match from pending config.",
                fields = emptyList(),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.CANCEL_START,
                direction = "client_to_server",
                description = "Leader-only: cancel pre-start countdown and return active match back to pending.",
                fields = emptyList(),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.READY_CHECK,
                direction = "client_to_server",
                description = "Leader-only: start 10-second readiness poll in the room.",
                fields = emptyList(),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.READY_CHECK_RESPONSE,
                direction = "client_to_server",
                description = "Respond to active readiness poll.",
                fields = listOf(
                    ProtocolFieldSpec("ready", "boolean", true, "true for READY, false for NOT_READY."),
                ),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.FINISH,
                direction = "client_to_server",
                description = "Report successful run result.",
                fields = listOf(
                    ProtocolFieldSpec("rttMs", "int64", true, "Real-time in milliseconds."),
                    ProtocolFieldSpec("igtMs", "int64", true, "In-game time in milliseconds."),
                ),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.DEATH,
                direction = "client_to_server",
                description = "Report death while RUNNING.",
                fields = emptyList(),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.ADVANCEMENT,
                direction = "client_to_server",
                description = "Report completed advancement for room broadcast notifications. Root advancements are ignored.",
                fields = listOf(
                    ProtocolFieldSpec("id", "string", true, "Advancement identifier."),
                ),
            ),
        ),
        serverMessages = listOf(
            ProtocolMessageSpec(
                type = RaceMessageTypes.WELCOME,
                direction = "server_to_client",
                description = "Session accepted and authenticated.",
                fields = listOf(
                    ProtocolFieldSpec("playerId", "string", true, "Echoed player id."),
                    ProtocolFieldSpec("name", "string", true, "Echoed player name."),
                    ProtocolFieldSpec("sessionId", "string", true, "Server-issued session id."),
                    ProtocolFieldSpec("resumed", "boolean", true, "Whether previous session was resumed."),
                    ProtocolFieldSpec("reconnectGraceMs", "int64", true, "Reconnect grace timeout in ms."),
                ),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.ACK,
                direction = "server_to_client",
                description = "Command accepted.",
                fields = listOf(
                    ProtocolFieldSpec("action", "string", true, "Accepted command type."),
                    ProtocolFieldSpec("message", "string", false, "Optional note."),
                ),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.STATE,
                direction = "server_to_client",
                description = "Authoritative state snapshot for the receiver.",
                fields = listOf(
                    ProtocolFieldSpec("snapshot", "object", true, "Complete race snapshot."),
                ),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.ERROR,
                direction = "server_to_client",
                description = "Command rejected or protocol error.",
                fields = listOf(
                    ProtocolFieldSpec("code", "string", true, "Machine-readable error code."),
                    ProtocolFieldSpec("message", "string", true, "Human-readable error message."),
                ),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.PONG,
                direction = "server_to_client",
                description = "Ping response.",
                fields = listOf(
                    ProtocolFieldSpec("serverTimeMs", "int64", true, "Current server timestamp."),
                ),
            ),
            ProtocolMessageSpec(
                type = RaceMessageTypes.ADVANCEMENT,
                direction = "server_to_client",
                description = "Advancement notification from another room participant.",
                fields = listOf(
                    ProtocolFieldSpec("playerId", "string", true, "Player id who completed advancement."),
                    ProtocolFieldSpec("playerName", "string", true, "Player display name."),
                    ProtocolFieldSpec("advancementId", "string", true, "Completed advancement identifier."),
                ),
            ),
        ),
    )
}

fun buildOpenApiJson(): String {
    val catalog = buildProtocolCatalog()

    val json = buildJsonObject {
        put("openapi", JsonPrimitive("3.1.0"))
        put("info", buildJsonObject {
            put("title", JsonPrimitive("Item Race Server API"))
            put("version", JsonPrimitive("1.0.0"))
            put("description", JsonPrimitive("HTTP inspection endpoints for race-server."))
        })
        put("paths", buildJsonObject {
            put("/", buildPathSpec("Service status"))
            put("/health", buildPathSpec("Health check"))
            put("/docs", buildPathSpec("Human-readable docs index"))
            put("/docs/protocol", buildPathSpec("Structured websocket protocol catalog"))
            put("/docs/openapi.json", buildPathSpec("OpenAPI document"))
            put("/docs/asyncapi.json", buildPathSpec("AsyncAPI document"))
            put("/admin", buildPathSpec("Admin web console"))
            put("/admin/api/overview", buildPathSpec("Admin API overview (token required)"))
        })
        put("x-websocket", buildJsonObject {
            put("path", JsonPrimitive(catalog.websocketPath))
            put("protocol", JsonPrimitive(catalog.protocol))
            put("description", JsonPrimitive("Use AsyncAPI document for websocket command/event contracts."))
        })
    }

    return DOCS_JSON.encodeToString(JsonObject.serializer(), json)
}

fun buildAsyncApiJson(): String {
    val catalog = buildProtocolCatalog()
    val clientMessages = catalog.clientMessages.associateBy { it.type }
    val serverMessages = catalog.serverMessages.associateBy { it.type }

    val messageComponents = buildJsonObject {
        for ((type, spec) in clientMessages) {
            put(type, buildAsyncApiMessage(type, spec))
        }
        for ((type, spec) in serverMessages) {
            put(type, buildAsyncApiMessage(type, spec))
        }
    }

    val json = buildJsonObject {
        put("asyncapi", JsonPrimitive("2.6.0"))
        put("info", buildJsonObject {
            put("title", JsonPrimitive("Item Race WS Protocol"))
            put("version", JsonPrimitive("1.0.0"))
            put("description", JsonPrimitive("Autogenerated from race-server protocol catalog."))
        })
        put("servers", buildJsonObject {
            put("local", buildJsonObject {
                put("url", JsonPrimitive("localhost:8080"))
                put("protocol", JsonPrimitive("ws"))
            })
        })
        put("channels", buildJsonObject {
            put(catalog.websocketPath, buildJsonObject {
                put("publish", buildJsonObject {
                    put("summary", JsonPrimitive("Client -> Server"))
                    put("message", buildJsonObject {
                        put("oneOf", buildJsonArray {
                            for (type in clientMessages.keys.sorted()) {
                                add(buildJsonObject {
                                    put("\$ref", JsonPrimitive("#/components/messages/$type"))
                                })
                            }
                        })
                    })
                })
                put("subscribe", buildJsonObject {
                    put("summary", JsonPrimitive("Server -> Client"))
                    put("message", buildJsonObject {
                        put("oneOf", buildJsonArray {
                            for (type in serverMessages.keys.sorted()) {
                                add(buildJsonObject {
                                    put("\$ref", JsonPrimitive("#/components/messages/$type"))
                                })
                            }
                        })
                    })
                })
            })
        })
        put("components", buildJsonObject {
            put("messages", messageComponents)
        })
    }

    return DOCS_JSON.encodeToString(JsonObject.serializer(), json)
}

private fun buildPathSpec(summary: String): JsonObject {
    return buildJsonObject {
        put("get", buildJsonObject {
            put("summary", JsonPrimitive(summary))
            put("responses", buildJsonObject {
                put("200", buildJsonObject {
                    put("description", JsonPrimitive("OK"))
                })
            })
        })
    }
}

private fun buildAsyncApiMessage(type: String, spec: ProtocolMessageSpec): JsonObject {
    val properties = buildJsonObject {
        put("type", buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("enum", JsonArray(listOf(JsonPrimitive(type))))
        })

        for (field in spec.fields) {
            put(field.name, buildJsonObject {
                put("type", JsonPrimitive(toJsonSchemaType(field.type)))
                put("description", JsonPrimitive(field.description))
            })
        }
    }

    val required = buildJsonArray {
        add(JsonPrimitive("type"))
        for (field in spec.fields.filter { it.required }) {
            add(JsonPrimitive(field.name))
        }
    }

    return buildJsonObject {
        put("name", JsonPrimitive(type))
        put("title", JsonPrimitive(type))
        put("summary", JsonPrimitive(spec.description))
        put("contentType", JsonPrimitive("application/json"))
        put("payload", buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", properties)
            put("required", required)
        })
    }
}

private fun toJsonSchemaType(rawType: String): String {
    return when (rawType.lowercase()) {
        "int64", "int32", "integer", "long" -> "integer"
        "boolean", "object", "array", "number", "string" -> rawType.lowercase()
        else -> "string"
    }
}
