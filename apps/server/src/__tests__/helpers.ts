/**
 * Shared test utilities: mock adapter, app builder, auth helper,
 * database cleanup, and multipart payload builder.
 */

import type { FastifyInstance } from "fastify";
import { buildApp } from "../app.js";
import type { CodexAdapter } from "../codex/interface.js";
import type {
  CodexSessionSummary,
  CodexSessionDetail,
  CodexSessionMessage,
  StartRunOptions,
  RunHandle,
  NewRunHandle,
} from "../codex/types.js";
import { initDb, getDb } from "../db.js";

// ── Mock RunHandle ─────────────────────────────────────────────────

export class MockRunHandle implements RunHandle {
  pid: number;
  private output = "";
  private exitCallbacks: Array<(code: number | null) => void> = [];
  private exitCode: number | null | undefined;
  private _stopped = false;
  private _stopDeferred = false;
  private _stopResolvers: Array<() => void> = [];
  private _totalBytes = 0;

  constructor() {
    this.pid = Math.floor(Math.random() * 90000) + 10000;
  }

  readOutput(): string {
    return this.output;
  }

  totalOutputBytes(): number {
    return this._totalBytes;
  }

  async stop(): Promise<void> {
    this._stopped = true;
    if (this._stopDeferred) {
      return new Promise<void>((resolve) => {
        this._stopResolvers.push(resolve);
      });
    }
  }

  onExit(cb: (code: number | null) => void): void {
    if (this.exitCode !== undefined) {
      cb(this.exitCode);
      return;
    }
    this.exitCallbacks.push(cb);
  }

  // ── Test-side controls ──────────────────────────────────────────

  appendOutput(text: string): void {
    this.output += text;
    this._totalBytes += Buffer.byteLength(text, "utf-8");
  }

  simulateExit(code: number | null): void {
    this.exitCode = code;
    for (const cb of this.exitCallbacks) cb(code);
  }

  get stopped(): boolean {
    return this._stopped;
  }

  /**
   * Make future `stop()` calls return a pending Promise that only
   * resolves when {@link resolveStop} is called.
   */
  deferStop(): void {
    this._stopDeferred = true;
  }

  /**
   * Resolve all pending `stop()` Promises created after
   * {@link deferStop} was called, and disable deferred mode.
   */
  resolveStop(): void {
    this._stopDeferred = false;
    for (const r of this._stopResolvers) r();
    this._stopResolvers = [];
  }
}

// ── Mock CodexAdapter ──────────────────────────────────────────────

export class MockCodexAdapter implements CodexAdapter {
  private sessions = new Map<string, CodexSessionDetail>();
  private messages = new Map<string, CodexSessionMessage[]>();
  private delayedNewSessions = false;
  archivedSessionIds: string[] = [];
  lastStartRunSessionId: string | null = null;
  lastStartRunOptions: StartRunOptions | null = null;
  lastStartNewRunCwd: string | null = null;
  lastStartNewRunOptions: StartRunOptions | null = null;
  /** Last RunHandle created by startRun — exposed for test assertions. */
  lastRunHandle: MockRunHandle | null = null;

  addSession(id: string, overrides?: Partial<CodexSessionDetail>): void {
    this.sessions.set(id, {
      codexSessionId: id,
      cwd: overrides?.cwd ?? "/tmp/test",
      lastActivityAt:
        overrides?.lastActivityAt ?? new Date().toISOString(),
      title: overrides?.title ?? `Session ${id}`,
      lastPreview: overrides?.lastPreview ?? null,
    });
    if (!this.messages.has(id)) {
      this.messages.set(id, []);
    }
  }

  removeSession(id: string): void {
    this.sessions.delete(id);
  }

  deferNewSessionVisibility(): void {
    this.delayedNewSessions = true;
  }

  async listSessions(): Promise<CodexSessionSummary[]> {
    return Array.from(this.sessions.values()).map((s) => ({
      codexSessionId: s.codexSessionId,
      cwd: s.cwd,
      lastActivityAt: s.lastActivityAt,
      title: s.title,
      lastPreview: s.lastPreview,
    }));
  }

  async getSessionDetail(
    codexSessionId: string,
  ): Promise<CodexSessionDetail | null> {
    return this.sessions.get(codexSessionId) ?? null;
  }

  async getSessionMessages(
    codexSessionId: string,
  ): Promise<CodexSessionMessage[]> {
    return this.messages.get(codexSessionId) ?? [];
  }

