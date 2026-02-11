const test = require("node:test");
const assert = require("node:assert/strict");
const net = require("node:net");
const { spawn } = require("node:child_process");
const { once } = require("node:events");
const WebSocket = require("ws");

const ROOT = process.cwd();

function getFreePort() {
    return new Promise((resolve, reject) => {
        const server = net.createServer();
        server.listen(0, "127.0.0.1", () => {
            const addr = server.address();
            const port = typeof addr === "object" && addr ? addr.port : 0;
            server.close((err) => {
                if (err) reject(err);
                else resolve(port);
            });
        });
        server.on("error", reject);
    });
}

function startServer(port) {
    return new Promise((resolve, reject) => {
        const child = spawn(process.execPath, ["server.js"], {
            cwd: ROOT,
            env: {
                ...process.env,
                HOST: "127.0.0.1",
                PORT: String(port),
            },
            stdio: ["ignore", "pipe", "pipe"],
        });

        let logs = "";
        const timer = setTimeout(() => {
            child.kill("SIGTERM");
            reject(new Error(`server start timeout on port ${port}\n${logs}`));
        }, 3000);

        child.stdout.on("data", (buf) => {
            const text = buf.toString();
            logs += text;
            if (text.includes(`Race server running on ws://127.0.0.1:${port}`)) {
                clearTimeout(timer);
                resolve({ child, getLogs: () => logs });
            }
        });
        child.stderr.on("data", (buf) => {
            logs += buf.toString();
        });
        child.on("exit", (code) => {
            if (code !== 0) {
                clearTimeout(timer);
                reject(new Error(`server exited with code ${code}\n${logs}`));
            }
        });
    });
}

async function stopServer(child) {
    if (!child || child.killed) return;
    child.kill("SIGTERM");
    try {
        await once(child, "exit");
    } catch {
        // ignore
    }
}

function connectWs(url) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(url);
        const timer = setTimeout(() => reject(new Error(`ws connect timeout: ${url}`)), 3000);
        ws.once("open", () => {
            clearTimeout(timer);
            resolve(ws);
        });
        ws.once("error", (err) => {
            clearTimeout(timer);
            reject(err);
        });
    });
}

function waitForMessage(ws, predicate, timeoutMs = 3000) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            cleanup();
            reject(new Error("message wait timeout"));
        }, timeoutMs);

        function onMessage(raw) {
            let msg = null;
            try {
                msg = JSON.parse(raw.toString());
            } catch {
                return;
            }
            if (predicate(msg)) {
                cleanup();
                resolve(msg);
            }
        }

        function onClose() {
            cleanup();
            reject(new Error("socket closed while waiting for message"));
        }

        function cleanup() {
            clearTimeout(timer);
            ws.off("message", onMessage);
            ws.off("close", onClose);
        }

        ws.on("message", onMessage);
        ws.on("close", onClose);
    });
}

function waitForNoMessage(ws, predicate, timeoutMs = 400) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            cleanup();
            resolve();
        }, timeoutMs);

        function onMessage(raw) {
            let msg = null;
            try {
                msg = JSON.parse(raw.toString());
            } catch {
                return;
            }
            if (predicate(msg)) {
                cleanup();
                reject(new Error(`unexpected message: ${JSON.stringify(msg)}`));
            }
        }

        function onClose() {
            cleanup();
            reject(new Error("socket closed while waiting for absence of message"));
        }

        function cleanup() {
            clearTimeout(timer);
            ws.off("message", onMessage);
            ws.off("close", onClose);
        }

        ws.on("message", onMessage);
        ws.on("close", onClose);
    });
}

function sendJson(ws, payload) {
    ws.send(JSON.stringify(payload));
}

test("ping returns pong", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws = await connectWs(`ws://127.0.0.1:${port}`);
        sendJson(ws, { type: "ping" });
        const pong = await waitForMessage(ws, (msg) => msg.type === "pong");
        assert.equal(pong.type, "pong");
        ws.close();
    } finally {
        await stopServer(child);
    }
});

