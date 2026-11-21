import {
  bearerToken,
  errorResponse,
  hashPassword,
  jsonResponse,
  randomToken,
  sha256Hex,
  signToken,
  verifyPassword,
  verifyToken,
  type Role,
} from "./auth";
import {
  CALL_RING_TTL_SECONDS,
  LOCATION_RETENTION_DAYS,
  PAIR_CODE_TTL_MINUTES,
  allowRequest,
  audit,
  configFor,
  defaultConfig,
  iceServers,
  isHttpsUrl,
  isValidEmail,
  isValidIsoTimestamp,
  minutesFromNow,
  nowIso,
  randomId,
  sanitiseConfig,
  saveConfig,
  type CallRow,
  type CommandRow,
  type ConfigRow,
  type DeviceRow,
  type Env,
  type LocationRow,
  type ParentRow,
  type ZoneRow,
} from "./db";
export { CallRoom } from "./callroom";

interface Auth {
  parentId: string;
  role: Role;
  deviceId?: string;
}

type AuthResult = { ok: true; auth: Auth } | { ok: false; response: Response };

const JSON_HEADERS = { "Content-Type": "application/json; charset=utf-8" };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "");
    const method = request.method.toUpperCase();

    try {
      if (!path.startsWith("/api/v1")) {
        return errorResponse("Not found", 404);
      }
      const parts = path.slice("/api/v1".length).split("/").filter((p) => p.length > 0);

      // ---------------- auth ----------------
      if (parts[0] === "auth" && parts[1] === "register" && method === "POST") {
        return await handleRegister(request, env);
      }
      if (parts[0] === "auth" && parts[1] === "login" && method === "POST") {
        return await handleLogin(request, env);
      }

      // ---------------- pairing ----------------
      if (parts[0] === "pair" && parts[1] === "code" && method === "POST") {
        const auth = await authenticate(request, env, "parent");
        if (!auth.ok) return auth.response;
        return await handleCreatePairCode(env, auth.auth);
      }
      if (parts[0] === "pair" && parts[1] === "claim" && method === "POST") {
        return await handleClaim(request, env);
      }

      // ---------------- parent device management ----------------
      if (parts[0] === "devices" && parts.length >= 2) {
        const auth = await authenticate(request, env, "parent");
        if (!auth.ok) return auth.response;
        const deviceId = parts[1] ?? "";
        const device = await loadOwnedDevice(env, auth.auth.parentId, deviceId);
        if (!device) return errorResponse("Device not found", 404);

        if (parts.length === 2 && method === "GET") {
          return await handleDeviceSnapshot(env, auth.auth.parentId, device);
        }
        if (parts.length === 3 && parts[2] === "config" && method === "PATCH") {
          return await handleParentConfigUpdate(request, env, auth.auth.parentId, device);
        }
        if (parts.length === 3 && parts[2] === "commands" && method === "POST") {
          return await handleParentCommand(request, env, auth.auth.parentId, device);
        }
        if (parts.length === 3 && parts[2] === "locations" && method === "GET") {
          return await handleLocations(env, device, url);
        }
        if (parts.length === 3 && parts[2] === "safezones" && method === "GET") {
          return await handleListZones(env, device);
        }
        if (parts.length === 3 && parts[2] === "safezones" && method === "POST") {
          return await handleAddZone(request, env, auth.auth.parentId, device);
        }
        if (parts.length === 4 && parts[2] === "safezones" && method === "DELETE") {
          return await handleDeleteZone(env, auth.auth.parentId, device, parts[3] ?? "");
        }
        if (parts.length === 3 && parts[2] === "call" && method === "POST") {
          return await handleParentStartCall(request, env, auth.auth.parentId, device);
        }
        if (parts.length === 4 && parts[2] === "call" && method === "DELETE") {
          return await handleEndCall(env, auth.auth.parentId, device, parts[3] ?? "");
        }
      }

      if (parts[0] === "devices" && parts.length === 1 && method === "GET") {
        const auth = await authenticate(request, env, "parent");
        if (!auth.ok) return auth.response;
        return await handleListDevices(env, auth.auth.parentId);
      }

      // ---------------- parent calls ----------------
      if (parts[0] === "parent" && parts[1] === "calls") {
        const auth = await authenticate(request, env, "parent");
        if (!auth.ok) return auth.response;
        if (parts.length === 2 && method === "GET") {
          return await handlePendingCalls(env, auth.auth.parentId);
        }
        if (parts.length === 4 && parts[2] && parts[3] === "join" && method === "POST") {
          return await handleJoinCall(request, env, auth.auth.parentId, parts[2], "parent");
        }
        if (parts.length === 4 && parts[2] && parts[3] === "decline" && method === "POST") {
          return await handleDeclineCall(env, auth.auth.parentId, parts[2], "parent");
        }
      }

      // ---------------- child device endpoints ----------------
      if (parts[0] === "device" && parts.length >= 2) {
        const auth = await authenticate(request, env, "device");
        if (!auth.ok) return auth.response;
        const deviceId = auth.auth.deviceId ?? "";
        const device = await loadDeviceById(env, deviceId);
        if (!device) return errorResponse("Device is no longer paired", 401);

        if (parts[1] === "me" && method === "GET") {
          return await handleDeviceSnapshot(env, device.parent_id, device);
        }
        if (parts[1] === "config" && method === "GET") {
          return jsonResponse(await configFor(env, device.id));
        }
        if (parts[1] === "config" && method === "PATCH") {
          return await handleDeviceConfigUpdate(request, env, device);
        }
        if (parts[1] === "status" && method === "POST") {
          return await handleStatus(request, env, device);
        }
        if (parts[1] === "commands" && parts.length === 2 && method === "GET") {
          return await handleDeviceCommands(env, device, url);
        }
        if (parts[1] === "commands" && parts.length === 4 && parts[3] === "ack" && method === "POST") {
          return await handleAck(request, env, device, parts[2] ?? "");
        }
        if (parts[1] === "events" && method === "POST") {
          return await handleDeviceEvent(request, env, device);
        }
        if (parts[1] === "location" && method === "POST") {
          return await handleLocationUpload(request, env, device);
        }
        if (parts[1] === "call" && parts.length === 2 && method === "POST") {
          return await handleChildStartCall(request, env, device);
        }
        if (parts[1] === "call" && parts.length === 4 && parts[3] === "join" && method === "POST") {
          return await handleJoinCall(request, env, device.parent_id, parts[2] ?? "", "device");
        }
        if (parts[1] === "call" && parts.length === 4 && parts[3] === "decline" && method === "POST") {
          return await handleDeclineCall(env, device.parent_id, parts[2] ?? "", "device");
        }
      }

      // ---------------- signalling ----------------
      if (parts[0] === "signal" && parts.length === 2) {
        return await handleSignal(request, env, parts[1] ?? "");
      }

      return errorResponse("Not found", 404);
    } catch (error) {
      console.error("Unhandled error", error instanceof Error ? error.message : "unknown");
      return errorResponse("The family service hit an unexpected error.", 500);
    }
  },
};

