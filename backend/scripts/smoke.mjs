#!/usr/bin/env node
/**
 * End-to-end smoke test for the Family Connect Worker.
 *
 * Requires a locally running worker with migrations applied:
 *   npm run db:migrate:local
 *   npm run dev
 *   node scripts/smoke.mjs
 *
 * Covers: registration, login, pairing (valid + replay + wrong code), device
 * isolation between two parents, config sync, command delivery/ack, location
 * upload + retention view, safe zones, call creation, and WebSocket signalling
 * between the two call roles.
 */
const BASE = process.env.BASE_URL ?? "http://127.0.0.1:8787";
const RUN_IP = `10.77.${Math.floor(Math.random() * 250)}.${Math.floor(Math.random() * 250)}`;

let failures = 0;
let checks = 0;

function check(name, condition, detail = "") {
  checks += 1;
  if (condition) {
    console.log(`  ok   ${name}`);
  } else {
    failures += 1;
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ""}`);
  }
}

async function call(method, path, { token, body, expect } = {}) {
  // A unique client IP per run keeps the server-side rate limits meaningful
  // without tripping them during repeated local test runs.
  const headers = { Accept: "application/json", "CF-Connecting-IP": RUN_IP };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  try {
    json = text.length > 0 ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (expect !== undefined && response.status !== expect) {
    throw new Error(`${method} ${path} expected ${expect}, got ${response.status}: ${text}`);
  }
  return { status: response.status, json, text };
}

function uniqueEmail(prefix) {
  return `${prefix}.${Date.now()}.${Math.floor(Math.random() * 1000)}@example.com`;
}

async function openSignal(url, role) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    const messages = [];
    const timer = setTimeout(() => reject(new Error("socket open timeout")), 8000);
    socket.addEventListener("open", () => {
      clearTimeout(timer);
      resolve({ socket, messages });
    });
    socket.addEventListener("message", (event) => messages.push(JSON.parse(event.data)));
    socket.addEventListener("error", () => {
      clearTimeout(timer);
      reject(new Error("socket error"));
    });
  });
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function main() {
  console.log(`Family Connect smoke test against ${BASE}`);

  // -------- health / auth --------
  const email = uniqueEmail("parent");
  const register = await call("POST", "/api/v1/auth/register", {
    body: { email, password: "sup3r-secret-pass", name: "Test Daddy" },
    expect: 201,
  });
  check("parent can register", typeof register.json?.token === "string");
  const parentToken = register.json.token;

  const login = await call("POST", "/api/v1/auth/login", {
    body: { email, password: "sup3r-secret-pass" },
    expect: 200,
  });
  check("parent can sign in", typeof login.json?.token === "string");

  const wrongPassword = await call("POST", "/api/v1/auth/login", {
    body: { email, password: "not-the-password" },
  });
  check("wrong password is rejected", wrongPassword.status === 401);

  // -------- pairing --------
  const codeRes = await call("POST", "/api/v1/pair/code", { token: parentToken, expect: 200 });
  const code = codeRes.json?.code;
  check("pairing code is six digits", typeof code === "string" && /^\d{6}$/.test(code));

  const badClaim = await call("POST", "/api/v1/pair/claim", {
    body: { code: "000000", deviceName: "Bad", platform: "android", appVersion: "1.0" },
  });
  check("wrong pairing code is rejected", badClaim.status === 404);

  const claim = await call("POST", "/api/v1/pair/claim", {
    body: { code, deviceName: "Test Child Phone", platform: "android", appVersion: "1.0.0" },
    expect: 200,
  });
  check("device claims the code", typeof claim.json?.deviceToken === "string");
  const deviceToken = claim.json.deviceToken;
  const deviceId = claim.json.deviceId;
  check("claim returns a default config", claim.json?.config?.nickname === "Little Star");

  const replay = await call("POST", "/api/v1/pair/claim", {
    body: { code, deviceName: "Replay", platform: "android", appVersion: "1.0.0" },
  });
  check("pairing code cannot be reused", replay.status === 410);

  // -------- isolation --------
  const otherEmail = uniqueEmail("intruder");
  const other = await call("POST", "/api/v1/auth/register", {
    body: { email: otherEmail, password: "another-secret-pass", name: "Other Parent" },
    expect: 201,
  });
  const otherToken = other.json.token;
  const foreign = await call("GET", `/api/v1/devices/${deviceId}`, { token: otherToken });
  check("another parent cannot read the device", foreign.status === 404);

  const deviceOnParentRoute = await call("GET", "/api/v1/devices", { token: deviceToken });
  check("device token cannot use parent routes", deviceOnParentRoute.status === 403);

  const parentOnDeviceRoute = await call("GET", "/api/v1/device/me", { token: parentToken });
  check("parent token cannot use device routes", parentOnDeviceRoute.status === 403);

  const forged = await call("GET", "/api/v1/device/me", { token: "dt_not-a-real-token" });
  check("forged device token is rejected", forged.status === 401);

  // -------- config sync --------
  const patch = await call("PATCH", `/api/v1/devices/${deviceId}/config`, {
    token: parentToken,
    body: {
      config: {
        nickname: "Ammu",
        parentName: "Daddy",
        learningUrl: "https://learning.example.com/lessons",
        playUrl: "",
        theme: "ocean",
        enabledTiles: ["learning", "call", "camera"],
        autoAnswerVoice: false,
        scheduleStart: "20:00",
        scheduleEnd: "07:00",
      },
    },
    expect: 200,
  });
  check("parent config is stored", patch.json?.nickname === "Ammu" && patch.json?.theme === "ocean");

  const insecure = await call("PATCH", `/api/v1/devices/${deviceId}/config`, {
    token: parentToken,
    body: { config: { learningUrl: "http://plain.example.com" } },
  });
  check("http learning URL is rejected", insecure.status === 400);

  const snapshot = await call("GET", "/api/v1/device/me", { token: deviceToken, expect: 200 });
  check("device sees its config", snapshot.json?.config?.learningUrl === "https://learning.example.com/lessons");
  check("device sees enabled tiles", Array.isArray(snapshot.json?.config?.enabledTiles));

  const deviceEdit = await call("PATCH", "/api/v1/device/config", {
    token: deviceToken,
    body: { config: { nickname: "Ammu Kutty" } },
    expect: 200,
  });
  check("device-side config edit is accepted", deviceEdit.json?.nickname === "Ammu Kutty");
  check("device edit keeps parent URL", deviceEdit.json?.learningUrl === "https://learning.example.com/lessons");

  // -------- commands --------
  const locate = await call("POST", `/api/v1/devices/${deviceId}/commands`, {
    token: parentToken,
    body: { type: "LOCATE", payloadJson: "{}" },
    expect: 202,
  });
  check("parent can queue a LOCATE", typeof locate.json?.commandId === "string");

  const commands = await call("GET", "/api/v1/device/commands", { token: deviceToken, expect: 200 });
  const locateCommand = (commands.json?.commands ?? []).find((c) => c.type === "LOCATE");
  check("device receives the LOCATE command", Boolean(locateCommand));

  const ack = await call("POST", `/api/v1/device/commands/${locateCommand?.id}/ack`, {
    token: deviceToken,
    body: { result: "ok" },
    expect: 204,
  });
  check("device acknowledges the command", ack.status === 204);

  const afterAck = await call("GET", "/api/v1/device/commands", { token: deviceToken });
  check(
    "acknowledged command is not repeated",
    (afterAck.json?.commands ?? []).every((c) => c.id !== locateCommand?.id),
  );

  const forbiddenCommand = await call("POST", `/api/v1/devices/${deviceId}/commands`, {
    token: parentToken,
    body: { type: "WIPE_DEVICE" },
  });
  check("unknown command is rejected", forbiddenCommand.status === 400);

  // -------- status + location --------
  await call("POST", "/api/v1/device/status", {
    token: deviceToken,
    body: {
      batteryPercent: 72,
      charging: false,
      appVersion: "1.0.0",
      webViewVersion: "120.0",
      androidVersion: "Android 12 (API 31)",
      deviceModel: "Test Phone",
      autoAnswerSupported: true,
    },
    expect: 204,
  });

  const now = new Date().toISOString();
  await call("POST", "/api/v1/device/location", {
    token: deviceToken,
    body: {
      lat: 9.9312,
      lng: 76.2673,
      accuracyMeters: 12.5,
      capturedAt: now,
      provider: "fused",
      batteryPercent: 72,
      isMock: false,
    },
    expect: 204,
  });

  const badLocation = await call("POST", "/api/v1/device/location", {
    token: deviceToken,
    body: { lat: 999, lng: 0, accuracyMeters: 5, capturedAt: now },
  });
  check("invalid latitude is rejected", badLocation.status === 400);

  const deviceAfter = await call("GET", `/api/v1/devices/${deviceId}`, {
    token: parentToken,
    expect: 200,
  });
  check("parent sees battery", deviceAfter.json?.batteryPercent === 72);
  check("parent sees online state", deviceAfter.json?.online === true);
  check("parent sees the location fix", Math.abs(deviceAfter.json?.lastLocation?.lat - 9.9312) < 0.0001);
  check(
    "parent sees accuracy and timestamp",
    deviceAfter.json?.lastLocation?.accuracyMeters === 12.5 &&
      deviceAfter.json?.lastLocation?.capturedAt === now,
  );

  const history = await call("GET", `/api/v1/devices/${deviceId}/locations?limit=5`, {
    token: parentToken,
    expect: 200,
  });
  check("location history is returned", Array.isArray(history.json) && history.json.length === 1);

  // -------- safe zones --------
  const zone = await call("POST", `/api/v1/devices/${deviceId}/safezones`, {
    token: parentToken,
    body: { name: "Home", lat: 9.9312, lng: 76.2673, radiusMeters: 200 },
    expect: 201,
  });
  check("safe zone is created", typeof zone.json?.id === "string");

  const zones = await call("GET", `/api/v1/devices/${deviceId}/safezones`, {
    token: parentToken,
    expect: 200,
  });
  check("safe zone is listed", zones.json?.length === 1);

  const zoneIsolation = await call("POST", `/api/v1/devices/${deviceId}/safezones`, {
    token: otherToken,
    body: { name: "Sneaky", lat: 0, lng: 0, radiusMeters: 100 },
  });
  check("another parent cannot add zones", zoneIsolation.status === 404);

  // -------- calls + signalling --------
  const startCall = await call("POST", `/api/v1/devices/${deviceId}/call`, {
    token: parentToken,
    body: { media: "audio" },
    expect: 200,
  });
  const callId = startCall.json?.callId;
  check("parent can start a call", typeof callId === "string");

  const callCommands = await call("GET", "/api/v1/device/commands", { token: deviceToken });
  const callCommand = (callCommands.json?.commands ?? []).find((c) => c.type === "CALL");
  check("device receives a CALL command", Boolean(callCommand));
  check(
    "call command carries the call id",
    callCommand ? JSON.parse(callCommand.payloadJson).callId === callId : false,
  );

  const childTicket = await call("POST", `/api/v1/device/call/${callId}/join`, {
    token: deviceToken,
    expect: 200,
  });
  check("child receives a signalling ticket", typeof childTicket.json?.websocketUrl === "string");
  check("child ticket has the device role", childTicket.json?.role === "device");
  check("ticket includes ICE configuration", Array.isArray(childTicket.json?.iceServers));

  const parentTicket = await call("POST", `/api/v1/parent/calls/${callId}/join`, {
    token: parentToken,
    expect: 200,
  });
  check("parent receives a signalling ticket", parentTicket.json?.role === "parent");

  const foreignTicket = await call("POST", `/api/v1/parent/calls/${callId}/join`, {
    token: otherToken,
  });
  check("another parent cannot join the call", foreignTicket.status === 404);

  if (childTicket.json?.mode === "live") {
    try {
      const child = await openSignal(childTicket.json.websocketUrl);
      const parent = await openSignal(parentTicket.json.websocketUrl);
      child.socket.send(JSON.stringify({ type: "offer", sdp: "v=0-test-offer" }));
      await wait(400);
      const received = parent.messages.find((m) => m.type === "offer");
      check("signalling relays the offer to the parent", received?.sdp === "v=0-test-offer");

      parent.socket.send(JSON.stringify({ type: "answer", sdp: "v=0-test-answer" }));
      await wait(400);
      check(
        "signalling relays the answer to the child",
        child.messages.some((m) => m.type === "answer"),
      );

      child.socket.close();
      parent.socket.close();
      await wait(250);
    } catch (error) {
      check("signalling sockets", false, String(error));
    }
  } else {
    console.log("  note  calls disabled on this deployment (simulated mode)");
  }

  const pending = await call("GET", "/api/v1/parent/calls", { token: parentToken, expect: 200 });
  check("pending calls endpoint responds", Array.isArray(pending.json));
  check(
    "answered call is no longer ringing",
    (pending.json ?? []).every((c) => c.callId !== callId),
  );

  const decline = await call("POST", `/api/v1/parent/calls/${callId}/decline`, {
    token: parentToken,
    expect: 204,
  });
  check("call can be declined", decline.status === 204);

  // -------- child-initiated call --------
  const childCall = await call("POST", "/api/v1/device/call", {
    token: deviceToken,
    body: { media: "audio" },
    expect: 200,
  });
  check("child can start a call", typeof childCall.json?.callId === "string");
  const parentPending = await call("GET", "/api/v1/parent/calls", { token: parentToken });
  check(
    "parent sees the child's ringing call",
    (parentPending.json ?? []).some((c) => c.callId === childCall.json.callId),
  );

  console.log(`\n${checks - failures}/${checks} checks passed`);
  if (failures > 0) {
    console.error(`${failures} check(s) failed`);
    process.exit(1);
  }
}

main().catch((error) => {
  console.error("Smoke test crashed:", error);
  process.exit(1);
});
