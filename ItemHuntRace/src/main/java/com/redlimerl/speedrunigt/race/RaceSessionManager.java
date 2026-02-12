package com.redlimerl.speedrunigt.race;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.redlimerl.speedrunigt.SpeedRunIGT;
import com.redlimerl.speedrunigt.timer.InGameTimer;
import com.redlimerl.speedrunigt.timer.InGameTimerUtils;
import com.redlimerl.speedrunigt.timer.TimerStatus;
import com.redlimerl.speedrunigt.timer.category.RunCategories;
import com.redlimerl.speedrunigt.timer.running.RunType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

@Environment(EnvType.CLIENT)
public final class RaceSessionManager {
    public enum ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

    public enum HealthStatus { UNKNOWN, CHECKING, ONLINE, OFFLINE }

    public enum FinishReason { TARGET_OBTAINED, DEATH }

    public record PlayerStatus(UUID id, String name, boolean ready, boolean isLeader) {}

    private record FinishTime(String playerId, String playerName, long igtMs, long rtaMs, boolean eliminated) {}

    public record LeaderboardEntry(String name, String time) {}

    public static final String SERVER_URI_PROPERTY = "speedrunigt.race.server";
    private static final String DEFAULT_SERVER_URI = "ws://race.flomik.xyz:8080/race";
    private static final long LOCAL_START_COUNTDOWN_MS = 10_000L;

    private static final RaceSessionManager INSTANCE = new RaceSessionManager();

