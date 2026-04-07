/**
 * Vitest global setup — runs before any test file is imported.
 *
 * Sets environment variables that module-level constants in db.ts and
 * auth/store.ts read at import time, so tests use an isolated SQLite
 * database and a known password.
 */

import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const testDataDir = mkdtempSync(join(tmpdir(), "codexremote-test-"));
process.env["CODEXREMOTE_DATA_DIR"] = testDataDir;
process.env["CODEXREMOTE_PASSWORD"] = "test-password";
