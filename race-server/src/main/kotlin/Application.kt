package dev.flomik

import dev.flomik.race.application.KotlinRandomSource
import dev.flomik.race.application.RaceService
import dev.flomik.race.application.UuidIdGenerator
import dev.flomik.race.persistence.FileRaceStateStore
import io.ktor.server.application.Application
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
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

    val persistenceEnabled = environment.config
        .propertyOrNull("race.persistence.enabled")
        ?.getString()
        ?.toBooleanStrictOrNull()
        ?: true

    val persistenceFilePath = environment.config
        .propertyOrNull("race.persistence.file")
        ?.getString()
        ?.takeIf { it.isNotBlank() }
        ?: Paths.get(
            System.getProperty("java.io.tmpdir"),
            "item-race",
            "race-state.json",
        ).toString()

    val stateStore = if (persistenceEnabled) {
        FileRaceStateStore(
            filePath = Paths.get(persistenceFilePath),
            json = json,
        )
    } else {
        null
    }

    val raceService = RaceService(
        reconnectGraceMs = reconnectGraceMs,
        idGenerator = UuidIdGenerator(),
        randomSource = KotlinRandomSource(),
        stateStore = stateStore,
    )
    runBlocking { raceService.warmup() }

    configureSerialization(json)
    configureMonitoring()
    configureRouting()
    configureSockets(raceService, json, reconnectGraceMs)
}