    public static RaceSessionManager getInstance() {
        return INSTANCE;
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile ConnectionStatus connectionStatus = ConnectionStatus.DISCONNECTED;
    private volatile HealthStatus healthStatus = HealthStatus.UNKNOWN;
    private volatile String lastError = null;

    private final AtomicBoolean healthCheckInFlight = new AtomicBoolean(false);
    private final AtomicBoolean finishTriggered = new AtomicBoolean(false);

    private URI serverUri = normalizeServerUri(URI.create(System.getProperty(SERVER_URI_PROPERTY, DEFAULT_SERVER_URI)));
    private WebSocket webSocket = null;
    private String serverSessionId = null;
    private volatile boolean authenticated = false;
    private volatile boolean helloInFlight = false;

    private final Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    private final StringBuilder partialMessage = new StringBuilder();

    private RaceState state = RaceState.IDLE;
    private String roomCode = "";
    private String leaderPlayerId = "";
    private final List<PlayerStatus> players = new ArrayList<>();
    private final ConcurrentHashMap<String, String> playerNameById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FinishTime> finishTimesByPlayerId = new ConcurrentHashMap<>();

    private boolean hasPendingMatch = false;
    private String activeMatchId = null;
    private String seedString = null;
    private Identifier targetItemId = null;

    private long startScheduledAt = 0L;
    private boolean worldCreationRequested = false;
    private boolean timerConfigured = false;

    private String pendingWorldDirectoryName = null;
    private String activeRaceWorldName = null;

    private long lastReconnectAttemptAt = 0L;
    private volatile String cachedClientPlayerId = null;

    private RaceSessionManager() {}

    public ConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    public HealthStatus getHealthStatus() {
        return healthStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public RaceState getState() {
        return state;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public List<PlayerStatus> getPlayers() {
        return List.copyOf(players);
    }

    public boolean hasPendingMatch() {
        return hasPendingMatch;
    }

    public Optional<Identifier> getTargetItemId() {
        return Optional.ofNullable(targetItemId);
    }

    public int getCountdownSecondsRemaining() {
        if (state != RaceState.STARTING) return 0;
        long remainingMs = startScheduledAt - System.currentTimeMillis();
        if (remainingMs <= 0) return 0;
        return (int) Math.ceil(remainingMs / 1000.0);
    }

    public boolean isRaceControlsLocked() {
        return state != RaceState.IDLE;
    }

    public boolean shouldRenderTargetHud() {
        return (state == RaceState.RUNNING || state == RaceState.STARTING) && targetItemId != null;
    }

    public boolean isLocalPlayerLeader() {
        if (!leaderPlayerId.isEmpty()) {
            return leaderPlayerId.equals(getClientPlayerId());
        }
        if (players.isEmpty()) return true;
        return players.get(0).id().toString().equals(getClientPlayerId());
    }

    public URI getServerUri() {
        return serverUri;
    }

    public void setServerUri(URI uri) {
        this.serverUri = normalizeServerUri(Objects.requireNonNull(uri, "serverUri"));
        this.healthStatus = HealthStatus.UNKNOWN;
        this.lastError = null;

        if (this.connectionStatus == ConnectionStatus.CONNECTED || this.connectionStatus == ConnectionStatus.CONNECTING) {
            disconnect();
        }
    }

    public void checkServerHealth() {
        if (!healthCheckInFlight.compareAndSet(false, true)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> healthStatus = HealthStatus.CHECKING);

        CompletableFuture<Boolean> pongFuture = new CompletableFuture<>();
        StringBuilder partialPongMessage = new StringBuilder();
        URI wsUri = websocketUri();

        try {
            httpClient.newWebSocketBuilder()
                    .buildAsync(wsUri, new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                            JsonObject ping = new JsonObject();
                            ping.addProperty("type", "ping");
                            webSocket.sendText(SpeedRunIGT.GSON.toJson(ping), true);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            if (pongFuture.isDone()) {
                                return WebSocket.Listener.super.onText(webSocket, data, last);
                            }

                            partialPongMessage.append(data);
                            if (!last) {
                                webSocket.request(1);
                                return CompletableFuture.completedFuture(null);
                            }

                            boolean ok = false;
                            try {
                                JsonObject json = JsonParser.parseString(partialPongMessage.toString()).getAsJsonObject();
                                ok = json.has("type") && "pong".equals(json.get("type").getAsString());
                            } catch (Exception ignored) {
                            } finally {
                                partialPongMessage.setLength(0);
                            }

                            pongFuture.complete(ok);
                            try {
                                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
                            } catch (Exception ignored) {
                            }
                            webSocket.request(1);
                            return CompletableFuture.completedFuture(null);
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            pongFuture.completeExceptionally(error);
                        }
                    })
                    .whenComplete((ws, err) -> {
                        if (err != null) pongFuture.completeExceptionally(err);
                    });
        } catch (Exception e) {
            pongFuture.completeExceptionally(e);
        }

        pongFuture
                .orTimeout(3, TimeUnit.SECONDS)
                .whenComplete((ok, err) -> client.execute(() -> {
                    healthStatus = err == null && Boolean.TRUE.equals(ok) ? HealthStatus.ONLINE : HealthStatus.OFFLINE;
                    healthCheckInFlight.set(false);
                }));
    }

    public void tick(MinecraftClient client) {
        long now = System.currentTimeMillis();

        if (state != RaceState.IDLE && connectionStatus == ConnectionStatus.DISCONNECTED) {
            if (now - lastReconnectAttemptAt >= 1_500L) {
                lastReconnectAttemptAt = now;
                connect();
            }
        }

        if (state == RaceState.STARTING && !worldCreationRequested && now >= startScheduledAt) {
            worldCreationRequested = true;
            startWorld(client);
        }

        if (state == RaceState.STARTING && client.world != null && client.player != null) {
            state = RaceState.RUNNING;
            configureTimerForRace();
        }
    }

    public void connect() {
        if (connectionStatus == ConnectionStatus.CONNECTED && webSocket != null) {
            ensureHelloSent(webSocket);
            return;
        }
        if (connectionStatus == ConnectionStatus.CONNECTING) return;

        lastError = null;
        connectionStatus = ConnectionStatus.CONNECTING;

        URI wsUri = websocketUri();
        try {
            httpClient.newWebSocketBuilder()
                    .buildAsync(wsUri, new WsListener())
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            MinecraftClient.getInstance().execute(() -> onConnectionFailed(err));
                        } else {
                            MinecraftClient.getInstance().execute(() -> onConnected(ws));
                        }
                    });
        } catch (Exception e) {
            onConnectionFailed(e);
        }
    }

    public void disconnect() {
        WebSocket ws = this.webSocket;
        this.webSocket = null;
        this.connectionStatus = ConnectionStatus.DISCONNECTED;
        this.authenticated = false;
        this.helloInFlight = false;
        this.pendingMessages.clear();
        this.partialMessage.setLength(0);

        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client disconnect");
            } catch (Exception ignored) {
            }
        }
    }

    public void createRoom() {
        if (state != RaceState.IDLE) return;
        pendingMessages.clear();
        connect();

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "create_room");
        send(msg);
    }

    public void joinRoom(String code) {
        if (state != RaceState.IDLE) return;

        String normalized = normalizeRoomCode(code);
        if (normalized.isEmpty()) return;

        pendingMessages.clear();
        connect();

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "join_room");
        msg.addProperty("roomCode", normalized);
        send(msg);
    }

    public void leaveRoom() {
        if (state == RaceState.IDLE) return;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "leave_room");
        send(msg);

        resetToIdle();
    }

    public void rollMatch() {
        if (state != RaceState.LOBBY && state != RaceState.FINISHED) return;
        if (!isLocalPlayerLeader()) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "roll_match");
        send(msg);
    }

    public void requestStart() {
        if (state != RaceState.LOBBY && state != RaceState.FINISHED) {
            lastError = "Cannot start: state=" + state;
            return;
        }
        if (!isLocalPlayerLeader()) {
            lastError = "Only leader can start";
            return;
        }
        if (MinecraftClient.getInstance().world != null) {
            lastError = "Leave world before start";
            return;
        }
        if (!hasPendingMatch) {
            lastError = "Roll an item first";
            return;
        }

        lastError = null;

        JsonObject start = new JsonObject();
        start.addProperty("type", "start_match");
        send(start);
    }

    public void cancelStart() {
        if (state != RaceState.STARTING) return;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "cancel_start");
        send(msg);

        // Optimistic local reset; authoritative state comes from next snapshot.
        state = RaceState.LOBBY;
        hasPendingMatch = true;
        activeMatchId = null;
        startScheduledAt = 0L;
        worldCreationRequested = false;
        finishTriggered.set(false);
        lastError = null;
    }

    public void sendAdvancementAchieved(Identifier id) {
        // Server protocol intentionally does not consume advancement events.
    }

    public void finishRun(FinishReason reason) {
        if (state != RaceState.RUNNING) return;
        if (!finishTriggered.compareAndSet(false, true)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getServer() == null || activeRaceWorldName == null) return;

        String current = client.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                .getParent().getFileName().toString();

        if (!current.equals(activeRaceWorldName)) {
            sendSystemChat("§cFinish ignored: not in race world");
            return;
        }

        InGameTimer.complete();
        InGameTimer timer = InGameTimer.getInstance();

        String selfId = getClientPlayerId();
        String selfName = getClientPlayerName();

        if (reason == FinishReason.DEATH) {
            finishTimesByPlayerId.put(selfId,
                    new FinishTime(selfId, selfName, timer.getInGameTime(false), timer.getRealTimeAttack(), true));

            JsonObject msg = new JsonObject();
            msg.addProperty("type", "death");
            send(msg);

            sendSystemChat("\u00a7cYou died! Race over.");
        } else {
            finishTimesByPlayerId.put(selfId,
                    new FinishTime(selfId, selfName, timer.getInGameTime(false), timer.getRealTimeAttack(), false));

            JsonObject msg = new JsonObject();
            msg.addProperty("type", "finish");
            msg.addProperty("rttMs", timer.getRealTimeAttack());
            msg.addProperty("igtMs", timer.getInGameTime(false));
            send(msg);

            String rta = InGameTimerUtils.timeToStringFormat(timer.getRealTimeAttack());
            sendSystemChat("\u00a7aYou found the target item! Time: " + rta);
        }
    }

    public List<LeaderboardEntry> getLeaderboard() {
        if (finishTimesByPlayerId.isEmpty()) return List.of();

        List<FinishTime> sorted = new ArrayList<>(finishTimesByPlayerId.values());
        sorted.sort((a, b) -> {
            if (a.eliminated && !b.eliminated) return 1;
            if (!a.eliminated && b.eliminated) return -1;
            return Long.compare(a.rtaMs, b.rtaMs);
        });

        List<LeaderboardEntry> result = new ArrayList<>(sorted.size());
        for (FinishTime t : sorted) {
            String time = t.eliminated
                    ? "§cLOSE"
                    : InGameTimerUtils.timeToStringFormat(Math.max(0L, t.rtaMs));
            result.add(new LeaderboardEntry(t.playerName, time));
        }
        return result;
    }

    private void resetToIdle() {
        state = RaceState.IDLE;
        roomCode = "";
        leaderPlayerId = "";
        players.clear();
        playerNameById.clear();
        finishTimesByPlayerId.clear();

        hasPendingMatch = false;
        activeMatchId = null;
        seedString = null;
        targetItemId = null;

        pendingWorldDirectoryName = null;
        activeRaceWorldName = null;

        startScheduledAt = 0L;
        worldCreationRequested = false;
        timerConfigured = false;
        finishTriggered.set(false);

        lastReconnectAttemptAt = 0L;
    }

    private void onConnected(WebSocket ws) {
        this.webSocket = ws;
        this.connectionStatus = ConnectionStatus.CONNECTED;
        this.healthStatus = HealthStatus.ONLINE;
        this.lastError = null;
        this.lastReconnectAttemptAt = 0L;
        this.authenticated = false;
        this.helloInFlight = false;
        ensureHelloSent(ws);
    }

    private void onConnectionFailed(Throwable err) {
        this.connectionStatus = ConnectionStatus.DISCONNECTED;
        this.webSocket = null;
        this.authenticated = false;
        this.helloInFlight = false;
        this.healthStatus = HealthStatus.OFFLINE;
        this.lastError = err != null ? err.getMessage() : "Connection failed";
    }

    private CompletionStage<WebSocket> sendHello(WebSocket ws) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "hello");
        msg.addProperty("playerId", getClientPlayerId());
        msg.addProperty("name", getClientPlayerName());
        if (serverSessionId != null && !serverSessionId.isBlank()) {
            msg.addProperty("sessionId", serverSessionId);
        }
        return ws.sendText(SpeedRunIGT.GSON.toJson(msg), true);
    }

    private void sendSyncState() {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "sync_state");
        send(msg);
    }

    private void send(JsonObject jsonObject) {
        String payload = SpeedRunIGT.GSON.toJson(jsonObject);
        String type = getString(jsonObject, "type");
        WebSocket ws = webSocket;

        if (connectionStatus != ConnectionStatus.CONNECTED || ws == null) {
            pendingMessages.add(payload);
            connect();
            return;
        }
        if (requiresAuthenticatedSession(type) && !authenticated) {
            pendingMessages.add(payload);
            ensureHelloSent(ws);
            return;
        }

        ws.sendText(payload, true).exceptionally(err -> {
            MinecraftClient.getInstance().execute(() -> {
                onConnectionFailed(err);
                pendingMessages.add(payload);
                connect();
            });
            return null;
        });
    }

    private void flushPending() {
        WebSocket ws = webSocket;
        if (connectionStatus != ConnectionStatus.CONNECTED || ws == null || !authenticated) return;

        String payload;
        while ((payload = pendingMessages.poll()) != null) {
            String payloadFinal = payload;
            ws.sendText(payloadFinal, true).exceptionally(err -> {
                MinecraftClient.getInstance().execute(() -> {
                    onConnectionFailed(err);
                    pendingMessages.add(payloadFinal);
                    connect();
                });
                return null;
            });
        }
    }

    private void handleIncoming(String rawMessage) {
        JsonObject msg;
        try {
            JsonElement element = JsonParser.parseString(rawMessage);
            if (!element.isJsonObject()) return;
            msg = element.getAsJsonObject();
        } catch (JsonParseException ignored) {
            return;
        }

        String type = getString(msg, "type");
        if (type == null || type.isEmpty()) return;

        switch (type) {
            case "welcome" -> {
                String sessionId = getString(msg, "sessionId");
                if (sessionId != null && !sessionId.isBlank()) {
                    this.serverSessionId = sessionId;
                }
                this.authenticated = true;
                this.helloInFlight = false;
                lastError = null;
                healthStatus = HealthStatus.ONLINE;
                flushPending();
                sendSyncState();
            }
            case "pong" -> healthStatus = HealthStatus.ONLINE;
            case "ack" -> {
                // State message is source-of-truth; ack is informational only.
            }
            case "error" -> {
                String code = getString(msg, "code");
                String message = getString(msg, "message");
                lastError = message != null ? message : "Unknown server error";

                if ("NOT_AUTHENTICATED".equals(code) && connectionStatus == ConnectionStatus.CONNECTED && webSocket != null) {
                    this.authenticated = false;
                    this.helloInFlight = false;
                    ensureHelloSent(webSocket);
                }
                if ("ALREADY_AUTHENTICATED".equals(code) && connectionStatus == ConnectionStatus.CONNECTED) {
                    this.authenticated = true;
                    this.helloInFlight = false;
                    lastError = null;
                    flushPending();
                    sendSyncState();
                }
                if ("PLAYER_NOT_IN_ROOM".equals(code) || "ROOM_NOT_FOUND".equals(code)) {
                    resetToIdle();
                }
            }
            case "state" -> {
                JsonObject snapshot = msg.has("snapshot") && msg.get("snapshot").isJsonObject()
                        ? msg.getAsJsonObject("snapshot")
                        : null;
                if (snapshot != null) {
                    applyStateSnapshot(snapshot);
                }
            }
            default -> {
                // ignore unknown message
            }
        }
    }

    private void applyStateSnapshot(JsonObject snapshot) {
        JsonObject self = snapshot.has("self") && snapshot.get("self").isJsonObject()
                ? snapshot.getAsJsonObject("self")
                : null;

        if (self != null) {
            String selfConnection = getString(self, "connectionState");
            if ("CONNECTED".equals(selfConnection)) {
                connectionStatus = ConnectionStatus.CONNECTED;
                healthStatus = HealthStatus.ONLINE;
            }
        }

        JsonObject room = snapshot.has("room") && snapshot.get("room").isJsonObject()
                ? snapshot.getAsJsonObject("room")
                : null;

        if (room == null) {
            resetToIdle();
            return;
        }

        String code = getString(room, "code");
        roomCode = normalizeRoomCode(code == null ? "" : code);
        leaderPlayerId = getString(room, "leaderId") == null ? "" : getString(room, "leaderId");

        parseRoomPlayers(room);

        JsonObject pendingMatchObj = room.has("pendingMatch") && room.get("pendingMatch").isJsonObject()
                ? room.getAsJsonObject("pendingMatch") : null;
        hasPendingMatch = pendingMatchObj != null;
        if (pendingMatchObj != null) {
            String target = getString(pendingMatchObj, "targetItem");
            if (target != null) {
                Identifier parsed = Identifier.tryParse(target);
                if (parsed != null && Registries.ITEM.containsId(parsed)) targetItemId = parsed;
            }
            Long seed = getLong(pendingMatchObj, "seed");
            if (seed != null) seedString = Long.toString(seed);
        }

        JsonObject currentMatch = room.has("currentMatch") && room.get("currentMatch").isJsonObject()
                ? room.getAsJsonObject("currentMatch")
                : null;

        if (currentMatch == null || !getBoolean(currentMatch, "isActive", false)) {
            if (!hasPendingMatch) {
                seedString = null;
                targetItemId = null;
            }
            activeMatchId = null;
            startScheduledAt = 0L;
            worldCreationRequested = false;
            timerConfigured = false;
            finishTriggered.set(false);

            if (!finishTimesByPlayerId.isEmpty()) {
                state = RaceState.FINISHED;
            } else {
                state = RaceState.LOBBY;
            }
            return;
        }

        applyActiveMatch(currentMatch);
    }

    private void parseRoomPlayers(JsonObject room) {
        players.clear();

        JsonArray playersArray = room.has("players") && room.get("players").isJsonArray()
                ? room.getAsJsonArray("players")
                : new JsonArray();

        for (JsonElement element : playersArray) {
            if (!element.isJsonObject()) continue;
            JsonObject player = element.getAsJsonObject();

            String playerId = getString(player, "playerId");
            String name = getString(player, "name");
            String connection = getString(player, "connectionState");

            if (playerId == null || playerId.isBlank()) continue;
            if (name == null || name.isBlank()) name = playerId;

            playerNameById.put(playerId, name);
            boolean ready = "CONNECTED".equals(connection);
            boolean isLeader = playerId.equals(leaderPlayerId);

            players.add(new PlayerStatus(safeUuid(playerId), name, ready, isLeader));
        }
    }

    private void applyActiveMatch(JsonObject match) {
        String matchId = getString(match, "id");
        if (matchId == null || matchId.isBlank()) return;

        if (!matchId.equals(activeMatchId)) {
            activeMatchId = matchId;
            finishTimesByPlayerId.clear();
            finishTriggered.set(false);
            timerConfigured = false;
            worldCreationRequested = false;
            startScheduledAt = System.currentTimeMillis() + LOCAL_START_COUNTDOWN_MS;
            pendingWorldDirectoryName = null;
            activeRaceWorldName = null;
        }

        Long seed = getLong(match, "seed");
        if (seed != null) {
            seedString = Long.toString(seed);
        }

        String target = getString(match, "targetItem");
        if (target != null) {
            Identifier parsed = Identifier.tryParse(target);
            if (parsed != null && Registries.ITEM.containsId(parsed)) {
                targetItemId = parsed;
            } else {
                lastError = "Invalid target item from server: " + target;
            }
        }

        JsonArray matchPlayers = match.has("players") && match.get("players").isJsonArray()
                ? match.getAsJsonArray("players")
                : new JsonArray();

        String selfId = getClientPlayerId();
        String selfStatus = null;
        int terminalCount = 0;
        int totalPlayers = 0;
        FinishTime firstFinisher = null;

        for (JsonElement element : matchPlayers) {
            if (!element.isJsonObject()) continue;
            JsonObject player = element.getAsJsonObject();

            String playerId = getString(player, "playerId");
            String status = getString(player, "status");
            if (playerId == null || status == null) continue;

            totalPlayers++;
            String playerName = playerNameById.getOrDefault(playerId, playerId);
            boolean wasTracked = finishTimesByPlayerId.containsKey(playerId);

            if ("FINISHED".equals(status)) {
                JsonObject result = player.has("result") && player.get("result").isJsonObject()
                        ? player.getAsJsonObject("result")
                        : null;
                long rttMs = result != null && getLong(result, "rttMs") != null ? Objects.requireNonNull(getLong(result, "rttMs")) : 0L;
                long igtMs = result != null && getLong(result, "igtMs") != null ? Objects.requireNonNull(getLong(result, "igtMs")) : 0L;

                FinishTime ft = new FinishTime(playerId, playerName, igtMs, rttMs, false);
                finishTimesByPlayerId.put(playerId, ft);
                terminalCount++;

                if (firstFinisher == null || rttMs < firstFinisher.rtaMs) firstFinisher = ft;

                if (!wasTracked && !selfId.equals(playerId)) {
                    String time = InGameTimerUtils.timeToStringFormat(rttMs);
                    sendSystemChat("\u00a7e" + playerName + " found the item! (" + time + ")");
                }
            } else if ("DEATH".equals(status) || "LEAVE".equals(status)) {
                finishTimesByPlayerId.put(playerId,
                        new FinishTime(playerId, playerName, Long.MAX_VALUE, Long.MAX_VALUE, true));
                terminalCount++;

                if (!wasTracked && !selfId.equals(playerId)) {
                    sendSystemChat("\u00a77" + playerName + " was eliminated");
                }
            } else if ("RUNNING".equals(status)) {
                finishTimesByPlayerId.remove(playerId);
            }

            if (selfId.equals(playerId)) {
                selfStatus = status;
            }
        }

        if (totalPlayers > 0 && terminalCount == totalPlayers && firstFinisher != null && !firstFinisher.eliminated) {
            sendWinnerAnnouncement(firstFinisher.playerName, firstFinisher);
        }

        if (selfStatus == null) {
            state = RaceState.LOBBY;
            return;
        }

        if ("RUNNING".equals(selfStatus)) {
            if (MinecraftClient.getInstance().world != null) {
                state = RaceState.RUNNING;
                configureTimerForRace();
            } else if (state != RaceState.RUNNING) {
                state = RaceState.STARTING;
            }
        } else {
            state = RaceState.FINISHED;
            startScheduledAt = 0L;
            worldCreationRequested = false;
        }
    }

    private void startWorld(MinecraftClient client) {
        if (seedString == null || targetItemId == null) {
            lastError = "Missing START parameters";
            state = RaceState.LOBBY;
            worldCreationRequested = false;
            return;
        }

        if (client.world != null) {
            lastError = "Must be in menu to start race world";
            state = RaceState.LOBBY;
            worldCreationRequested = false;
            return;
        }

        OptionalLong parsedSeed = GeneratorOptions.parseSeed(seedString);
        long seed = parsedSeed.isPresent() ? parsedSeed.getAsLong() : (long) seedString.hashCode();

        InGameTimerUtils.IS_SET_SEED = true;

        String dir = makeWorldDirectoryName();
        pendingWorldDirectoryName = dir;
        activeRaceWorldName = dir;

        LevelInfo info = new LevelInfo(
                "Item Hunt Race",
                GameMode.SURVIVAL,
                true,
                Difficulty.HARD,
                false,
                new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES),
                new DataConfiguration(DataPackSettings.SAFE_MODE, FeatureFlags.DEFAULT_ENABLED_FEATURES)
        );

        GeneratorOptions options = new GeneratorOptions(seed, true, false);

        client.createIntegratedServerLoader().createAndStart(
                dir,
                info,
                options,
                wrapper -> wrapper.getOrThrow(RegistryKeys.WORLD_PRESET)
                        .getOrThrow(WorldPresets.DEFAULT)
                        .value()
                        .createDimensionsRegistryHolder(),
                client.currentScreen
        );
    }

    private String makeWorldDirectoryName() {
        String code = roomCode == null || roomCode.isEmpty() ? "race" : roomCode.toLowerCase(Locale.ROOT);
        return "item_hunt_" + code + "_" + (System.currentTimeMillis() / 1000L);
    }

    private void configureTimerForRace() {
        if (timerConfigured) return;
        timerConfigured = true;

        InGameTimer timer = InGameTimer.getInstance();
        if (pendingWorldDirectoryName != null && !pendingWorldDirectoryName.isEmpty()) {
            InGameTimer.start(pendingWorldDirectoryName, RunType.fromBoolean(InGameTimerUtils.IS_SET_SEED));
            timer = InGameTimer.getInstance();
        }
        timer.setCategory(RunCategories.CUSTOM, false);
        timer.setUncompleted(false);
    }

    private void sendSystemChat(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal(message));
        }
    }

    private URI websocketUri() {
        return normalizeServerUri(serverUri);
    }

    private static URI normalizeServerUri(URI uri) {
        String raw = uri.toString().trim();
        if (raw.isEmpty()) {
            return URI.create(DEFAULT_SERVER_URI);
        }

        URI parsed = URI.create(raw);
        String path = parsed.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return URI.create(raw.endsWith("/") ? raw + "race" : raw + "/race");
        }
        return parsed;
    }

    private static String normalizeRoomCode(String code) {
        if (code == null) return "";
        return code.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    private static UUID safeUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (Exception ignored) {
            return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String getClientPlayerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.getSession() != null ? client.getSession().getUsername() : "Player";
    }

    private String getClientPlayerId() {
        String cached = this.cachedClientPlayerId;
        if (cached != null && !cached.isEmpty()) return cached;

        String computed = computeClientPlayerId();
        this.cachedClientPlayerId = computed;
        return computed;
    }

    private static String computeClientPlayerId() {
        MinecraftClient client = MinecraftClient.getInstance();
        String name = getClientPlayerName();
        if (client.getSession() != null) {
            UUID uuid = client.getSession().getUuidOrNull();
            if (uuid != null) return uuid.toString();
        }
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String getString(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        JsonElement element = obj.get(key);
        if (!element.isJsonPrimitive()) return null;
        try {
            String value = element.getAsString();
            return value != null && !value.isBlank() ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long getLong(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        JsonElement element = obj.get(key);
        if (!element.isJsonPrimitive()) return null;

        try {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                return primitive.getAsLong();
            }
            if (primitive.isString()) {
                String raw = primitive.getAsString();
                if (raw == null || raw.isBlank()) return null;
                return Long.parseLong(raw.trim());
            }
        } catch (Exception ignored) {
            return null;
        }

        return null;
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean fallback) {
        if (!obj.has(key)) return fallback;
        JsonElement element = obj.get(key);
        if (!element.isJsonPrimitive()) return fallback;

        try {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            if (primitive.isString()) return Boolean.parseBoolean(primitive.getAsString());
        } catch (Exception ignored) {
            return fallback;
        }

        return fallback;
    }

    private static boolean requiresAuthenticatedSession(String messageType) {
        return messageType != null && !"hello".equals(messageType) && !"ping".equals(messageType);
    }

    private void ensureHelloSent(WebSocket ws) {
        if (authenticated || helloInFlight) return;
        helloInFlight = true;
        sendHello(ws).whenComplete((ignored, error) -> MinecraftClient.getInstance().execute(() -> {
            if (error != null) {
                helloInFlight = false;
                onConnectionFailed(error);
            }
        }));
    }

    private void spawnFireworks() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        NbtCompound fireworks = new NbtCompound();
        NbtList explosions = new NbtList();

        for (int i = 0; i < 3; i++) {
            NbtCompound explosion = new NbtCompound();
            explosion.putBoolean("Flicker", true);
            explosion.putBoolean("Trail", true);
            explosion.putInt("Type", 1);
            explosion.putIntArray("Colors", new int[]{0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00});
            explosions.add(explosion);
        }

        fireworks.put("Explosions", explosions);
        fireworks.putByte("Flight", (byte) 1);

        NbtCompound tag = new NbtCompound();
        // TODO 1.21 NBT API migration for rocket visuals.
        // tag.put("Fireworks", fireworks);
        // stack.setNbt(tag);
    }

    private void sendWinnerAnnouncement(String winnerName, FinishTime time) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud == null) return;

        ChatHud chat = client.inGameHud.getChatHud();
        MutableText line1 = Text.literal("🏆 ").formatted(Formatting.YELLOW)
                .append(Text.translatable("speedrunigt.race.chat.winner_prefix").formatted(Formatting.WHITE))
                .append(Text.literal(winnerName).formatted(Formatting.GOLD, Formatting.BOLD));
        chat.addMessage(line1);

        if (time != null && !time.eliminated) {
            String igt = InGameTimerUtils.timeToStringFormat(time.igtMs());
            String rta = InGameTimerUtils.timeToStringFormat(time.rtaMs());
            chat.addMessage(Text.translatable("speedrunigt.race.chat.time", igt, rta).formatted(Formatting.GRAY));
        }
    }

    private final class WsListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialMessage.append(data);
            if (last) {
                String message = partialMessage.toString();
                partialMessage.setLength(0);
                MinecraftClient.getInstance().execute(() -> handleIncoming(message));
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            MinecraftClient.getInstance().execute(() -> {
                if (RaceSessionManager.this.webSocket != webSocket) {
                    return;
                }

                connectionStatus = ConnectionStatus.DISCONNECTED;
                RaceSessionManager.this.webSocket = null;
                authenticated = false;
                helloInFlight = false;
                healthStatus = HealthStatus.OFFLINE;
                lastError = reason == null || reason.isBlank() ? "Disconnected" : "Disconnected: " + reason;
            });
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            MinecraftClient.getInstance().execute(() -> {
                if (RaceSessionManager.this.webSocket != null && RaceSessionManager.this.webSocket != webSocket) {
                    return;
                }
                onConnectionFailed(error);
            });
        }
    }
}
