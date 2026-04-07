/**
 * Centralised configuration parsing, validation, and operator-visible echo.
 *
 * Every environment variable that configures runtime behaviour is parsed
 * here so that:
 *
 * 1. Invalid values (typos, non-numeric strings) are caught at startup
 *    rather than silently falling back to defaults.
 * 2. Cross-constraints (e.g. upload stall timeout < request timeout) are
 *    enforced in one place.
 * 3. The effective configuration is logged on startup so operators can
 *    verify their setup without reading source code.
 *
 * Other modules import the exported constants instead of parsing env
 * vars themselves.
 */

import { homedir } from "node:os";
import { join } from "node:path";
import { DEFAULT_ALLOWED_UPLOAD_MIME_PATTERNS } from "@codexremote/shared";

// ── Parse result type ────────────────────────────────────────────────

export interface ConfigParseResult {
  port: number;
  host: string;
  requestTimeoutMs: number;
  connectionTimeoutMs: number;
  runTimeoutMs: number;
  shutdownTimeoutMs: number;
  sseIdleTimeoutMs: number;
  uploadStreamTimeoutMs: number;
  maxOutputBytes: number;
  dataDir: string;
  codexBin: string;
  codexStateDir: string;
  sseWriteBufferMax: number;
  globalRateLimitMax: number;
  globalRateLimitWindowMs: number;
  authRateLimitMax: number;
  authRateLimitWindowMs: number;
  minFreeDiskBytes: number;
  allowedUploadMimePatterns: readonly string[];
  /** Env vars that were explicitly set but couldn't be parsed. */
  issues: string[];
}

// ── Parse helpers ────────────────────────────────────────────────────

function parsePositiveInt(
  env: Record<string, string | undefined>,
  name: string,
  defaultValue: number,
  issues: string[],
): number {
  const raw = env[name];
  if (raw === undefined || raw === "") return defaultValue;
  const parsed = parseInt(raw, 10);
  if (Number.isNaN(parsed) || parsed <= 0) {
    issues.push(
      `${name}="${raw}" is not a valid positive integer (using default ${defaultValue})`,
    );
    return defaultValue;
  }
  return parsed;
}

function parsePort(
  env: Record<string, string | undefined>,
  issues: string[],
): number {
  const raw = env["PORT"];
  if (raw === undefined || raw === "") return 3000;
  const parsed = parseInt(raw, 10);
  if (Number.isNaN(parsed) || parsed < 1 || parsed > 65535) {
    issues.push(`PORT="${raw}" is not a valid port number (1–65535)`);
    return 3000;
  }
  return parsed;
}

/**
 * Parse CODEXREMOTE_ALLOWED_UPLOAD_TYPES — a comma-separated list of
 * additional MIME patterns appended to the built-in defaults.
 *
 * Empty/whitespace-only entries are silently dropped.
 */
function parseAllowedMimePatterns(
  env: Record<string, string | undefined>,
): readonly string[] {
  const raw = env["CODEXREMOTE_ALLOWED_UPLOAD_TYPES"];
  if (!raw || raw.trim() === "") return DEFAULT_ALLOWED_UPLOAD_MIME_PATTERNS;

  const extras = raw
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s.length > 0);

  // Merge: defaults + operator additions (deduped).
  const merged = new Set([...DEFAULT_ALLOWED_UPLOAD_MIME_PATTERNS, ...extras]);
  return [...merged];
}

// ── Core parse function (exported for testing) ──────────────────────

/**
 * Parse all configuration from an environment-variable-like object.
 *
 * Invalid numeric values are collected in the `issues` array and the
 * corresponding default is used so the constant is always a usable
 * number.  Call {@link validateParsedConfig} to turn collected issues
 * into a hard startup failure.
 */
