import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const cliMock = vi.hoisted(() => ({
  detectCodexAppServerThreadStart: vi.fn(),
  scanSessionDirs: vi.fn(),
  sessionDirExists: vi.fn(),
  readSessionMeta: vi.fn(),
  readSessionMessages: vi.fn(),
  spawnCodexAppServerNewThread: vi.fn(),
  spawnCodexAppServerResumeRun: vi.fn(),
  spawnCodexRun: vi.fn(),
  spawnCodexNewRun: vi.fn(),
}));

vi.mock("../codex/cli.js", () => cliMock);

describe("LocalCodexAdapter.startNewRun", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    cliMock.scanSessionDirs.mockResolvedValue([]);
    cliMock.sessionDirExists.mockResolvedValue(false);
    cliMock.readSessionMeta.mockResolvedValue(null);
    cliMock.readSessionMessages.mockResolvedValue([]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("uses the preferred app-server strategy for create-only sessions when available", async () => {
    cliMock.sessionDirExists.mockResolvedValue(true);
    cliMock.detectCodexAppServerThreadStart.mockResolvedValue({
      available: true,
      reason: "supported",
    });
    cliMock.spawnCodexAppServerNewThread.mockResolvedValue({
      sessionId: "thread-123",
      child: { pid: 1234, exitCode: 0, signalCode: null, on: vi.fn(), kill: vi.fn() },
      readOutput: () => "",
      totalOutputBytes: () => 0,
    });

    const { LocalCodexAdapter } = await import("../codex/local.js");
    const adapter = new LocalCodexAdapter();

    const started = await adapter.startNewRun("/tmp/project", {
      prompt: "bootstrap",
      startupMode: "create-only",
    });

    expect(started.sessionId).toBe("thread-123");
    expect(cliMock.detectCodexAppServerThreadStart).toHaveBeenCalledTimes(1);
    expect(cliMock.spawnCodexAppServerNewThread).toHaveBeenCalledWith(
      "/tmp/project",
      { model: undefined, prompt: "bootstrap" },
    );
    expect(cliMock.spawnCodexNewRun).not.toHaveBeenCalled();
  });

  it("falls back to codex exec when the preferred thread is not readable on disk", async () => {
    vi.useFakeTimers();
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    cliMock.detectCodexAppServerThreadStart.mockResolvedValue({
      available: true,
      reason: "supported",
    });
    cliMock.sessionDirExists.mockResolvedValue(false);
    cliMock.spawnCodexAppServerNewThread.mockResolvedValue({
      sessionId: "thread-unreadable",
      child: { pid: 1234, exitCode: 0, signalCode: null, on: vi.fn(), kill: vi.fn() },
      readOutput: () => "",
      totalOutputBytes: () => 0,
    });
    cliMock.spawnCodexNewRun.mockResolvedValue({
      sessionId: "exec-after-unreadable",
      child: { pid: 4321, exitCode: 0, signalCode: null, on: vi.fn(), kill: vi.fn() },
      readOutput: () => "",
      totalOutputBytes: () => 0,
    });

    const { LocalCodexAdapter } = await import("../codex/local.js");
    const adapter = new LocalCodexAdapter();

    const startedPromise = adapter.startNewRun("/tmp/project", {
      prompt: "bootstrap",
      startupMode: "create-only",
    });
    await vi.advanceTimersByTimeAsync(8_500);
    const started = await startedPromise;

    expect(started.sessionId).toBe("exec-after-unreadable");
    expect(cliMock.spawnCodexAppServerNewThread).toHaveBeenCalledTimes(1);
    expect(cliMock.spawnCodexNewRun).toHaveBeenCalledTimes(1);
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining("session metadata was not readable in time"),
    );
    vi.useRealTimers();
  }, 20_000);

  it("falls back to codex exec when the preferred strategy is unavailable", async () => {
    cliMock.detectCodexAppServerThreadStart.mockResolvedValue({
      available: false,
      reason: "app-server help missing",
    });
    cliMock.spawnCodexNewRun.mockResolvedValue({
      sessionId: "exec-123",
      child: { pid: 4321, exitCode: 0, signalCode: null, on: vi.fn(), kill: vi.fn() },
      readOutput: () => "",
      totalOutputBytes: () => 0,
    });

    const { LocalCodexAdapter } = await import("../codex/local.js");
    const adapter = new LocalCodexAdapter();

    const started = await adapter.startNewRun("/tmp/project", {
      prompt: "bootstrap",
      startupMode: "create-only",
    });

    expect(started.sessionId).toBe("exec-123");
    expect(cliMock.spawnCodexAppServerNewThread).not.toHaveBeenCalled();
    expect(cliMock.spawnCodexNewRun).toHaveBeenCalledWith("/tmp/project", "bootstrap", {
      model: undefined,
      reasoningEffort: undefined,
    });
  });

  it("falls back to codex exec when the preferred strategy fails", async () => {
    cliMock.detectCodexAppServerThreadStart.mockResolvedValue({
      available: true,
      reason: "supported",
    });
    cliMock.spawnCodexAppServerNewThread.mockRejectedValue(
      new Error("thread/start failed"),
    );
    cliMock.spawnCodexNewRun.mockResolvedValue({
      sessionId: "exec-456",
      child: { pid: 4567, exitCode: 0, signalCode: null, on: vi.fn(), kill: vi.fn() },
      readOutput: () => "",
      totalOutputBytes: () => 0,
    });

    const { LocalCodexAdapter } = await import("../codex/local.js");
    const adapter = new LocalCodexAdapter();

    const started = await adapter.startNewRun("/tmp/project", {
      prompt: "bootstrap",
      startupMode: "create-only",
    });

    expect(started.sessionId).toBe("exec-456");
    expect(cliMock.spawnCodexAppServerNewThread).toHaveBeenCalledTimes(1);
    expect(cliMock.spawnCodexNewRun).toHaveBeenCalledTimes(1);
  });
});
