/**
 * Low-level helpers for shelling out to the Codex CLI.
 *
 * Every direct interaction with `codex` binary or its on-disk layout
 * lives here.  If Codex CLI invocation conventions change, only this
 * file should need updating.
 */

import { spawn, type ChildProcess } from "node:child_process";
import { constants as fsConstants } from "node:fs";
import { access, readdir, readFile, stat } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, join } from "node:path";
import { MAX_OUTPUT_BYTES } from "../config.js";
import type { RunHandle } from "./types.js";

export { MAX_OUTPUT_BYTES };

const APP_SERVER_STDIO_URL = "stdio://";
const APP_SERVER_INIT_REQUEST_ID = 1;
const APP_SERVER_THREAD_START_REQUEST_ID = 2;
const APP_SERVER_TURN_START_REQUEST_ID = 3;
const APP_SERVER_THREAD_NAME_SET_REQUEST_ID = 4;
const APP_SERVER_THREAD_ARCHIVE_REQUEST_ID = 5;
const APP_SERVER_THREAD_RESUME_REQUEST_ID = 6;
const APP_SERVER_START_TIMEOUT_MS = 10_000;
const APP_SERVER_SHUTDOWN_GRACE_MS = 6_500;
const APP_SERVER_PROTOCOL_VERSION = "2025-06-18";
const DESKTOP_BUNDLED_CODEX_BIN = "/Applications/Codex.app/Contents/Resources/codex";

// ── Bounded output buffer ────────────────────────────────────────────

/**
 * Ring-style output buffer that keeps at most `maxBytes` of the most
 * recent output.  Older content is silently discarded (tail behaviour).
 *
 * All accounting and truncation operate on real UTF-8 byte lengths —
 * not JavaScript string character counts — so the configured byte
 * budget is honoured even for multibyte content (CJK, emoji, etc.).
 *
 * When truncation cuts into a multi-byte UTF-8 sequence, any orphaned
 * continuation bytes at the start of the retained window are skipped
 * so that {@link read} never produces replacement characters.
 *
 * Designed to replace the unbounded `string[]` accumulation pattern
 * that previously risked OOM on verbose runs.
 */
export class BoundedOutputBuffer {
  private buf: Buffer = Buffer.alloc(0);
  private _totalBytes = 0;

  constructor(private readonly maxBytes: number = MAX_OUTPUT_BYTES) {}

  /** Append a string (converted to UTF-8 bytes internally). */
  append(text: string): void {
    this.appendBytes(Buffer.from(text, "utf-8"));
  }

  /** Append raw bytes directly — avoids a string→Buffer round-trip. */
  appendBytes(chunk: Buffer): void {
    this._totalBytes += chunk.length;
    this.buf = Buffer.concat([this.buf, chunk]);
    if (this.buf.length > this.maxBytes) {
      let start = this.buf.length - this.maxBytes;
      // Skip orphaned UTF-8 continuation bytes (10xxxxxx) at the cut
      // point so read() never decodes a partial multi-byte sequence.
      while (start < this.buf.length && (this.buf[start] & 0xc0) === 0x80) {
        start++;
      }
      this.buf = Buffer.from(this.buf.subarray(start));
    }
  }

  /** Return the buffered output decoded as UTF-8 (at most maxBytes of the tail). */
  read(): string {
    return this.buf.toString("utf-8");
  }

  /** Current byte length of the retained buffer. */
  get byteLength(): number {
    return this.buf.length;
  }

  /** Total bytes received since creation, including truncated content. */
  get totalBytes(): number {
    return this._totalBytes;
  }

  /** True when some output has been discarded. */
  get truncated(): boolean {
    return this._totalBytes > this.buf.length;
  }
}

// ── Configuration ────────────────────────────────────────────────────

/**
 * Path to the Codex CLI binary.
 * Honour an env override so devs can point at a local build.
 */
export function codexBin(): string {
  return process.env["CODEX_BIN"] ?? "codex";
}

async function codexAppServerBin(): Promise<string> {
  const override = process.env["CODEX_APP_SERVER_BIN"];
  if (override) {
    return override;
  }

  try {
    await access(DESKTOP_BUNDLED_CODEX_BIN, fsConstants.X_OK);
    return DESKTOP_BUNDLED_CODEX_BIN;
  } catch {
    return codexBin();
  }
}

/**
 * Root directory where Codex stores session state.
 *
 * The real path may vary across Codex versions; centralising it here
 * means we only need to update one spot.
 */
export function codexStateDir(): string {
  return (
    process.env["CODEX_STATE_DIR"] ?? join(homedir(), ".codex", "sessions")
  );
}

function codexSessionIndexFile(): string {
  return (
    process.env["CODEX_SESSION_INDEX_FILE"] ??
    join(homedir(), ".codex", "session_index.jsonl")
  );
}

// ── Session discovery ────────────────────────────────────────────────

export interface RawSessionEntry {
  id: string;
  cwd: string | null;
  lastActivityAt: string | null;
  title: string | null;
  lastPreview: string | null;
  isSubagent: boolean;
}

export interface RawSessionMessage {
  id: string;
  role: "user" | "assistant" | "system";
  kind: "message" | "reasoning";
  text: string;
  createdAt: string;
}

interface ParsedHistoryMessage extends RawSessionMessage {
  source: "event_msg" | "response_item";
}

export interface CodexThreadStartCapability {
  available: boolean;
  reason: string;
}

const ATTACHMENT_WRAPPER_PREFIX =
  "You have access to these uploaded session artifacts";
const ATTACHMENT_WRAPPER_REQUEST_MARKER = "User request:";

/**
 * Scan the local Codex session directory and return one entry per
 * real Codex rollout file.
 *
 * Real Codex Desktop state is nested by date (`YYYY/MM/DD/*.jsonl`),
 * not as one directory per session. We therefore recurse for rollout
 * `.jsonl` files and recover the actual session id from the
 * `session_meta.payload.id` record inside each file.
 *
 * Tolerant of missing directories and malformed files — returns an
 * empty array or skips bad entries so the app stays usable even if
 * Codex has never run.
 */
