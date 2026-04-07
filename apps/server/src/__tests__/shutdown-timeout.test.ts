/**
 * Tests for the global shutdown timeout safety net.
 *
 * Validates:
 *  - Normal shutdown: all phases run, exit(0) is called, timer is cleared.
 *  - Hanging shutdownAll: force-exit fires after the timeout, DB is closed.
 *  - Hanging closeApp: force-exit fires after the timeout, DB is closed.
 *  - Forced-timeout path still exits even when closeDb throws.
 *  - Errors in shutdown phases are caught and do not prevent later phases.
 *  - closeDb is always called even when earlier phases fail.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { gracefulShutdown, SHUTDOWN_TIMEOUT_MS } from "../shutdown.js";
import type { ShutdownDeps } from "../shutdown.js";

// ── Helpers ────────────────────────────────────────────────────────

interface TestDeps extends ShutdownDeps {
  infos: string[];
  errors: Array<{ err: unknown; msg: string }>;
  state: { exitCode: number | null };
}

function makeDeps(overrides?: Partial<ShutdownDeps>): TestDeps {
  const infos: string[] = [];
  const errors: Array<{ err: unknown; msg: string }> = [];
  const state = { exitCode: null as number | null };

  return {
    shutdownAll: overrides?.shutdownAll ?? (async () => {}),
    closeApp: overrides?.closeApp ?? (async () => {}),
    closeDb: overrides?.closeDb ?? (() => {}),
    log: overrides?.log ?? {
      info: (msg: string) => infos.push(msg),
      error: (err: unknown, msg: string) => errors.push({ err, msg }),
    },
    exit: overrides?.exit ?? ((code: number) => { state.exitCode = code; }),
    infos,
    errors,
    state,
  };
}

// ── Tests ──────────────────────────────────────────────────────────

describe("gracefulShutdown", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("normal shutdown calls all phases and exits 0", async () => {
    const closeDb = vi.fn();
    const deps = makeDeps({ closeDb });

    const p = gracefulShutdown("SIGTERM", deps);
    // The function is fully async with no hanging — advance timers to
    // resolve any micro-tasks then await.
    await vi.runAllTimersAsync();
    await p;

    expect(deps.state.exitCode).toBe(0);
    expect(closeDb).toHaveBeenCalledOnce();
    expect(deps.infos).toContain("Received SIGTERM — shutting down gracefully");
    expect(deps.infos).toContain("Shutdown complete");
    expect(deps.errors).toHaveLength(0);
  });

  it("force-exits when shutdownAll hangs past the timeout", async () => {
    const TIMEOUT = 200;
    const closeDb = vi.fn();
    // shutdownAll returns a promise that never resolves.
    const deps = makeDeps({
      shutdownAll: () => new Promise<void>(() => {}),
      closeDb,
    });

    const p = gracefulShutdown("SIGINT", deps, TIMEOUT);

    // Advance past the timeout threshold.
    await vi.advanceTimersByTimeAsync(TIMEOUT + 50);

    // The force-exit timer should have fired.
    expect(deps.state.exitCode).toBe(1);
    expect(deps.errors.length).toBeGreaterThanOrEqual(1);
    expect(deps.errors[0].msg).toMatch(/forcing exit/);
    // DB should have been closed on the forced path.
    expect(closeDb).toHaveBeenCalledOnce();
  });

  it("force-exits when closeApp hangs past the timeout", async () => {
    const TIMEOUT = 200;
    const closeDb = vi.fn();
    const deps = makeDeps({
      closeApp: () => new Promise<void>(() => {}),
      closeDb,
    });

    const p = gracefulShutdown("SIGTERM", deps, TIMEOUT);

    await vi.advanceTimersByTimeAsync(TIMEOUT + 50);

    expect(deps.state.exitCode).toBe(1);
    expect(deps.errors.length).toBeGreaterThanOrEqual(1);
    expect(deps.errors.some((e) => e.msg.includes("forcing exit"))).toBe(true);
    // DB should have been closed on the forced path.
    expect(closeDb).toHaveBeenCalledOnce();
  });

  it("forced-timeout still exits when closeDb throws", async () => {
    const TIMEOUT = 200;
    const deps = makeDeps({
      shutdownAll: () => new Promise<void>(() => {}),
      closeDb: () => { throw new Error("DB lock stuck"); },
    });

    const p = gracefulShutdown("SIGTERM", deps, TIMEOUT);

    await vi.advanceTimersByTimeAsync(TIMEOUT + 50);

    // Should still have force-exited despite closeDb throwing.
    expect(deps.state.exitCode).toBe(1);
    // Two errors: the timeout message and the closeDb failure.
    expect(deps.errors.length).toBe(2);
    expect(deps.errors[0].msg).toMatch(/forcing exit/);
    expect(deps.errors[1].msg).toMatch(/closing database during forced shutdown/);
  });

  it("catches shutdownAll errors and continues to closeApp and closeDb", async () => {
    const closeDb = vi.fn();
    const deps = makeDeps({
      shutdownAll: async () => { throw new Error("runs exploded"); },
      closeDb,
    });

    const p = gracefulShutdown("SIGTERM", deps);
    await vi.runAllTimersAsync();
    await p;

    // Should have logged the error but still completed shutdown.
    expect(deps.errors).toHaveLength(1);
    expect(deps.errors[0].msg).toMatch(/stopping active runs/);
    expect(closeDb).toHaveBeenCalledOnce();
    expect(deps.state.exitCode).toBe(0);
  });

  it("catches closeApp errors and still closes the database", async () => {
    const closeDb = vi.fn();
    const deps = makeDeps({
      closeApp: async () => { throw new Error("fastify stuck"); },
      closeDb,
    });

    const p = gracefulShutdown("SIGTERM", deps);
    await vi.runAllTimersAsync();
    await p;

    expect(deps.errors).toHaveLength(1);
    expect(deps.errors[0].msg).toMatch(/closing Fastify/);
    expect(closeDb).toHaveBeenCalledOnce();
    expect(deps.state.exitCode).toBe(0);
  });

  it("clears the force-exit timer on successful shutdown", async () => {
    const deps = makeDeps();

    const p = gracefulShutdown("SIGTERM", deps, 500);
    await vi.runAllTimersAsync();
    await p;

    // Normal exit should have been called (code 0), not force-exit (code 1).
    expect(deps.state.exitCode).toBe(0);

    // Advancing past the timeout should not trigger a second exit call.
    const exitSpy = vi.fn();
    deps.exit = exitSpy;
    await vi.advanceTimersByTimeAsync(1000);

    expect(exitSpy).not.toHaveBeenCalled();
  });
});

describe("SHUTDOWN_TIMEOUT_MS", () => {
  it("defaults to 30 seconds", () => {
    // The module reads the env at import time.  Our test setup does not
    // set CODEXREMOTE_SHUTDOWN_TIMEOUT_MS, so it should be the default.
    expect(SHUTDOWN_TIMEOUT_MS).toBe(30_000);
  });
});
