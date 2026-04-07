import { afterEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import {
  MockCodexAdapter,
  authHeader,
  cleanTables,
  createTestApp,
  loginHelper,
} from "./helpers.js";
import { SessionDetailResponse } from "@codexremote/shared";

describe("session detail route", () => {
  let app: FastifyInstance | null = null;

  afterEach(() => {
    cleanTables();
    void app?.close();
    app = null;
  });

  it("returns canonical session history with reasoning and final assistant messages", async () => {
    const adapter = new MockCodexAdapter();
    adapter.addSession("detail-session-id", {
      cwd: "/workspace/CodexRemote",
      title: "会话详情",
      lastPreview: "最终答案放在这里。",
    });
    adapter.setSessionMessages("detail-session-id", [
      {
        id: "msg-1",
        role: "user",
        kind: "message",
        text: "先检查历史上下文",
        createdAt: "2026-04-06T12:00:00.000Z",
      },
      {
        id: "msg-2",
        role: "assistant",
        kind: "reasoning",
        text: "先确认当前运行状态，再整理修改点。",
        createdAt: "2026-04-06T12:00:01.000Z",
      },
      {
        id: "msg-3",
        role: "assistant",
        kind: "message",
        text: "最终答案放在这里。",
        createdAt: "2026-04-06T12:00:02.000Z",
      },
    ]);

    const created = await createTestApp(adapter);
    app = created.app;
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/detail-session-id",
      headers: authHeader(token),
    });

    expect(res.statusCode).toBe(200);

    const body = SessionDetailResponse.parse(res.json());
    expect(body.session).toMatchObject({
      id: "detail-session-id",
      title: "会话详情",
      lastPreview: "最终答案放在这里。",
    });
    expect(body.messages).toEqual([
      expect.objectContaining({ role: "user", kind: "message" }),
      expect.objectContaining({ role: "assistant", kind: "reasoning" }),
      expect.objectContaining({ role: "assistant", kind: "message" }),
    ]);
  });
});
