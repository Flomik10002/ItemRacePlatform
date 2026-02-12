package dev.flomik

import dev.flomik.race.application.KotlinRandomSource
import dev.flomik.race.application.RaceService
import dev.flomik.race.application.UuidIdGenerator
import io.ktor.server.application.Application
import kotlinx.serialization.json.Json

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
        idGenerator = UuidIdGenerator(),
        randomSource = KotlinRandomSource(),
    )

    configureSerialization(json)
    configureMonitoring()
    configureRouting()
    configureSockets(raceService, json, reconnectGraceMs)
}
