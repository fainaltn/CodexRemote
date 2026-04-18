import { describe, expect, it, vi } from "vitest";
import type {
  CodexAdapter,
  CodexApprovalDecision,
  CodexApprovalRequest,
  RunHandle,
} from "../codex/index.js";
import { getDb } from "../db.js";
import { RunManager } from "../runs/manager.js";
import { cleanTables } from "./helpers.js";

function buildApprovalHandle() {
  const approvalCallbacks = new Set<(approval: CodexApprovalRequest) => void>();
  const respondToApproval = vi.fn<
    (approvalId: string, decision: CodexApprovalDecision) => Promise<void>
  >();

  const handle: RunHandle = {
    pid: 4242,
    readOutput: () => "",
    totalOutputBytes: () => 0,
    stop: () => Promise.resolve(),
    onExit: () => {},
    onApprovalRequest: (cb) => {
      approvalCallbacks.add(cb);
      return () => {
        approvalCallbacks.delete(cb);
      };
    },
    respondToApproval,
  };

  return {
    handle,
    emitApproval(approval: CodexApprovalRequest) {
      for (const cb of approvalCallbacks) {
        cb(approval);
      }
    },
    respondToApproval,
  };
}

describe("RunManager approvals", () => {
  it("stores pending approvals emitted by a run handle", async () => {
    cleanTables();

    const approvalHandle = buildApprovalHandle();
    const adapter: CodexAdapter = {
      listSessions: async () => [],
      getSessionDetail: async () => ({
        codexSessionId: "sess-approval",
        cwd: "/tmp",
        lastActivityAt: new Date().toISOString(),
        title: "Approval session",
        lastPreview: null,
      }),
      getSessionMessages: async () => [],
      startRun: async () => approvalHandle.handle,
      startNewRun: async () => ({
        sessionId: "sess-approval",
        handle: approvalHandle.handle,
      }),
      archiveSession: async () => {},
      stopRun: async () => {},
    };

    const manager = new RunManager(adapter);
    await manager.startRun("sess-approval", { prompt: "hello", permissionMode: "on-request" });

    approvalHandle.emitApproval({
      id: "approval-1",
      threadId: "sess-approval",
      turnId: "turn-1",
      itemId: "item-1",
      kind: "command",
      scope: "turn",
      status: "pending",
      createdAt: new Date().toISOString(),
      title: "Command execution approval",
      detail: "git status",
      rpcRequestIdValue: 2,
      rpcRequestId: "2",
      rpcMethod: "item/commandExecution/requestApproval",
      command: "git status",
      cwd: "/tmp",
    });

    expect(manager.getPendingApprovals("sess-approval")).toEqual([
      expect.objectContaining({
        id: "approval-1",
        kind: "command",
        detail: "git status",
      }),
    ]);

    await manager.stopRun("sess-approval");
  });

  it("forwards approval decisions back to the run handle and clears pending state", async () => {
    cleanTables();

    const approvalHandle = buildApprovalHandle();
    const adapter: CodexAdapter = {
      listSessions: async () => [],
      getSessionDetail: async () => ({
        codexSessionId: "sess-approval-2",
        cwd: "/tmp",
        lastActivityAt: new Date().toISOString(),
        title: "Approval session",
        lastPreview: null,
      }),
      getSessionMessages: async () => [],
      startRun: async () => approvalHandle.handle,
      startNewRun: async () => ({
        sessionId: "sess-approval-2",
        handle: approvalHandle.handle,
      }),
      archiveSession: async () => {},
      stopRun: async () => {},
    };

    const manager = new RunManager(adapter);
    await manager.startRun("sess-approval-2", { prompt: "hello", permissionMode: "on-request" });

    approvalHandle.emitApproval({
      id: "approval-2",
      threadId: "sess-approval-2",
      turnId: "turn-2",
      itemId: "item-2",
      kind: "command",
      scope: "turn",
      status: "pending",
      createdAt: new Date().toISOString(),
      title: "Command execution approval",
      detail: "git status",
      rpcRequestIdValue: 3,
      rpcRequestId: "3",
      rpcMethod: "item/commandExecution/requestApproval",
      command: "git status",
      cwd: "/tmp",
    });

    await manager.decideApproval("sess-approval-2", "approval-2", {
      kind: "command",
      decision: "acceptForSession",
    });

    expect(approvalHandle.respondToApproval).toHaveBeenCalledWith("approval-2", {
      kind: "command",
      decision: "acceptForSession",
    });
    expect(manager.getPendingApprovals("sess-approval-2")).toEqual([]);

    const runRow = getDb()
      .prepare("SELECT status FROM runs WHERE session_id = ? ORDER BY started_at DESC LIMIT 1")
      .get("sess-approval-2") as { status: string } | undefined;
    expect(runRow?.status).toBe("running");

    await manager.stopRun("sess-approval-2");
  });
});