test("create_room returns room_created and leader in room_update", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws = await connectWs(`ws://127.0.0.1:${port}`);
        const playerId = "11111111-1111-1111-1111-111111111111";

        sendJson(ws, { type: "create_room", playerName: "Leader", playerId });
        const created = await waitForMessage(ws, (msg) => msg.type === "room_created");
        assert.match(created.roomCode, /^[A-Z0-9]{6}$/);

        const update = await waitForMessage(ws, (msg) => msg.type === "room_update" && msg.roomCode === created.roomCode);
        assert.equal(update.state, "lobby");
        assert.equal(update.players.length, 1);
        assert.equal(update.players[0].id, playerId);
        assert.equal(update.players[0].isLeader, true);
        ws.close();
    } finally {
        await stopServer(child);
    }
});

test("leader start_request sends start packet", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws = await connectWs(`ws://127.0.0.1:${port}`);
        const playerId = "22222222-2222-2222-2222-222222222222";

        sendJson(ws, { type: "create_room", playerName: "Leader", playerId });
        const created = await waitForMessage(ws, (msg) => msg.type === "room_created");
        sendJson(ws, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId,
            countdown: 0,
        });

        const start = await waitForMessage(ws, (msg) => msg.type === "start");
        assert.equal(typeof start.seed, "string");
        assert.match(start.targetItemId, /^minecraft:/);
        ws.close();
    } finally {
        await stopServer(child);
    }
});

test("non-leader start_request is rejected", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const leader = await connectWs(`ws://127.0.0.1:${port}`);
        const guest = await connectWs(`ws://127.0.0.1:${port}`);

        const leaderId = "33333333-3333-3333-3333-333333333333";
        const guestId = "44444444-4444-4444-4444-444444444444";

        sendJson(leader, { type: "create_room", playerName: "Leader", playerId: leaderId });
        const created = await waitForMessage(leader, (msg) => msg.type === "room_created");
        sendJson(guest, { type: "join_room", roomCode: created.roomCode, playerName: "Guest", playerId: guestId });
        await waitForMessage(guest, (msg) => msg.type === "room_joined");

        sendJson(guest, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Guest",
            playerId: guestId,
            countdown: 0,
        });
        const err = await waitForMessage(guest, (msg) => msg.type === "error");
        assert.match(err.message, /Only leader can start/);

        leader.close();
        guest.close();
    } finally {
        await stopServer(child);
    }
});

test("start_request without room session returns explicit error", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws = await connectWs(`ws://127.0.0.1:${port}`);
        sendJson(ws, {
            type: "start_request",
            roomCode: "ABCDEF",
            playerName: "Ghost",
            playerId: "55555555-5555-5555-5555-555555555555",
        });
        const err = await waitForMessage(ws, (msg) => msg.type === "error");
        assert.match(err.message, /Not in a room|Room not found/);
        ws.close();
    } finally {
        await stopServer(child);
    }
});

test("join_room for unknown code returns room not found", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws = await connectWs(`ws://127.0.0.1:${port}`);
        sendJson(ws, {
            type: "join_room",
            roomCode: "ZZZZZZ",
            playerName: "Guest",
            playerId: "77777777-7777-7777-7777-777777777777",
        });
        const err = await waitForMessage(ws, (msg) => msg.type === "error");
        assert.match(err.message, /Room not found/);
        ws.close();
    } finally {
        await stopServer(child);
    }
});

test("cancel_start from non-leader resets room to lobby", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const leader = await connectWs(`ws://127.0.0.1:${port}`);
        const guest = await connectWs(`ws://127.0.0.1:${port}`);

        const leaderId = "88888888-8888-8888-8888-888888888888";
        const guestId = "99999999-9999-9999-9999-999999999999";

        sendJson(leader, { type: "create_room", playerName: "Leader", playerId: leaderId });
        const created = await waitForMessage(leader, (msg) => msg.type === "room_created");
        sendJson(guest, { type: "join_room", roomCode: created.roomCode, playerName: "Guest", playerId: guestId });
        await waitForMessage(guest, (msg) => msg.type === "room_joined");

        sendJson(leader, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId: leaderId,
            countdown: 30,
        });
        await waitForMessage(guest, (msg) => msg.type === "start");

        sendJson(guest, {
            type: "cancel_start",
            roomCode: created.roomCode,
            playerName: "Guest",
            playerId: guestId,
        });

        await waitForMessage(leader, (msg) => msg.type === "start_cancelled");
        const update = await waitForMessage(leader, (msg) => msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "lobby");
        assert.equal(update.state, "lobby");

        leader.close();
        guest.close();
    } finally {
        await stopServer(child);
    }
});

