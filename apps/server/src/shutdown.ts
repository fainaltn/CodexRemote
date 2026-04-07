/**
 * Graceful shutdown with a global safety-net timeout.
 *
 * The shutdown sequence (stop active runs → close HTTP server → close DB)
 * can hang if a child process is unkillable, a request handler blocks, or
 * Fastify's close hook stalls.  A global timeout ensures the process always
 * exits within a bounded window, even in pathological cases.
 *
 * The logic is extracted from server.ts into a standalone function so the
 * timeout and sequencing behaviour can be unit-tested without spawning a
 * real server.
 */

import { SHUTDOWN_TIMEOUT_MS } from "./config.js";

export { SHUTDOWN_TIMEOUT_MS };

/** Dependencies injected by the caller so the function is testable. */
export interface ShutdownDeps {
  shutdownAll: () => Promise<void>;
  closeApp: () => Promise<void>;
  closeDb: () => void;
  log: {
    info: (msg: string) => void;
    error: (err: unknown, msg: string) => void;
  };
  exit: (code: number) => void;
}

/**
 * Run the full shutdown sequence with a safety-net timer.
 *
 * 1. Start a force-exit timer (unref'd so it doesn't keep the loop alive).
 * 2. Stop all active Codex runs.
 * 3. Close Fastify (stop accepting connections, drain in-flight requests).
 * 4. Close the SQLite database.
 * 5. Clear the timer and exit cleanly.
 *
 * If steps 2–4 do not complete within `timeoutMs`, the timer fires and
 * calls `deps.exit(1)` to force the process down.
 */
export async function gracefulShutdown(
  signal: string,
  deps: ShutdownDeps,
  timeoutMs: number = SHUTDOWN_TIMEOUT_MS,
): Promise<void> {
  deps.log.info(`Received ${signal} — shutting down gracefully`);

  // Safety net: force-exit if the graceful path hangs.
  const forceExitTimer = setTimeout(() => {
    deps.log.error(
      new Error("Shutdown timeout"),
      `Graceful shutdown did not complete within ${timeoutMs}ms — forcing exit`,
    );

    // Best-effort DB close so WAL checkpoints and file locks are released
    // even on the forced-timeout path.
    try {
      deps.closeDb();
    } catch (dbErr) {
      deps.log.error(dbErr, "Error closing database during forced shutdown");
    }

    deps.exit(1);
  }, timeoutMs);

  // Don't keep the event loop alive just for this timer — if everything
  // else has drained, Node should still be able to exit naturally.
  forceExitTimer.unref();

  try {
    await deps.shutdownAll();
  } catch (err) {
    deps.log.error(err, "Error stopping active runs during shutdown");
  }

  try {
    await deps.closeApp();
  } catch (err) {
    deps.log.error(err, "Error closing Fastify during shutdown");
  }

  clearTimeout(forceExitTimer);
  deps.closeDb();
  deps.log.info("Shutdown complete");
  deps.exit(0);
}
