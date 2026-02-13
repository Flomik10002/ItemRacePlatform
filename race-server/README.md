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
- `GET /admin` - admin web console

## Docs endpoints

- `GET /docs` - human-readable docs index
- `GET /docs/protocol` - structured protocol catalog
- `GET /docs/openapi.json` - OpenAPI for HTTP endpoints
- `GET /docs/asyncapi.json` - AsyncAPI for websocket protocol

Admin API endpoints are mounted under `GET/POST/DELETE /admin/api/*` and require admin token (`X-Admin-Token` header or `Authorization: Bearer <token>`).

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
- `RACE_PING_TIMEOUT_MS`
- `RACE_ADMIN_ENABLED`
- `RACE_ADMIN_TOKEN`
- `RACE_TARGET_ITEMS_FILE`

If `RACE_ADMIN_TOKEN` is not configured, server falls back to `dev-admin-token` and logs a warning. Set a strong token in production.

Target items are loaded from:

- external file (`RACE_TARGET_ITEMS_FILE` / `race.target-items-file`) when set
- otherwise from classpath `src/main/resources/items.txt`

## Docker deployment

```bash
cd race-server
docker compose up --build
```

This starts:

- `race-server` on `:8080`
- `postgres` with persisted volume

Default compose configuration uses postgres persistence.
Docker build runs from `race-server` standalone settings, so `ItemHuntRace` module is excluded from container build.

## Deploy on host (Docker + Postgres)

1. Copy repository to host and install Docker Engine + Docker Compose plugin.
2. Edit target item pool in `race-server/config/items.txt`.
3. (Optional) Change DB credentials and ports in `race-server/docker-compose.yml`.
4. Start:
   ```bash
   cd race-server
   docker compose up -d --build
   ```
5. Check status/logs:
   ```bash
   docker compose ps
   docker compose logs -f race-server
   ```
6. Verify:
   - `http://<host>:8080/health`
   - `http://<host>:8080/docs`
