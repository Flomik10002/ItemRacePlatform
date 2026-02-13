package dev.flomik

import dev.flomik.race.application.KotlinRandomSource
import dev.flomik.race.application.RaceService
import dev.flomik.race.application.TargetItemCatalog
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
    val pingTimeoutMs = readConfigOrEnv(
        configKey = "race.ping-timeout-ms",
        envKey = "RACE_PING_TIMEOUT_MS",
    )?.toLongOrNull()
        ?: 180_000L

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
    val targetItems = loadTargetItems()

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
        targetItems = targetItems,
    )
    runBlocking { raceService.warmup() }

    configureSerialization(json)
    configureMonitoring()
    configureRouting()
    configureSockets(raceService, json, reconnectGraceMs, pingTimeoutMs)
}

private fun Application.readConfigOrEnv(configKey: String, envKey: String): String? {
    val envValue = System.getenv(envKey)?.takeIf { it.isNotBlank() }
    if (envValue != null) return envValue
    return environment.config.propertyOrNull(configKey)?.getString()
}

private fun Application.loadTargetItems(): List<String> {
    val externalPath = readConfigOrEnv(
        configKey = "race.target-items-file",
        envKey = "RACE_TARGET_ITEMS_FILE",
    )?.trim()?.takeIf { it.isNotEmpty() }

    return if (externalPath != null) {
        val items = TargetItemCatalog.loadFromFile(Paths.get(externalPath))
        environment.log.info("Loaded {} target items from {}", items.size, externalPath)
        items
    } else {
        val items = TargetItemCatalog.loadFromClasspath()
        environment.log.info("Loaded {} target items from classpath resource items.txt", items.size)
        items
    }
}
