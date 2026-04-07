-- Independent inbox intake for remote material drop-off.
-- Separate from session artifacts: this is a lightweight "received"
-- queue for later consumption by an external/local knowledge system.

CREATE TABLE IF NOT EXISTS inbox_items (
  id             TEXT    PRIMARY KEY,
  host_id        TEXT    NOT NULL REFERENCES hosts(id),
  kind           TEXT    NOT NULL CHECK (kind IN ('link', 'file')),
  status         TEXT    NOT NULL DEFAULT 'received' CHECK (status IN ('received')),
  url            TEXT,
  title          TEXT,
  original_name  TEXT,
  note           TEXT,
  source         TEXT,
  stored_path    TEXT,
  mime_type      TEXT,
  size_bytes     INTEGER,
  created_at     TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_inbox_items_host_id    ON inbox_items(host_id);
CREATE INDEX IF NOT EXISTS idx_inbox_items_created_at ON inbox_items(created_at);
