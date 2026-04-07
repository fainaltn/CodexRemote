/**
 * SQLite database initialisation and access.
 *
 * Opens (or creates) a `better-sqlite3` database at the path derived from
 * the CODEXREMOTE_DATA_DIR environment variable (default: `data/`).
 *
 * Migrations are applied eagerly on first call to {@link initDb} using a
 * simple `user_version`-based scheme that reads sequential `.sql` files
 * from `db/migrations/`.
 *
 * Module consumers should call {@link initDb} once at startup, then use
 * {@link getDb} from any module that needs the database handle.
 */

import Database from "better-sqlite3";
import type BetterSqlite3 from "better-sqlite3";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const DATA_ROOT = process.env["CODEXREMOTE_DATA_DIR"] ?? "data";
const DB_PATH = path.join(DATA_ROOT, "codexremote.db");

/** Folder that contains numbered `.sql` migration files. */
const MIGRATIONS_DIR = path.resolve(__dirname, "..", "db", "migrations");

let _db: BetterSqlite3.Database | null = null;

/**
 * Open the database, enable WAL mode, and run any pending migrations.
 * Safe to call more than once — subsequent calls are no-ops.
 */
export function initDb(): BetterSqlite3.Database {
  if (_db) return _db;

  // Ensure the data directory exists.
  fs.mkdirSync(DATA_ROOT, { recursive: true });

  _db = new Database(DB_PATH);

  // WAL mode gives better concurrent-read performance and crash safety.
  _db.pragma("journal_mode = WAL");
  _db.pragma("foreign_keys = ON");
  // Avoid SQLITE_BUSY under concurrent access (e.g. SSE + upload).
  _db.pragma("busy_timeout = 3000");

  applyMigrations(_db);

  return _db;
}

/**
 * Return the already-initialised database handle.
 * Throws if called before {@link initDb}.
 */
export function getDb(): BetterSqlite3.Database {
  if (!_db) {
    throw new Error("Database not initialised — call initDb() first");
  }
  return _db;
}

/**
 * Close the database handle cleanly. Intended for graceful shutdown or
 * test teardown. After calling this, {@link initDb} can be called again
 * to re-open the database.
 */
export function closeDb(): void {
  if (_db) {
    _db.close();
    _db = null;
  }
}

// ── Migration runner ───────────────────────────────────────────────

/**
 * Simple migration scheme based on SQLite's built-in `user_version` pragma.
 *
 * Migration files are named `NNN_<description>.sql` (e.g. `001_initial_schema.sql`).
 * The numeric prefix determines the ordering.  The current `user_version` records
 * how many migrations have already been applied, so only new files are executed.
 */
function applyMigrations(db: BetterSqlite3.Database): void {
  const currentVersion: number = (
    db.pragma("user_version") as { user_version: number }[]
  )[0].user_version;

  const files = fs
    .readdirSync(MIGRATIONS_DIR)
    .filter((f) => f.endsWith(".sql"))
    .sort();

  for (let i = currentVersion; i < files.length; i++) {
    const filePath = path.join(MIGRATIONS_DIR, files[i]);
    const sql = fs.readFileSync(filePath, "utf-8");

    // Run each migration inside a transaction for atomicity.
    db.transaction(() => {
      db.exec(sql);
      db.pragma(`user_version = ${i + 1}`);
    })();
  }
}