export async function scanSessionDirs(): Promise<RawSessionEntry[]> {
  const base = codexStateDir();

  const files = await findSessionFiles(base);
  const byId = new Map<string, RawSessionEntry>();
  for (const filePath of files) {
    try {
      const parsed = await readSessionFile(filePath);
      if (!parsed.id) continue;
      const s = await stat(filePath);
      const next: RawSessionEntry = {
        id: parsed.id,
        cwd: parsed.cwd,
        title: parsed.title,
        lastPreview: parsed.lastPreview,
        lastActivityAt: parsed.lastActivityAt ?? s.mtime.toISOString(),
        isSubagent: parsed.isSubagent,
      };

      const prev = byId.get(parsed.id);
      byId.set(parsed.id, mergeSessionEntries(prev, next));
    } catch {
      // Skip unreadable entries
    }
  }

  return [...byId.values()]
    .filter((entry) => !entry.isSubagent)
    .filter((entry) => isVisibleSession(entry.cwd))
    .sort((a, b) =>
      (b.lastActivityAt ?? "").localeCompare(a.lastActivityAt ?? ""),
    );
}

function mergeSessionEntries(
  prev: RawSessionEntry | undefined,
  next: RawSessionEntry,
): RawSessionEntry {
  if (!prev) return next;

  const prevTs = prev.lastActivityAt ?? "";
  const nextTs = next.lastActivityAt ?? "";
  const newest = nextTs >= prevTs ? next : prev;
  const oldest = newest === next ? prev : next;

  return {
    id: newest.id,
    lastActivityAt: newest.lastActivityAt ?? oldest.lastActivityAt,
    cwd: newest.cwd ?? oldest.cwd,
    title: pickBetterSessionTitle(newest, oldest),
    lastPreview: pickRicherText(newest.lastPreview, oldest.lastPreview),
    // Some subagent rollouts repeat the parent thread id. Keep the
    // thread visible as long as we have seen at least one non-subagent
    // rollout for that id.
    isSubagent: newest.isSubagent && oldest.isSubagent,
  };
}

function pickRicherText(primary: string | null, fallback: string | null): string | null {
  if (!primary) return fallback;
  if (!fallback) return primary;
  return primary.length >= fallback.length ? primary : fallback;
}

function pickBetterSessionTitle(
  newest: RawSessionEntry,
  oldest: RawSessionEntry,
): string | null {
  const newestTitle = newest.title;
  const oldestTitle = oldest.title;
  if (!newestTitle) return oldestTitle;
  if (!oldestTitle) return newestTitle;

  const newestLooksDerived = newest.cwd
    ? newestTitle === basename(newest.cwd)
    : false;
  const oldestLooksDerived = oldest.cwd
    ? oldestTitle === basename(oldest.cwd)
    : false;

  if (!newestLooksDerived && oldestLooksDerived) return newestTitle;
  if (newestLooksDerived && !oldestLooksDerived) return oldestTitle;
  return pickRicherText(newestTitle, oldestTitle);
}

function isVisibleSession(cwd: string | null): boolean {
  if (!cwd) return true;
  const normalized = cwd.replace(/\/+$/, "");
  const base = basename(normalized);
  return !(
    normalized === "/tmp" ||
    normalized.startsWith("/tmp/") ||
    normalized === "/private/tmp" ||
    normalized.startsWith("/private/tmp/") ||
    normalized.startsWith("/private/var/folders/") ||
    base.startsWith("tmp.") ||
    base.startsWith("codexremote-smoke")
  );
}

async function findSessionFiles(dir: string): Promise<string[]> {
  try {
    const entries = await readdir(dir, { withFileTypes: true });
    const results: string[] = [];
    for (const entry of entries) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) {
        results.push(...(await findSessionFiles(path)));
        continue;
      }
      if (entry.isFile() && entry.name.endsWith(".jsonl")) {
        results.push(path);
      }
    }
    return results;
  } catch {
    return [];
  }
}

/**
 * Return true if the given session id exists anywhere under the Codex
 * session-state tree.
 */
export async function sessionDirExists(codexSessionId: string): Promise<boolean> {
  return (await findSessionFileById(codexSessionId)) !== null;
}

/**
 * Try to read richer detail (title, last preview) from session state.
 */
export async function readSessionMeta(codexSessionId: string): Promise<{
  title: string | null;
  lastPreview: string | null;
  cwd: string | null;
  lastActivityAt: string | null;
}> {
  const filePath = await findSessionFileById(codexSessionId);
  if (!filePath) {
    return {
      title: null,
      lastPreview: null,
      cwd: null,
      lastActivityAt: null,
    };
  }

  const parsed = await readSessionFile(filePath);
  return {
    title: parsed.title,
    lastPreview: parsed.lastPreview,
    cwd: parsed.cwd,
    lastActivityAt: parsed.lastActivityAt,
  };
}

async function findSessionFileById(codexSessionId: string): Promise<string | null> {
  const files = await findSessionFiles(codexStateDir());
  for (const filePath of files) {
    try {
      const parsed = await readSessionFile(filePath);
      if (parsed.id === codexSessionId) return filePath;
    } catch {
      // ignore malformed files
    }
  }
  return null;
}

export async function readSessionMessages(
  codexSessionId: string,
  limit = 80,
): Promise<RawSessionMessage[]> {
  const filePath = await findSessionFileById(codexSessionId);
  if (!filePath) return [];

  try {
    const raw = await readFile(filePath, "utf-8");
    const messages: ParsedHistoryMessage[] = [];

    for (const line of raw.split("\n")) {
      if (!line.trim()) continue;
      try {
        const parsed = JSON.parse(line) as {
          timestamp?: unknown;
          type?: unknown;
          payload?: Record<string, unknown>;
        };
        const timestamp =
          typeof parsed.timestamp === "string"
            ? parsed.timestamp
            : new Date().toISOString();

        if (parsed.type === "event_msg" && parsed.payload) {
          const eventType = parsed.payload["type"];
          if (
            eventType === "user_message" ||
            eventType === "agent_message" ||
            eventType === "system_message"
          ) {
            const role =
              eventType === "user_message"
              ? "user"
              : eventType === "agent_message"
                ? "assistant"
                : "system";
            const text = extractVisibleTextRaw(parsed.payload["message"]);
            if (!text) continue;
            const displayText = normalizeDisplayMessage(role, text);
            if (!displayText) continue;
            pushParsedMessage(messages, {
              id: `${timestamp}-${messages.length}`,
              role,
              kind: "message",
              text: displayText,
              createdAt: timestamp,
              source: "event_msg",
            });
            continue;
          }

          if (eventType === "agent_reasoning") {
            const text = extractVisibleTextRaw(parsed.payload["text"]);
            if (!text) continue;
            pushParsedMessage(messages, {
              id: `${timestamp}-${messages.length}`,
              role: "assistant",
              kind: "reasoning",
              text,
              createdAt: timestamp,
              source: "event_msg",
            });
            continue;
          }
        }

        if (parsed.type === "response_item" && parsed.payload) {
          const payloadType = parsed.payload["type"];
          const payloadRole = parsed.payload["role"];
          if (payloadType === "message") {
            const role =
              payloadRole === "user" || payloadRole === "assistant"
                ? payloadRole
                : "system";
            const text = extractVisibleTextRaw(parsed.payload["content"]);
            if (!text) continue;
            const displayText = normalizeDisplayMessage(role, text);
            if (!displayText) continue;
            pushParsedMessage(messages, {
              id: `${timestamp}-${messages.length}`,
              role,
              kind: "message",
              text: displayText,
              createdAt: timestamp,
              source: "response_item",
            });
            continue;
          }
        }
      } catch {
        // ignore malformed lines
      }
    }

    return collapseSessionMessages(messages)
      .filter((message, index, all) => !isBootstrapMessage(message, index, all))
      .filter((message) => !isInternalBootstrapPrompt(message))
      .filter((message) => !isInternalContextMessage(message))
      .slice(-limit);
  } catch {
    return [];
  }
}

