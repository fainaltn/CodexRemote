import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { EventEmitter } from "node:events";

const spawnMock = vi.fn();

vi.mock("node:child_process", () => ({
  spawn: (...args: unknown[]) => spawnMock(...args),
}));

class FakeChild extends EventEmitter {
  pid = 1234;
  exitCode: number | null = null;
  signalCode: NodeJS.Signals | null = null;
  stdin = {
    write: vi.fn(),
    end: vi.fn(),
  };
  stdout = new EventEmitter();
  stderr = new EventEmitter();
  kill = vi.fn();
}

describe("Codex CLI spawn args", () => {
  beforeEach(() => {
    process.env["CODEX_APP_SERVER_BIN"] = "/tmp/codex-app-server-bin";
    spawnMock.mockReset();
    spawnMock.mockReturnValue(new FakeChild());
  });

  afterEach(() => {
    delete process.env["CODEX_APP_SERVER_BIN"];
    vi.restoreAllMocks();
  });

  it("forces danger-full-access with no approvals when resuming an existing session", async () => {
    const { spawnCodexRun } = await import("../codex/cli.js");

    spawnCodexRun("sess-123", "hello");

    expect(spawnMock).toHaveBeenCalledTimes(1);
    const [, args] = spawnMock.mock.calls[0] as [string, string[]];
    expect(args).toEqual([
      "-a",
      "never",
      "-s",
      "danger-full-access",
      "exec",
      "resume",
      "sess-123",
      "--skip-git-repo-check",
    ]);
  });

  it("forces danger-full-access with no approvals when creating a new session", async () => {
    const { spawnCodexNewRun } = await import("../codex/cli.js");
    const child = spawnMock.mock.results[0]?.value as FakeChild | undefined;

    const runPromise = spawnCodexNewRun("/tmp/project", "hello");

    const spawnedChild = (spawnMock.mock.results[0]?.value ??
      child) as FakeChild;
    spawnedChild.stdout.emit(
      "data",
      Buffer.from('{"type":"thread.started","thread_id":"sess-new"}\n'),
    );

    await runPromise;

    expect(spawnMock).toHaveBeenCalledTimes(1);
    const [, args] = spawnMock.mock.calls[0] as [string, string[]];
    expect(args).toEqual([
      "-a",
      "never",
      "-s",
      "danger-full-access",
      "exec",
      "--json",
      "--skip-git-repo-check",
    ]);
  });

  it("detects app-server thread/start support from codex app-server help", async () => {
    const { detectCodexAppServerThreadStart } = await import("../codex/cli.js");
    const child = spawnMock.mock.results[0]?.value as FakeChild | undefined;

    const detectionPromise = detectCodexAppServerThreadStart();
    await Promise.resolve();

    const spawnedChild = (spawnMock.mock.results[0]?.value ??
      child) as FakeChild;
    spawnedChild.stdout.emit(
      "data",
      Buffer.from("[experimental] Run the app server or related tooling\ngenerate-json-schema\n"),
    );
    spawnedChild.emit("close", 0);

    await expect(detectionPromise).resolves.toEqual({
      available: true,
      reason: "/tmp/codex-app-server-bin app-server help is available",
    });
    expect(spawnMock).toHaveBeenCalledTimes(1);
    const [bin, args] = spawnMock.mock.calls[0] as [string, string[]];
    expect(bin).toBe("/tmp/codex-app-server-bin");
    expect(args).toEqual(["app-server", "--help"]);
  });

  it("starts a thread through bundled codex app-server, submits the bootstrap turn, and returns the thread id", async () => {
    const { spawnCodexAppServerNewThread } = await import("../codex/cli.js");
    const child = spawnMock.mock.results[0]?.value as FakeChild | undefined;

    const runPromise = spawnCodexAppServerNewThread("/tmp/project", {
      model: "gpt-5.4",
      prompt: "Do not respond. Create the session and exit immediately without any assistant-visible text.",
    });
    await Promise.resolve();

    const spawnedChild = (spawnMock.mock.results[0]?.value ??
      child) as FakeChild;
    spawnedChild.stdout.emit(
      "data",
      Buffer.from(
        '{"id":1,"result":{"userAgent":"Codex Desktop"}}\n' +
          '{"id":2,"result":{"thread":{"id":"thread-app-server"}}}\n' +
          '{"id":3,"result":{"turn":{"id":"turn-123","status":"inProgress","items":[],"error":null}}}\n' +
          '{"id":4,"result":{}}\n',
      ),
    );
    spawnedChild.emit("close", 0);

    await expect(runPromise).resolves.toMatchObject({
      sessionId: "thread-app-server",
    });
    expect(spawnMock).toHaveBeenCalledTimes(1);
    const [bin, args] = spawnMock.mock.calls[0] as [string, string[]];
    expect(bin).toBe("/tmp/codex-app-server-bin");
    expect(args).toEqual(["app-server", "--listen", "stdio://"]);
    expect(spawnedChild.stdin.write).toHaveBeenCalledTimes(4);
    expect(spawnedChild.stdin.write).toHaveBeenNthCalledWith(
      3,
      expect.stringContaining('"method":"turn/start"'),
    );
    expect(spawnedChild.stdin.write).toHaveBeenNthCalledWith(
      4,
      expect.stringContaining('"method":"thread/name/set"'),
    );
    expect(spawnedChild.kill).not.toHaveBeenCalled();
  });

  it("resumes an existing thread through app-server and enforces danger-full-access on turn/start", async () => {
    const { spawnCodexAppServerResumeRun } = await import("../codex/cli.js");

    const runPromise = spawnCodexAppServerResumeRun("thread-existing", "hello world", {
      cwd: "/tmp/project",
      model: "gpt-5.4",
      reasoningEffort: "high",
    });
    await Promise.resolve();

    const spawnedChild = spawnMock.mock.results[0]?.value as FakeChild;
    spawnedChild.stdout.emit(
      "data",
      Buffer.from(
        '{"id":1,"result":{"userAgent":"Codex Desktop"}}\n' +
          '{"id":6,"result":{"thread":{"id":"thread-existing"}}}\n' +
          '{"id":3,"result":{"turn":{"id":"turn-123","status":"inProgress","items":[],"error":null}}}\n' +
          '{"method":"item/agentMessage/delta","params":{"threadId":"thread-existing","turnId":"turn-123","itemId":"item-1","delta":"Hello"}}\n' +
          '{"method":"turn/completed","params":{"threadId":"thread-existing","turn":{"id":"turn-123","status":"completed","error":null}}}\n',
      ),
    );
    spawnedChild.emit("close", 0);

    const handle = await runPromise;

    expect(spawnMock).toHaveBeenCalledTimes(1);
    const [bin, args] = spawnMock.mock.calls[0] as [string, string[]];
    expect(bin).toBe("/tmp/codex-app-server-bin");
    expect(args).toEqual(["app-server", "--listen", "stdio://"]);
    expect(spawnedChild.stdin.write).toHaveBeenNthCalledWith(
      2,
      expect.stringContaining('"method":"thread/resume"'),
    );
    expect(spawnedChild.stdin.write).toHaveBeenNthCalledWith(
      2,
      expect.stringContaining('"sandbox":"danger-full-access"'),
    );
    expect(spawnedChild.stdin.write).toHaveBeenNthCalledWith(
      3,
      expect.stringContaining('"method":"turn/start"'),
    );
    expect(spawnedChild.stdin.write).toHaveBeenNthCalledWith(
      3,
      expect.stringContaining('"threadId":"thread-existing"'),
    );
    expect(spawnedChild.stdin.write).toHaveBeenNthCalledWith(
      3,
      expect.stringContaining('"approvalPolicy":"never"'),
    );
    expect(spawnedChild.stdin.write).toHaveBeenNthCalledWith(
      3,
      expect.stringContaining('"type":"dangerFullAccess"'),
    );
    expect(handle.readOutput()).toContain("Hello");
  });
});