test("same playerId join does not duplicate room roster", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws1 = await connectWs(`ws://127.0.0.1:${port}`);
        const ws2 = await connectWs(`ws://127.0.0.1:${port}`);
        const playerId = "66666666-6666-6666-6666-666666666666";

        sendJson(ws1, { type: "create_room", playerName: "Solo", playerId });
        const created = await waitForMessage(ws1, (msg) => msg.type === "room_created");

        sendJson(ws2, { type: "join_room", roomCode: created.roomCode, playerName: "Solo", playerId });
        const joined = await waitForMessage(ws2, (msg) => msg.type === "room_joined");
        assert.equal(joined.players.length, 1);
        assert.equal(joined.players[0].id, playerId);

        ws1.close();
        ws2.close();
    } finally {
        await stopServer(child);
    }
});

test("start_request transitions room state to starting then running", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws = await connectWs(`ws://127.0.0.1:${port}`);
        const playerId = "12121212-1212-1212-1212-121212121212";

        sendJson(ws, { type: "create_room", playerName: "Leader", playerId });
        const created = await waitForMessage(ws, (msg) => msg.type === "room_created");
        sendJson(ws, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId,
            countdown: 0,
        });

        const startingUpdate = await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "starting"
        );
        assert.equal(startingUpdate.state, "starting");

        const runningUpdate = await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "running"
        );
        assert.equal(runningUpdate.state, "running");
        ws.close();
    } finally {
        await stopServer(child);
    }
});

test("leader leaving room promotes next player", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const leader = await connectWs(`ws://127.0.0.1:${port}`);
        const guest = await connectWs(`ws://127.0.0.1:${port}`);
        const leaderId = "13131313-1313-1313-1313-131313131313";
        const guestId = "14141414-1414-1414-1414-141414141414";

        sendJson(leader, { type: "create_room", playerName: "Leader", playerId: leaderId });
        const created = await waitForMessage(leader, (msg) => msg.type === "room_created");
        sendJson(guest, { type: "join_room", roomCode: created.roomCode, playerName: "Guest", playerId: guestId });
        await waitForMessage(guest, (msg) => msg.type === "room_joined");

        sendJson(leader, { type: "leave_room", roomCode: created.roomCode, playerId: leaderId });
        const update = await waitForMessage(guest, (msg) =>
            msg.type === "room_update"
            && msg.roomCode === created.roomCode
            && msg.players.length === 1
            && msg.players[0].id === guestId
            && msg.players[0].isLeader === true
        );
        assert.equal(update.players[0].id, guestId);
        assert.equal(update.players[0].isLeader, true);
        leader.close();
        guest.close();
    } finally {
        await stopServer(child);
    }
});

test("empty room is deleted after last player leaves", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws = await connectWs(`ws://127.0.0.1:${port}`);
        const playerId = "15151515-1515-1515-1515-151515151515";

        sendJson(ws, { type: "create_room", playerName: "Solo", playerId });
        const created = await waitForMessage(ws, (msg) => msg.type === "room_created");
        sendJson(ws, { type: "leave_room", roomCode: created.roomCode, playerId });
        await waitForNoMessage(ws, (msg) => msg.type === "error", 200);
        ws.close();

        const ws2 = await connectWs(`ws://127.0.0.1:${port}`);
        sendJson(ws2, {
            type: "join_room",
            roomCode: created.roomCode,
            playerName: "LateJoiner",
            playerId: "16161616-1616-1616-1616-161616161616",
        });
        const err = await waitForMessage(ws2, (msg) => msg.type === "error");
        assert.match(err.message, /Room not found/);
        ws2.close();
    } finally {
        await stopServer(child);
    }
});

test("leader can reset_lobby from running state", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws = await connectWs(`ws://127.0.0.1:${port}`);
        const playerId = "17171717-1717-1717-1717-171717171717";

        sendJson(ws, { type: "create_room", playerName: "Leader", playerId });
        const created = await waitForMessage(ws, (msg) => msg.type === "room_created");

        sendJson(ws, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId,
            countdown: 0,
        });
        await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "running"
        );

        sendJson(ws, {
            type: "reset_lobby",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId,
        });
        const update = await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "lobby"
        );
        assert.equal(update.state, "lobby");
        ws.close();
    } finally {
        await stopServer(child);
    }
});