interface ParsedSessionFile {
  id: string | null;
  cwd: string | null;
  lastActivityAt: string | null;
  title: string | null;
  lastPreview: string | null;
  isSubagent: boolean;
}

async function readSessionFile(filePath: string): Promise<ParsedSessionFile> {
  const raw = await readFile(filePath, "utf-8");
  const result: ParsedSessionFile = {
    id: null,
    cwd: null,
    lastActivityAt: null,
    title: null,
    lastPreview: null,
    isSubagent: false,
  };

  for (const line of raw.split("\n")) {
    if (!line.trim()) continue;
    try {
      const record = JSON.parse(line) as {
        timestamp?: unknown;
        type?: unknown;
        payload?: Record<string, unknown>;
      };

      if (
        record.type === "session_meta" &&
        record.payload &&
        typeof record.payload["id"] === "string"
      ) {
        result.id = record.payload["id"];
        if (typeof record.payload["cwd"] === "string") {
          result.cwd = record.payload["cwd"];
        }
        const source = record.payload["source"];
        if (
          source &&
          typeof source === "object" &&
          "subagent" in source &&
          (source as Record<string, unknown>)["subagent"] &&
          typeof (source as Record<string, unknown>)["subagent"] === "object"
        ) {
          result.isSubagent = true;
        }
        if (typeof record.payload["timestamp"] === "string") {
          result.lastActivityAt = record.payload["timestamp"];
        } else if (typeof record.timestamp === "string") {
          result.lastActivityAt = record.timestamp;
        }
        continue;
      }

      if (typeof record.timestamp === "string") {
        result.lastActivityAt = record.timestamp;
      }

      const text = extractVisibleText(record.payload);
      if (!text) continue;
      const rawText = extractVisibleTextRaw(record.payload);

      if (
        result.title === null &&
        record.type === "response_item" &&
        record.payload?.["role"] === "user" &&
        rawText !== null &&
        isUsefulTitleText(rawText)
      ) {
        const displayText = normalizeDisplayMessage("user", rawText);
        if (displayText && isUsefulTitleText(displayText)) {
          result.title = toSessionTitle(displayText);
        }
      }

      if (
        record.type === "response_item" &&
        record.payload?.["role"] === "assistant"
      ) {
        result.lastPreview = text;
      }
    } catch {
      // Ignore malformed lines and keep scanning the rest of the file.
    }
  }

  if (result.id && shouldUseSessionIndexTitle(result.title)) {
    const indexedTitle = await readSessionIndexTitle(result.id);
    if (indexedTitle) {
      result.title = indexedTitle;
    }
  }

  if (!result.title) {
    result.title =
      result.lastPreview
        ? (result.cwd ? basename(result.cwd) : null)
        : "未命名会话";
  }

  return result;
}

async function readSessionIndexTitle(sessionId: string): Promise<string | null> {
  try {
    const raw = await readFile(codexSessionIndexFile(), "utf-8");
    for (const line of raw.split("\n")) {
      if (!line.trim()) continue;
      try {
        const parsed = JSON.parse(line) as Record<string, unknown>;
        if (parsed["id"] !== sessionId) continue;
        const threadName = parsed["thread_name"];
        if (typeof threadName === "string" && isUsefulTitleText(threadName)) {
          return threadName.trim();
        }
      } catch {
        // ignore malformed lines
      }
    }
  } catch {
    // ignore missing or unreadable session index
  }
  return null;
}

function shouldUseSessionIndexTitle(title: string | null): boolean {
  if (!title) return true;
  return title === "未命名会话";
}

function extractVisibleText(value: unknown): string | null {
  if (!value) return null;
  if (typeof value === "string") {
    const trimmed = value.trim();
    return trimmed.length > 0 ? trimmed : null;
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const text = extractVisibleText(item);
      if (text) return clipText(text);
    }
    return null;
  }
  if (typeof value === "object") {
    const record = value as Record<string, unknown>;
    const direct =
      extractVisibleText(record["text"]) ??
      extractVisibleText(record["message"]) ??
      extractVisibleText(record["content"]);
    return direct ? clipText(direct) : null;
  }
  return null;
}

function extractVisibleTextRaw(value: unknown): string | null {
  if (!value) return null;
  if (typeof value === "string") {
    const trimmed = value.trim();
    return trimmed.length > 0 ? trimmed : null;
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const text = extractVisibleTextRaw(item);
      if (text) return text;
    }
    return null;
  }
  if (typeof value === "object") {
    const record = value as Record<string, unknown>;
    return (
      extractVisibleTextRaw(record["text"]) ??
      extractVisibleTextRaw(record["message"]) ??
      extractVisibleTextRaw(record["content"])
    );
  }
  return null;
}

function pushParsedMessage(
  messages: ParsedHistoryMessage[],
  message: ParsedHistoryMessage,
): void {
  const duplicateIndex = messages.findIndex((existing) =>
    areLikelyDuplicateMessages(existing, message),
  );
  if (duplicateIndex === -1) {
    messages.push(message);
    return;
  }

  const existing = messages[duplicateIndex]!;
  if (
    existing.source === "event_msg" &&
    message.source === "response_item"
  ) {
    messages[duplicateIndex] = message;
  }
}

