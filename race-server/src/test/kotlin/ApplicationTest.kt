package dev.flomik

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun rootEndpointIsAvailable() = testApplication {
        environment {
            config = MapApplicationConfig(
                "race.persistence.enabled" to "false",
            )
        }
        application {
            module()
        }

        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun healthEndpointIsAvailable() = testApplication {
        environment {
            config = MapApplicationConfig(
                "race.persistence.enabled" to "false",
            )
        }
        application {
            module()
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun docsEndpointsAreAvailable() = testApplication {
        environment {
            config = MapApplicationConfig(
                "race.persistence.enabled" to "false",
            )
        }
        application {
            module()
        }

        val docs = client.get("/docs")
        assertEquals(HttpStatusCode.OK, docs.status)
        assertTrue(docs.bodyAsText().contains("Item Race Server Documentation"))

        val protocol = client.get("/docs/protocol")
        assertEquals(HttpStatusCode.OK, protocol.status)
        assertTrue(protocol.bodyAsText().contains("item-race-ws-v1"))

        val openapi = client.get("/docs/openapi.json")
        assertEquals(HttpStatusCode.OK, openapi.status)
        assertTrue(openapi.bodyAsText().contains("\"openapi\""))

        val asyncapi = client.get("/docs/asyncapi.json")
        assertEquals(HttpStatusCode.OK, asyncapi.status)
        assertTrue(asyncapi.bodyAsText().contains("\"asyncapi\""))
    }
}
