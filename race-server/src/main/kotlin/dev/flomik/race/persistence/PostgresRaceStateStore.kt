package dev.flomik.race.persistence

import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Snapshot persistence backed by a relational database (PostgreSQL in production).
 * Uses a single-row table to keep the latest state atomically.
 */
class PostgresRaceStateStore(
    private val jdbcUrl: String,
    private val user: String?,
    private val password: String?,
    private val json: Json,
) : RaceStateStore {
    override suspend fun load(): PersistedState? = withContext(Dispatchers.IO) {
        openConnection().use { connection ->
            ensureSchema(connection)

            connection.prepareStatement(
                "SELECT payload FROM race_state_snapshot WHERE singleton_id = 1",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        return@withContext null
                    }
                    val raw = result.getString("payload") ?: return@withContext null
                    json.decodeFromString<PersistedState>(raw)
                }
            }
        }
    }

    override suspend fun save(state: PersistedState) = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(PersistedState.serializer(), state)
        openConnection().use { connection ->
            ensureSchema(connection)
            connection.autoCommit = false
            try {
                val updated = connection.prepareStatement(
                    """
                    UPDATE race_state_snapshot
                    SET schema_version = ?, saved_at_ms = ?, payload = ?
                    WHERE singleton_id = 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, state.schemaVersion)
                    statement.setLong(2, state.savedAtMs)
                    statement.setString(3, payload)
                    statement.executeUpdate()
                }

                if (updated == 0) {
                    connection.prepareStatement(
                        """
                        INSERT INTO race_state_snapshot (singleton_id, schema_version, saved_at_ms, payload)
                        VALUES (1, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setInt(1, state.schemaVersion)
                        statement.setLong(2, state.savedAtMs)
                        statement.setString(3, payload)
                        statement.executeUpdate()
                    }
                }

                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun openConnection(): Connection {
        val username = user?.takeIf { it.isNotBlank() }
        return if (username == null) {
            DriverManager.getConnection(jdbcUrl)
        } else {
            DriverManager.getConnection(jdbcUrl, username, password ?: "")
        }
    }

    private fun ensureSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS race_state_snapshot (
                    singleton_id SMALLINT PRIMARY KEY,
                    schema_version INT NOT NULL,
                    saved_at_ms BIGINT NOT NULL,
                    payload TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
    }
}