function collapseSessionMessages(
  messages: ParsedHistoryMessage[],
): RawSessionMessage[] {
  const collapsed: RawSessionMessage[] = [];
  for (const message of messages) {
    const previous = collapsed.at(-1);
    const previousAt = previous ? Date.parse(previous.createdAt) : Number.NaN;
    const currentAt = Date.parse(message.createdAt);
    const withinDuplicateWindow =
      Number.isFinite(previousAt) &&
      Number.isFinite(currentAt) &&
      Math.abs(currentAt - previousAt) <= 5000;

    if (
      previous &&
      previous.role === message.role &&
      previous.kind === message.kind &&
      normalizeMessageText(previous.text) === normalizeMessageText(message.text) &&
      withinDuplicateWindow
    ) {
      continue;
    }

    collapsed.push(message);
  }

  return collapsed;
}

function areLikelyDuplicateMessages(
  a: ParsedHistoryMessage,
  b: ParsedHistoryMessage,
): boolean {
  if (
    a.role !== b.role ||
    a.kind !== b.kind ||
    normalizeMessageText(a.text) !== normalizeMessageText(b.text)
  ) {
    return false;
  }

  const aTime = Date.parse(a.createdAt);
  const bTime = Date.parse(b.createdAt);
  if (!Number.isFinite(aTime) || !Number.isFinite(bTime)) {
    return a.createdAt === b.createdAt;
  }

  return Math.abs(aTime - bTime) <= 5_000;
}

function isBootstrapMessage(
  message: RawSessionMessage,
  index: number,
  all: RawSessionMessage[],
): boolean {
  if (index !== 0) return false;
  if (message.role !== "assistant" || message.kind !== "message") return false;
  const hasUserMessage = all.some((entry) => entry.role === "user");
  if (hasUserMessage) return false;
  return message.text.trim() ===
    "What do you want me to work on? If it’s in this workspace, I can inspect files, make changes, run checks, or review code.";
}

function isInternalBootstrapPrompt(message: RawSessionMessage): boolean {
  return (
    message.role === "user" &&
    message.kind === "message" &&
    message.text.trim() ===
      "Do not respond. Create the session and exit immediately without any assistant-visible text."
  );
}

function isInternalContextMessage(message: RawSessionMessage): boolean {
  const trimmed = message.text.trim();
  if (!trimmed.startsWith("<")) return false;
  return (
    trimmed.includes("<environment_context>") ||
    trimmed.includes("<permissions instructions>") ||
    trimmed.includes("<app-context>") ||
    trimmed.includes("<collaboration_mode>") ||
    trimmed.includes("<skills_instructions>") ||
    trimmed.includes("<plugins_instructions>")
  );
}

