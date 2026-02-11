// race-server.js
const WebSocket = require('ws');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

function heartbeat() {
    this.isAlive = true;
}

const SERVER_HOST = process.env.HOST || "0.0.0.0";
const SERVER_PORT = Number.parseInt(process.env.PORT || "8080", 10);
const DEBUG_PROTOCOL = process.env.DEBUG_PROTOCOL === "1";
const ROOM_RECONNECT_GRACE_MS = Number.parseInt(process.env.ROOM_RECONNECT_GRACE_MS || "30000", 10);
const wss = new WebSocket.Server({ host: SERVER_HOST, port: SERVER_PORT }, () => {
    console.log(`Race server running on ws://${SERVER_HOST}:${SERVER_PORT}`);
});

const ITEMS_FILE = path.join(__dirname, 'items.txt');

function loadItems() {
    try {
        const raw = fs.readFileSync(ITEMS_FILE, 'utf-8');
        const items = raw
            .split('\n')
            .map(l => l.trim())
            .filter(l => l.startsWith('minecraft:') && l.length > "minecraft:".length);
        console.log(`Loaded ${items.length} items`);
        return items.length ? items : ["minecraft:diamond"];
    } catch {
        return ["minecraft:diamond"];
    }
}

let ITEM_POOL = loadItems();

function randomRoomCode() {
    const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    return Array.from({ length: 6 }, () => chars[Math.floor(Math.random() * chars.length)]).join('');
}

function randomSeed() {
    return Math.floor(Math.random() * Number.MAX_SAFE_INTEGER).toString();
}

function randomItem() {
    return ITEM_POOL[Math.floor(Math.random() * ITEM_POOL.length)];
}

function broadcast(room, data) {
    const msg = JSON.stringify(data);
    room.players.forEach(p => {
        if (p.ws && p.ws.readyState === WebSocket.OPEN) p.ws.send(msg);
    });
}

function updateRoom(room) {
    broadcast(room, {
        type: "room_update",
        roomCode: room.code,
        state: room.state,
        players: sanitizePlayers(room)
    });
}

const rooms = new Map();
const roomCleanupTimers = new Map();

function cancelRoomCleanup(roomCode) {
    const timer = roomCleanupTimers.get(roomCode);
    if (timer) {
        clearTimeout(timer);
        roomCleanupTimers.delete(roomCode);
    }
}

function scheduleRoomCleanup(room) {
    if (!room || roomCleanupTimers.has(room.code)) return;
    const timer = setTimeout(() => {
        roomCleanupTimers.delete(room.code);
        const current = rooms.get(room.code);
        if (!current) return;
        const hasConnected = current.players.some(p => p.ws && p.ws.readyState === WebSocket.OPEN);
        if (!hasConnected) {
            rooms.delete(room.code);
            console.log(`Room ${room.code} deleted (reconnect timeout)`);
        }
    }, ROOM_RECONNECT_GRACE_MS);
    roomCleanupTimers.set(room.code, timer);
}

function resetRoomToLobby(room) {
    room.state = "lobby";
    room.seed = null;
    room.targetItemId = null;
    room.startedAt = 0;
    room.results = [];
    room.startGeneration = (room.startGeneration || 0) + 1;
    room.players.forEach(p => {
        p.inWorld = false;
    });
    updateRoom(room);
    console.log(`Room ${room.code} reset to LOBBY`);
}

function safeString(x, max = 64) {
    if (typeof x !== 'string') return '';
    const s = x.trim();
    return s.length > max ? s.slice(0, max) : s;
}

function safeInt(x, min, max) {
    const n = Number(x);
    if (!Number.isFinite(n)) return min;
    const v = Math.floor(n);
    return Math.max(min, Math.min(max, v));
}

function safeUuidString(x) {
    if (typeof x !== 'string') return '';
    const s = x.trim();
    if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(s)) return '';
    return s.toLowerCase();
}

function roomCodeFromData(data) {
    return safeString(data.roomCode, 12).toUpperCase().replaceAll(' ', '');
}

function sanitizePlayers(room) {
    return room.players
        .filter(p => p.ws && p.ws.readyState === WebSocket.OPEN)
        .map(p => ({
        id: p.id,
        name: p.name,
        ready: false,
        isLeader: p.id === room.leaderId,
        inWorld: !!p.inWorld
    }));
}

function normalizeName(name) {
    return safeString(name, 32).toLowerCase();
}

