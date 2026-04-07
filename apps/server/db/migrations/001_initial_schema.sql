-- CodexRemote initial SQLite schema
-- Baseline single-host schema with room for future multi-host expansion

-- ── Hosts ───────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS hosts (
  id          TEXT    PRIMARY KEY,
  label       TEXT    NOT NULL,
  kind        TEXT    NOT NULL CHECK (kind IN ('local', 'remote')),
  base_url    TEXT,
  tailscale_ip TEXT,
  status      TEXT    NOT NULL DEFAULT 'unknown' CHECK (status IN ('online', 'offline', 'unknown')),
  last_seen_at TEXT
);

-- ── Sessions ────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS sessions (
  id              TEXT    PRIMARY KEY,
  host_id         TEXT    NOT NULL REFERENCES hosts(id),
  provider        TEXT    NOT NULL DEFAULT 'codex' CHECK (provider IN ('codex')),
  codex_session_id TEXT,
  title           TEXT    NOT NULL,
  cwd             TEXT,
  created_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  last_preview    TEXT
);

CREATE INDEX IF NOT EXISTS idx_sessions_host_id ON sessions(host_id);

-- ── Runs ────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS runs (
  id               TEXT    PRIMARY KEY,
  session_id       TEXT    NOT NULL REFERENCES sessions(id),
  status           TEXT    NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'running', 'completed', 'failed', 'stopped')),
  prompt           TEXT    NOT NULL,
  model            TEXT,
  reasoning_effort TEXT,
  started_at       TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  finished_at      TEXT,
  last_output      TEXT,
  error            TEXT
);

CREATE INDEX IF NOT EXISTS idx_runs_session_id ON runs(session_id);

-- ── Artifacts ───────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS artifacts (
  id            TEXT    PRIMARY KEY,
  session_id    TEXT    NOT NULL REFERENCES sessions(id),
  run_id        TEXT    REFERENCES runs(id),
  kind          TEXT    NOT NULL CHECK (kind IN ('image', 'file')),
  original_name TEXT    NOT NULL,
  stored_path   TEXT    NOT NULL,
  mime_type     TEXT    NOT NULL,
  size_bytes    INTEGER NOT NULL DEFAULT 0,
  created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_artifacts_session_id ON artifacts(session_id);
CREATE INDEX IF NOT EXISTS idx_artifacts_run_id     ON artifacts(run_id);

-- ── Access Sessions ─────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS access_sessions (
  token_id     TEXT    PRIMARY KEY,
  created_at   TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  expires_at   TEXT    NOT NULL,
  device_label TEXT
);
