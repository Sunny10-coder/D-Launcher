/** Database helpers and shared validation. */

export interface Env {
  DB: D1Database;
  CALL_ROOMS: DurableObjectNamespace;
  SESSION_SECRET: string;
  ALLOW_REGISTRATION?: string;
  ICE_SERVERS_JSON?: string;
  CALLS_DISABLED?: string;
  FCM_SERVICE_ACCOUNT_JSON?: string;
  /** Public base URL of this Worker, used to build WebSocket URLs. */
  PUBLIC_ORIGIN?: string;
}

export interface ParentRow {
  id: string;
  email: string;
  name: string;
  password_hash: string;
  created_at: string;
}

export interface DeviceRow {
  id: string;
  parent_id: string;
  name: string;
  platform: string;
  app_version: string | null;
  token_hash: string;
  paired_at: string;
  last_seen: string | null;
  online: number;
  battery_percent: number | null;
  charging: number | null;
  model: string | null;
  android_version: string | null;
  webview_version: string | null;
  auto_answer_supported: number;
}

export interface ConfigRow {
  device_id: string;
  json: string;
  updated_at: string;
}

export interface CommandRow {
  id: string;
  device_id: string;
  type: string;
  payload_json: string;
  created_at: string;
  delivered_at: string | null;
  acked_at: string | null;
  result: string | null;
}

export interface LocationRow {
  id: string;
  device_id: string;
  lat: number;
  lng: number;
  accuracy_meters: number;
  captured_at: string;
  provider: string;
  battery_percent: number | null;
  is_mock: number;
  created_at: string;
}

export interface ZoneRow {
  id: string;
  device_id: string;
  name: string;
  lat: number;
  lng: number;
  radius_meters: number;
  notify_on_entry: number;
  notify_on_exit: number;
  created_at: string;
}

export interface CallRow {
  id: string;
  parent_id: string;
  device_id: string;
  media: string;
  status: string;
  initiated_by: string;
  created_at: string;
  ended_at: string | null;
}

export const LOCATION_RETENTION_DAYS = 30;
export const PAIR_CODE_TTL_MINUTES = 10;
export const CALL_RING_TTL_SECONDS = 90;

export function nowIso(): string {
  return new Date().toISOString();
}

export function minutesFromNow(minutes: number): string {
  return new Date(Date.now() + minutes * 60_000).toISOString();
}

