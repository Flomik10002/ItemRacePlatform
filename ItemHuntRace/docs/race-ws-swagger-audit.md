# Item Race WS Protocol + Client Audit

Дата аудита: 2026-02-12  
Кодовая база: `server.js` + клиентские race-классы (`RaceSessionManager`, `RaceLobbyScreen`, mixins)

## Кодовые якоря (для быстрой сверки)

- Серверный switch по `type`: `server.js:203`
- `start_request` сервер: `server.js:301`
- `cancel/stop` aliases сервер: `server.js:367`
- `player_world_state` сервер: `server.js:461`
- Клиентская отправка `start_request`: `src/main/java/com/redlimerl/speedrunigt/race/RaceSessionManager.java:403`
- Клиентская отправка legacy `ready`: `src/main/java/com/redlimerl/speedrunigt/race/RaceSessionManager.java:890`
- Клиентский обработчик входящих пакетов: `src/main/java/com/redlimerl/speedrunigt/race/RaceSessionManager.java:655`

## Короткий ответ по `ready`

`ready` на сервере **больше не требуется** и **не обрабатывается**.

- В `server.js` нет `case "ready"` (обрабатываются только `create_room`, `join_room`, `leave_room`, `reset_lobby`, `start_request`, cancel aliases, `finish`, `player_world_state`, `advancement`, `reload_items`, `ping`).
- Но клиент все еще шлет legacy `ready=true`:
  - после `room_created`/`room_joined`,
  - на `room_update` в `LOBBY`,
  - принудительно перед `start_request`.
- Сейчас это no-op: сервер игнорирует это как unknown `type`.

## 1. Транспорт и базовые принципы

### 1.1 Транспорт

- Протокол: WebSocket (JSON text frames).
- Endpoint: `ws://<HOST>:<PORT>`
  - `HOST` по умолчанию: `0.0.0.0`
  - `PORT` по умолчанию: `8080`
- Сервер heartbeat:
  - каждые 10 секунд `ping`,
  - если `pong` не пришел к следующему циклу: `terminate()`.

### 1.2 Идентичность игрока

- Серверная сессия привязана к `player` внутри конкретного сокета.
- Для реконнекта сервер пытается rebind по:
  - `roomCode + playerId`, если `playerId` валидный UUID,
  - иначе fallback по `roomCode + playerName` (только если в комнате ровно одно совпадение по имени).

### 1.3 Состояния комнаты (`room.state`)

- `lobby`
- `starting`
- `running`

Переходы:

- `create_room` -> `lobby`
- `start_request` (валидный) -> `starting`
- таймер `countdown` -> `running`
- любой `cancel/stop` alias в `starting|running` -> `lobby`
- `reset_lobby` (leader only) -> `lobby`
- `player_world_state` при `running`, если все `inWorld=false` -> `lobby`

### 1.4 Схема игрока в `room_update.players`

```json
{
  "id": "uuid",
  "name": "PlayerName",
  "ready": false,
  "isLeader": true,
  "inWorld": false
}
```

Примечания:

- `ready` сейчас всегда `false` (legacy-поле, фактически мертвое).
- В список попадают только игроки с активным открытым сокетом.

## 2. Swagger-аналог: Client -> Server

## `create_room`

Назначение: создать комнату и сделать отправителя лидером.

### Payload

| Поле | Тип | Обяз. | Ограничения / поведение |
|---|---|---:|---|
| `type` | string | да | `"create_room"` |
| `playerName` | string | нет | `safeString(..., 32)`, иначе `"Player"` |
| `playerId` | string(UUID) | нет | если валиден UUID -> используется; иначе `crypto.randomUUID()` |

### Ответы/события

- direct: `room_created`
- broadcast: `room_update(state=lobby)`

---

## `join_room`

Назначение: присоединиться к существующей комнате.

### Payload

| Поле | Тип | Обяз. | Ограничения / поведение |
|---|---|---:|---|
| `type` | string | да | `"join_room"` |
| `roomCode` | string | да | trim + upper + remove spaces, max 12 до нормализации |
| `playerName` | string | нет | `safeString(..., 32)`, иначе `"Player"` |
| `playerId` | string(UUID) | нет | если валиден UUID и уже есть в комнате -> rebind existing player |

### Ответы/события

- если комнаты нет: `error("Room not found")`
- если ok: `room_joined` + `room_update`

---

## `leave_room`

Назначение: выйти из комнаты.

