package dev.flomik

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respond(mapOf("service" to "race-server", "status" to "ok"))
        }
        get("/health") {
            call.respond(
                mapOf(
                    "status" to "ok",
                    "timeMs" to Instant.now().toEpochMilli(),
                ),
            )
        }
    }
}
