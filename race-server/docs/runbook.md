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

## Persistence config

`application.yaml`:

```yaml
race:
  persistence:
    enabled: true
    file: ./race-server-data/state.json
```

## Troubleshooting

- `error: NOT_AUTHENTICATED`
  - client skipped `hello` before command
- `error: PLAYER_ALREADY_IN_ROOM`
  - stale client state or double join
- `error: MATCH_ALREADY_ACTIVE`
  - room has active match, wait for completion or leave room