### Payload

| Поле | Тип | Обяз. | Ограничения / поведение |
|---|---|---:|---|
| `type` | string | да | `"leave_room"` |
| `roomCode` | string | нет | если передан и не совпадает с сессионным `player.roomCode` -> игнор |
| `playerId` | string(UUID) | нет | сервер для case не использует напрямую |

### Ответы/события

- broadcast `room_update` после удаления игрока.
- при пустой комнате: удаление комнаты.
- если ушел лидер: назначается следующий (предпочтительно подключенный).

---

## `reset_lobby`

Назначение: принудительный сброс комнаты в лобби.

### Payload

| Поле | Тип | Обяз. | Ограничения / поведение |
|---|---|---:|---|
| `type` | string | да | `"reset_lobby"` |
| `roomCode` | string | нет | сервер не валидирует отдельно в case |
| `playerName` | string | нет | не обязателен |
| `playerId` | string(UUID) | нет | не обязателен |

### Предусловия

- должен быть привязанный `player` и существующая комната;
- только `player.id === room.leaderId`.

### Ответы/события

- при успехе: `room_update(state=lobby)`
- для non-leader: молча игнор.

---

## `start_request`

Назначение: инициировать матч.

### Payload

| Поле | Тип | Обяз. | Ограничения / поведение |
|---|---|---:|---|
| `type` | string | да | `"start_request"` |
| `roomCode` | string | да | нужен для bind/rebind |
| `playerName` | string | желательно | участвует в fallback bind по имени |
| `playerId` | string(UUID) | желательно | основной ключ bind |
| `countdown` | int | нет | clamp `0..30`, default `10` |

### Предусловия

- есть привязанный `player` (или удалось `bindPlayerFromPayload`);
- `room.state === "lobby"`;
- отправитель лидер;
- ни у кого в `room.players` нет `inWorld=true`.

### Ошибки

- `error("Not in a room")`
- `error("Cannot start while state=<state>")`
- `error("Only leader can start")`
- `error("Players still in game world")`

### Ответы/события

- immediate broadcast `start { seed, targetItemId, countdown }`
- broadcast `room_update(state=starting)`
- после таймера (если generation не сменился): `room_update(state=running)`

---

## `cancel_start` / `cancel_start_request` / `start_cancel` / `stop_start` / `abort_start`

Назначение: отменить countdown или остановить текущий матч.

### Payload

| Поле | Тип | Обяз. | Ограничения / поведение |
|---|---|---:|---|
| `type` | string | да | один из 5 alias-типов |
| `roomCode` | string | нет | сервер case отдельно не нормализует |
| `playerName` | string | нет | логирование |
| `playerId` | string(UUID) | нет | bind на уровне connection pre-switch |

### Предусловия

- `room.state` должен быть `starting` или `running`.

### Ответы/события

- broadcast `start_cancelled`
- затем `room_update(state=lobby)` через `resetRoomToLobby`.

Примечание: сейчас cancel/stop может отправить любой участник комнаты (ограничение только на start).

---

## `finish`

Назначение: отправить финиш/смерть игрока.

### Payload

| Поле | Тип | Обяз. | Ограничения / поведение |
|---|---|---:|---|
| `type` | string | да | `"finish"` |
| `roomCode` | string | нет | для bind/маршрутизации |
| `playerName` | string | нет | берется сессионный `player.name` для результатов |
| `playerId` | string(UUID) | нет | для bind |
| `reason` | string | нет | `safeString(...,32).toLowerCase()`; `"death"` -> eliminated, иначе finished |
| `rtaMs` | int | да | clamp `0..2147483647` |
| `igtMs` | int | да | clamp `0..2147483647` |

### Предусловия

- `room.state === "running"`.

### Ответы/события

- broadcast `player_result`
- если rank=1: broadcast `winner`
- room.state не меняется автоматически.

---

## `player_world_state`

Назначение: синхронизация факта “игрок в игровом мире/не в мире”.

### Payload

| Поле | Тип | Обяз. | Ограничения / поведение |
|---|---|---:|---|
| `type` | string | да | `"player_world_state"` |
| `roomCode` | string | нет | bind |
| `playerName` | string | нет | bind fallback |
| `playerId` | string(UUID) | нет | bind primary |
| `inWorld` | bool | да | `!!data.inWorld` |

### Поведение

- сервер обновляет `player.inWorld`.
- если `room.state === "running"` и у всех игроков `inWorld=false` -> `resetRoomToLobby`.

