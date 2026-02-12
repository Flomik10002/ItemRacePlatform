# WebSocket Protocol

Version: `item-race-ws-v1`

## Connection

Endpoint: `/race`

Client must authenticate first:

```json
{"type":"hello","playerId":"<uuid>","name":"<nick>","sessionId":"<optional>"}
```

Server responds:

```json
{"type":"welcome","playerId":"...","name":"...","sessionId":"...","resumed":false,"reconnectGraceMs":45000}
```

## Client -> Server Commands

- `ping`
- `sync_state`
- `create_room`
- `join_room` (`roomCode`)
- `leave_room`
- `roll_match`
- `start_match`
- `finish` (`rttMs`, `igtMs`)
- `death`

## Server -> Client Events

- `welcome`
- `ack` (`action`)
- `state` (`snapshot`)
- `error` (`code`, `message`)
- `pong` (`serverTimeMs`)

## Authoritative State

`state.snapshot` is the source of truth for client UI/game logic.

Contains:

- `self`
  - player identity and connection state
- `room` (nullable)
  - room info, roster, leader
  - `pendingMatch` (nullable)
  - `currentMatch` (nullable)

## Reconnect Contract

- temporary disconnect does not mean match leave
- if grace timeout expires while still disconnected:
  - player transitions to `LEAVE`
  - reason: `RECONNECT_TIMEOUT`

## Machine-readable specs

- `/docs/protocol`
- `/docs/asyncapi.json`