export function randomId(prefix: string): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${prefix}_${hex}`;
}

export function defaultConfig(parentName: string): Record<string, unknown> {
  return {
    nickname: "Little Star",
    parentName: parentName || "Daddy",
    learningUrl: "",
    playUrl: "",
    theme: "sunrise",
    enabledTiles: ["learning", "call", "youtube", "camera", "play"],
    autoAnswerVoice: false,
    autoAnswerNoticeAccepted: false,
    muteDuringQuietHours: false,
    scheduleStart: "",
    scheduleEnd: "",
    remoteCameraEnabled: false,
    contactPhone: "",
    updatedAt: nowIso(),
  };
}

const TILE_IDS = new Set(["learning", "call", "youtube", "camera", "play"]);
const THEMES = new Set(["sunrise", "ocean", "garden"]);

/**
 * Normalises an incoming config document. Unknown keys are dropped and every
 * field is bounded so a compromised client cannot store arbitrary payloads.
 */
export function sanitiseConfig(input: unknown, parentNameFallback: string): Record<string, unknown> {
  const base = defaultConfig(parentNameFallback);
  if (typeof input !== "object" || input === null) return base;
  const raw = input as Record<string, unknown>;
  const str = (key: string, max: number, fallback: string): string => {
    const value = raw[key];
    return typeof value === "string" ? value.slice(0, max) : fallback;
  };
  const bool = (key: string, fallback: boolean): boolean =>
    typeof raw[key] === "boolean" ? (raw[key] as boolean) : fallback;
  const tiles = Array.isArray(raw.enabledTiles)
    ? (raw.enabledTiles as unknown[])
        .filter((t): t is string => typeof t === "string" && TILE_IDS.has(t))
        .slice(0, 5)
    : (base.enabledTiles as string[]);
  const theme = str("theme", 16, "sunrise");
  return {
    nickname: str("nickname", 24, "Little Star") || "Little Star",
    parentName: str("parentName", 24, parentNameFallback) || parentNameFallback,
    learningUrl: str("learningUrl", 2048, ""),
    playUrl: str("playUrl", 2048, ""),
    theme: THEMES.has(theme) ? theme : "sunrise",
    enabledTiles: tiles,
    autoAnswerVoice: bool("autoAnswerVoice", false),
    autoAnswerNoticeAccepted: bool("autoAnswerNoticeAccepted", false),
    muteDuringQuietHours: bool("muteDuringQuietHours", false),
    scheduleStart: str("scheduleStart", 5, ""),
    scheduleEnd: str("scheduleEnd", 5, ""),
    remoteCameraEnabled: false,
    contactPhone: str("contactPhone", 32, ""),
    updatedAt: nowIso(),
  };
}

export function isValidEmail(value: string): boolean {
  return /^[^\s@]{1,64}@[^\s@]{1,190}\.[A-Za-z]{2,}$/.test(value);
}

export function isHttpsUrl(value: string): boolean {
  if (value.length === 0) return true;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && url.username === "" && url.password === "";
  } catch {
    return false;
  }
}

export function isValidIsoTimestamp(value: unknown): value is string {
  if (typeof value !== "string" || value.length < 10 || value.length > 40) return false;
  const time = Date.parse(value);
  if (!Number.isFinite(time)) return false;
  // Reject dates far outside a plausible window (clock skew / replay guards).
  const delta = Math.abs(Date.now() - time);
  return delta < 1000 * 60 * 60 * 24 * 30;
}

/** Fixed-window rate limiter backed by D1. Returns false when the limit is hit. */
export async function allowRequest(
  env: Env,
  key: string,
  limit: number,
  windowSeconds: number,
): Promise<boolean> {
  const windowStart = Math.floor(Date.now() / 1000 / windowSeconds) * windowSeconds;
  await env.DB.prepare(
    `INSERT INTO rate_limits (key, window_start, count) VALUES (?, ?, 1)
     ON CONFLICT(key, window_start) DO UPDATE SET count = count + 1`,
  )
    .bind(key, windowStart)
    .run();
  const row = await env.DB.prepare(
    "SELECT count FROM rate_limits WHERE key = ? AND window_start = ?",
  )
    .bind(key, windowStart)
    .first<{ count: number }>();
  return (row?.count ?? 0) <= limit;
}

export async function audit(
  env: Env,
  parentId: string | null,
  deviceId: string | null,
  action: string,
  detail?: string,
): Promise<void> {
  await env.DB.prepare(
    "INSERT INTO audit (id, parent_id, device_id, action, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)",
  )
    .bind(randomId("aud"), parentId, deviceId, action, detail ?? null, nowIso())
    .run();
}

export async function configFor(env: Env, deviceId: string): Promise<Record<string, unknown>> {
  const row = await env.DB.prepare("SELECT * FROM device_configs WHERE device_id = ?")
    .bind(deviceId)
    .first<ConfigRow>();
  if (!row) return {};
  try {
    return JSON.parse(row.json) as Record<string, unknown>;
  } catch {
    return {};
  }
}

export async function saveConfig(
  env: Env,
  deviceId: string,
  config: Record<string, unknown>,
): Promise<void> {
  const updatedAt = nowIso();
  const json = JSON.stringify({ ...config, updatedAt });
  await env.DB.prepare(
    `INSERT INTO device_configs (device_id, json, updated_at) VALUES (?, ?, ?)
     ON CONFLICT(device_id) DO UPDATE SET json = excluded.json, updated_at = excluded.updated_at`,
  )
    .bind(deviceId, json, updatedAt)
    .run();
}

export function iceServers(env: Env): unknown[] {
  try {
    const parsed = JSON.parse(env.ICE_SERVERS_JSON ?? "[]");
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}