test("non-leader reset_lobby is ignored", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const leader = await connectWs(`ws://127.0.0.1:${port}`);
        const guest = await connectWs(`ws://127.0.0.1:${port}`);
        const leaderId = "18181818-1818-1818-1818-181818181818";
        const guestId = "19191919-1919-1919-1919-191919191919";

        sendJson(leader, { type: "create_room", playerName: "Leader", playerId: leaderId });
        const created = await waitForMessage(leader, (msg) => msg.type === "room_created");
        sendJson(guest, { type: "join_room", roomCode: created.roomCode, playerName: "Guest", playerId: guestId });
        await waitForMessage(guest, (msg) => msg.type === "room_joined");

        sendJson(leader, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId: leaderId,
            countdown: 0,
        });
        await waitForMessage(guest, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "running"
        );

        sendJson(guest, {
            type: "reset_lobby",
            roomCode: created.roomCode,
            playerName: "Guest",
            playerId: guestId,
        });

        await waitForNoMessage(guest, (msg) => msg.type === "room_update" && msg.state === "lobby", 500);
        leader.close();
        guest.close();
    } finally {
        await stopServer(child);
    }
});

test("single player can reconnect and start within grace window", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const leaderA = await connectWs(`ws://127.0.0.1:${port}`);
        const leaderId = "20202020-2020-2020-2020-202020202020";

        sendJson(leaderA, { type: "create_room", playerName: "Leader", playerId: leaderId });
        const created = await waitForMessage(leaderA, (msg) => msg.type === "room_created");
        leaderA.close();

        const leaderB = await connectWs(`ws://127.0.0.1:${port}`);
        sendJson(leaderB, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId: leaderId,
            countdown: 0,
        });
        const start = await waitForMessage(leaderB, (msg) => msg.type === "start");
        assert.equal(typeof start.seed, "string");
        leaderB.close();
    } finally {
        await stopServer(child);
    }
});

test("reconnect with changed playerId but same name can still start", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const first = await connectWs(`ws://127.0.0.1:${port}`);
        sendJson(first, {
            type: "create_room",
            playerName: "Leader",
            playerId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        });
        const created = await waitForMessage(first, (msg) => msg.type === "room_created");
        first.close();

        const second = await connectWs(`ws://127.0.0.1:${port}`);
        sendJson(second, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            countdown: 0,
        });

        const start = await waitForMessage(second, (msg) => msg.type === "start");
        assert.equal(typeof start.seed, "string");
        second.close();
    } finally {
        await stopServer(child);
    }
});

test("start_request is rejected while any room player is in world", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const leader = await connectWs(`ws://127.0.0.1:${port}`);
        const guest = await connectWs(`ws://127.0.0.1:${port}`);
        const leaderId = "24242424-2424-2424-2424-242424242424";
        const guestId = "25252525-2525-2525-2525-252525252525";

        sendJson(leader, { type: "create_room", playerName: "Leader", playerId: leaderId });
        const created = await waitForMessage(leader, (msg) => msg.type === "room_created");

        sendJson(guest, { type: "join_room", roomCode: created.roomCode, playerName: "Guest", playerId: guestId });
        await waitForMessage(guest, (msg) => msg.type === "room_joined");

        sendJson(guest, {
            type: "player_world_state",
            roomCode: created.roomCode,
            playerName: "Guest",
            playerId: guestId,
            inWorld: true,
        });

        sendJson(leader, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId: leaderId,
            countdown: 0,
        });

        const err = await waitForMessage(leader, (msg) => msg.type === "error");
        assert.match(err.message, /Players still in game world/);
        leader.close();
        guest.close();
    } finally {
        await stopServer(child);
    }
});

test("running room auto-resets to lobby after all players report inWorld=false", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws = await connectWs(`ws://127.0.0.1:${port}`);
        const playerId = "26262626-2626-2626-2626-262626262626";

        sendJson(ws, { type: "create_room", playerName: "Solo", playerId });
        const created = await waitForMessage(ws, (msg) => msg.type === "room_created");

        sendJson(ws, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Solo",
            playerId,
            countdown: 0,
        });
        await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "running"
        );

        sendJson(ws, {
            type: "player_world_state",
            roomCode: created.roomCode,
            playerName: "Solo",
            playerId,
            inWorld: true,
        });
        sendJson(ws, {
            type: "player_world_state",
            roomCode: created.roomCode,
            playerName: "Solo",
            playerId,
            inWorld: false,
        });

        const lobby = await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "lobby"
        );
        assert.equal(lobby.state, "lobby");
        ws.close();
    } finally {
        await stopServer(child);
    }
});