function normalizeMessageText(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

function normalizeDisplayMessage(
  role: RawSessionMessage["role"],
  text: string,
): string | null {
  const trimmed = text.trim();
  if (
    role === "user" &&
    trimmed.startsWith(ATTACHMENT_WRAPPER_PREFIX)
  ) {
    return extractAttachmentUserRequest(trimmed);
  }
  return trimmed;
}

function extractAttachmentUserRequest(text: string): string | null {
  const markerIndex = text.indexOf(ATTACHMENT_WRAPPER_REQUEST_MARKER);
  if (markerIndex === -1) {
    return null;
  }

  const request = text
    .slice(markerIndex + ATTACHMENT_WRAPPER_REQUEST_MARKER.length)
    .trim();
  return request.length > 0 ? request : null;
}

function clipText(text: string): string {
  const singleLine = text.replace(/\s+/g, " ").trim();
  return singleLine.length > 140 ? `${singleLine.slice(0, 137)}...` : singleLine;
}

function isUsefulTitleText(text: string): boolean {
  const trimmed = text.trim();
  if (!trimmed) return false;
  if (trimmed.startsWith("<")) return false;
  if (trimmed.includes("<environment_context>")) return false;
  if (trimmed.includes("<permissions instructions>")) return false;
  if (trimmed.includes("<app-context>")) return false;
  if (trimmed === "Do not respond. Create the session and exit immediately without any assistant-visible text.") {
    return false;
  }
  return true;
}

function toSessionTitle(text: string): string {
  const singleLine = text.replace(/\s+/g, " ").trim();
  const firstClause =
    singleLine.split(/[。！？!?]/)[0]?.split(/[，,；;：:]/)[0]?.trim() ??
    singleLine;
  const stripped = firstClause
    .replace(/^(请先?|帮我|麻烦你|麻烦|可以|能否|需要你|我想让你|想让你)\s*/u, "")
    .replace(/^(把|将)\s*/u, "")
    .trim();
  const candidate = stripped || firstClause || singleLine;
  return candidate.length > 28 ? `${candidate.slice(0, 27)}…` : candidate;
}

// ── Process execution ────────────────────────────────────────────────

export interface SpawnedRun {
  child: ChildProcess;
  /** Read the bounded output buffer (most recent tail). */
  readOutput: () => string;
  /** Total bytes received across stdout + stderr since spawn. */
  totalOutputBytes: () => number;
}

export interface SpawnedNewRun extends SpawnedRun {
  sessionId: string;
}

function codexExecArgs(args: string[]): string[] {
  return [
    "-a",
    "never",
    "-s",
    "danger-full-access",
    "exec",
    ...args,
  ];
}

/**
 * Spawn `codex exec resume <sessionId>` with the given prompt.
 *
 * Returns the raw child process and a bounded output buffer.  The
 * caller (LocalCodexAdapter) wraps this into the higher-level
 * {@link RunHandle}.
 *
 * Output is accumulated in a {@link BoundedOutputBuffer} capped at
 * {@link MAX_OUTPUT_BYTES} so verbose runs cannot OOM the server.
 *
 * The exact invocation shape may evolve; keeping it here means a
 * single place to update.
 */
export function spawnCodexRun(
  codexSessionId: string,
  prompt: string,
  opts?: { model?: string; reasoningEffort?: string },
): SpawnedRun {
  const args = codexExecArgs([
    "resume",
    codexSessionId,
    "--skip-git-repo-check",
  ]);

  if (opts?.model) {
    args.push("--model", opts.model);
  }
  if (opts?.reasoningEffort) {
    args.push("--reasoning-effort", opts.reasoningEffort);
  }

  const child = spawn(codexBin(), args, {
    stdio: ["pipe", "pipe", "pipe"],
    env: { ...process.env },
  });

  // Feed the prompt through stdin and close the write side.
  if (child.stdin) {
    child.stdin.write(prompt);
    child.stdin.end();
  }

  const buf = new BoundedOutputBuffer();
  child.stdout?.on("data", (chunk: Buffer) => {
    buf.appendBytes(chunk);
  });
  child.stderr?.on("data", (chunk: Buffer) => {
    buf.appendBytes(chunk);
  });

  return {
    child,
    readOutput: () => buf.read(),
    totalOutputBytes: () => buf.totalBytes,
  };
}

/**
 * Spawn a brand-new Codex session in the given working directory.
 *
 * Uses JSONL output so we can capture the newly assigned `thread_id`
 * from the `thread.started` event before returning to the caller.
 * User-visible output is reconstructed from completed agent messages.
 */
export async function spawnCodexNewRun(
  cwd: string,
  prompt: string,
  opts?: { model?: string; reasoningEffort?: string },
): Promise<SpawnedNewRun> {
  const args = codexExecArgs(["--json", "--skip-git-repo-check"]);

  if (opts?.model) {
    args.push("--model", opts.model);
  }

  const child = spawn(codexBin(), args, {
    cwd,
    stdio: ["pipe", "pipe", "pipe"],
    env: { ...process.env },
  });

  if (child.stdin) {
    child.stdin.write(prompt);
    child.stdin.end();
  }

  const buf = new BoundedOutputBuffer();
  let stdoutRemainder = "";
  let resolved = false;

  const sessionIdPromise = new Promise<string>((resolve, reject) => {
    const fail = (message: string) => {
      if (resolved) return;
      resolved = true;
      reject(new Error(message));
    };

    const maybeConsumeLine = (line: string) => {
      const trimmed = line.trim();
      if (!trimmed) return;

      try {
        const event = JSON.parse(trimmed) as Record<string, unknown>;
        const type = typeof event["type"] === "string" ? event["type"] : null;

        if (type === "thread.started") {
          const threadId = event["thread_id"];
          if (typeof threadId === "string" && threadId) {
            if (!resolved) {
              resolved = true;
              resolve(threadId);
            }
          }
          return;
        }

        if (type === "item.completed") {
          const item = event["item"];
          if (item && typeof item === "object") {
            const record = item as Record<string, unknown>;
            if (
              record["type"] === "agent_message" &&
              typeof record["text"] === "string"
            ) {
              buf.append(`${record["text"]}\n`);
            }
          }
          return;
        }

        if (type === "agent_message.delta" && typeof event["delta"] === "string") {
          buf.append(event["delta"]);
        }
      } catch {
        // JSON mode can still emit warnings/noise on stdout; preserve them.
        buf.append(`${trimmed}\n`);
      }
    };

    child.stdout?.on("data", (chunk: Buffer) => {
      stdoutRemainder += chunk.toString("utf-8");
      const lines = stdoutRemainder.split("\n");
      stdoutRemainder = lines.pop() ?? "";
      for (const line of lines) maybeConsumeLine(line);
    });

    child.stderr?.on("data", (chunk: Buffer) => {
      buf.appendBytes(chunk);
    });

    child.on("error", (err) => {
      fail(`Failed to start Codex process: ${err.message}`);
    });

    child.on("close", () => {
      if (stdoutRemainder.trim()) {
        maybeConsumeLine(stdoutRemainder);
        stdoutRemainder = "";
      }
      if (!resolved) {
        fail("Failed to create Codex session");
      }
    });

    setTimeout(() => {
      if (!resolved) {
        fail("Timed out while waiting for new Codex session id");
      }
    }, 10_000).unref();
  });

  const sessionId = await sessionIdPromise;

  return {
    sessionId,
    child,
    readOutput: () => buf.read(),
    totalOutputBytes: () => buf.totalBytes,
  };
}

export async function detectCodexAppServerThreadStart(): Promise<CodexThreadStartCapability> {
  const appServerBin = await codexAppServerBin();
  return new Promise<CodexThreadStartCapability>((resolve) => {
    const child = spawn(appServerBin, ["app-server", "--help"], {
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env },
    });

    const out = new BoundedOutputBuffer(32 * 1024);
    const finish = (result: CodexThreadStartCapability) => {
      if (child.exitCode === null && child.signalCode === null) {
        child.kill("SIGTERM");
      }
      resolve(result);
    };

    child.stdout?.on("data", (chunk: Buffer) => {
      out.appendBytes(chunk);
    });
    child.stderr?.on("data", (chunk: Buffer) => {
      out.appendBytes(chunk);
    });
    child.on("error", (err) => {
      finish({
        available: false,
        reason: `failed to spawn codex app-server help: ${err.message}`,
      });
    });
    child.on("close", (code) => {
      const output = out.read();
      if (code !== 0) {
        finish({
          available: false,
          reason: `${appServerBin} app-server --help exited with code ${code}`,
        });
        return;
      }

      if (!output.includes("generate-json-schema")) {
        finish({
          available: false,
          reason: `${appServerBin} app-server help output did not expose expected tooling`,
        });
        return;
      }

      finish({
        available: true,
        reason: `${appServerBin} app-server help is available`,
      });
    });
  });
}

