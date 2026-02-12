package dev.flomik

import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.Instant
import kotlinx.serialization.Serializable

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respond(RootResponse(service = "race-server", status = "ok"))
        }

        get("/health") {
            call.respond(HealthResponse(status = "ok", timeMs = Instant.now().toEpochMilli()))
        }
    }
}

@Serializable
private data class RootResponse(
    val service: String,
    val status: String,
)

@Serializable
private data class HealthResponse(
    val status: String,
    val timeMs: Long,
)