  setSessionMessages(
    codexSessionId: string,
    messages: CodexSessionMessage[],
  ): void {
    this.messages.set(codexSessionId, messages);
  }

  async startRun(
    codexSessionId: string,
    options: StartRunOptions,
  ): Promise<RunHandle> {
    this.lastStartRunSessionId = codexSessionId;
    this.lastStartRunOptions = options;
    const handle = new MockRunHandle();
    this.lastRunHandle = handle;
    return handle;
  }

  async startNewRun(
    cwd: string,
    options: StartRunOptions,
  ): Promise<NewRunHandle> {
    this.lastStartNewRunCwd = cwd;
    this.lastStartNewRunOptions = options;
    const id = `new-${Math.random().toString(36).slice(2, 10)}`;
    const handle = new MockRunHandle();
    if (this.delayedNewSessions) {
      setTimeout(() => {
        this.addSession(id, { cwd, title: `Session ${id}` });
        handle.simulateExit(0);
      }, 250);
    } else {
      this.addSession(id, { cwd, title: `Session ${id}` });
      queueMicrotask(() => handle.simulateExit(0));
    }
    this.lastRunHandle = handle;
    return { sessionId: id, handle };
  }

  async archiveSession(codexSessionId: string): Promise<void> {
    this.archivedSessionIds.push(codexSessionId);
  }

  async stopRun(_pid: number): Promise<void> {
    /* no-op */
  }
}

import type { AppOptions } from "../app.js";

// ── App factory ────────────────────────────────────────────────────

export async function createTestApp(
  adapter?: MockCodexAdapter,
  extraOpts?: Partial<AppOptions>,
): Promise<{
  app: FastifyInstance;
  adapter: MockCodexAdapter;
}> {
  const mock = adapter ?? new MockCodexAdapter();
  const { app } = await buildApp({
    logger: false,
    adapter: mock,
    skipRecovery: true,
    skipArtifactRepair: true,
    // Generous rate limits for general tests so they don't trip rate limiting.
    globalRateLimitMax: 1000,
    globalRateLimitWindowMs: 60_000,
    authRateLimitMax: 1000,
    authRateLimitWindowMs: 60_000,
    ...extraOpts,
  });
  return { app, adapter: mock };
}

// ── Auth helpers ───────────────────────────────────────────────────

export async function loginHelper(
  app: FastifyInstance,
  password = "test-password",
): Promise<string> {
  const res = await app.inject({
    method: "POST",
    url: "/api/auth/login",
    payload: { password },
  });
  const body = JSON.parse(res.body);
  return body.token as string;
}

export function authHeader(token: string): { authorization: string } {
  return { authorization: `Bearer ${token}` };
}

// ── Database cleanup ───────────────────────────────────────────────

export function cleanTables(): void {
  initDb(); // Idempotent — ensures DB is open before first use.
  const db = getDb();
  // Ensure the audit_log table exists (tests may have dropped it).
  db.exec(`CREATE TABLE IF NOT EXISTS audit_log (
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
  )`);
  // Respect FK ordering: children first.
  db.exec("DELETE FROM audit_log");
  db.exec("DELETE FROM inbox_items");
  db.exec("DELETE FROM artifacts");
  db.exec("DELETE FROM runs");
  db.exec("DELETE FROM access_sessions");
  db.exec("DELETE FROM sessions");
  db.exec("DELETE FROM hosts");
}

// ── Multipart payload builder ──────────────────────────────────────

export function buildMultipartPayload(
  boundary: string,
  fields: Record<string, string>,
  file?: {
    fieldName: string;
    filename: string;
    content: Buffer;
    contentType: string;
  },
): Buffer {
  const parts: Buffer[] = [];

  for (const [name, value] of Object.entries(fields)) {
    parts.push(
      Buffer.from(
        `--${boundary}\r\n` +
          `Content-Disposition: form-data; name="${name}"\r\n\r\n` +
          `${value}\r\n`,
      ),
    );
  }

  if (file) {
    parts.push(
      Buffer.from(
        `--${boundary}\r\n` +
          `Content-Disposition: form-data; name="${file.fieldName}"; filename="${file.filename}"\r\n` +
          `Content-Type: ${file.contentType}\r\n\r\n`,
      ),
    );
    parts.push(file.content);
    parts.push(Buffer.from("\r\n"));
  }

  parts.push(Buffer.from(`--${boundary}--\r\n`));
  return Buffer.concat(parts);
}
