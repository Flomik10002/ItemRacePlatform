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
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientAdvancementManager;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.advancement.PlacedAdvancement;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.registry.RegistryKeys;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

@Environment(EnvType.CLIENT)
public final class RaceSessionManager {
    public enum ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }
    public enum HealthStatus { UNKNOWN, CHECKING, ONLINE, OFFLINE }
    public enum FinishReason { TARGET_OBTAINED, DEATH }
    private String activeRaceWorldName = null;

    public record PlayerStatus(UUID id, String name, boolean ready, boolean isLeader) {}

    public static final String SERVER_URI_PROPERTY = "speedrunigt.race.server";

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

    private URI serverUri = URI.create(System.getProperty(SERVER_URI_PROPERTY, "ws://race.flomik.xyz:8080"));
    private WebSocket webSocket = null;
    private final Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    private final StringBuilder partialMessage = new StringBuilder();

    private RaceState state = RaceState.IDLE;
    private String roomCode = "";
    private final List<PlayerStatus> players = new ArrayList<>();
    private final ConcurrentHashMap<String, FinishTime> finishTimesByPlayerName = new ConcurrentHashMap<>();
    private final Set<String> announcedAdvancements = ConcurrentHashMap.newKeySet();

    private String seedString = null;
    private Identifier targetItemId = null;
    private String pendingWorldDirectoryName = null;
    private boolean startRequested = false;
    private long startRequestSentAt = 0L;

    private long startScheduledAt = 0L;
    private boolean worldCreationRequested = false;
    private boolean timerConfigured = false;
    private final AtomicBoolean finishTriggered = new AtomicBoolean(false);
    private long lastReconnectAttemptAt = 0L;
    private volatile String cachedClientPlayerId = null;
    private long nextConnectionId = 1L;
    private long activeConnectionId = 0L;
    private String legacyReadyRoomCode = "";
    private Boolean lastReportedInWorldState = null;

    private RaceSessionManager() {}

    public ConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    public HealthStatus getHealthStatus() {
        return healthStatus;
    }

    public void checkServerHealth() {
        if (!healthCheckInFlight.compareAndSet(false, true)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> healthStatus = HealthStatus.CHECKING);

        CompletableFuture<Boolean> pongFuture = new CompletableFuture<>();
        StringBuilder partialPongMessage = new StringBuilder();
        try {
            httpClient.newWebSocketBuilder()
                    .buildAsync(serverUri, new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                            webSocket.sendText("{\"type\":\"ping\"}", true);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            if (pongFuture.isDone()) return WebSocket.Listener.super.onText(webSocket, data, last);

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
                            } catch (Exception ignored) {}
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
                    if (err == null && Boolean.TRUE.equals(ok)) {
                        healthStatus = HealthStatus.ONLINE;
                    } else {
                        healthStatus = HealthStatus.OFFLINE;
                    }
                    healthCheckInFlight.set(false);
                }));
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
        return state == RaceState.RUNNING && targetItemId != null;
    }

    public boolean isLocalPlayerLeader() {
        if (players.isEmpty()) return true;
        String local = normalizePlayerKey(getClientPlayerName());
        for (PlayerStatus player : players) {
            if (player.isLeader()) {
                return normalizePlayerKey(player.name()).equals(local);
            }
        }
        return normalizePlayerKey(players.get(0).name()).equals(local);
    }

    public boolean isStartRequestInFlight() {
        return startRequested && (System.currentTimeMillis() - startRequestSentAt) < 3_000L;
    }

    public URI getServerUri() {
        return serverUri;
    }

    public void setServerUri(URI serverUri) {
        this.serverUri = Objects.requireNonNull(serverUri, "serverUri");
        this.healthStatus = HealthStatus.UNKNOWN;
        if (this.connectionStatus == ConnectionStatus.CONNECTED || this.connectionStatus == ConnectionStatus.CONNECTING) {
            disconnect();
        }
    }

    public void tick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (state != RaceState.IDLE && connectionStatus == ConnectionStatus.DISCONNECTED) {
            if (now - lastReconnectAttemptAt >= 1500L) {
                lastReconnectAttemptAt = now;
                connect();
            }
        }

        if (state != RaceState.IDLE && roomCode != null && !roomCode.isEmpty()) {
            boolean inWorld = client.world != null;
            if (lastReportedInWorldState == null || lastReportedInWorldState != inWorld) {
                sendPlayerWorldState(inWorld);
                lastReportedInWorldState = inWorld;
            }
        } else {
            lastReportedInWorldState = null;
        }

        if (state == RaceState.STARTING && !worldCreationRequested && System.currentTimeMillis() >= startScheduledAt) {
            worldCreationRequested = true;
            startWorld(client);
        }

        if (state == RaceState.STARTING && client.world != null && client.player != null) {
            state = RaceState.RUNNING;
            configureTimerForRace();
        }
    }


    private void sendSystemChat(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud != null)
            client.inGameHud.getChatHud().addMessage(Text.literal(msg));
    }

    public void connect() {
        if (connectionStatus == ConnectionStatus.CONNECTED && webSocket != null) return;
        if (connectionStatus == ConnectionStatus.CONNECTING) return;

        lastError = null;
        connectionStatus = ConnectionStatus.CONNECTING;
        SpeedRunIGT.debug("[RaceWS] connect begin uri=" + serverUri);
        try {
            httpClient.newWebSocketBuilder()
                    .buildAsync(serverUri, new WsListener())
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
        this.pendingMessages.clear();
        this.partialMessage.setLength(0);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client disconnect");
            } catch (Exception ignored) {}
        }
    }

    public void createRoom() {
        if (state != RaceState.IDLE) return;
        pendingMessages.clear();
        connect();
        String playerId = getClientPlayerId();
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "create_room");
        msg.addProperty("playerName", getClientPlayerName());
        msg.addProperty("playerId", playerId);
        SpeedRunIGT.debug("[RaceWS] createRoom playerId=" + playerId);
        send(msg);
    }

    public void joinRoom(String code) {
        if (state != RaceState.IDLE) return;
        String normalized = normalizeRoomCode(code);
        if (normalized.isEmpty()) return;

        pendingMessages.clear();
        connect();
        String playerId = getClientPlayerId();
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "join_room");
        msg.addProperty("roomCode", normalized);
        msg.addProperty("playerName", getClientPlayerName());
        msg.addProperty("playerId", playerId);
        SpeedRunIGT.debug("[RaceWS] joinRoom room=" + normalized + " playerId=" + playerId);
        send(msg);
    }

    public void leaveRoom() {
        if (state == RaceState.IDLE) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "leave_room");
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("playerId", getClientPlayerId());
        WebSocket ws = webSocket;
        if (connectionStatus == ConnectionStatus.CONNECTED && ws != null) {
            SpeedRunIGT.debug("[RaceWS] send leave_room room=" + roomCode + " state=" + state);
            ws.sendText(SpeedRunIGT.GSON.toJson(msg), true).exceptionally(err -> null);
        } else {
            SpeedRunIGT.debug("[RaceWS] skip leave_room send (not connected) room=" + roomCode + " state=" + state);
        }

        resetToIdle();
    }

    public void requestStart() {
        if (state != RaceState.LOBBY && state != RaceState.FINISHED) {
            lastError = "Cannot start: state=" + state;
            SpeedRunIGT.debug("[RaceWS] requestStart blocked: " + lastError);
            return;
        }
        if (!isLocalPlayerLeader()) {
            lastError = "Only leader can start";
            SpeedRunIGT.debug("[RaceWS] requestStart blocked: " + lastError);
            return;
        }
        if (MinecraftClient.getInstance().world != null) {
            lastError = "Leave world before start";
            SpeedRunIGT.debug("[RaceWS] requestStart blocked: " + lastError);
            return;
        }
        if (isStartRequestInFlight()) return;

        SpeedRunIGT.debug("[RaceWS] FORCE_READY_BEFORE_START room=" + roomCode);
        sendLegacyReadyTrue(true);
        startRequested = true;
        startRequestSentAt = System.currentTimeMillis();
        lastError = null;
        String playerId = getClientPlayerId();
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "start_request");
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("playerName", getClientPlayerName());
        msg.addProperty("playerId", playerId);
        msg.addProperty("countdown", 10);
        SpeedRunIGT.debug("[RaceWS] requestStart room=" + roomCode + " state=" + state + " leader=" + isLocalPlayerLeader() + " playerId=" + playerId);
        send(msg);
    }

    public void cancelStart() {
        if (state != RaceState.STARTING && state != RaceState.RUNNING) return;
        sendCancelStart();
    }

    public void sendAdvancementAchieved(Identifier id) {
        if (state != RaceState.RUNNING) return;
        if (id == null || roomCode == null || roomCode.isEmpty()) return;
        if (!announcedAdvancements.add(id.toString())) return;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "advancement");
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("playerName", getClientPlayerName());
        msg.addProperty("playerId", getClientPlayerId());
        msg.addProperty("advancementId", id.toString());
        send(msg);
    }

    private void sendCancelStart() {
        if (roomCode == null || roomCode.isEmpty()) return;
        sendCancelStartType("cancel_start");
        sendCancelStartType("start_cancel");
        sendCancelStartType("cancel_start_request");
        sendCancelStartType("stop_start");
        sendCancelStartType("abort_start");
    }

    private void sendCancelStartType(String type) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("playerName", getClientPlayerName());
        msg.addProperty("playerId", getClientPlayerId());
        send(msg);
    }

    private void sendResetLobby() {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "reset_lobby");
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("playerName", getClientPlayerName());
        msg.addProperty("playerId", getClientPlayerId());
        send(msg);
    }

    private void cancelStarting() {
        if (state != RaceState.STARTING) return;
        state = RaceState.LOBBY;
        startScheduledAt = 0L;
        worldCreationRequested = false;
        timerConfigured = false;
        finishTriggered.set(false);
        seedString = null;
        targetItemId = null;
        pendingWorldDirectoryName = null;
        activeRaceWorldName = null;
        announcedAdvancements.clear();
        startRequested = false;
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

        finishTimesByPlayerName.put(
                normalizePlayerKey(getClientPlayerName()),
                new FinishTime(timer.getInGameTime(false), timer.getRealTimeAttack(), reason == FinishReason.DEATH)
        );

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "finish");
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("playerName", getClientPlayerName());
        msg.addProperty("playerId", getClientPlayerId());
        msg.addProperty("reason", reason.name().toLowerCase(Locale.ROOT));
        msg.addProperty("rtaMs", timer.getRealTimeAttack());
        msg.addProperty("igtMs", timer.getInGameTime(false));
        send(msg);
    }

    private void resetToIdle() {
        state = RaceState.IDLE;
        roomCode = "";
        players.clear();
        finishTimesByPlayerName.clear();
        announcedAdvancements.clear();
        seedString = null;
        targetItemId = null;
        pendingWorldDirectoryName = null;
        startRequested = false;
        startRequestSentAt = 0L;
        startScheduledAt = 0L;
        worldCreationRequested = false;
        timerConfigured = false;
        finishTriggered.set(false);
        activeRaceWorldName = null;
        lastReconnectAttemptAt = 0L;
        pendingMessages.clear();
        legacyReadyRoomCode = "";
        lastReportedInWorldState = null;
    }

    private void startWorld(MinecraftClient client) {
        if (seedString == null || targetItemId == null) {
            lastError = "Missing START parameters";
            cancelStarting();
            return;
        }

        if (client.world != null) {
            lastError = "Must be in menu to start race world";
            cancelStarting();
            return;
        }

        OptionalLong parsedSeed = GeneratorOptions.parseSeed(seedString);
        long seed = parsedSeed.isPresent() ? parsedSeed.getAsLong() : (long) seedString.hashCode();

        InGameTimerUtils.IS_SET_SEED = true;

        String dir = makeWorldDirectoryName();
        pendingWorldDirectoryName = dir;
        activeRaceWorldName = dir;

        LevelInfo info = new LevelInfo("Item Hunt Race", GameMode.SURVIVAL, true,
                Difficulty.HARD, false,
                new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES),
                new DataConfiguration(DataPackSettings.SAFE_MODE, FeatureFlags.DEFAULT_ENABLED_FEATURES));

        GeneratorOptions options = new GeneratorOptions(seed, true, false);

        client.createIntegratedServerLoader().createAndStart(
                dir, info, options,
                wrapper -> wrapper.getOrThrow(RegistryKeys.WORLD_PRESET)
                        .getOrThrow(WorldPresets.DEFAULT).value().createDimensionsRegistryHolder(),
                client.currentScreen
        );
    }

    private String makeWorldDirectoryName() {
        String code = roomCode.isEmpty() ? "race" : roomCode.toLowerCase(Locale.ROOT);
        return "item_hunt_" + code + "_" + (System.currentTimeMillis() / 1000L);
    }

    private void send(JsonObject jsonObject) {
        String type = jsonObject.has("type") ? jsonObject.get("type").getAsString() : "?";
        String payload = SpeedRunIGT.GSON.toJson(jsonObject);
        WebSocket ws = webSocket;
        if (connectionStatus != ConnectionStatus.CONNECTED || ws == null) {
            SpeedRunIGT.debug("[RaceWS] queue type=" + type + " conn=" + connectionStatus + " state=" + state + " room=" + roomCode);
            pendingMessages.add(payload);
            connect();
            return;
        }
        String suffix = " cid=" + activeConnectionId + " ws=" + System.identityHashCode(ws) + " state=" + state + " room=" + roomCode;
        if ("start_request".equals(type)) {
            SpeedRunIGT.debug("[RaceWS] send type=" + type + " conn=" + connectionStatus + suffix + " payload=" + payload);
        } else {
            SpeedRunIGT.debug("[RaceWS] send type=" + type + " conn=" + connectionStatus + suffix);
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
        if (connectionStatus != ConnectionStatus.CONNECTED || ws == null) return;
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

    private void onConnected(WebSocket ws) {
        this.webSocket = ws;
        this.connectionStatus = ConnectionStatus.CONNECTED;
        this.lastError = null;
        this.healthStatus = HealthStatus.ONLINE;
        this.lastReconnectAttemptAt = 0L;
        this.activeConnectionId = nextConnectionId++;
        SpeedRunIGT.debug("[RaceWS] connected cid=" + activeConnectionId + " ws=" + System.identityHashCode(ws));
        sendRoomResumeIfNeeded(ws);
        flushPending();
    }

    private void onConnectionFailed(Throwable err) {
        this.connectionStatus = ConnectionStatus.DISCONNECTED;
        this.webSocket = null;
        this.lastError = err.getMessage();
        this.healthStatus = HealthStatus.OFFLINE;
        SpeedRunIGT.debug("[RaceWS] connection failed: " + err.getClass().getSimpleName() + ": " + err.getMessage());
    }

    private void sendRoomResumeIfNeeded(WebSocket ws) {
        if (roomCode == null || roomCode.isEmpty()) return;
        if (state != RaceState.LOBBY && state != RaceState.FINISHED) return;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "join_room");
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("playerName", getClientPlayerName());
        msg.addProperty("playerId", getClientPlayerId());

        String payload = SpeedRunIGT.GSON.toJson(msg);
        ws.sendText(payload, true).exceptionally(err -> {
            MinecraftClient.getInstance().execute(() -> {
                onConnectionFailed(err);
                pendingMessages.add(payload);
                connect();
            });
            return null;
        });
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

        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        switch (type) {
            case "room_created", "room_joined" -> {
                String code = msg.has("roomCode") ? msg.get("roomCode").getAsString() : "";
                if (code.isEmpty()) return;
                roomCode = normalizeRoomCode(code);
                players.clear();
                finishTimesByPlayerName.clear();
                if (msg.has("players") && msg.get("players").isJsonArray()) {
                    parsePlayers(msg.getAsJsonArray("players"));
                }
                state = RaceState.LOBBY;
                resetRoundDataForLobby();
                finishTriggered.set(false);
                startRequested = false;
                startRequestSentAt = 0L;
                lastError = null;
                sendLegacyReadyTrue();
            }
            case "room_update" -> {
                if (msg.has("state")) {
                    String s = msg.get("state").getAsString();
                    if ("lobby".equals(s) && state != RaceState.LOBBY && state != RaceState.IDLE) {
                        // Server forced reset to lobby
                        resetRoundDataForLobby();
                        finishTimesByPlayerName.clear();
                        finishTriggered.set(false);
                    }

                    if ("lobby".equals(s) && state == RaceState.LOBBY && startRequested && !isStartRequestInFlight()) {
                        startRequested = false;
                        startRequestSentAt = 0L;
                        lastError = "Start request rejected by server";
                    }
                }
                
                if (msg.has("players") && msg.get("players").isJsonArray()) {
                    players.clear();
                    parsePlayers(msg.getAsJsonArray("players"));
                }
                if (state == RaceState.LOBBY) {
                    sendLegacyReadyTrue();
                }
            }
            case "finish" -> {
                String player = getStringFromKeys(msg, "playerName", "player", "name");
                Long igt = getLongFromKeys(msg, "igtMs", "igt", "igt_ms", "igtMillis", "igt_millis");
                Long rta = getLongFromKeys(msg, "rtaMs", "rta", "rta_ms", "rtaMillis", "rta_millis");
                if ((igt == null || rta == null) && msg.has("time") && msg.get("time").isJsonObject()) {
                    JsonObject time = msg.getAsJsonObject("time");
                    if (igt == null) igt = getLongFromKeys(time, "igtMs", "igt", "igt_ms", "igtMillis", "igt_millis");
                    if (rta == null) rta = getLongFromKeys(time, "rtaMs", "rta", "rta_ms", "rtaMillis", "rta_millis");
                }
                if (player != null && igt != null && rta != null) {
                    finishTimesByPlayerName.put(normalizePlayerKey(player), new FinishTime(igt, rta, false));
                }
            }
            case "advancement" -> {
                String player = getStringFromKeys(msg, "playerName", "player", "name");
                String adv = getStringFromKeys(msg, "advancementId", "advancement", "id");
                if (player == null || adv == null) return;
                Identifier id = Identifier.tryParse(adv);
                sendAdvancementChat(player, id);
            }
            case "player_result" -> {
                String player = getStringFromKeys(msg, "player");
                String reason = getStringFromKeys(msg, "reason");
                Long igt = getLongFromKeys(msg, "igtMs");
                Long rta = getLongFromKeys(msg, "rtaMs");

                if (player == null || reason == null) return;

                if ("death".equals(reason) || "eliminated".equals(reason)) {
                    finishTimesByPlayerName.put(normalizePlayerKey(player), new FinishTime(rta, igt, true));
                    sendSystemChat("§7☠ " + player + " died (" + InGameTimerUtils.timeToStringFormat(rta) + ")");
                } else {
                    finishTimesByPlayerName.put(normalizePlayerKey(player), new FinishTime(rta, igt, false));
                    sendFinishChat(player, new FinishTime(rta, igt, false));
                }
            }
            case "winner" -> {
                String winner = getStringFromKeys(msg, "player", "playerName", "name");
                if (winner == null || winner.isEmpty()) return;

                FinishTime time = finishTimesByPlayerName.get(normalizePlayerKey(winner));
                if (time == null) {
                    Long igt = getLongFromKeys(msg, "igtMs", "igt", "igt_ms", "igtMillis", "igt_millis");
                    Long rta = getLongFromKeys(msg, "rtaMs", "rta", "rta_ms", "rtaMillis", "rta_millis");
                    if (igt != null && rta != null) time = new FinishTime(igt, rta, false);
                }
                if (time == null && normalizePlayerKey(winner).equals(normalizePlayerKey(getClientPlayerName()))) {
                    InGameTimer timer = InGameTimer.getInstance();
                    time = new FinishTime(timer.getInGameTime(false), timer.getRealTimeAttack(), false);
                }
                
                if (normalizePlayerKey(winner).equals(normalizePlayerKey(getClientPlayerName()))) {
                    spawnFireworks();
                }

                sendWinnerAnnouncement(winner, time);
            }
            case "start_cancelled", "cancel_start", "starting_cancelled", "start_cancel", "cancel_start_request", "stop_start", "abort_start" -> {
                if (state != RaceState.IDLE) resetRoundDataForLobby();
                startRequested = false;
            }
            case "start" -> {
                startRequested = false;
                startRequestSentAt = 0L;
                announcedAdvancements.clear();

                String seed = msg.has("seed") ? msg.get("seed").getAsString() : null;
                String target = msg.has("targetItemId") ? msg.get("targetItemId").getAsString() : null;
                if (seed == null || target == null) return;

                Identifier id = Identifier.tryParse(target);
                if (id == null || !Registries.ITEM.containsId(id)) {
                    lastError = "Invalid targetItemId: " + target;
                    return;
                }

                seedString = seed;
                targetItemId = id;
                int seconds = msg.has("countdown") ? msg.get("countdown").getAsInt() : 5;
                int countdownSeconds = Math.max(0, Math.min(30, seconds));
                startScheduledAt = System.currentTimeMillis() + (long) countdownSeconds * 1000L;
                worldCreationRequested = false;
                state = RaceState.STARTING;
                timerConfigured = false;
                finishTriggered.set(false);
            }
            case "error" -> lastError = msg.has("message") ? msg.get("message").getAsString() : "Unknown server error";
        }
    }

    private void configureTimerForRace() {
        if (timerConfigured) return;
        timerConfigured = true;

        InGameTimer timer = InGameTimer.getInstance();
        // Force start a new timer for the race, even if one exists
        if (pendingWorldDirectoryName != null && !pendingWorldDirectoryName.isEmpty()) {
            InGameTimer.start(pendingWorldDirectoryName, RunType.fromBoolean(InGameTimerUtils.IS_SET_SEED));
            timer = InGameTimer.getInstance();
        }
        timer.setCategory(RunCategories.CUSTOM, false);
        timer.setUncompleted(false);
    }

    private void parsePlayers(JsonArray playersArray) {
        for (JsonElement el : playersArray) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String name = o.has("name") ? o.get("name").getAsString() : "Unknown";
            boolean ready = o.has("ready") && o.get("ready").getAsBoolean();
            boolean isLeader = o.has("isLeader") && o.get("isLeader").getAsBoolean();
            UUID id = o.has("id") ? safeUuid(o.get("id").getAsString()) : UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            players.add(new PlayerStatus(id, name, ready, isLeader));
        }
    }

    private static UUID safeUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception ignored) {
            return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String normalizeRoomCode(String code) {
        if (code == null) return "";
        return code.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    private static String normalizePlayerKey(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT);
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

    private void sendPlayerWorldState(boolean inWorld) {
        if (roomCode == null || roomCode.isEmpty() || state == RaceState.IDLE) return;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "player_world_state");
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("playerName", getClientPlayerName());
        msg.addProperty("playerId", getClientPlayerId());
        msg.addProperty("inWorld", inWorld);
        SpeedRunIGT.debug("[RaceWS] send player_world_state room=" + roomCode + " inWorld=" + inWorld);
        send(msg);
    }

    private void sendLegacyReadyTrue() {
        sendLegacyReadyTrue(false);
    }

    private void sendLegacyReadyTrue(boolean force) {
        if (roomCode == null || roomCode.isEmpty()) return;
        if (!force && roomCode.equals(legacyReadyRoomCode)) return;
        legacyReadyRoomCode = roomCode;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "ready");
        msg.addProperty("roomCode", roomCode);
        msg.addProperty("playerName", getClientPlayerName());
        msg.addProperty("playerId", getClientPlayerId());
        msg.addProperty("ready", true);
        SpeedRunIGT.debug("[RaceWS] send legacy ready=true room=" + roomCode);
        send(msg);
    }

    private void resetRoundDataForLobby() {
        state = RaceState.LOBBY;
        startScheduledAt = 0L;
        worldCreationRequested = false;
        timerConfigured = false;
        finishTriggered.set(false);
        seedString = null;
        targetItemId = null;
        pendingWorldDirectoryName = null;
        activeRaceWorldName = null;
        announcedAdvancements.clear();
        startRequested = false;
        startRequestSentAt = 0L;
        lastError = null;
    }

    private record FinishTime(long igtMs, long rtaMs, boolean eliminated) {}

    public record LeaderboardEntry(String name, String time) {}

    public List<LeaderboardEntry> getLeaderboard() {
        if (finishTimesByPlayerName.isEmpty()) return List.of();
        
        List<java.util.Map.Entry<String, FinishTime>> sorted = new ArrayList<>(finishTimesByPlayerName.entrySet());
        sorted.sort((e1, e2) -> {
            FinishTime t1 = e1.getValue();
            FinishTime t2 = e2.getValue();
            if (t1.eliminated && !t2.eliminated) return 1; // Eliminated players at bottom
            if (!t1.eliminated && t2.eliminated) return -1;
            return Long.compare(t1.rtaMs, t2.rtaMs);
        });
        
        List<LeaderboardEntry> result = new ArrayList<>();
        for (java.util.Map.Entry<String, FinishTime> entry : sorted) {
            FinishTime t = entry.getValue();
            String timeStr = t.eliminated ? "§cLOSE" : InGameTimerUtils.timeToStringFormat(t.rtaMs);
            result.add(new LeaderboardEntry(entry.getKey(), timeStr));
        }
        return result;
    }

    private void sendAdvancementChat(String playerName, Identifier advancementId) {
        if (advancementId == null) return;
        
        // Ignore "recipes/" and "/root" advancements
        String idStr = advancementId.toString();
        if (idStr.startsWith("minecraft:recipes/") || idStr.endsWith("/root")) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud == null) return;

        MutableText title = null;

        // Try to get translated title from ClientAdvancementManager
        try {
            if (client.getNetworkHandler() != null) {
                ClientAdvancementManager handler = client.getNetworkHandler().getAdvancementHandler();
                if (handler != null) {
                    PlacedAdvancement placed = handler.getManager().get(advancementId);
                    if (placed != null && placed.getAdvancement().display().isPresent()) {
                         title = placed.getAdvancement().display().get().getTitle().copy();
                    }
                }
            }
        } catch (Exception ignored) {}

        // Fallback if no translation found
        if (title == null) {
             String path = advancementId.getPath();
             if (path.contains("/")) path = path.substring(path.lastIndexOf('/') + 1);
             String readable = path.replace("_", " ");
             if (!readable.isEmpty()) {
                 readable = Character.toUpperCase(readable.charAt(0)) + readable.substring(1);
             }
             title = Text.literal(readable);
        }

        client.inGameHud.getChatHud().addMessage(
                Text.translatable(
                                "speedrunigt.race.chat.advancement",
                                Text.literal(playerName).formatted(Formatting.GOLD),
                                title.formatted(Formatting.GREEN)
                        )
                        .formatted(Formatting.WHITE)
        );
    }

    private void sendWinnerAnnouncement(String winnerName, FinishTime time) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud == null) return;
        ChatHud chat = client.inGameHud.getChatHud();

        MutableText line1 = Text.literal("🏆 ").formatted(Formatting.YELLOW)
                .append(Text.translatable("speedrunigt.race.chat.winner_prefix").formatted(Formatting.WHITE)) // "Winner: "
                .append(Text.literal(winnerName).formatted(Formatting.GOLD).formatted(Formatting.BOLD));
        chat.addMessage(line1);

        if (time != null) {
            String igt = InGameTimerUtils.timeToStringFormat(time.igtMs());
            String rta = InGameTimerUtils.timeToStringFormat(time.rtaMs());
            chat.addMessage(Text.translatable("speedrunigt.race.chat.time", igt, rta).formatted(Formatting.GRAY));
        }
    }

    private void sendFinishChat(String playerName, FinishTime time) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud == null) return;
        ChatHud chat = client.inGameHud.getChatHud();

        // "Player finished in 12:34.567"
        MutableText msg = Text.literal("🏁 ").formatted(Formatting.AQUA)
                .append(Text.literal(playerName).formatted(Formatting.WHITE))
                .append(Text.literal(" finished in ").formatted(Formatting.GRAY))
                .append(Text.literal(InGameTimerUtils.timeToStringFormat(time.rtaMs())).formatted(Formatting.YELLOW));
        
        chat.addMessage(msg);
    }

    private void spawnFireworks() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        NbtCompound fireworks = new NbtCompound();
        NbtList explosions = new NbtList();
        
        // Add a few explosions
        for (int i = 0; i < 3; i++) {
             NbtCompound explosion = new NbtCompound();
             explosion.putBoolean("Flicker", true);
             explosion.putBoolean("Trail", true);
             explosion.putInt("Type", 1); // Large Ball
             // Colors (int array)
             explosion.putIntArray("Colors", new int[]{0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00});
             explosions.add(explosion);
        }
        
        fireworks.put("Explosions", explosions);
        fireworks.putByte("Flight", (byte) 1);
        
        NbtCompound tag = new NbtCompound();
        // NBT API changed in 1.21, disabling visuals for now to fix build
        // tag.put("Fireworks", fireworks);
        // stack.setNbt(tag);
        
        // FireworkRocketEntity rocket = new FireworkRocketEntity(client.world, client.player.getX(), client.player.getY(), client.player.getZ(), stack);
        // client.world.spawnEntity(rocket);
    }
    private static String getStringFromKeys(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                try {
                    String value = obj.get(key).getAsString();
                    if (value != null && !value.isEmpty()) return value;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static Long getLongFromKeys(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                try {
                    JsonPrimitive primitive = obj.getAsJsonPrimitive(key);
                    if (primitive.isNumber()) return primitive.getAsLong();
                    if (primitive.isString()) {
                        String s = primitive.getAsString();
                        if (s == null || s.isEmpty()) continue;
                        try {
                            return Long.parseLong(s);
                        } catch (NumberFormatException ignored) {
                            Long parsed = parseTimeStringToMillis(s);
                            if (parsed != null) return parsed;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static Long parseTimeStringToMillis(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("--")) return null;

        // Formats: MM:SS.mmm or H:MM:SS.mmm
        int dot = s.lastIndexOf('.');
        int colon = s.lastIndexOf(':');
        if (dot > 0 && colon > 0 && dot > colon) {
            String msPart = s.substring(dot + 1);
            String left = s.substring(0, dot);

            int ms;
            try {
                String padded = msPart.length() >= 3 ? msPart.substring(0, 3) : (msPart + "000").substring(0, 3);
                ms = Integer.parseInt(padded);
            } catch (Exception ignored) {
                return null;
            }

            String[] parts = left.split(":");
            try {
                if (parts.length == 2) {
                    long minutes = Long.parseLong(parts[0]);
                    long seconds = Long.parseLong(parts[1]);
                    return (minutes * 60L + seconds) * 1000L + ms;
                }
                if (parts.length == 3) {
                    long hours = Long.parseLong(parts[0]);
                    long minutes = Long.parseLong(parts[1]);
                    long seconds = Long.parseLong(parts[2]);
                    return (hours * 3600L + minutes * 60L + seconds) * 1000L + ms;
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
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
                    SpeedRunIGT.debug("[RaceWS] stale close ignored ws=" + System.identityHashCode(webSocket) + " code=" + statusCode + " reason=" + reason);
                    return;
                }
                connectionStatus = ConnectionStatus.DISCONNECTED;
                RaceSessionManager.this.webSocket = null;
                lastError = "Disconnected: " + reason;
                healthStatus = HealthStatus.OFFLINE;
                SpeedRunIGT.debug("[RaceWS] closed cid=" + activeConnectionId + " ws=" + System.identityHashCode(webSocket) + " code=" + statusCode + " reason=" + reason);
            });
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            MinecraftClient.getInstance().execute(() -> {
                if (RaceSessionManager.this.webSocket != null && RaceSessionManager.this.webSocket != webSocket) {
                    SpeedRunIGT.debug("[RaceWS] stale error ignored ws=" + System.identityHashCode(webSocket) + " err=" + error.getMessage());
                    return;
                }
                SpeedRunIGT.debug("[RaceWS] error ws=" + System.identityHashCode(webSocket) + " err=" + error.getMessage());
                onConnectionFailed(error);
            });
        }
    }
}
