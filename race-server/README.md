# race-server

`race-server` is the authoritative websocket backend for Item Race.

## Quick start

```bash
./gradlew :race-server:run
```

Server listens on `:8080` by default.

## Test

```bash
./gradlew :race-server:test
```

## Main endpoints

- `GET /` - service status
- `GET /health` - health check
- `WS /race` - race protocol endpoint

## Docs endpoints

- `GET /docs` - human-readable docs index
- `GET /docs/protocol` - structured protocol catalog
- `GET /docs/openapi.json` - OpenAPI for HTTP endpoints
- `GET /docs/asyncapi.json` - AsyncAPI for websocket protocol

## Additional docs

- `docs/architecture.md`
- `docs/protocol.md`
- `docs/runbook.md`

## Persistence

Configured in `src/main/resources/application.yaml`:

```yaml
race:
  persistence:
    enabled: true
    file: ./race-server-data/state.json
```

When enabled, server snapshots state to file and restores on boot.