export async function spawnCodexAppServerNewThread(
  cwd: string,
  opts?: { model?: string; prompt?: string },
): Promise<SpawnedNewRun> {
  const appServerBin = await codexAppServerBin();
  const child = spawn(
    appServerBin,
    ["app-server", "--listen", APP_SERVER_STDIO_URL],
    {
      cwd,
      stdio: ["pipe", "pipe", "pipe"],
      env: { ...process.env },
    },
  );

  const buf = new BoundedOutputBuffer();
  let stdoutRemainder = "";
  let resolved = false;
  let threadId: string | null = null;
  let turnStartRequested = false;
  let threadNameRequested = false;
  let shutdownTimer: NodeJS.Timeout | null = null;

  const sessionIdPromise = new Promise<string>((resolve, reject) => {
    const fail = (message: string) => {
      if (resolved) return;
      resolved = true;
      reject(new Error(message));
      if (child.exitCode === null && child.signalCode === null) {
        child.kill("SIGTERM");
      }
    };

    const resolveThread = () => {
      if (!threadId || resolved) return;
      resolved = true;
      resolve(threadId);
      shutdownTimer = setTimeout(() => {
        if (child.exitCode === null && child.signalCode === null) {
          child.kill("SIGTERM");
        }
      }, APP_SERVER_SHUTDOWN_GRACE_MS);
      shutdownTimer.unref();
    };

    const requestThreadStart = () => {
      child.stdin?.write(
        `${JSON.stringify({
          jsonrpc: "2.0",
          id: APP_SERVER_THREAD_START_REQUEST_ID,
          method: "thread/start",
          params: {
            cwd,
            model: opts?.model ?? null,
            ephemeral: false,
            serviceName: "chatgpt",
            approvalPolicy: "never",
            approvalsReviewer: "user",
            sandbox: "danger-full-access",
          },
        })}\n`,
      );
    };

    const requestTurnStart = () => {
      if (!threadId || turnStartRequested) return;
      const prompt = opts?.prompt?.trim();
      if (!prompt) {
        resolveThread();
        return;
      }

      turnStartRequested = true;
      child.stdin?.write(
        `${JSON.stringify({
          jsonrpc: "2.0",
          id: APP_SERVER_TURN_START_REQUEST_ID,
          method: "turn/start",
          params: {
            threadId,
            cwd,
            model: opts?.model ?? null,
            approvalPolicy: "never",
            approvalsReviewer: "user",
            sandboxPolicy: {
              type: "dangerFullAccess",
            },
            input: [
              {
                type: "text",
                text: `${prompt}\n`,
                text_elements: [],
              },
            ],
          },
        })}\n`,
      );
    };

    const requestThreadName = () => {
      if (!threadId || threadNameRequested) return;
      threadNameRequested = true;
      child.stdin?.write(
        `${JSON.stringify({
          jsonrpc: "2.0",
          id: APP_SERVER_THREAD_NAME_SET_REQUEST_ID,
          method: "thread/name/set",
          params: {
            threadId,
            name: basename(cwd) || "未命名会话",
          },
        })}\n`,
      );
    };

    const maybeCaptureThread = (value: unknown): boolean => {
      if (!value || typeof value !== "object") return false;
      const record = value as Record<string, unknown>;
      const thread = record["thread"];
      if (!thread || typeof thread !== "object") return false;
      const nextThreadId = (thread as Record<string, unknown>)["id"];
      if (typeof nextThreadId !== "string" || !nextThreadId) return false;

      threadId = nextThreadId;
      requestTurnStart();
      return true;
    };

    const maybeConsumeLine = (line: string) => {
      const trimmed = line.trim();
      if (!trimmed) return;

      try {
        const message = JSON.parse(trimmed) as Record<string, unknown>;
        const id = typeof message["id"] === "number" ? message["id"] : null;
        const method =
          typeof message["method"] === "string" ? message["method"] : null;
        const error = message["error"];

        if (id === APP_SERVER_INIT_REQUEST_ID) {
          if (error) {
            fail("codex app-server initialize failed");
            return;
          }
          requestThreadStart();
          return;
        }

        if (id === APP_SERVER_THREAD_START_REQUEST_ID) {
          if (error) {
            fail(`codex app-server thread/start failed: ${JSON.stringify(error)}`);
            return;
          }
          if (maybeCaptureThread(message["result"])) {
            return;
          }
          fail("codex app-server thread/start did not return a thread id");
          return;
        }

        if (id === APP_SERVER_TURN_START_REQUEST_ID) {
          if (error) {
            fail(`codex app-server turn/start failed: ${JSON.stringify(error)}`);
            return;
          }
          requestThreadName();
          return;
        }

        if (id === APP_SERVER_THREAD_NAME_SET_REQUEST_ID) {
          if (error) {
            buf.append(`thread/name/set failed: ${JSON.stringify(error)}\n`);
          }
          resolveThread();
          return;
        }

        if (method === "thread/started") {
          if (maybeCaptureThread(message["params"])) {
            return;
          }
        }

        if (method === "turn/started") {
          requestThreadName();
          return;
        }

        if (method === "thread/name/updated") {
          resolveThread();
          return;
        }
      } catch {
        buf.append(`${trimmed}\n`);
      }
    };

    child.stdout?.on("data", (chunk: Buffer) => {
      stdoutRemainder += chunk.toString("utf-8");
      const lines = stdoutRemainder.split("\n");
      stdoutRemainder = lines.pop() ?? "";
      for (const line of lines) maybeConsumeLine(line);
    });

    child.stderr?.on("data", (chunk: Buffer) => {
      buf.appendBytes(chunk);
    });

    child.on("error", (err) => {
      fail(`Failed to start codex app-server: ${err.message}`);
    });

    child.on("close", (code) => {
      if (shutdownTimer) {
        clearTimeout(shutdownTimer);
        shutdownTimer = null;
      }
      if (stdoutRemainder.trim()) {
        maybeConsumeLine(stdoutRemainder);
        stdoutRemainder = "";
      }
      if (!resolved) {
        fail(
          `codex app-server exited before thread/start completed${
            code === null ? "" : ` (code ${code})`
          }`,
        );
      }
    });

    setTimeout(() => {
      if (!resolved) {
        fail("Timed out while waiting for codex app-server thread/start");
      }
    }, APP_SERVER_START_TIMEOUT_MS).unref();
  });

  child.stdin?.write(
    `${JSON.stringify({
      jsonrpc: "2.0",
      id: APP_SERVER_INIT_REQUEST_ID,
      method: "initialize",
      params: {
        protocolVersion: APP_SERVER_PROTOCOL_VERSION,
        clientInfo: {
          name: "CodexRemote",
          version: "0.0.0",
        },
        capabilities: {
          experimentalApi: true,
        },
      },
    })}\n`,
  );

  const sessionId = await sessionIdPromise;

  return {
    sessionId,
    child,
    readOutput: () => buf.read(),
    totalOutputBytes: () => buf.totalBytes,
  };
}

