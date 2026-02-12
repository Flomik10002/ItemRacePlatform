package dev.flomik

import dev.flomik.race.transport.buildAsyncApiJson
import dev.flomik.race.transport.buildOpenApiJson
import dev.flomik.race.transport.buildProtocolCatalog
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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

        get("/docs/protocol") {
            call.respond(buildProtocolCatalog())
        }

        get("/docs/openapi.json") {
            call.respondText(
                text = buildOpenApiJson(),
                contentType = ContentType.Application.Json,
            )
        }

        get("/docs/asyncapi.json") {
            call.respondText(
                text = buildAsyncApiJson(),
                contentType = ContentType.Application.Json,
            )
        }

        get("/docs") {
            call.respondText(
                text = docsHtml(),
                contentType = ContentType.Text.Html,
            )
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

private fun docsHtml(): String {
    return """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Item Race Server Docs</title>
  <style>
    body { font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif; margin: 2rem; line-height: 1.5; }
    h1 { margin: 0 0 1rem 0; }
    code { background: #f2f2f2; padding: 0.15rem 0.3rem; border-radius: 4px; }
    .block { margin: 1rem 0; padding: 1rem; border: 1px solid #ddd; border-radius: 8px; }
    a { color: #0b57d0; text-decoration: none; }
    a:hover { text-decoration: underline; }
  </style>
</head>
<body>
  <h1>Item Race Server Documentation</h1>
  <div class="block">
    <h2>HTTP Endpoints</h2>
    <ul>
      <li><code>GET /</code> - service status</li>
      <li><code>GET /health</code> - health check</li>
    </ul>
  </div>
  <div class="block">
    <h2>Protocol Docs</h2>
    <ul>
      <li><a href="/docs/protocol">/docs/protocol</a> - structured websocket protocol catalog</li>
      <li><a href="/docs/openapi.json">/docs/openapi.json</a> - OpenAPI (HTTP endpoints)</li>
      <li><a href="/docs/asyncapi.json">/docs/asyncapi.json</a> - AsyncAPI (websocket messages)</li>
    </ul>
  </div>
  <div class="block">
    <h2>WebSocket Endpoint</h2>
    <p>Connect to <code>/race</code> and start with <code>{"type":"hello","playerId":"...","name":"..."}</code>.</p>
  </div>
</body>
</html>
""".trimIndent()
}
