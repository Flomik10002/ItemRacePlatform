package dev.flomik

import dev.flomik.race.application.KotlinRandomSource
import dev.flomik.race.application.RaceService
import dev.flomik.race.application.UuidIdGenerator
import dev.flomik.race.persistence.FileRaceStateStore
import dev.flomik.race.persistence.PostgresRaceStateStore
import io.ktor.server.application.Application
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val reconnectGraceMs = readConfigOrEnv(
        configKey = "race.reconnect-grace-ms",
        envKey = "RACE_RECONNECT_GRACE_MS",
    )?.toLongOrNull()
        ?: 45_000L

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    val persistenceEnabled = readConfigOrEnv(
        configKey = "race.persistence.enabled",
        envKey = "RACE_PERSISTENCE_ENABLED",
    )
        ?.toBooleanStrictOrNull()
        ?: true

    val persistenceProvider = readConfigOrEnv(
        configKey = "race.persistence.provider",
        envKey = "RACE_PERSISTENCE_PROVIDER",
    )?.lowercase() ?: "file"

    val stateStore = if (!persistenceEnabled) {
        null
    } else when (persistenceProvider) {
        "file" -> {
            val persistenceFilePath = readConfigOrEnv(
                configKey = "race.persistence.file",
                envKey = "RACE_PERSISTENCE_FILE",
            )?.takeIf { it.isNotBlank() } ?: Paths.get(
                System.getProperty("java.io.tmpdir"),
                "item-race",
                "race-state.json",
            ).toString()

            FileRaceStateStore(
                filePath = Paths.get(persistenceFilePath),
                json = json,
            )
        }

        "postgres" -> {
            val jdbcUrl = readConfigOrEnv(
                configKey = "race.persistence.postgres.url",
                envKey = "RACE_DB_URL",
            )?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "Postgres persistence requires race.persistence.postgres.url or RACE_DB_URL",
                )

            val user = readConfigOrEnv(
                configKey = "race.persistence.postgres.user",
                envKey = "RACE_DB_USER",
            )
            val password = readConfigOrEnv(
                configKey = "race.persistence.postgres.password",
                envKey = "RACE_DB_PASSWORD",
            )

            PostgresRaceStateStore(
                jdbcUrl = jdbcUrl,
                user = user,
                password = password,
                json = json,
            )
        }

        else -> throw IllegalStateException("Unknown persistence provider: $persistenceProvider")
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

private fun Application.readConfigOrEnv(configKey: String, envKey: String): String? {
    val envValue = System.getenv(envKey)?.takeIf { it.isNotBlank() }
    if (envValue != null) return envValue
    return environment.config.propertyOrNull(configKey)?.getString()
}
