import Fastify from "fastify";
import multipart from "@fastify/multipart";
import rateLimit from "@fastify/rate-limit";
import { healthRoutes } from "./routes/health.js";
import { authRoutes } from "./routes/auth.js";
import { sessionRoutes } from "./routes/sessions.js";
import { projectRoutes } from "./routes/projects.js";
import { liveRunRoutes } from "./routes/live-runs.js";
import { uploadRoutes } from "./routes/uploads.js";
import { inboxRoutes } from "./routes/inbox.js";
import { LocalCodexAdapter } from "./codex/index.js";
import type { CodexAdapter } from "./codex/index.js";
import { RunManager } from "./runs/manager.js";
import { MAX_UPLOAD_BYTES, repairArtifactConsistency } from "./artifacts/store.js";
import { requireAuth } from "./auth/middleware.js";
import { initDb } from "./db.js";
import { JSON_BODY_LIMIT_BYTES } from "@codexremote/shared";
import {
  REQUEST_TIMEOUT_MS,
  CONNECTION_TIMEOUT_MS,
  GLOBAL_RATE_LIMIT_MAX,
  GLOBAL_RATE_LIMIT_WINDOW_MS,
  AUTH_RATE_LIMIT_MAX,
  AUTH_RATE_LIMIT_WINDOW_MS,
} from "./config.js";

export interface AppOptions {
  logger?: boolean;
  /** Override the Codex adapter (useful for testing). */
  adapter?: CodexAdapter;
  /** Skip orphaned-run recovery (useful for tests that control DB state). */
  skipRecovery?: boolean;
  /** Skip startup artifact consistency repair (useful for tests). */
  skipArtifactRepair?: boolean;
  /** Override HTTP request timeout (useful for testing). */
  requestTimeoutMs?: number;
  /** Override TCP connection timeout (useful for testing). */
  connectionTimeoutMs?: number;
  /** Override SSE idle timeout (useful for testing). */
  sseIdleTimeoutMs?: number;
  /** Override SSE write buffer limit for backpressure (useful for testing). */
  sseWriteBufferMax?: number;
  /** Override upload stream timeout (useful for testing). */
  uploadStreamTimeoutMs?: number;
  /** Override allowed MIME type patterns for uploads (useful for testing). */
  allowedUploadMimePatterns?: readonly string[];
  /** Override global rate limit max (useful for testing). */
  globalRateLimitMax?: number;
  /** Override global rate limit window (useful for testing). */
  globalRateLimitWindowMs?: number;
  /** Override auth rate limit max (useful for testing). */
  authRateLimitMax?: number;
  /** Override auth rate limit window (useful for testing). */
  authRateLimitWindowMs?: number;
}

/**
 * Build and return a configured Fastify instance.
 *
 * Route registration is split into small functions so that
 * each domain can grow independently in later phases.
 *
 * The database is initialised eagerly so that callers (e.g. test
 * harnesses) that skip `server.ts` still get a working store.
 *
 * Returns both the Fastify app and the RunManager so the caller
 * (e.g. server.ts) can wire graceful shutdown.
 */
export async function buildApp(opts: AppOptions = {}): Promise<{
  app: ReturnType<typeof Fastify>;
  runManager: RunManager;
}> {
  // Ensure the SQLite database is available — idempotent if already called.
  initDb();

  const reqTimeout = opts.requestTimeoutMs ?? REQUEST_TIMEOUT_MS;
  const connTimeout = opts.connectionTimeoutMs ?? CONNECTION_TIMEOUT_MS;

  const app = Fastify({
    logger: opts.logger ?? true,
    // Explicit body-size cap for JSON payloads (defence-in-depth).
    // The multipart plugin registers its own content-type parser with
    // a separate fileSize limit, so this does not affect file uploads.
    bodyLimit: JSON_BODY_LIMIT_BYTES,
    // Bound stalled HTTP requests — clients that open a connection and
    // never finish sending (common on mobile-over-Tailscale).
    requestTimeout: reqTimeout,
    // Bound the initial TCP handshake so idle accepted sockets don't
    // linger indefinitely.
    connectionTimeout: connTimeout,
  });

  // Multipart support for file uploads (§8).
  await app.register(multipart, {
    limits: { fileSize: MAX_UPLOAD_BYTES },
  });

  // Global per-IP rate limiting — defence against runaway retry loops,
  // resource exhaustion, and casual abuse even on a Tailscale network.
  await app.register(rateLimit, {
    max: opts.globalRateLimitMax ?? GLOBAL_RATE_LIMIT_MAX,
    timeWindow: opts.globalRateLimitWindowMs ?? GLOBAL_RATE_LIMIT_WINDOW_MS,
    // Use the Fastify trust-proxy default (X-Forwarded-For).
    // On Tailscale this is usually the direct peer IP anyway.
  });

  // Instantiate the Codex adapter once for the whole app.
  const adapter: CodexAdapter = opts.adapter ?? new LocalCodexAdapter();

  // Run manager sits between the routes and the adapter.
  const runManager = new RunManager(adapter);

  // Recover any runs left in a non-terminal state by a previous crash.
  if (!opts.skipRecovery) {
    runManager.recoverOrphanedRuns();
  }

  // Repair artifact disk↔DB consistency on startup (parallel to run
  // recovery above).  Removes orphaned disk files and logs warnings
  // about DB rows whose backing file is missing.
  if (!opts.skipArtifactRepair) {
    const repair = await repairArtifactConsistency();
    if (
      repair.orphanedFilesRemoved > 0 ||
      repair.missingFileArtifactIds.length > 0 ||
      repair.errors.length > 0
    ) {
      app.log.warn(
        { artifactRepair: repair },
        `Artifact repair: ${repair.orphanedFilesRemoved} orphaned file(s) removed, ` +
          `${repair.missingFileArtifactIds.length} DB row(s) with missing files`,
      );
    } else {
      app.log.info("Artifact consistency: clean");
    }
  }

  // ── Public routes (no token required) ────────────────────────────
  await app.register(healthRoutes(runManager));
  await app.register(authRoutes({
    authRateLimitMax: opts.authRateLimitMax ?? AUTH_RATE_LIMIT_MAX,
    authRateLimitWindowMs: opts.authRateLimitWindowMs ?? AUTH_RATE_LIMIT_WINDOW_MS,
  }));

  // ── Protected routes (valid Bearer token required) ───────────────
  // Scoped plugin: the preHandler hook applies only to the routes
  // registered inside this scope, keeping health and auth public.
  await app.register(async function protectedRoutes(scope) {
    scope.addHook("preHandler", requireAuth);

    await scope.register(sessionRoutes(adapter, runManager));
    await scope.register(projectRoutes());
    await scope.register(liveRunRoutes(runManager, {
      sseIdleTimeoutMs: opts.sseIdleTimeoutMs,
      sseWriteBufferMax: opts.sseWriteBufferMax,
    }));
    await scope.register(uploadRoutes(adapter, runManager, {
      uploadStreamTimeoutMs: opts.uploadStreamTimeoutMs,
      allowedUploadMimePatterns: opts.allowedUploadMimePatterns,
    }));
    await scope.register(inboxRoutes({
      uploadStreamTimeoutMs: opts.uploadStreamTimeoutMs,
      allowedUploadMimePatterns: opts.allowedUploadMimePatterns,
    }));
  });

  return { app, runManager };
}