/* ------------------------------------------------------------------ */
/* authentication                                                      */
/* ------------------------------------------------------------------ */

function secret(env: Env): string {
  const value = env.SESSION_SECRET;
  if (!value || value.length < 24) {
    throw new Error("SESSION_SECRET is missing or too short");
  }
  return value;
}

async function authenticate(request: Request, env: Env, role: Role): Promise<AuthResult> {
  const token = bearerToken(request);
  if (!token) return { ok: false, response: errorResponse("Sign in required", 401) };

  // Signed session tokens (parents, and short-lived call tickets).
  const claims = await verifyToken(secret(env), token);
  if (claims) {
    if (claims.role !== role) {
      return { ok: false, response: errorResponse("Wrong account type for this action", 403) };
    }
    return { ok: true, auth: { parentId: claims.sub, role } };
  }

  // Device tokens are opaque random secrets: only a SHA-256 hash is stored, so a
  // database leak cannot be replayed against the API.
  if (role === "device" && token.startsWith("dt_")) {
    const hash = await sha256Hex(token);
    const device = await env.DB.prepare(
      "SELECT id, parent_id, name FROM devices WHERE token_hash = ?",
    )
      .bind(hash)
      .first<{ id: string; parent_id: string; name: string }>();
    if (!device) {
      return { ok: false, response: errorResponse("This phone is no longer paired.", 401) };
    }
    return {
      ok: true,
      auth: { parentId: device.parent_id, role, deviceId: device.id },
    };
  }

  // A device credential presented on a parent-only route is valid but not allowed.
  if (role === "parent" && token.startsWith("dt_")) {
    const hash = await sha256Hex(token);
    const device = await env.DB.prepare("SELECT id FROM devices WHERE token_hash = ?")
      .bind(hash)
      .first<{ id: string }>();
    if (device) {
      return { ok: false, response: errorResponse("Wrong account type for this action", 403) };
    }
  }

  return { ok: false, response: errorResponse("Session expired", 401) };
}

