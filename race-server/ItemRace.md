**Models**

```
type Room = {
  id: RoomId
  code: string
  players: Set<PlayerId>
  leaderId: PlayerId
  
  // Взаимоисключающие состояния:
  // либо готовится следующий матч, либо идёт текущий
  currentMatch: Match | null
  pendingMatch: PendingMatchConfig | null
}

type PendingMatchConfig = {
  targetItem: string
  seed: number
  rolledAt: timestamp
  revision: number
}

type Match = {
  id: MatchId
  roomId: RoomId
  revision: number
  targetItem: string
  seed: number
  isActive: boolean   // true пока не завершён
  players: Map<PlayerId, PlayerState>
  createdAt: timestamp
  updatedAt: timestamp
  completedAt?: timestamp   // обязателен если isActive == false
}

type PlayerState = {
  status: RUNNING | FINISHED | DEATH | LEAVE
  result?: PlayerResult | null
  leaveReason?: MANUAL | RECONNECT_TIMEOUT | KICK
  leftAt?: timestamp
}

type PlayerResult = {
  rtt: number
  igt: number
}

type PlayerSession = {
  sessionId: string
  playerId: PlayerId
  connectionState: CONNECTED | DISCONNECTED
  lastSeenAt: timestamp
  disconnectedAt?: timestamp
} // runtime only

type Player = {
  id: PlayerId        // minecraft uuid (PRIMARY KEY)
  name: string        // последний известный ник
  createdAt: timestamp
  lastSeenAt: timestamp
} // persistent identity

```



**Server Boot Example**

1. load all rooms
2. load all matches where isActive = true
3. for each match:
      reconstruct PlayerState map
4. link rooms -> currentMatch
5. accept websocket connections
6. on reconnect:
      reattach player to PlayerState


**Reconnect policy (DISCONNECTED vs LEAVE)**

1. on websocket close:
      set PlayerSession.connectionState = DISCONNECTED
      set PlayerSession.disconnectedAt = now
      keep PlayerState.status unchanged (обычно RUNNING)
2. start reconnect grace timer (например 45_000 ms)
3. if player reconnects before timeout:
      set PlayerSession.connectionState = CONNECTED
      clear disconnectedAt
      continue match
4. if timeout expires and PlayerState.status == RUNNING:
      set PlayerState.status = LEAVE
      set leaveReason = RECONNECT_TIMEOUT
      set leftAt = now


**Server invariants**

Инварианты Player
∀ playerId:
  count(Room where playerId ∈ room.players) ≤ 1
  
room.leaderId ∈ room.players


Инварианты Room

room.pendingMatch != null  ⇒ room.currentMatch == null
room.currentMatch != null ⇒ room.pendingMatch == null
room.currentMatch != null ⇒ room.currentMatch.roomId == room.id


Инварианты PendingMatchConfig

seed определён
targetItem определён
rolledAt определён


Инварианты Match

match.roomId существует
playerId уникален в match.players
match.completedAt != null ⇒ match.isActive == false
match.isActive == true ⇒ match.completedAt == null
∀ playerId ∈ match.players:
  playerId ∈ room.players


Инварианты PlayerState

Терминальные состояния не могут вернуться в RUNNING
FINISHED | DEATH | LEAVE => терминальные состояния
LEAVE => leaveReason != null и leftAt != null


Инварианты PlayerResult

rttMs ≥ 0
igtMs ≥ 0


Инварианты PlayerSession

Одна сессия — один игрок.
count(active sessions for playerId) ≤ 1
DISCONNECTED — сетевое состояние сессии, а не статус участия в матче
DISCONNECTED не означает LEAVE до истечения reconnect timeout
