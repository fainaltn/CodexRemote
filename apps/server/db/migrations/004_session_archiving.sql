ALTER TABLE sessions ADD COLUMN archived_at TEXT;

CREATE INDEX IF NOT EXISTS idx_sessions_archived_at ON sessions(archived_at);