function bindPlayerFromPayload(ws, data) {
    const code = roomCodeFromData(data);
    if (!code) return null;
    const room = rooms.get(code);
    if (!room) return null;

    const id = safeUuidString(data.playerId);
    if (id) {
        const byId = room.players.find(p => p.id === id);
        if (byId) {
            byId.ws = ws;
            cancelRoomCleanup(room.code);
            return byId;
        }
    }

    const incomingName = normalizeName(data.playerName);
    if (!incomingName) return null;
    const sameName = room.players.filter(p => normalizeName(p.name) === incomingName);
    if (sameName.length === 1) {
        sameName[0].ws = ws;
        cancelRoomCleanup(room.code);
        return sameName[0];
    }

    return null;
}

function roomOrError(ws, player) {
    if (!player) return null;
    const room = rooms.get(player.roomCode);
    if (!room) {
        ws.send(JSON.stringify({ type: "error", message: "Room not found" }));
        return null;
    }
    return room;
}

wss.on('connection', (ws) => {
    ws.isAlive = true;
    ws.on('pong', heartbeat);

    let player = null;

    ws.on('message', (message) => {
        let data;
        try { data = JSON.parse(message.toString()); } catch { return; }
        if (!data || typeof data.type !== 'string') return;
        if (DEBUG_PROTOCOL) {
            console.log(`[PROTO] recv type=${data.type} room=${safeString(data.roomCode ?? "", 12)} player=${safeString(data.playerName ?? "", 32)}`);
        }

        // Allow clients to reconnect and keep their identity/room.
        if (!player && typeof data.roomCode === 'string') {
            player = bindPlayerFromPayload(ws, data) || null;
        }

        switch (data.type) {

            case "create_room": {
                const code = randomRoomCode();
                const room = {
                    code,
                    players: [],
                    leaderId: null,
                    state: "lobby", // lobby | starting | running
                    seed: null,
                    targetItemId: null,
                    startedAt: 0,
                    results: [],
                    startGeneration: 0
                };
                rooms.set(code, room);
                cancelRoomCleanup(code);

                const name = safeString(data.playerName, 32) || "Player";
                const id = safeUuidString(data.playerId) || crypto.randomUUID();
                player = { id, name, ws, roomCode: code, inWorld: false };
                room.players.push(player);
                room.leaderId = player.id; // Creator is leader

                ws.send(JSON.stringify({ type: "room_created", roomCode: code, players: sanitizePlayers(room) }));
                updateRoom(room);
                console.log(`Room ${code} created by ${name} id=${id}`);
                break;
            }

            case "join_room": {
                const code = roomCodeFromData(data);
                const room = rooms.get(code);
                if (!room) return ws.send(JSON.stringify({ type: "error", message: "Room not found" }));

                const name = safeString(data.playerName, 32) || "Player";
                const id = safeUuidString(data.playerId) || crypto.randomUUID();
                const existing = room.players.find(p => p.id === id);
                if (existing) {
                    existing.ws = ws;
                    existing.name = name;
                    player = existing;
                    cancelRoomCleanup(room.code);
                } else {
                    player = { id, name, ws, roomCode: room.code, inWorld: false };
                    room.players.push(player);
                }

                // If room has no leader (shouldn't happen but for safety), assign
                if (!room.leaderId) room.leaderId = player.id;

                ws.send(JSON.stringify({ type: "room_joined", roomCode: room.code, players: sanitizePlayers(room) }));
                updateRoom(room);
                console.log(`${name} joined room ${room.code} id=${player.id}`);
                break;
            }

            case "leave_room": {
                if (!player) return;
                const requestedCode = roomCodeFromData(data);
                if (requestedCode && requestedCode !== player.roomCode) {
                    console.log(`leave_room ignored: requested=${requestedCode} session=${player.roomCode} player=${player.name}`);
                    return;
                }
                const room = rooms.get(player.roomCode);
                if (!room) { player = null; return; }

                room.players = room.players.filter(p => p.id !== player.id);
                updateRoom(room);
                console.log(`${player.name} left room ${room.code}`);

                if (room.players.length === 0) {
                    cancelRoomCleanup(room.code);
                    rooms.delete(room.code);
                    console.log(`Room ${room.code} deleted (empty)`);
                } else if (room.leaderId === player.id) {
                    // Leader left, assign new leader
                    const connected = room.players.filter(p => p.ws && p.ws.readyState === WebSocket.OPEN);
                    const nextLeader = connected[0] || room.players[0];
                    room.leaderId = nextLeader.id;
                    console.log(`Player ${player.name} (leader) left, new leader is ${nextLeader.name}`);
                    updateRoom(room);
                }
                player = null;
                break;
            }

            case "reset_lobby": {
                const room = roomOrError(ws, player);
                if (!room) return;

                // Only leader can reset
                if (player.id !== room.leaderId) return;

                resetRoomToLobby(room);
                break;
            }

            case "start_request": {
                if (!player) {
                    player = bindPlayerFromPayload(ws, data) || null;
                }
                console.log(`start_request recv room=${safeString(data.roomCode, 12)} playerId=${safeString(data.playerId, 40)} playerSession=${player ? player.name : "null"}`);
                const room = roomOrError(ws, player);
                if (!room) {
                    ws.send(JSON.stringify({ type: "error", message: "Not in a room" }));
                    console.log(`start_request ignored: no player session room=${safeString(data.roomCode, 12)} playerId=${safeString(data.playerId, 40)} playerName=${safeString(data.playerName, 32)}`);
                    return;
                }

                // Only allow from lobby
                if (room.state !== "lobby") {
                    ws.send(JSON.stringify({ type: "error", message: `Cannot start while state=${room.state}` }));
                    console.log(`start_request ignored: room ${room.code} state=${room.state}`);
                    return;
                }

                // Only leader can start
                if (player.id !== room.leaderId) {
                    ws.send(JSON.stringify({ type: "error", message: "Only leader can start" }));
                    console.log(`start_request ignored: ${player.name} is not leader in room ${room.code}`);
                    return;
                }

                const inWorldPlayers = room.players.filter(p => p.inWorld);
                if (inWorldPlayers.length > 0) {
                    ws.send(JSON.stringify({ type: "error", message: "Players still in game world" }));
                    console.log(`start_request ignored: room ${room.code} has inWorld players: ${inWorldPlayers.map(p => p.name).join(",")}`);
                    return;
                }

                room.state = "starting";
                room.seed = randomSeed();
                room.targetItemId = randomItem();
                room.results = [];
                const generation = (room.startGeneration || 0) + 1;
                room.startGeneration = generation;
                updateRoom(room);

                const countdown = safeInt(data.countdown ?? 10, 0, 30);

                broadcast(room, {
                    type: "start",
                    seed: room.seed,
                    targetItemId: room.targetItemId,
                    countdown
                });

                console.log(`Room ${room.code} STARTING seed=${room.seed} target=${room.targetItemId} countdown=${countdown}s`);

                setTimeout(() => {
                    // If nobody cancelled, move to running
                    if (!rooms.has(room.code)) return;
                    if (room.state === "starting" && room.startGeneration === generation) {
                        room.state = "running";
                        room.startedAt = Date.now();
                        updateRoom(room);
                        console.log(`Room ${room.code} is now RUNNING`);
                    }
                }, countdown * 1000);

                break;
            }

            case "cancel_start":
            case "cancel_start_request":
            case "start_cancel":
            case "stop_start":
            case "abort_start": {
                const room = roomOrError(ws, player);
                if (!room) return;
                if (room.state !== "starting" && room.state !== "running") {
                    console.log(`${data.type} ignored: room ${room.code} state=${room.state}`);
                    return;
                }

                const wasRunning = room.state === "running";
                broadcast(room, { type: "start_cancelled" });
                resetRoomToLobby(room);
                if (wasRunning) {
                    console.log(`Match stopped by ${player.name} in room ${room.code}`);
                } else {
                    console.log(`Start cancelled by ${player.name} in room ${room.code}`);
                }
                break;
            }

            // MULTI-WINNER RESULTS (no room finishing here)
            case "finish": {
                const room = roomOrError(ws, player);
                if (!room) return;

                if (room.state !== "running") return;

                const reason = safeString(data.reason, 32).toLowerCase(); // "target_obtained" | "death"
                const rtaMs = safeInt(data.rtaMs, 0, 2_147_483_647);
                const igtMs = safeInt(data.igtMs, 0, 2_147_483_647);

                // Store result
                room.results = room.results || [];

                let rank = -1;
                let status = "finished";

                if (reason === "death") {
                    status = "eliminated";
                    rank = 999; // Or unranked
                } else {
                    // Only rank finishers
                    const finishers = room.results.filter(r => r.status === "finished").length;
                    rank = finishers + 1;
                }

                const resultEntry = {
                    name: player.name,
                    reason: reason || "unknown",
                    status: status,
                    rtaMs,
                    igtMs,
                    rank,
                    at: Date.now()
                };

                // Remove existing result for this player if any (updates)
                room.results = room.results.filter(r => r.name !== player.name);
                room.results.push(resultEntry);

                // Sort results: Finished (by rank) -> Eliminated
                room.results.sort((a, b) => {
                    if (a.status === "finished" && b.status !== "finished") return -1;
                    if (a.status !== "finished" && b.status === "finished") return 1;
                    if (a.status === "finished") return a.rank - b.rank;
                    return 0;
                });

                broadcast(room, {
                    type: "player_result",
                    player: player.name,
                    reason: reason || "unknown",
                    rtaMs,
                    igtMs,
                    rank
                });

                if (rank === 1) {
                    broadcast(room, {
                        type: "winner",
                        player: player.name,
                        rtaMs,
                        igtMs
                    });
                    console.log(`WINNER room=${room.code} player=${player.name}`);
                }

                console.log(`RESULT room=${room.code} player=${player.name} reason=${reason} rta=${rtaMs} igt=${igtMs}`);
                break;
            }

            case "player_world_state": {
                const room = roomOrError(ws, player);
                if (!room) return;

                player.inWorld = !!data.inWorld;
                if (DEBUG_PROTOCOL) {
                    console.log(`[PROTO] world_state room=${room.code} player=${player.name} inWorld=${player.inWorld}`);
                }

                if (room.state === "running") {
                    const someoneInWorld = room.players.some(p => p.inWorld);
                    if (!someoneInWorld) {
                        resetRoomToLobby(room);
                    }
                }
                break;
            }

            // ADVANCEMENT BROADCAST
            case "advancement": {
                const room = roomOrError(ws, player);
                if (!room) return;
                if (room.state !== "running") return;

                const advancementId = safeString(data.advancementId, 128);
                if (!advancementId) return;

                broadcast(room, {
                    type: "advancement",
                    playerName: player.name,
                    advancementId
                });

                console.log(`ADV room=${room.code} player=${player.name} adv=${advancementId}`);
                break;
            }

            // Optional: reload item pool without restart (server-side admin use)
            case "reload_items": {
                ITEM_POOL = loadItems();
                ws.send(JSON.stringify({ type: "items_reloaded", count: ITEM_POOL.length }));
                break;
            }

            // HEALTH CHECK PING
            case "ping": {
                ws.send(JSON.stringify({ type: "pong" }));
                break;
            }

            default: {
                // ignore unknown
                break;
            }
        }
    });

    ws.on('close', () => {
        if (!player) return;
        const room = rooms.get(player.roomCode);
        if (!room) return;

        if (room.players.length === 1 && room.players[0].id === player.id) {
            room.players[0].ws = null;
            room.players[0].inWorld = false;
            scheduleRoomCleanup(room);
            console.log(`${player.name} disconnected from room ${room.code} (grace ${ROOM_RECONNECT_GRACE_MS}ms)`);
            return;
        }

        room.players = room.players.filter(p => p.id !== player.id);
        updateRoom(room);
        console.log(`${player.name} disconnected from room ${room.code}`);

        if (room.players.length === 0) {
            cancelRoomCleanup(room.code);
            rooms.delete(room.code);
            console.log(`Room ${room.code} deleted (empty)`);
        } else if (room.leaderId === player.id) {
            // Leader left, assign new leader
            const connected = room.players.filter(p => p.ws && p.ws.readyState === WebSocket.OPEN);
            const nextLeader = connected[0] || room.players[0];
            room.leaderId = nextLeader.id;
            console.log(`Player ${player.name} (leader) disconnected, new leader is ${nextLeader.name}`);
            updateRoom(room);
        }
    });
});

// Heartbeat interval (every 5 seconds)
const interval = setInterval(function ping() {
    wss.clients.forEach(function each(ws) {
        if (ws.isAlive === false) return ws.terminate();

        ws.isAlive = false;
        ws.ping();
    });
}, 10000);

wss.on('close', function close() {
    clearInterval(interval);
    roomCleanupTimers.forEach(timer => clearTimeout(timer));
    roomCleanupTimers.clear();
});