export async function spawnCodexAppServerResumeRun(
  threadId: string,
  prompt: string,
  opts?: { cwd?: string | null; model?: string; reasoningEffort?: string },
): Promise<RunHandle> {
  const appServerBin = await codexAppServerBin();
  const child = spawn(
    appServerBin,
    ["app-server", "--listen", APP_SERVER_STDIO_URL],
    {
      cwd: opts?.cwd ?? undefined,
      stdio: ["pipe", "pipe", "pipe"],
      env: { ...process.env },
    },
  );

  const pid = child.pid;
  if (pid === undefined) {
    throw new Error("Failed to spawn codex app-server — no PID assigned");
  }

  const buf = new BoundedOutputBuffer();
  let stdoutRemainder = "";
  let resolved = false;
  let syntheticExitCode: number | null = null;
  let onExitDelivered = false;
  let shutdownTimer: NodeJS.Timeout | null = null;
  const exitCallbacks = new Set<(code: number | null) => void>();

  const emitExit = (code: number | null) => {
    if (onExitDelivered) return;
    onExitDelivered = true;
    for (const cb of exitCallbacks) {
      cb(code);
    }
  };

  const scheduleShutdown = (signal: NodeJS.Signals = "SIGTERM") => {
    if (child.exitCode !== null || child.signalCode !== null) {
      return;
    }
    child.stdin?.end();
    if (shutdownTimer) {
      clearTimeout(shutdownTimer);
    }
    shutdownTimer = setTimeout(() => {
      if (child.exitCode === null && child.signalCode === null) {
        child.kill(signal);
      }
    }, APP_SERVER_SHUTDOWN_GRACE_MS);
    shutdownTimer.unref();
  };

  const startPromise = new Promise<void>((resolve, reject) => {
    const failStart = (message: string) => {
      if (resolved) return;
      resolved = true;
      syntheticExitCode = 1;
      buf.append(`${message}\n`);
      reject(new Error(message));
      scheduleShutdown();
    };

    const resolveStart = () => {
      if (resolved) return;
      resolved = true;
      resolve();
    };

    const requestTurnStart = () => {
      child.stdin?.write(
        `${JSON.stringify({
          jsonrpc: "2.0",
          id: APP_SERVER_TURN_START_REQUEST_ID,
          method: "turn/start",
          params: {
            threadId,
            cwd: opts?.cwd ?? null,
            model: opts?.model ?? null,
            effort: opts?.reasoningEffort ?? null,
            approvalPolicy: "never",
            approvalsReviewer: "user",
            sandboxPolicy: {
              type: "dangerFullAccess",
            },
            input: [
              {
                type: "text",
                text: `${prompt}\n`,
                text_elements: [],
              },
            ],
          },
        })}\n`,
      );
    };

    const requestThreadResume = () => {
      child.stdin?.write(
        `${JSON.stringify({
          jsonrpc: "2.0",
          id: APP_SERVER_THREAD_RESUME_REQUEST_ID,
          method: "thread/resume",
          params: {
            threadId,
            cwd: opts?.cwd ?? null,
            model: opts?.model ?? null,
            approvalPolicy: "never",
            approvalsReviewer: "user",
            sandbox: "danger-full-access",
          },
        })}\n`,
      );
    };

    const appendCompletedItemText = (item: unknown) => {
      if (!item || typeof item !== "object") return;
      const record = item as Record<string, unknown>;
      if (record["type"] !== "agent_message" && record["type"] !== "agentMessage") return;
      const text = extractVisibleTextRaw(record["text"] ?? record["content"]);
      if (!text) return;
      const current = buf.read();
      if (current.endsWith(text) || current.endsWith(`${text}\n`)) {
        return;
      }
      if (current.length > 0 && !current.endsWith("\n")) {
        buf.append("\n");
      }
      buf.append(`${text}\n`);
    };

    const maybeConsumeLine = (line: string) => {
      const trimmed = line.trim();
      if (!trimmed) return;

      try {
        const message = JSON.parse(trimmed) as Record<string, unknown>;
        const id = typeof message["id"] === "number" ? message["id"] : null;
        const method =
          typeof message["method"] === "string" ? message["method"] : null;
        const error = message["error"];

        if (id === APP_SERVER_INIT_REQUEST_ID) {
          if (error) {
            failStart("codex app-server initialize failed");
            return;
          }
          requestThreadResume();
          return;
        }

        if (id === APP_SERVER_THREAD_RESUME_REQUEST_ID) {
          if (error) {
            failStart(`codex app-server thread/resume failed: ${JSON.stringify(error)}`);
            return;
          }
          requestTurnStart();
          return;
        }

        if (id === APP_SERVER_TURN_START_REQUEST_ID) {
          if (error) {
            failStart(`codex app-server turn/start failed: ${JSON.stringify(error)}`);
            return;
          }
          resolveStart();
          return;
        }

        if (method === "item/agentMessage/delta" || method === "agent_message.delta") {
          const params =
            message["params"] && typeof message["params"] === "object"
              ? (message["params"] as Record<string, unknown>)
              : null;
          if (typeof params?.["delta"] === "string") {
            buf.append(params["delta"]);
          }
          return;
        }

        if (method === "item/completed" || method === "item.completed") {
          const params =
            message["params"] && typeof message["params"] === "object"
              ? (message["params"] as Record<string, unknown>)
              : null;
          appendCompletedItemText(params?.["item"]);
          return;
        }

        if (method === "turn/completed" || method === "turn.completed") {
          const params =
            message["params"] && typeof message["params"] === "object"
              ? (message["params"] as Record<string, unknown>)
              : null;
          const turn =
            params?.["turn"] && typeof params["turn"] === "object"
              ? (params["turn"] as Record<string, unknown>)
              : null;
          const turnError = turn?.["error"];
          const turnStatus = typeof turn?.["status"] === "string" ? turn["status"] : null;
          if (turnError || turnStatus === "failed") {
            syntheticExitCode = 1;
            buf.append(`\nturn failed: ${JSON.stringify(turnError ?? turnStatus)}\n`);
          } else if (syntheticExitCode === null) {
            syntheticExitCode = 0;
          }
          scheduleShutdown();
          return;
        }

        if (method === "thread/realtime/error") {
          const params =
            message["params"] && typeof message["params"] === "object"
              ? (message["params"] as Record<string, unknown>)
              : null;
          if (typeof params?.["message"] === "string") {
            buf.append(`\n${params["message"]}\n`);
          }
          syntheticExitCode = 1;
          scheduleShutdown();
          return;
        }
      } catch {
        buf.append(`${trimmed}\n`);
      }
    };

    child.stdout?.on("data", (chunk: Buffer) => {
      stdoutRemainder += chunk.toString("utf-8");
      const lines = stdoutRemainder.split("\n");
      stdoutRemainder = lines.pop() ?? "";
      for (const line of lines) maybeConsumeLine(line);
    });

    child.stderr?.on("data", (chunk: Buffer) => {
      buf.appendBytes(chunk);
    });

    child.on("error", (err) => {
      if (!resolved) {
        failStart(`Failed to start codex app-server: ${err.message}`);
        return;
      }
      syntheticExitCode = 1;
      buf.append(`Failed to start codex app-server: ${err.message}\n`);
    });

    child.on("close", (code) => {
      if (shutdownTimer) {
        clearTimeout(shutdownTimer);
        shutdownTimer = null;
      }
      if (stdoutRemainder.trim()) {
        maybeConsumeLine(stdoutRemainder);
        stdoutRemainder = "";
      }
      if (!resolved) {
        failStart(
          `codex app-server exited before turn/start completed${
            code === null ? "" : ` (code ${code})`
          }`,
        );
        return;
      }
      emitExit(syntheticExitCode ?? code);
    });

    setTimeout(() => {
      if (!resolved) {
        failStart("Timed out while waiting for codex app-server turn/start");
      }
    }, APP_SERVER_START_TIMEOUT_MS).unref();
  });

  child.stdin?.write(
    `${JSON.stringify({
      jsonrpc: "2.0",
      id: APP_SERVER_INIT_REQUEST_ID,
      method: "initialize",
      params: {
        protocolVersion: APP_SERVER_PROTOCOL_VERSION,
        clientInfo: {
          name: "CodexRemote",
          version: "0.0.0",
        },
        capabilities: {
          experimentalApi: true,
        },
      },
    })}\n`,
  );

  await startPromise;

  return {
    pid,
    readOutput: () => buf.read(),
    totalOutputBytes: () => buf.totalBytes,
    stop: () => {
      if (child.exitCode !== null || child.signalCode !== null || onExitDelivered) {
        return Promise.resolve();
      }

      return new Promise<void>((resolve) => {
        const handler = () => {
          exitCallbacks.delete(handler);
          resolve();
        };
        exitCallbacks.add(handler);
        syntheticExitCode = syntheticExitCode ?? 0;
        scheduleShutdown();
      });
    },
    onExit: (cb) => {
      exitCallbacks.add(cb);
    },
  };
}