---

## `advancement`

Назначение: broadcast достижения в рантайме матча.

### Payload

| Поле | Тип | Обяз. | Ограничения / поведение |
|---|---|---:|---|
| `type` | string | да | `"advancement"` |
| `roomCode` | string | нет | bind |
| `playerName` | string | нет | bind |
| `playerId` | string(UUID) | нет | bind |
| `advancementId` | string | да | `safeString(...,128)` |

### Предусловия

- `room.state === "running"`.

### Ответы/события

- broadcast `advancement { playerName, advancementId }`.

---

## `reload_items`

Назначение: reload `items.txt` без перезапуска.

### Payload

| Поле | Тип | Обяз. |
|---|---|---:|
| `type` | string | да (`"reload_items"`) |

### Ответы

- direct `items_reloaded { count }`.

---

## `ping`

Назначение: health check.

### Payload

| Поле | Тип | Обяз. |
|---|---|---:|
| `type` | string | да (`"ping"`) |

### Ответ

- direct `pong`.

## 3. Swagger-аналог: Server -> Client

## `room_created`

| Поле | Тип | Обяз. | Комментарий |
|---|---|---:|---|
| `type` | string | да | `"room_created"` |
| `roomCode` | string | да | 6-симв. код |
| `players` | Player[] | да | sanitizePlayers, только online |

## `room_joined`

Схема как у `room_created`.

## `room_update`

| Поле | Тип | Обяз. | Комментарий |
|---|---|---:|---|
| `type` | string | да | `"room_update"` |
| `roomCode` | string | да | |
| `state` | string | да | `lobby | starting | running` |
| `players` | Player[] | да | online-only |

## `start`

| Поле | Тип | Обяз. | Комментарий |
|---|---|---:|---|
| `type` | string | да | `"start"` |
| `seed` | string | да | генерируется сервером |
| `targetItemId` | string | да | `minecraft:*` |
| `countdown` | int | да | `0..30` |

## `start_cancelled`

| Поле | Тип | Обяз. |
|---|---|---:|
| `type` | string | да (`"start_cancelled"`) |

## `player_result`

| Поле | Тип | Обяз. |
|---|---|---:|
| `type` | string | да |
| `player` | string | да |
| `reason` | string | да |
| `rtaMs` | int | да |
| `igtMs` | int | да |
| `rank` | int | да |

## `winner`

| Поле | Тип | Обяз. |
|---|---|---:|
| `type` | string | да |
| `player` | string | да |
| `rtaMs` | int | да |
| `igtMs` | int | да |

## `advancement`

| Поле | Тип | Обяз. |
|---|---|---:|
| `type` | string | да |
| `playerName` | string | да |
| `advancementId` | string | да |

## `items_reloaded`

| Поле | Тип | Обяз. |
|---|---|---:|
| `type` | string | да |
| `count` | int | да |

## `pong`

| Поле | Тип | Обяз. |
|---|---|---:|
| `type` | string | да (`"pong"`) |

## `error`

| Поле | Тип | Обяз. |
|---|---|---:|
| `type` | string | да (`"error"`) |
| `message` | string | да |

## 4. Клиентская фактическая логика (as-is)

Источник: `RaceSessionManager`, `RaceLobbyScreen`, `MinecraftClientMixin`, `TargetItemTracker`, `ClientAdvancementManagerMixin`.

### 4.1 Client state machine

- `IDLE`
  - `createRoom()` или `joinRoom()` -> открытие WS + отправка запроса.
- `LOBBY`
  - вход при `room_created|room_joined`.
  - `requestStart()` (leader, not in-world) -> `start_request`.
- `STARTING`
  - вход по сообщению `start`.
  - по таймеру `startScheduledAt` запускает создание singleplayer мира.
  - при появлении `client.world` -> `RUNNING`.
- `RUNNING`
  - HUD цели, трекинг инвентаря и смерти.
  - `finishRun()` отправляет `finish`.
- `LOBBY` reset
  - на `start_cancelled*`/`room_update state=lobby`.

### 4.2 Что реально уходит с клиента (C2S)

- `create_room`
- `join_room`
- `leave_room`
- `start_request`
- `cancel_start` + `start_cancel` + `cancel_start_request` + `stop_start` + `abort_start` (все сразу)
- `finish`
- `advancement`
- `player_world_state`
- `ready` (legacy, still sent, no server effect)

