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
    provider: file # file | postgres
    file: ./race-server-data/state.json
    postgres:
      url: jdbc:postgresql://localhost:5432/itemrace
      user: itemrace
      password: itemrace
```

When enabled, server snapshots state and restores on boot.

Environment variables (override config):

- `RACE_PERSISTENCE_ENABLED`
- `RACE_PERSISTENCE_PROVIDER`
- `RACE_PERSISTENCE_FILE`
- `RACE_DB_URL`
- `RACE_DB_USER`
- `RACE_DB_PASSWORD`
- `RACE_RECONNECT_GRACE_MS`

## Docker deployment

```bash
cd race-server
docker compose up --build
```

This starts:

- `race-server` on `:8080`
- `postgres` with persisted volume

Default compose configuration uses postgres persistence.