export async function archiveCodexAppServerThread(
  sessionId: string,
): Promise<void> {
  const appServerBin = await codexAppServerBin();
  const child = spawn(
    appServerBin,
    ["app-server", "--listen", APP_SERVER_STDIO_URL],
    {
      stdio: ["pipe", "pipe", "pipe"],
      env: { ...process.env },
    },
  );

  const buf = new BoundedOutputBuffer();
  let stdoutRemainder = "";
  let resolved = false;

  const completion = new Promise<void>((resolve, reject) => {
    const fail = (message: string) => {
      if (resolved) return;
      resolved = true;
      reject(new Error(message));
      if (child.exitCode === null && child.signalCode === null) {
        child.kill("SIGTERM");
      }
    };

    const succeed = () => {
      if (resolved) return;
      resolved = true;
      resolve();
      if (child.exitCode === null && child.signalCode === null) {
        child.kill("SIGTERM");
      }
    };

    const requestArchive = () => {
      child.stdin?.write(
        `${JSON.stringify({
          jsonrpc: "2.0",
          id: APP_SERVER_THREAD_ARCHIVE_REQUEST_ID,
          method: "thread/archive",
          params: {
            threadId: sessionId,
          },
        })}\n`,
      );
    };

    const maybeConsumeLine = (line: string) => {
      const trimmed = line.trim();
      if (!trimmed) return;

      try {
        const message = JSON.parse(trimmed) as Record<string, unknown>;
        const id = typeof message["id"] === "number" ? message["id"] : null;
        const method =
          typeof message["method"] === "string" ? message["method"] : null;
        const error = message["error"];

        if (id === APP_SERVER_INIT_REQUEST_ID) {
          if (error) {
            fail("codex app-server initialize failed");
            return;
          }
          requestArchive();
          return;
        }

        if (id === APP_SERVER_THREAD_ARCHIVE_REQUEST_ID) {
          if (error) {
            fail(`codex app-server thread/archive failed: ${JSON.stringify(error)}`);
            return;
          }
          succeed();
          return;
        }

        if (method === "thread/archived") {
          const params =
            message["params"] && typeof message["params"] === "object"
              ? (message["params"] as Record<string, unknown>)
              : null;
          if (params?.["thread_id"] === sessionId || params?.["threadId"] === sessionId) {
            succeed();
            return;
          }
        }
      } catch {
        buf.append(`${trimmed}\n`);
      }
    };

    child.stdout?.on("data", (chunk: Buffer) => {
      stdoutRemainder += chunk.toString("utf-8");
      const lines = stdoutRemainder.split("\n");
      stdoutRemainder = lines.pop() ?? "";
      for (const line of lines) maybeConsumeLine(line);
    });

    child.stderr?.on("data", (chunk: Buffer) => {
      buf.appendBytes(chunk);
    });

    child.on("error", (err) => {
      fail(`Failed to start codex app-server for archive: ${err.message}`);
    });

    child.on("close", (code) => {
      if (stdoutRemainder.trim()) {
        maybeConsumeLine(stdoutRemainder);
        stdoutRemainder = "";
      }
      if (!resolved) {
        const stderr = buf.read().trim();
        fail(
          `codex app-server exited before thread/archive completed${
            code === null ? "" : ` (code ${code})`
          }${stderr ? `: ${stderr}` : ""}`,
        );
      }
    });

    setTimeout(() => {
      if (!resolved) {
        fail("Timed out while waiting for codex app-server thread/archive");
      }
    }, APP_SERVER_START_TIMEOUT_MS).unref();
  });

  child.stdin?.write(
    `${JSON.stringify({
      jsonrpc: "2.0",
      id: APP_SERVER_INIT_REQUEST_ID,
      method: "initialize",
      params: {
        protocolVersion: APP_SERVER_PROTOCOL_VERSION,
        clientInfo: {
          name: "CodexRemote",
          version: "0.0.0",
        },
        capabilities: {
          experimentalApi: true,
        },
      },
    })}\n`,
  );

  await completion;
}