## 5. Слабые места и захардкоженность

## P0-P1 (влияет на стабильность матчей)

1. Legacy `ready` шумит и путает диагностику.
- Где: `RaceSessionManager.sendLegacyReadyTrue()`.
- Симптом: в логах кажется, что есть ready-модель, хотя сервер ее не поддерживает.
- Риск: ложные гипотезы и регрессии при будущем рефакторе.

2. Cancel отправляется 5 раз разными `type`.
- Где: `sendCancelStart()`.
- Симптом: лишние пакеты, сложнее трассировка.
- Риск: race-condition/дубли, особенно при плохой сети.

3. `RaceSessionManager` перегружен (сеть + протокол + state + world bootstrap + чат + визуал).
- Размер ~1100+ строк.
- Риск: любое изменение протокола может ломать мир/таймер UI и наоборот.

4. Нет проверки stale-socket в `WsListener.onText`.
- Есть stale-check в `onClose`/`onError`, но нет в `onText`.
- Риск: теоретическое смешивание сообщений старого/нового WS при гонках реконнекта.

5. `pendingMessages` без ограничения размера.
- Где: `send()`, `flushPending()`.
- Риск: рост памяти при длительном офлайне/ошибках сети.

6. `room_update(state=running)` клиентом почти не используется для перехода.
- Текущее `RUNNING` выставляется локально при появлении мира.
- Риск: при потере `start` или несогласованности можно получить desync клиент/сервер.

## P2 (долг, читаемость, поддержка)

7. Дублирование hardcoded server URL.
- Где: `RaceSessionManager` default URI + `ServerChangeScreen RESET`.

8. Hardcoded countdown=10 в клиенте.
- Где: `requestStart()`.

9. Hardcoded health-check cadence и reconnect cadence.
- Где: `RaceLobbyScreen.tick()` (2.5s), `RaceSessionManager.tick()` (1.5s).

10. `sendResetLobby()` мертвый код (не вызывается).

11. `PlayerStatus.ready` уже не несет смысла, но живет в клиентской модели.

12. Часть UI строк в `ServerChangeScreen` не локализована.
- `"Change Server"`, `"Server URL"`, `"SAVE"`, `"RESET"`, `"Enter server URL:"`.

13. В `RaceLobbyScreen` повторяются вычисления layout в `init/render/mouseClicked`.
- Риск: мелкие UI-баги от несогласованной геометрии.

14. В `LeaderboardOverlay` есть тяжелая логика с `leaderboard.indexOf(entry)` внутри цикла.
- Риск: O(n^2) при больших списках.

15. В `server.js` нет защиты от редкой коллизии `roomCode`.
- При совпадении потенциально перезапишет room в `Map`.

16. В `server.js` результат хранится по `player.name`, не по `player.id`.
- Риск: одинаковые ники = перезапись результата.

## 6. Серверные API-gaps (несимметричность/контракт)

1. Для многих невалидных операций сервер просто молчит.
- Примеры: `reset_lobby` не-лидера, cancel в неподходящем state.
- Лучше возвращать explicit `error`, чтобы UI не залипал в ожидании.

2. Отсутствует единая schema-version.
- Сейчас контракт implicit, изменения трудно выкатывать без регрессий.

3. Слишком много alias для одной операции stop/cancel.
- Нужен один канонический `stop_request` + временный compatibility слой.

## 7. Рекомендуемый план упрощения (чтобы “не ехало по швам”)

### Этап 1: стабилизация контракта

- Убрать client-side `ready` полностью.
- Оставить 1 канонический stop тип.
- Ввести strict error responses для всех reject-сценариев.
- Зафиксировать схему в отдельном `protocolVersion`.

### Этап 2: декомпозиция клиента

- Разделить `RaceSessionManager` на:
  - `RaceTransport` (WS, reconnect, queue),
  - `RaceProtocol` (serialize/parse messages),
  - `RaceStateStore` (state machine),
  - `RaceGameBridge` (world start/finish hooks).

### Этап 3: чистка UI/локализации

- Полная локализация `ServerChangeScreen`.
- Один `LayoutModel` для координат лобби экрана.
- Убрать dead-code и legacy поля (`ready`).

## 8. Итог по вопросу “нужен ли ready”

Нет, в текущем `server.js` `ready` не нужен.  
Он остался только как legacy-отправка со стороны клиента и не влияет на старт/стоп напрямую.
