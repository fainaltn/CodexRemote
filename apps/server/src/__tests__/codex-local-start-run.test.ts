import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const cliMock = vi.hoisted(() => ({
  archiveCodexAppServerThread: vi.fn(),
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

describe("LocalCodexAdapter.startRun", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    cliMock.scanSessionDirs.mockResolvedValue([]);
    cliMock.sessionDirExists.mockResolvedValue(true);
    cliMock.readSessionMeta.mockResolvedValue({
      cwd: "/tmp/project",
      lastActivityAt: "2026-04-07T00:00:00.000Z",
      title: "Test",
      lastPreview: null,
    });
    cliMock.readSessionMessages.mockResolvedValue([]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("prefers app-server resume when available", async () => {
    const onExit = vi.fn();
    cliMock.detectCodexAppServerThreadStart.mockResolvedValue({
      available: true,
      reason: "supported",
    });
    cliMock.spawnCodexAppServerResumeRun.mockResolvedValue({
      pid: 2222,
      readOutput: () => "hello",
      totalOutputBytes: () => 5,
      stop: vi.fn().mockResolvedValue(undefined),
      onExit,
    });

    const { LocalCodexAdapter } = await import("../codex/local.js");
    const adapter = new LocalCodexAdapter();

    const handle = await adapter.startRun("thread-123", {
      prompt: "resume",
      model: "gpt-5.4",
      reasoningEffort: "high",
    });

    expect(handle.pid).toBe(2222);
    expect(cliMock.spawnCodexAppServerResumeRun).toHaveBeenCalledWith(
      "thread-123",
      "resume",
      {
        cwd: "/tmp/project",
        model: "gpt-5.4",
        reasoningEffort: "high",
      },
    );
    expect(cliMock.spawnCodexRun).not.toHaveBeenCalled();
  });

  it("falls back to codex exec resume when app-server resume fails", async () => {
    const child = {
      pid: 3333,
      exitCode: null,
      signalCode: null,
      on: vi.fn(),
      kill: vi.fn(),
    };
    cliMock.detectCodexAppServerThreadStart.mockResolvedValue({
      available: true,
      reason: "supported",
    });
    cliMock.spawnCodexAppServerResumeRun.mockRejectedValue(
      new Error("turn/start failed"),
    );
    cliMock.spawnCodexRun.mockReturnValue({
      child,
      readOutput: () => "",
      totalOutputBytes: () => 0,
    });

    const { LocalCodexAdapter } = await import("../codex/local.js");
    const adapter = new LocalCodexAdapter();

    const handle = await adapter.startRun("thread-456", {
      prompt: "resume",
    });

    expect(handle.pid).toBe(3333);
    expect(cliMock.spawnCodexAppServerResumeRun).toHaveBeenCalledTimes(1);
    expect(cliMock.spawnCodexRun).toHaveBeenCalledWith("thread-456", "resume", {
      model: undefined,
      reasoningEffort: undefined,
    });
  });
});
