-- Cached session metadata and message tail snapshots.
-- These tables are server-owned acceleration structures and should not
-- replace the canonical Codex state files; they only reduce repeated
-- expensive scans on common read paths.

CREATE TABLE IF NOT EXISTS session_metadata_cache (
  session_id        TEXT    PRIMARY KEY,
  title             TEXT,
  cwd               TEXT,
  last_activity_at  TEXT,
  last_preview      TEXT,
  synced_at         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_session_metadata_cache_synced_at
  ON session_metadata_cache(synced_at);

CREATE TABLE IF NOT EXISTS session_message_cache (
  session_id        TEXT    PRIMARY KEY,
  source_signature  TEXT    NOT NULL,
  messages_json     TEXT    NOT NULL,
  updated_at        TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_session_message_cache_updated_at
  ON session_message_cache(updated_at);