export function parseConfig(
  env: Record<string, string | undefined> = process.env as Record<
    string,
    string | undefined
  >,
): ConfigParseResult {
  const issues: string[] = [];

  return {
    port: parsePort(env, issues),
    host: env["HOST"] ?? "127.0.0.1",
    requestTimeoutMs: parsePositiveInt(
      env,
      "CODEXREMOTE_REQUEST_TIMEOUT_MS",
      60_000,
      issues,
    ),
    connectionTimeoutMs: parsePositiveInt(
      env,
      "CODEXREMOTE_CONNECTION_TIMEOUT_MS",
      30_000,
      issues,
    ),
    runTimeoutMs: parsePositiveInt(
      env,
      "CODEXREMOTE_RUN_TIMEOUT_MS",
      30 * 60 * 1000,
      issues,
    ),
    shutdownTimeoutMs: parsePositiveInt(
      env,
      "CODEXREMOTE_SHUTDOWN_TIMEOUT_MS",
      30_000,
      issues,
    ),
    sseIdleTimeoutMs: parsePositiveInt(
      env,
      "CODEXREMOTE_SSE_IDLE_TIMEOUT_MS",
      5 * 60 * 1000,
      issues,
    ),
    uploadStreamTimeoutMs: parsePositiveInt(
      env,
      "CODEXREMOTE_UPLOAD_STREAM_TIMEOUT_MS",
      30_000,
      issues,
    ),
    maxOutputBytes: parsePositiveInt(
      env,
      "CODEXREMOTE_MAX_OUTPUT_BYTES",
      512 * 1024,
      issues,
    ),
    sseWriteBufferMax: parsePositiveInt(
      env,
      "CODEXREMOTE_SSE_WRITE_BUFFER_MAX",
      1024 * 1024,
      issues,
    ),
    globalRateLimitMax: parsePositiveInt(
      env,
      "CODEXREMOTE_RATE_LIMIT_MAX",
      100,
      issues,
    ),
    globalRateLimitWindowMs: parsePositiveInt(
      env,
      "CODEXREMOTE_RATE_LIMIT_WINDOW_MS",
      60_000,
      issues,
    ),
    authRateLimitMax: parsePositiveInt(
      env,
      "CODEXREMOTE_AUTH_RATE_LIMIT_MAX",
      5,
      issues,
    ),
    authRateLimitWindowMs: parsePositiveInt(
      env,
      "CODEXREMOTE_AUTH_RATE_LIMIT_WINDOW_MS",
      15 * 60 * 1000,
      issues,
    ),
    dataDir: env["CODEXREMOTE_DATA_DIR"] ?? "data",
    codexBin: env["CODEX_BIN"] ?? "codex",
    minFreeDiskBytes: parsePositiveInt(
      env,
      "CODEXREMOTE_MIN_FREE_DISK_BYTES",
      100 * 1024 * 1024,
      issues,
    ),
    allowedUploadMimePatterns: parseAllowedMimePatterns(env),
    codexStateDir:
      env["CODEX_STATE_DIR"] ?? join(homedir(), ".codex", "sessions"),
    issues,
  };
}

// ── Module-level constants (parsed once at import time) ────────────

const _parsed = parseConfig();

/** HTTP listen port (default 3000). */
export const PORT: number = _parsed.port;

/** Bind address (default 127.0.0.1). */
export const HOST: string = _parsed.host;

/**
 * HTTP-level timeout for reading an entire request (headers + body).
 * Protects against clients that open a connection and stall mid-request,
 * which is common on mobile-over-Tailscale with unstable connectivity.
 * Default: 60 seconds.
 */
export const REQUEST_TIMEOUT_MS: number = _parsed.requestTimeoutMs;

/**
 * Socket-level idle timeout (maps to `server.timeout` / `socket.setTimeout`).
 * Kills sockets that go idle for longer than this threshold.
 * Default: 30 seconds.
 *
 * Long-lived routes (SSE streams, multipart uploads) opt out via
 * `request.raw.setTimeout(0)` and rely on their own route-level timers.
 */
export const CONNECTION_TIMEOUT_MS: number = _parsed.connectionTimeoutMs;

/**
 * Maximum wall-clock time a single run is allowed before the watchdog
 * force-kills it.  Configurable via CODEXREMOTE_RUN_TIMEOUT_MS.
 * Default: 30 minutes.
 */
export const RUN_TIMEOUT_MS: number = _parsed.runTimeoutMs;

/**
 * Maximum wall-clock time the entire shutdown sequence is allowed before
 * a forced exit.  Configurable via CODEXREMOTE_SHUTDOWN_TIMEOUT_MS.
 * Default: 30 seconds.
 */
export const SHUTDOWN_TIMEOUT_MS: number = _parsed.shutdownTimeoutMs;

/**
 * Maximum time an SSE connection may be idle (no run events delivered)
 * before the server proactively closes it.  The client is expected to
 * reconnect via Last-Event-ID.  Default: 5 minutes.
 */
export const SSE_IDLE_TIMEOUT_MS: number = _parsed.sseIdleTimeoutMs;

/**
 * Maximum time allowed between consecutive chunks during multipart file
 * stream consumption.  Must be shorter than REQUEST_TIMEOUT_MS.
 * Default: 30 seconds.
 */
export const UPLOAD_STREAM_TIMEOUT_MS: number = _parsed.uploadStreamTimeoutMs;

/**
 * Maximum size of the in-memory output buffer per run (bytes).
 * Output exceeding this limit is truncated from the front (tail
 * behaviour).  Default: 512 KB.
 */
