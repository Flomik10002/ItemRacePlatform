# Race Server Architecture

## Overview

`race-server` is a Ktor-based websocket service for item-race sessions.

The codebase is split into explicit layers:

- `dev.flomik.race.domain`
  - core entities and transition guards
  - pure domain rules (`PlayerState.finish`, `PlayerState.leave`, etc.)
- `dev.flomik.race.application`
  - command handlers, invariants, room/match lifecycle
  - reconnect timeout policy
  - persistence hydration and snapshots
- `dev.flomik.race.transport`
  - websocket protocol parsing/serialization
  - protocol catalog used to generate docs
- `dev.flomik.race.persistence`
  - state store abstraction
  - in-memory + file + postgres implementations

Ktor glue lives in:

- `Application.kt`
- `Routing.kt`
- `Sockets.kt`
- `Serialization.kt`
- `Monitoring.kt`

## Runtime Flow

1. Boot
   - read config
   - construct `RaceService`
   - `warmup()` loads persisted state (if enabled)
2. WebSocket auth
   - client sends `hello`
   - server returns `welcome`
3. Commands
   - client sends command (`create_room`, `start_match`, `finish`, ...)
   - server responds `ack` or `error`
   - server pushes authoritative `state`
   - non-state event broadcasts (`advancement`) are relayed to room participants
4. Reconnect
   - disconnect marks session as `DISCONNECTED`
   - grace timeout runs
   - if client doesn't return, player transitions to `LEAVE(RECONNECT_TIMEOUT)`

## Persistence

Persistence is snapshot-based.

- Store contract: `RaceStateStore`
- Implementations:
  - `InMemoryRaceStateStore`
  - `FileRaceStateStore`
  - `PostgresRaceStateStore`

Saved model:

- players
- rooms
- matches

Sessions are runtime-only and re-established through websocket `hello`.

## Invariants (enforced in service)

- one player in at most one room
- room leader must be in room
- room cannot have both active and pending match
- active match must point to room and include room players only
- terminal player states cannot transition back to `RUNNING`
- `LEAVE` requires `leaveReason` and `leftAt`
- completed matches are pruned from memory once no longer active
- disconnected players with no room are pruned after reconnect grace timeout

## Entry Points

- Websocket: `/race`
- Health: `/health`
- Docs index: `/docs`
- Protocol catalog: `/docs/protocol`
- OpenAPI: `/docs/openapi.json`
- AsyncAPI: `/docs/asyncapi.json`