async function readJson<T>(request: Request): Promise<T | null> {
  try {
    const text = await request.text();
    if (text.length > 200_000) return null;
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}

async function clientKey(request: Request): Promise<string> {
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  return ip;
}

async function handleRegister(request: Request, env: Env): Promise<Response> {
  if ((env.ALLOW_REGISTRATION ?? "false") !== "true") {
    return errorResponse("Account creation is disabled on this family service.", 403);
  }
  if (!(await allowRequest(env, `register:${await clientKey(request)}`, 5, 3600))) {
    return errorResponse("Too many attempts. Try again later.", 429);
  }
  const body = await readJson<{ email?: string; password?: string; name?: string }>(request);
  if (!body) return errorResponse("Invalid request body", 400);
  const email = (body.email ?? "").trim().toLowerCase();
  const password = body.password ?? "";
  const name = (body.name ?? "").trim().slice(0, 60);
  if (!isValidEmail(email)) return errorResponse("Enter a valid email address.", 400);
  if (password.length < 8 || password.length > 200) {
    return errorResponse("Use a password of at least 8 characters.", 400);
  }
  const existing = await env.DB.prepare("SELECT id FROM parents WHERE email = ?")
    .bind(email)
    .first<{ id: string }>();
  if (existing) return errorResponse("An account with that email already exists.", 409);
  const id = randomId("par");
  await env.DB.prepare(
    "INSERT INTO parents (id, email, name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
  )
    .bind(id, email, name || "Parent", await hashPassword(password), nowIso())
    .run();
  const token = await signToken(secret(env), {
    sub: id,
    role: "parent",
    exp: Math.floor(Date.now() / 1000) + 60 * 60 * 12,
  });
  return jsonResponse(
    {
      token,
      expiresAt: new Date(Date.now() + 12 * 3600_000).toISOString(),
      parent: { id, name: name || "Parent", email },
    },
    201,
  );
}

async function handleLogin(request: Request, env: Env): Promise<Response> {
  const key = `login:${await clientKey(request)}`;
  if (!(await allowRequest(env, key, 12, 900))) {
    return errorResponse("Too many attempts. Wait a few minutes.", 429);
  }
  const body = await readJson<{ email?: string; password?: string }>(request);
  if (!body) return errorResponse("Invalid request body", 400);
  const email = (body.email ?? "").trim().toLowerCase();
  const password = body.password ?? "";
  const parent = await env.DB.prepare("SELECT * FROM parents WHERE email = ?")
    .bind(email)
    .first<ParentRow>();
  // Always run the password check to keep timing roughly constant.
  const stored = parent?.password_hash ?? "pbkdf2-sha256$210000$AAAAAAAAAAAAAAAAAAAAAA$AAAA";
  const matches = await verifyPassword(password, stored);
  if (!parent || !matches) return errorResponse("Email or password is not correct.", 401);
  if (!(await allowRequest(env, `login-acct:${parent.id}`, 20, 900))) {
    return errorResponse("Too many attempts. Wait a few minutes.", 429);
  }
  const token = await signToken(secret(env), {
    sub: parent.id,
    role: "parent",
    exp: Math.floor(Date.now() / 1000) + 60 * 60 * 12,
  });
  await audit(env, parent.id, null, "login");
  return jsonResponse({
    token,
    expiresAt: new Date(Date.now() + 12 * 3600_000).toISOString(),
    parent: { id: parent.id, name: parent.name, email: parent.email },
  });
}

/* ------------------------------------------------------------------ */
/* pairing                                                             */
/* ------------------------------------------------------------------ */

async function handleCreatePairCode(env: Env, auth: Auth): Promise<Response> {
  if (!(await allowRequest(env, `pair:${auth.parentId}`, 10, 3600))) {
    return errorResponse("Too many pairing codes. Try again later.", 429);
  }
  const code = String(Math.floor(100000 + Math.random() * 900000));
  const expiresAt = minutesFromNow(PAIR_CODE_TTL_MINUTES);
  await env.DB.prepare(
    "INSERT OR REPLACE INTO pair_codes (code, parent_id, created_at, expires_at, used_at) VALUES (?, ?, ?, ?, NULL)",
  )
    .bind(code, auth.parentId, nowIso(), expiresAt)
    .run();
  await audit(env, auth.parentId, null, "pair_code_created");
  return jsonResponse({ code, expiresAt });
}

async function handleClaim(request: Request, env: Env): Promise<Response> {
  if (!(await allowRequest(env, `claim:${await clientKey(request)}`, 10, 900))) {
    return errorResponse("Too many attempts. Wait a few minutes.", 429);
  }
  const body = await readJson<{
    code?: string;
    deviceName?: string;
    platform?: string;
    appVersion?: string;
  }>(request);
  if (!body) return errorResponse("Invalid request body", 400);
  const code = (body.code ?? "").replace(/\D/g, "");
  if (code.length !== 6) return errorResponse("Enter the 6 digit pairing code.", 400);

  const row = await env.DB.prepare("SELECT * FROM pair_codes WHERE code = ?")
    .bind(code)
    .first<{ code: string; parent_id: string; expires_at: string; used_at: string | null }>();
  if (!row) return errorResponse("That pairing code is not valid.", 404);
  if (row.used_at) return errorResponse("That pairing code was already used.", 410);
  if (Date.parse(row.expires_at) < Date.now()) {
    return errorResponse("That pairing code has expired.", 410);
  }

  const parent = await env.DB.prepare("SELECT * FROM parents WHERE id = ?")
    .bind(row.parent_id)
    .first<ParentRow>();
  if (!parent) return errorResponse("The parent account no longer exists.", 410);

  const used = await env.DB.prepare(
    "UPDATE pair_codes SET used_at = ? WHERE code = ? AND used_at IS NULL",
  )
    .bind(nowIso(), code)
    .run();
  if ((used.meta.changes ?? 0) !== 1) {
    return errorResponse("That pairing code was already used.", 410);
  }

  const deviceId = randomId("dev");
  const deviceToken = `dt_${randomToken(32)}`;
  const name = (body.deviceName ?? "Child phone").trim().slice(0, 60) || "Child phone";
  await env.DB.prepare(
    `INSERT INTO devices (id, parent_id, name, platform, app_version, token_hash, paired_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(
      deviceId,
      parent.id,
      name,
      (body.platform ?? "android").slice(0, 20),
      (body.appVersion ?? "").slice(0, 40),
      await sha256Hex(deviceToken),
      nowIso(),
    )
    .run();
  const config = defaultConfig(parent.name);
  await saveConfig(env, deviceId, config);
  await audit(env, parent.id, deviceId, "device_paired");
  return jsonResponse({
    deviceId,
    deviceToken,
    parentName: parent.name,
    config,
  });
}

/* ------------------------------------------------------------------ */
/* devices                                                             */
/* ------------------------------------------------------------------ */

async function loadDeviceById(env: Env, deviceId: string): Promise<DeviceRow | null> {
  return await env.DB.prepare("SELECT * FROM devices WHERE id = ?")
    .bind(deviceId)
    .first<DeviceRow>();
}

async function loadOwnedDevice(
  env: Env,
  parentId: string,
  deviceId: string,
): Promise<DeviceRow | null> {
  return await env.DB.prepare("SELECT * FROM devices WHERE id = ? AND parent_id = ?")
    .bind(deviceId, parentId)
    .first<DeviceRow>();
}

async function handleListDevices(env: Env, parentId: string): Promise<Response> {
  const result = await env.DB.prepare(
    "SELECT * FROM devices WHERE parent_id = ? ORDER BY paired_at ASC",
  )
    .bind(parentId)
    .all<DeviceRow>();
  const devices = (result.results ?? []).map((row) => ({
    deviceId: row.id,
    name: row.name,
    online: row.online === 1 && isRecent(row.last_seen, 120_000),
    lastSeen: row.last_seen,
    batteryPercent: row.battery_percent,
    charging: row.charging === null ? null : row.charging === 1,
    appVersion: row.app_version,
    pairedAt: row.paired_at,
  }));
  return jsonResponse(devices);
}

function isRecent(iso: string | null, windowMs: number): boolean {
  if (!iso) return false;
  const value = Date.parse(iso);
  return Number.isFinite(value) && Date.now() - value < windowMs;
}

async function handleDeviceSnapshot(
  env: Env,
  parentId: string,
  device: DeviceRow,
): Promise<Response> {
  if (device.parent_id !== parentId) return errorResponse("Device not found", 404);
  const config = await configFor(env, device.id);
  const location = await env.DB.prepare(
    "SELECT * FROM locations WHERE device_id = ? ORDER BY captured_at DESC LIMIT 1",
  )
    .bind(device.id)
    .first<LocationRow>();
  const zones = await env.DB.prepare("SELECT * FROM safe_zones WHERE device_id = ?")
    .bind(device.id)
    .all<ZoneRow>();
  return jsonResponse({
    deviceId: device.id,
    name: device.name,
    config: { ...defaultConfig("Daddy"), ...config },
    online: device.online === 1 && isRecent(device.last_seen, 120_000),
    lastSeen: device.last_seen,
    batteryPercent: device.battery_percent,
    charging: device.charging === null ? null : device.charging === 1,
    appVersion: device.app_version,
    webViewVersion: device.webview_version,
    androidVersion: device.android_version,
    deviceModel: device.model,
    autoAnswerSupported: device.auto_answer_supported === 1,
    lastLocation: location ? locationJson(location) : null,
    safeZones: (zones.results ?? []).map(zoneJson),
  });
}

function locationJson(row: LocationRow) {
  return {
    lat: row.lat,
    lng: row.lng,
    accuracyMeters: row.accuracy_meters,
    capturedAt: row.captured_at,
    provider: row.provider,
    batteryPercent: row.battery_percent,
    isMock: row.is_mock === 1,
  };
}

function zoneJson(row: ZoneRow) {
  return {
    id: row.id,
    name: row.name,
    lat: row.lat,
    lng: row.lng,
    radiusMeters: row.radius_meters,
    notifyOnEntry: row.notify_on_entry === 1,
    notifyOnExit: row.notify_on_exit === 1,
    createdAt: row.created_at,
  };
}

async function handleParentConfigUpdate(
  request: Request,
  env: Env,
  parentId: string,
  device: DeviceRow,
): Promise<Response> {
  const body = await readJson<{ config?: unknown }>(request);
  if (!body || typeof body.config !== "object" || body.config === null) {
    return errorResponse("Expected a config object", 400);
  }
  const incoming = body.config as Record<string, unknown>;
  if (typeof incoming.learningUrl === "string" && !isHttpsUrl(incoming.learningUrl)) {
    return errorResponse("The learning website must be an https:// address.", 400);
  }
  if (typeof incoming.playUrl === "string" && !isHttpsUrl(incoming.playUrl)) {
    return errorResponse("The play website must be an https:// address.", 400);
  }
  const parent = await env.DB.prepare("SELECT * FROM parents WHERE id = ?")
    .bind(parentId)
    .first<ParentRow>();
  const config = sanitiseConfig(incoming, parent?.name ?? "Daddy");
  await saveConfig(env, device.id, config);
  await audit(env, parentId, device.id, "config_updated");
  // Nudge the child to pick the change up quickly.
  await enqueueCommand(env, device.id, "REFRESH_CONFIG", {});
  return jsonResponse(config);
}

async function handleDeviceConfigUpdate(
  request: Request,
  env: Env,
  device: DeviceRow,
): Promise<Response> {
  const body = await readJson<{ config?: unknown }>(request);
  if (!body || typeof body.config !== "object" || body.config === null) {
    return errorResponse("Expected a config object", 400);
  }
  const incoming = body.config as Record<string, unknown>;
  if (typeof incoming.learningUrl === "string" && !isHttpsUrl(incoming.learningUrl)) {
    return errorResponse("The learning website must be an https:// address.", 400);
  }
  const existing = await configFor(env, device.id);
  const merged = { ...existing, ...incoming };
  const config = sanitiseConfig(merged, device.name);
  await saveConfig(env, device.id, config);
  await audit(env, device.parent_id, device.id, "config_updated_on_device");
  return jsonResponse(config);
}

async function handleStatus(request: Request, env: Env, device: DeviceRow): Promise<Response> {
  const body = await readJson<{
    batteryPercent?: number;
    charging?: boolean;
    appVersion?: string;
    webViewVersion?: string;
    androidVersion?: string;
    deviceModel?: string;
    autoAnswerSupported?: boolean;
  }>(request);
  if (!body) return errorResponse("Invalid request body", 400);
  const battery = Math.max(0, Math.min(100, Math.round(body.batteryPercent ?? 0)));
  await env.DB.prepare(
    `UPDATE devices SET last_seen = ?, online = 1, battery_percent = ?, charging = ?,
       app_version = ?, webview_version = ?, android_version = ?, model = ?,
       auto_answer_supported = ? WHERE id = ?`,
  )
    .bind(
      nowIso(),
      battery,
      body.charging === true ? 1 : 0,
      (body.appVersion ?? "").slice(0, 40),
      (body.webViewVersion ?? "").slice(0, 40),
      (body.androidVersion ?? "").slice(0, 80),
      (body.deviceModel ?? "").slice(0, 80),
      body.autoAnswerSupported === true ? 1 : 0,
      device.id,
    )
    .run();
  return new Response(null, { status: 204 });
}

async function enqueueCommand(
  env: Env,
  deviceId: string,
  type: string,
  payload: Record<string, unknown>,
): Promise<string> {
  const id = randomId("cmd");
  await env.DB.prepare(
    "INSERT INTO commands (id, device_id, type, payload_json, created_at) VALUES (?, ?, ?, ?, ?)",
  )
    .bind(id, deviceId, type, JSON.stringify(payload), nowIso())
    .run();
  return id;
}

async function handleParentCommand(
  request: Request,
  env: Env,
  parentId: string,
  device: DeviceRow,
): Promise<Response> {
  if (!(await allowRequest(env, `cmd:${parentId}`, 240, 3600))) {
    return errorResponse("Too many commands. Please wait.", 429);
  }
  const body = await readJson<{ type?: string; payloadJson?: string }>(request);
  if (!body || !body.type) return errorResponse("Expected a command type", 400);
  const allowed = new Set(["LOCATE", "FIND_PHONE", "REFRESH_CONFIG", "STOP_RINGING", "CALL"]);
  if (!allowed.has(body.type)) return errorResponse("Unknown command", 400);
  let payload: Record<string, unknown> = {};
  if (body.payloadJson) {
    try {
      const parsed = JSON.parse(body.payloadJson) as unknown;
      if (typeof parsed === "object" && parsed !== null) payload = parsed as Record<string, unknown>;
    } catch {
      return errorResponse("Command payload is not valid JSON", 400);
    }
  }
  if (body.type === "CALL") {
    return errorResponse("Use the call endpoint to start a call", 400);
  }
  const id = await enqueueCommand(env, device.id, body.type, payload);
  await audit(env, parentId, device.id, `command_${body.type.toLowerCase()}`);
  return jsonResponse({ commandId: id }, 202);
}

async function handleDeviceCommands(env: Env, device: DeviceRow, url: URL): Promise<Response> {
  const since = url.searchParams.get("since");
  const rows = since
    ? await env.DB.prepare(
        "SELECT * FROM commands WHERE device_id = ? AND acked_at IS NULL AND created_at > ? ORDER BY created_at ASC LIMIT 50",
      )
        .bind(device.id, since)
        .all<CommandRow>()
    : await env.DB.prepare(
        "SELECT * FROM commands WHERE device_id = ? AND acked_at IS NULL ORDER BY created_at ASC LIMIT 50",
      )
        .bind(device.id)
        .all<CommandRow>();
  const commands = (rows.results ?? []).map((row) => ({
    id: row.id,
    type: row.type,
    payloadJson: row.payload_json,
    createdAt: row.created_at,
  }));
  return jsonResponse({ commands });
}

async function handleAck(
  request: Request,
  env: Env,
  device: DeviceRow,
  commandId: string,
): Promise<Response> {
  const body = await readJson<{ result?: string }>(request);
  await env.DB.prepare(
    "UPDATE commands SET acked_at = ?, result = ? WHERE id = ? AND device_id = ?",
  )
    .bind(nowIso(), (body?.result ?? "ok").slice(0, 200), commandId, device.id)
    .run();
  return new Response(null, { status: 204 });
}

async function handleDeviceEvent(request: Request, env: Env, device: DeviceRow): Promise<Response> {
  const body = await readJson<{ type?: string; payload?: Record<string, unknown> }>(request);
  if (!body || !body.type) return errorResponse("Expected an event type", 400);
  const allowed = new Set(["geofence", "location_disabled", "webview_error", "battery_low", "call_event"]);
  if (!allowed.has(body.type)) return errorResponse("Unknown event", 400);
  await audit(env, device.parent_id, device.id, `event_${body.type}`, JSON.stringify(body.payload ?? {}));
  return new Response(null, { status: 204 });
}

/* ------------------------------------------------------------------ */
/* location                                                            */
/* ------------------------------------------------------------------ */

async function handleLocationUpload(request: Request, env: Env, device: DeviceRow): Promise<Response> {
  const body = await readJson<{
    lat?: number;
    lng?: number;
    accuracyMeters?: number;
    capturedAt?: string;
    provider?: string;
    batteryPercent?: number;
    isMock?: boolean;
  }>(request);
  if (!body) return errorResponse("Invalid request body", 400);
  const lat = body.lat;
  const lng = body.lng;
  const accuracy = body.accuracyMeters;
  if (typeof lat !== "number" || lat < -90 || lat > 90) return errorResponse("Invalid latitude", 400);
  if (typeof lng !== "number" || lng < -180 || lng > 180) return errorResponse("Invalid longitude", 400);
  if (typeof accuracy !== "number" || accuracy < 0 || accuracy > 5000) {
    return errorResponse("Invalid accuracy", 400);
  }
  if (!isValidIsoTimestamp(body.capturedAt)) return errorResponse("Invalid timestamp", 400);
  // A mocked fix is recorded but flagged so the parent never sees it as trusted.
  const isMock = body.isMock === true;
  await env.DB.prepare(
    `INSERT INTO locations (id, device_id, lat, lng, accuracy_meters, captured_at, provider,
       battery_percent, is_mock, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(
      randomId("loc"),
      device.id,
      lat,
      lng,
      accuracy,
      body.capturedAt,
      (body.provider ?? "fused").slice(0, 30),
      typeof body.batteryPercent === "number" ? body.batteryPercent : null,
      isMock ? 1 : 0,
      nowIso(),
    )
    .run();
  // Retention: keep a bounded history per device.
  const cutoff = new Date(Date.now() - LOCATION_RETENTION_DAYS * 86_400_000).toISOString();
  await env.DB.prepare("DELETE FROM locations WHERE device_id = ? AND created_at < ?")
    .bind(device.id, cutoff)
    .run();
  await env.DB.prepare("UPDATE devices SET last_seen = ?, online = 1 WHERE id = ?")
    .bind(nowIso(), device.id)
    .run();
  return new Response(null, { status: 204 });
}

async function handleLocations(env: Env, device: DeviceRow, url: URL): Promise<Response> {
  const limitParam = Number.parseInt(url.searchParams.get("limit") ?? "20", 10);
  const limit = Number.isFinite(limitParam) ? Math.max(1, Math.min(100, limitParam)) : 20;
  const rows = await env.DB.prepare(
    "SELECT * FROM locations WHERE device_id = ? ORDER BY captured_at DESC LIMIT ?",
  )
    .bind(device.id, limit)
    .all<LocationRow>();
  return jsonResponse((rows.results ?? []).map(locationJson));
}

/* ------------------------------------------------------------------ */
/* safe zones                                                          */
/* ------------------------------------------------------------------ */

async function handleListZones(env: Env, device: DeviceRow): Promise<Response> {
  const rows = await env.DB.prepare("SELECT * FROM safe_zones WHERE device_id = ? ORDER BY created_at ASC")
    .bind(device.id)
    .all<ZoneRow>();
  return jsonResponse((rows.results ?? []).map(zoneJson));
}

async function handleAddZone(
  request: Request,
  env: Env,
  parentId: string,
  device: DeviceRow,
): Promise<Response> {
  const body = await readJson<{
    name?: string;
    lat?: number;
    lng?: number;
    radiusMeters?: number;
  }>(request);
  if (!body) return errorResponse("Invalid request body", 400);
  const name = (body.name ?? "").trim().slice(0, 60);
  if (name.length === 0) return errorResponse("Give the safe zone a name.", 400);
  if (typeof body.lat !== "number" || body.lat < -90 || body.lat > 90) {
    return errorResponse("Invalid latitude", 400);
  }
  if (typeof body.lng !== "number" || body.lng < -180 || body.lng > 180) {
    return errorResponse("Invalid longitude", 400);
  }
  if (typeof body.radiusMeters !== "number" || body.radiusMeters < 50 || body.radiusMeters > 5000) {
    return errorResponse("Radius must be between 50 and 5000 metres.", 400);
  }
  const existing = await env.DB.prepare("SELECT COUNT(*) AS n FROM safe_zones WHERE device_id = ?")
    .bind(device.id)
    .first<{ n: number }>();
  if ((existing?.n ?? 0) >= 20) {
    return errorResponse("This device already has the maximum number of safe zones.", 409);
  }
  const id = randomId("zone");
  await env.DB.prepare(
    `INSERT INTO safe_zones (id, device_id, name, lat, lng, radius_meters, notify_on_entry,
       notify_on_exit, created_at) VALUES (?, ?, ?, ?, ?, ?, 1, 1, ?)`,
  )
    .bind(id, device.id, name, body.lat, body.lng, Math.round(body.radiusMeters), nowIso())
    .run();
  await audit(env, parentId, device.id, "safezone_added");
  const row = await env.DB.prepare("SELECT * FROM safe_zones WHERE id = ?").bind(id).first<ZoneRow>();
  return jsonResponse(row ? zoneJson(row) : { id }, 201);
}

async function handleDeleteZone(
  env: Env,
  parentId: string,
  device: DeviceRow,
  zoneId: string,
): Promise<Response> {
  const result = await env.DB.prepare("DELETE FROM safe_zones WHERE id = ? AND device_id = ?")
    .bind(zoneId, device.id)
    .run();
  if ((result.meta.changes ?? 0) === 0) return errorResponse("Safe zone not found", 404);
  await audit(env, parentId, device.id, "safezone_removed");
  return new Response(null, { status: 204 });
}

/* ------------------------------------------------------------------ */
/* calls                                                               */
/* ------------------------------------------------------------------ */

async function handleParentStartCall(
  request: Request,
  env: Env,
  parentId: string,
  device: DeviceRow,
): Promise<Response> {
  const body = await readJson<{ media?: string }>(request);
  const media = body?.media === "video" ? "video" : "audio";
  if (!(await allowRequest(env, `call:${parentId}`, 30, 3600))) {
    return errorResponse("Too many call attempts. Please wait.", 429);
  }
  // Do not stack ringing calls: end any stale ones first.
  await env.DB.prepare(
    "UPDATE calls SET status = 'ended', ended_at = ? WHERE device_id = ? AND status = 'ringing'",
  )
    .bind(nowIso(), device.id)
    .run();
  const callId = randomId("call");
  await env.DB.prepare(
    `INSERT INTO calls (id, parent_id, device_id, media, status, initiated_by, created_at)
     VALUES (?, ?, ?, ?, 'ringing', 'parent', ?)`,
  )
    .bind(callId, parentId, device.id, media, nowIso())
    .run();
  await enqueueCommand(env, device.id, "CALL", { callId, media });
  await audit(env, parentId, device.id, "call_started", media);
  return jsonResponse({ callId, media });
}

async function handleChildStartCall(request: Request, env: Env, device: DeviceRow): Promise<Response> {
  const body = await readJson<{ media?: string }>(request);
  const media = body?.media === "video" ? "video" : "audio";
  if (!(await allowRequest(env, `call:device:${device.id}`, 20, 3600))) {
    return errorResponse("Too many call attempts. Please wait.", 429);
  }
  // The child may only call the parent it is paired with.
  const parent = await env.DB.prepare("SELECT * FROM parents WHERE id = ?")
    .bind(device.parent_id)
    .first<ParentRow>();
  if (!parent) return errorResponse("The parent account no longer exists.", 410);
  await env.DB.prepare(
    "UPDATE calls SET status = 'ended', ended_at = ? WHERE device_id = ? AND status = 'ringing'",
  )
    .bind(nowIso(), device.id)
    .run();
  const callId = randomId("call");
  await env.DB.prepare(
    `INSERT INTO calls (id, parent_id, device_id, media, status, initiated_by, created_at)
     VALUES (?, ?, ?, ?, 'ringing', 'child', ?)`,
  )
    .bind(callId, device.parent_id, device.id, media, nowIso())
    .run();
  await audit(env, device.parent_id, device.id, "call_started_by_child", media);
  return jsonResponse({ callId, media });
}

async function handlePendingCalls(env: Env, parentId: string): Promise<Response> {
  const cutoff = new Date(Date.now() - CALL_RING_TTL_SECONDS * 1000).toISOString();
  await env.DB.prepare(
    "UPDATE calls SET status = 'missed', ended_at = ? WHERE parent_id = ? AND status = 'ringing' AND created_at < ?",
  )
    .bind(nowIso(), parentId, cutoff)
    .run();
  const rows = await env.DB.prepare(
    "SELECT * FROM calls WHERE parent_id = ? AND status = 'ringing' ORDER BY created_at DESC LIMIT 5",
  )
    .bind(parentId)
    .all<CallRow>();
  return jsonResponse(
    (rows.results ?? []).map((row) => ({
      callId: row.id,
      media: row.media,
      status: row.status,
      createdAt: row.created_at,
    })),
  );
}

async function handleJoinCall(
  request: Request,
  env: Env,
  parentId: string,
  callId: string,
  role: "parent" | "device",
): Promise<Response> {
  const call = await env.DB.prepare("SELECT * FROM calls WHERE id = ? AND parent_id = ?")
    .bind(callId, parentId)
    .first<CallRow>();
  if (!call) return errorResponse("Call not found", 404);
  if (call.status === "ended" || call.status === "declined" || call.status === "missed") {
    return errorResponse("That call has already finished.", 409);
  }
  if (call.status === "ringing") {
    await env.DB.prepare("UPDATE calls SET status = 'active' WHERE id = ?").bind(callId).run();
  }
  const device = await loadDeviceById(env, call.device_id);
  const config = device ? await configFor(env, device.id) : {};
  const parent = await env.DB.prepare("SELECT * FROM parents WHERE id = ?")
    .bind(parentId)
    .first<ParentRow>();
  const token = await signToken(secret(env), {
    sub: parentId,
    role,
    did: callId,
    exp: Math.floor(Date.now() / 1000) + 3600,
  });
  const mode = env.CALLS_DISABLED === "true" ? "simulated" : "live";
  const requestOrigin = new URL(request.url).origin;
  const publicOrigin = (env.PUBLIC_ORIGIN ?? requestOrigin).replace(/^http/, "ws");
  return jsonResponse({
    callId: call.id,
    room: call.id,
    websocketUrl: `${publicOrigin}/api/v1/signal/${call.id}?token=${encodeURIComponent(token)}`,
    token,
    role,
    parentName: (config.parentName as string | undefined) ?? parent?.name ?? "Daddy",
    media: call.media,
    expiresAt: new Date(Date.now() + 3600_000).toISOString(),
    mode,
    iceServers: iceServers(env),
  });
}

async function handleDeclineCall(
  env: Env,
  parentId: string,
  callId: string,
  role: "parent" | "device",
): Promise<Response> {
  const call = await env.DB.prepare("SELECT * FROM calls WHERE id = ? AND parent_id = ?")
    .bind(callId, parentId)
    .first<CallRow>();
  if (!call) return errorResponse("Call not found", 404);
  await env.DB.prepare("UPDATE calls SET status = 'declined', ended_at = ? WHERE id = ?")
    .bind(nowIso(), callId)
    .run();
  if (role === "parent") {
    await enqueueCommand(env, call.device_id, "STOP_RINGING", { callId });
  }
  await audit(env, parentId, call.device_id, "call_declined", role);
  return new Response(null, { status: 204 });
}

async function handleEndCall(
  env: Env,
  parentId: string,
  device: DeviceRow,
  callId: string,
): Promise<Response> {
  await env.DB.prepare(
    "UPDATE calls SET status = 'ended', ended_at = ? WHERE id = ? AND parent_id = ? AND device_id = ?",
  )
    .bind(nowIso(), callId, parentId, device.id)
    .run();
  return new Response(null, { status: 204 });
}

/* ------------------------------------------------------------------ */
/* signalling                                                          */
/* ------------------------------------------------------------------ */

async function handleSignal(request: Request, env: Env, callId: string): Promise<Response> {
  if (request.headers.get("Upgrade") !== "websocket") {
    return errorResponse("WebSocket upgrade required", 426);
  }
  const url = new URL(request.url);
  const token = url.searchParams.get("token") ?? "";
  const claims = await verifyToken(secret(env), token);
  if (!claims || claims.did !== callId) {
    return errorResponse("Invalid call token", 401);
  }
  const call = await env.DB.prepare("SELECT * FROM calls WHERE id = ? AND parent_id = ?")
    .bind(callId, claims.sub)
    .first<CallRow>();
  if (!call) return errorResponse("Call not found", 404);
  if (call.status === "ended" || call.status === "declined" || call.status === "missed") {
    return errorResponse("That call has finished.", 409);
  }

  const roomId = env.CALL_ROOMS.idFromName(callId);
  const stub = env.CALL_ROOMS.get(roomId);
  const headers = new Headers(request.headers);
  headers.set("x-mlw-role", claims.role === "device" ? "device" : "parent");
  headers.set("x-mlw-call-id", callId);
  const doRequest = new Request("https://call-room/join", {
    method: "GET",
    headers,
  });
  return stub.fetch(doRequest);
}
