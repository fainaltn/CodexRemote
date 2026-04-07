/**
 * Request payload size limits.
 *
 * Centralised here so both server-side Zod validation and client-side
 * UX (character counters, early rejection) reference the same values.
 *
 * These bounds prevent a phone client (buggy paste, network replay, or
 * adversarial input) from sending payloads large enough to destabilise
 * the server — e.g. a multi-MB prompt stored in SQLite, broadcast via
 * SSE, and written to child-process stdin without backpressure.
 */

// ── Prompt & run parameters ────────────────────────────────────────

/** Maximum characters for a Codex prompt.  128 KB of UTF-16 chars is
 *  generous for even code-heavy prompts while keeping SSE events and
 *  the SQLite `runs.prompt` column bounded. */
export const MAX_PROMPT_LENGTH = 128_000;

/** Maximum characters for a model identifier (e.g. "o4-mini"). */
export const MAX_MODEL_LENGTH = 200;

/** Maximum characters for reasoning-effort (e.g. "medium"). */
export const MAX_REASONING_EFFORT_LENGTH = 50;

// ── Inbox fields ───────────────────────────────────────────────────

/** Maximum characters for an inbox link URL. */
export const MAX_URL_LENGTH = 2_000;

/** Maximum characters for an optional inbox title. */
export const MAX_INBOX_TITLE_LENGTH = 200;

/** Maximum characters for an optional inbox note. */
export const MAX_INBOX_NOTE_LENGTH = 4_000;

/** Maximum characters for an optional inbox source label. */
export const MAX_INBOX_SOURCE_LENGTH = 100;

// ── Auth fields ────────────────────────────────────────────────────

/** Maximum characters for the app password. */
export const MAX_PASSWORD_LENGTH = 1_000;

/** Maximum characters for a device label (e.g. "Pixel 7 Pro"). */
export const MAX_DEVICE_LABEL_LENGTH = 200;

// ── Route parameters & identifiers ─────────────────────────────────

/** Maximum characters for any route-param identifier
 *  (hostId, sessionId, artifactId, runId). */
export const MAX_ID_LENGTH = 500;

/** Maximum characters for an absolute local path or cwd selector. */
export const MAX_PATH_LENGTH = 2_000;
export const MAX_SESSION_TITLE_LENGTH = 200;

// ── Fastify body limit ─────────────────────────────────────────────

/**
 * Explicit body-size cap for JSON request payloads (bytes).
 *
 * Defence-in-depth: even if a Zod `.max()` is accidentally removed,
 * Fastify will reject payloads above this threshold before parsing.
 * The multipart plugin registers its own content-type parser with a
 * separate `fileSize` limit, so this does not affect file uploads.
 */
export const JSON_BODY_LIMIT_BYTES = 512 * 1024; // 512 KB

// ── Upload file-type policy (§11 security model) ──────────────────

/**
 * Default allowed MIME type patterns for uploads.
 *
 * Supports exact types (`application/pdf`) and wildcard prefixes
 * (`image/*`) as part of the repository's upload safety policy.
 *
 * The list is intentionally generous for Codex workflows (screenshots,
 * code, logs, archives) while blocking executable and package types
 * that have no legitimate use in the upload flow.
 *
 * Operators can extend via CODEXREMOTE_ALLOWED_UPLOAD_TYPES at
 * startup.
 */
export const DEFAULT_ALLOWED_UPLOAD_MIME_PATTERNS: readonly string[] = [
  // Images — primary APK upload use case (screenshots, photos)
  "image/*",
  // Text and code — logs, source files, configs
  "text/*",
  // Documents
  "application/pdf",
  // Structured data — configs, API responses
  "application/json",
  "application/xml",
  "application/yaml",
  "application/x-yaml",
  "application/toml",
  // Archives — code bundles, log archives
  "application/zip",
  "application/gzip",
  "application/x-tar",
  "application/x-gzip",
  "application/x-bzip2",
  "application/x-7z-compressed",
  // Generic binary — mobile file pickers often default to this
  "application/octet-stream",
];
