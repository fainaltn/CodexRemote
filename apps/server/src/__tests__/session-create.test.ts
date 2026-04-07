import { afterEach, describe, expect, it } from "vitest";
import {
  MockCodexAdapter,
  authHeader,
  cleanTables,
  createTestApp,
  loginHelper,
} from "./helpers.js";
import { mkdir, rm } from "node:fs/promises";
import { homedir } from "node:os";
import path from "node:path";

describe("session creation and project browsing", () => {
  afterEach(() => {
    cleanTables();
  });

  it("creates a new session in the requested cwd", async () => {
    const { app, adapter } = await createTestApp();
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions",
      headers: authHeader(token),
      payload: {
        cwd: "/tmp/forgeos",
        prompt: "帮我新开一个线程",
      },
    });

    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.sessionId).toBeTruthy();
    expect(body.runId).toBeTruthy();

    const detail = await adapter.getSessionDetail(body.sessionId);
    expect(detail?.cwd).toBe("/tmp/forgeos");
    expect(adapter.lastStartNewRunOptions?.startupMode).toBe("create-only");
    expect(adapter.lastStartRunSessionId).toBe(body.sessionId);
    expect(adapter.lastStartRunOptions?.prompt).toBe("帮我新开一个线程");
  });

  it("waits until a prompt-created session detail is readable before returning", async () => {
    const adapter = new MockCodexAdapter();
    adapter.deferNewSessionVisibility();
    const { app } = await createTestApp(adapter);
    const token = await loginHelper(app);

    const startedAt = Date.now();
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions",
      headers: authHeader(token),
      payload: {
        cwd: "/workspace/CodexRemote",
        prompt: "你不用回复，测试内容3",
      },
    });

    expect(res.statusCode).toBe(201);
    expect(Date.now() - startedAt).toBeGreaterThanOrEqual(200);
    const body = res.json();
    const detail = await adapter.getSessionDetail(body.sessionId);
    expect(detail?.cwd).toBe("/workspace/CodexRemote");
  });

  it("creates a new empty session shell when prompt is omitted", async () => {
    const { app } = await createTestApp();
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions",
      headers: authHeader(token),
      payload: {
        cwd: "/tmp/forgeos-empty",
      },
    });

    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.sessionId).toBeTruthy();
  });

  it("does not leave an active bootstrap run after creating an empty session shell", async () => {
    const { app } = await createTestApp();
    const token = await loginHelper(app);

    const created = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions",
      headers: authHeader(token),
      payload: {
        cwd: "/workspace/CodexRemote",
      },
    });

    expect(created.statusCode).toBe(201);
    const body = created.json();

    const live = await app.inject({
      method: "GET",
      url: `/api/hosts/local/sessions/${body.sessionId}/live`,
      headers: authHeader(token),
    });

    expect(live.statusCode).toBe(200);
    expect(live.json()).toBeNull();
  });

  it("waits until the new session detail is readable before returning", async () => {
    const adapter = new MockCodexAdapter();
    adapter.deferNewSessionVisibility();
    const { app } = await createTestApp(adapter);
    const token = await loginHelper(app);

    const startedAt = Date.now();
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions",
      headers: authHeader(token),
      payload: {
        cwd: "/workspace/CodexRemote",
      },
    });

    expect(res.statusCode).toBe(201);
    expect(Date.now() - startedAt).toBeGreaterThanOrEqual(200);
    const body = res.json();
    const detail = await adapter.getSessionDetail(body.sessionId);
    expect(detail?.cwd).toBe("/workspace/CodexRemote");
  });

  it("rejects invalid session creation input", async () => {
    const { app } = await createTestApp();
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions",
      headers: authHeader(token),
      payload: {
        cwd: "",
        prompt: "",
      },
    });

    expect(res.statusCode).toBe(400);
  });

  it("lists project directories under the host home root", async () => {
    const { app } = await createTestApp();
    const token = await loginHelper(app);
    const tempDir = path.join(homedir(), "codexremote-project-browser-test");
    const childDir = path.join(tempDir, "demo-project");

    await mkdir(childDir, { recursive: true });

    try {
      const res = await app.inject({
        method: "GET",
        url: `/api/hosts/local/projects/browse?path=${encodeURIComponent(tempDir)}`,
        headers: authHeader(token),
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(body.currentPath).toBe(tempDir);
      expect(body.entries).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            name: "demo-project",
            path: childDir,
          }),
        ]),
      );
    } finally {
      await rm(tempDir, { recursive: true, force: true });
    }
  });

  it("includes dot-prefixed directories in project browsing", async () => {
    const { app } = await createTestApp();
    const token = await loginHelper(app);
    const tempDir = path.join(homedir(), "codexremote-project-browser-dot-test");
    const childDir = path.join(tempDir, ".claude");

    await mkdir(childDir, { recursive: true });

    try {
      const res = await app.inject({
        method: "GET",
        url: `/api/hosts/local/projects/browse?path=${encodeURIComponent(tempDir)}`,
        headers: authHeader(token),
      });

      expect(res.statusCode).toBe(200);
      const body = res.json();
      expect(body.entries).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            name: ".claude",
            path: childDir,
          }),
        ]),
      );
    } finally {
      await rm(tempDir, { recursive: true, force: true });
    }
  });

  it("allows renaming a session title and reflects it in the session list", async () => {
    const adapter = new MockCodexAdapter();
    adapter.addSession("rename-session", {
      cwd: "/workspace/CodexRemote",
      title: "旧标题",
    });
    const { app } = await createTestApp(adapter);
    const token = await loginHelper(app);

    const rename = await app.inject({
      method: "PATCH",
      url: "/api/hosts/local/sessions/rename-session/title",
      headers: authHeader(token),
      payload: { title: "新的主题名" },
    });

    expect(rename.statusCode).toBe(200);

    const list = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions",
      headers: authHeader(token),
    });

    expect(list.statusCode).toBe(200);
    expect(list.json().sessions).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          id: "rename-session",
          title: "新的主题名",
        }),
      ]),
    );
  });
});
