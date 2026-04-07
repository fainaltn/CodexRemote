-- Audit log for security-relevant events (§11 Phase 1 security controls).
-- Records login attempts (success and failure), logouts, and uploads so
-- operators can investigate incidents and observe usage during APK trials.

CREATE TABLE IF NOT EXISTS audit_log (
  id            TEXT    PRIMARY KEY,
  timestamp     TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  event_type    TEXT    NOT NULL CHECK (event_type IN (
    'login_success', 'login_failure', 'logout',
    'upload_success', 'upload_failure'
  )),
  ip            TEXT,
  token_id      TEXT,
  device_label  TEXT,
  session_id    TEXT,
  artifact_id   TEXT,
  detail        TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp  ON audit_log(timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_log_event_type ON audit_log(event_type);
