import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { closeDb } from "../db.js";
import { cleanTables } from "./helpers.js";
import { createInboxLinkItem } from "../inbox/store.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, "..", "..", "..", "..");
const SCRIPT_PATH = path.join(REPO_ROOT, "scripts", "clear-inbox.sh");
const DATA_ROOT = process.env["CODEXREMOTE_DATA_DIR"] ?? "data";
const STAGING_ROOT = process.env["CODEXREMOTE_STAGING_DIR"] ?? path.join(DATA_ROOT, "submissions");
const DB_PATH = path.join(DATA_ROOT, "codexremote.db");

describe("clear-inbox.sh", () => {
  beforeEach(() => {
    cleanTables();
    fs.rmSync(STAGING_ROOT, { recursive: true, force: true });
  });

  afterEach(() => {
    closeDb();
    fs.rmSync(STAGING_ROOT, { recursive: true, force: true });
  });

  it("supports dry-run filtering by submission id", async () => {
    const created = await createInboxLinkItem({
      hostId: "local",
      url: "https://example.com/dry-run",
      title: "Dry Run Link",
      source: "test",
    });

    const output = execFileSync("zsh", [SCRIPT_PATH, "--dry-run", "--submission-id", created.submissionId ?? created.id], {
      cwd: REPO_ROOT,
      env: process.env,
      encoding: "utf-8",
    });

    expect(output).toContain("Dry run only");
    expect(output).toContain(created.submissionId ?? created.id);
    expect(created.stagingDir).toBeTruthy();
    expect(fs.existsSync(created.stagingDir!)).toBe(true);
  });

  it("deletes matching rows and staging dirs by title keyword", async () => {
    const target = await createInboxLinkItem({
      hostId: "local",
      url: "https://example.com/remove-me",
      title: "integration test marker",
      source: "test",
    });
    const keep = await createInboxLinkItem({
      hostId: "local",
      url: "https://example.com/keep-me",
      title: "production keep",
      source: "test",
    });

    execFileSync("zsh", [SCRIPT_PATH, "--host-id", "local", "--title-contains", "integration test"], {
      cwd: REPO_ROOT,
      env: process.env,
      encoding: "utf-8",
    });

    expect(target.stagingDir).toBeTruthy();
    expect(keep.stagingDir).toBeTruthy();
    expect(fs.existsSync(target.stagingDir!)).toBe(false);
    expect(fs.existsSync(keep.stagingDir!)).toBe(true);
    const rowsLeft = execFileSync("sqlite3", [DB_PATH, "SELECT COUNT(*) FROM inbox_items;"], {
      cwd: REPO_ROOT,
      env: process.env,
      encoding: "utf-8",
    }).trim();
    expect(rowsLeft).toBe("1");
  });
});
