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

## Live test with client mod

1. Start server:
   ```bash
   ./gradlew :race-server:run
   ```
2. Start client mod:
   ```bash
   ./gradlew :ItemHuntRace:runClient
   ```
3. In race lobby set server address to:
   - `ws://127.0.0.1:8080`
4. Create/join room and run match flow.

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
