# Race Server Runbook

## Local Run

```bash
./gradlew :race-server:run
```

Default port: `8080`

## Tests

```bash
./gradlew :race-server:test
```

## Useful URLs

- `http://localhost:8080/`
- `http://localhost:8080/health`
- `http://localhost:8080/docs`
- `http://localhost:8080/docs/protocol`
- `http://localhost:8080/docs/openapi.json`
- `http://localhost:8080/docs/asyncapi.json`

## Live E2E with mod client

1. Start backend:
   ```bash
   ./gradlew :race-server:run
   ```
2. Launch mod client:
   ```bash
   ./gradlew :ItemHuntRace:runClient
   ```
3. In race lobby set server address to `ws://127.0.0.1:8080`.
4. Validate flow:
   - create room
   - second player joins
   - roll/start match
   - finish/death updates leaderboard

## Persistence config

`application.yaml`:

```yaml
race:
  target-items-file: "" # optional path to external items.txt
  persistence:
    enabled: true
    provider: file # file | postgres
    file: ./race-server-data/state.json
    postgres:
      url: jdbc:postgresql://localhost:5432/itemrace
      user: itemrace
      password: itemrace
```

## Docker / host deploy

```bash
cd race-server
docker compose up --build
```

Compose provisions:

- race-server container
- postgres container with persistent volume
- binds `./config/items.txt` into container and uses it as target item pool

## Production deploy checklist

1. Configure firewall/NAT for TCP `8080` (or change mapping in compose).
2. Set strong Postgres credentials in `docker-compose.yml`.
3. Edit `config/items.txt` with allowed race targets.
4. Start stack:
   ```bash
   cd race-server
   docker compose up -d --build
   ```
5. Smoke check:
   - `curl http://127.0.0.1:8080/health`
   - open `http://127.0.0.1:8080/docs`
6. Watch logs:
   ```bash
   docker compose logs -f race-server
   ```

## Memory policy

- Completed matches are pruned from in-memory registry after match completion or `cancel_start`.
- Disconnected players with no room are pruned after reconnect grace timeout.
- Recommended JVM options for low footprint:
  - `-XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxRAMPercentage=70 -XX:MaxMetaspaceSize=192m`

## Troubleshooting

- `error: NOT_AUTHENTICATED`
  - client skipped `hello` before command
- `error: PLAYER_ALREADY_IN_ROOM`
  - stale client state or double join
- `error: MATCH_ALREADY_ACTIVE`
  - room has active match, wait for completion or leave room
