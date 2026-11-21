-- Family Connect schema (D1 / SQLite).
-- Everything is scoped by parent_id or device_id: no cross-family reads exist.

CREATE TABLE IF NOT EXISTS parents (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  parent_id TEXT NOT NULL REFERENCES parents(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT 'android',
  app_version TEXT,
  token_hash TEXT NOT NULL UNIQUE,
  paired_at TEXT NOT NULL,
  last_seen TEXT,
  online INTEGER NOT NULL DEFAULT 0,
  battery_percent INTEGER,
  charging INTEGER,
  model TEXT,
  android_version TEXT,
  webview_version TEXT,
  auto_answer_supported INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_devices_parent ON devices(parent_id);

CREATE TABLE IF NOT EXISTS pair_codes (
  code TEXT PRIMARY KEY,
  parent_id TEXT NOT NULL REFERENCES parents(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  used_at TEXT
);

CREATE TABLE IF NOT EXISTS device_configs (
  device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  json TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS commands (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  type TEXT NOT NULL,
  payload_json TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL,
  delivered_at TEXT,
  acked_at TEXT,
  result TEXT
);
CREATE INDEX IF NOT EXISTS idx_commands_device_created ON commands(device_id, created_at);

CREATE TABLE IF NOT EXISTS locations (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  lat REAL NOT NULL,
  lng REAL NOT NULL,
  accuracy_meters REAL NOT NULL,
  captured_at TEXT NOT NULL,
  provider TEXT NOT NULL,
  battery_percent INTEGER,
  is_mock INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_locations_device_captured ON locations(device_id, captured_at DESC);

CREATE TABLE IF NOT EXISTS safe_zones (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  lat REAL NOT NULL,
  lng REAL NOT NULL,
  radius_meters INTEGER NOT NULL,
  notify_on_entry INTEGER NOT NULL DEFAULT 1,
  notify_on_exit INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_zones_device ON safe_zones(device_id);

CREATE TABLE IF NOT EXISTS calls (
  id TEXT PRIMARY KEY,
  parent_id TEXT NOT NULL REFERENCES parents(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  media TEXT NOT NULL DEFAULT 'audio',
  status TEXT NOT NULL,
  initiated_by TEXT NOT NULL,
  created_at TEXT NOT NULL,
  ended_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_calls_parent_status ON calls(parent_id, status, created_at);

CREATE TABLE IF NOT EXISTS audit (
  id TEXT PRIMARY KEY,
  parent_id TEXT,
  device_id TEXT,
  action TEXT NOT NULL,
  detail TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_parent ON audit(parent_id, created_at DESC);

CREATE TABLE IF NOT EXISTS rate_limits (
  key TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (key, window_start)
);
