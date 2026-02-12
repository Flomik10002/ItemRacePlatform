package dev.flomik

import io.ktor.server.application.*
import kotlinx.serialization.json.Json

import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val reconnectGraceMs = environment.config
        .propertyOrNull("race.reconnect-grace-ms")
        ?.getString()
        ?.toLongOrNull()
        ?: 45_000L

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val raceService = RaceService(
        reconnectGraceMs = reconnectGraceMs,
    )

    configureHTTP()
    configureSerialization(json)
    configureMonitoring()
    configureSockets(raceService, json, reconnectGraceMs)
    configureRouting()
}