test("old start timer cannot force running after cancel and new start", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws = await connectWs(`ws://127.0.0.1:${port}`);
        const playerId = "27272727-2727-2727-2727-272727272727";

        sendJson(ws, { type: "create_room", playerName: "Leader", playerId });
        const created = await waitForMessage(ws, (msg) => msg.type === "room_created");

        sendJson(ws, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId,
            countdown: 1,
        });
        await waitForMessage(ws, (msg) => msg.type === "start");

        sendJson(ws, {
            type: "cancel_start",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId,
        });
        await waitForMessage(ws, (msg) => msg.type === "start_cancelled");
        await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "lobby"
        );

        sendJson(ws, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId,
            countdown: 3,
        });
        await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "starting"
        );

        // If old timer leaks, room would become running around +1s here. It must stay starting.
        await waitForNoMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "running",
            1500
        );

        ws.close();
    } finally {
        await stopServer(child);
    }
});

test("abort_start alias cancels countdown", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const leader = await connectWs(`ws://127.0.0.1:${port}`);
        const guest = await connectWs(`ws://127.0.0.1:${port}`);
        const leaderId = "22222222-aaaa-bbbb-cccc-222222222222";
        const guestId = "23232323-2323-2323-2323-232323232323";

        sendJson(leader, { type: "create_room", playerName: "Leader", playerId: leaderId });
        const created = await waitForMessage(leader, (msg) => msg.type === "room_created");
        sendJson(guest, { type: "join_room", roomCode: created.roomCode, playerName: "Guest", playerId: guestId });
        await waitForMessage(guest, (msg) => msg.type === "room_joined");

        sendJson(leader, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId: leaderId,
            countdown: 30,
        });
        await waitForMessage(guest, (msg) => msg.type === "start");

        sendJson(leader, {
            type: "abort_start",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId: leaderId,
        });

        await waitForMessage(guest, (msg) => msg.type === "start_cancelled");
        const update = await waitForMessage(guest, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "lobby"
        );
        assert.equal(update.state, "lobby");
        leader.close();
        guest.close();
    } finally {
        await stopServer(child);
    }
});

test("stop_start from running resets room to lobby", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws = await connectWs(`ws://127.0.0.1:${port}`);
        const playerId = "30303030-3030-3030-3030-303030303030";

        sendJson(ws, { type: "create_room", playerName: "Leader", playerId });
        const created = await waitForMessage(ws, (msg) => msg.type === "room_created");

        sendJson(ws, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId,
            countdown: 0,
        });
        await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "running"
        );

        sendJson(ws, {
            type: "stop_start",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId,
        });

        await waitForMessage(ws, (msg) => msg.type === "start_cancelled");
        const lobby = await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "lobby"
        );
        assert.equal(lobby.state, "lobby");
        ws.close();
    } finally {
        await stopServer(child);
    }
});

test("can start again in same room after stop_start from running", async () => {
    const port = await getFreePort();
    const { child } = await startServer(port);
    try {
        const ws = await connectWs(`ws://127.0.0.1:${port}`);
        const playerId = "31313131-3131-3131-3131-313131313131";

        sendJson(ws, { type: "create_room", playerName: "Leader", playerId });
        const created = await waitForMessage(ws, (msg) => msg.type === "room_created");

        sendJson(ws, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId,
            countdown: 0,
        });
        await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "running"
        );

        sendJson(ws, {
            type: "stop_start",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId,
        });
        await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "lobby"
        );

        sendJson(ws, {
            type: "start_request",
            roomCode: created.roomCode,
            playerName: "Leader",
            playerId,
            countdown: 0,
        });
        const runningAgain = await waitForMessage(ws, (msg) =>
            msg.type === "room_update" && msg.roomCode === created.roomCode && msg.state === "running"
        );
        assert.equal(runningAgain.state, "running");
        ws.close();
    } finally {
        await stopServer(child);
    }
});
