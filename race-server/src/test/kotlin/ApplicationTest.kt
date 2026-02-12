package dev.flomik

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