export const MAX_OUTPUT_BYTES: number = _parsed.maxOutputBytes;

/**
 * Maximum bytes allowed to accumulate in the server-side writable buffer
 * for a single SSE connection before the stream is closed as a slow client.
 * The client can reconnect via Last-Event-ID.  Default: 1 MB.
 */
export const SSE_WRITE_BUFFER_MAX: number = _parsed.sseWriteBufferMax;

/**
 * Global per-IP rate limit: max requests within the window.
 * Default: 100 requests per 60 seconds.
 */
export const GLOBAL_RATE_LIMIT_MAX: number = _parsed.globalRateLimitMax;
export const GLOBAL_RATE_LIMIT_WINDOW_MS: number = _parsed.globalRateLimitWindowMs;

/**
 * Auth login per-IP rate limit: much stricter to prevent brute-force.
 * Default: 5 attempts per 15 minutes.
 */
export const AUTH_RATE_LIMIT_MAX: number = _parsed.authRateLimitMax;
export const AUTH_RATE_LIMIT_WINDOW_MS: number = _parsed.authRateLimitWindowMs;

/**
 * Minimum free disk space (bytes) required before artifact writes.
 * When available disk space falls below this threshold, uploads are
 * rejected with 507 Insufficient Storage.  Default: 100 MB.
 */
export const MIN_FREE_DISK_BYTES: number = _parsed.minFreeDiskBytes;

/**
 * Effective MIME type allowlist for uploads.  Combines the built-in
 * defaults with any operator additions via CODEXREMOTE_ALLOWED_UPLOAD_TYPES.
 * Used by the upload route to reject disallowed file types with 415.
 */
export const ALLOWED_UPLOAD_MIME_PATTERNS: readonly string[] =
  _parsed.allowedUploadMimePatterns;

// ── Validation ─────────────────────────────────────────────────────

/**
 * Validate a parsed configuration, throwing on any invalid values or
 * violated cross-constraints.  Exported for testing.
 */
export function validateParsedConfig(config: ConfigParseResult): void {
  if (config.issues.length > 0) {
    throw new Error(
      "Invalid configuration:\n" +
        config.issues.map((i) => `  • ${i}`).join("\n") +
        "\n\nFix the variables above or remove them to use defaults.",
    );
  }

  // Upload stall timeout must be shorter than the global request
  // timeout so the route-level timer is the effective bound.
  if (config.uploadStreamTimeoutMs >= config.requestTimeoutMs) {
    throw new Error(
      `CODEXREMOTE_UPLOAD_STREAM_TIMEOUT_MS (${config.uploadStreamTimeoutMs}ms) must be ` +
        `less than CODEXREMOTE_REQUEST_TIMEOUT_MS (${config.requestTimeoutMs}ms). ` +
        `Otherwise the global request timeout kills uploads before the ` +
        `stall timer can act.`,
    );
  }
}

/**
 * Fail-fast check for the process-level configuration.
 * Call once at server startup — if it throws, exit immediately.
 */
export function validateConfig(): void {
  validateParsedConfig(_parsed);
}

// ── Operator-visible config echo ───────────────────────────────────

/**
 * Log every effective configuration value so operators can verify their
 * setup after deploying.  Intended to be called once at server startup,
 * after validation passes.
 */
export function logEffectiveConfig(log: {
  info: (obj: Record<string, unknown>, msg: string) => void;
}): void {
  log.info(
    {
      port: _parsed.port,
      host: _parsed.host,
      dataDir: _parsed.dataDir,
      codexBin: _parsed.codexBin,
      codexStateDir: _parsed.codexStateDir,
      requestTimeoutMs: _parsed.requestTimeoutMs,
      connectionTimeoutMs: _parsed.connectionTimeoutMs,
      sseIdleTimeoutMs: _parsed.sseIdleTimeoutMs,
      uploadStreamTimeoutMs: _parsed.uploadStreamTimeoutMs,
      runTimeoutMs: _parsed.runTimeoutMs,
      shutdownTimeoutMs: _parsed.shutdownTimeoutMs,
      maxOutputBytes: _parsed.maxOutputBytes,
      sseWriteBufferMax: _parsed.sseWriteBufferMax,
      globalRateLimitMax: _parsed.globalRateLimitMax,
      globalRateLimitWindowMs: _parsed.globalRateLimitWindowMs,
      authRateLimitMax: _parsed.authRateLimitMax,
      authRateLimitWindowMs: _parsed.authRateLimitWindowMs,
      minFreeDiskBytes: _parsed.minFreeDiskBytes,
      allowedUploadMimePatterns: _parsed.allowedUploadMimePatterns,
    },
    "Effective configuration",
  );
}
