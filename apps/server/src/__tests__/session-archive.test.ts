import { describe, it, expect, beforeEach, afterEach } from "vitest";
import type { FastifyInstance } from "fastify";
import {
  MockCodexAdapter,
  createTestApp,
  loginHelper,
  authHeader,
  cleanTables,
} from "./helpers.js";

let app: FastifyInstance;
let adapter: MockCodexAdapter;
let token: string;

describe("Session archiving", () => {
  beforeEach(async () => {
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-a", { title: "A", cwd: "/workspace/CodexRemote" });
    adapter.addSession("sess-b", { title: "B", cwd: "/workspace/CodexRemote" });
    token = await loginHelper(app);
  });

  afterEach(async () => {
    await app.close();
  });

  it("archives selected sessions and hides them from the default list", async () => {
    const archiveRes = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/archive",
      headers: authHeader(token),
      payload: { sessionIds: ["sess-a"] },
    });

    expect(archiveRes.statusCode).toBe(200);
    expect(JSON.parse(archiveRes.body)).toEqual({ ok: true, archivedCount: 1 });
    expect(adapter.archivedSessionIds).toEqual(["sess-a"]);

    const listRes = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions",
      headers: authHeader(token),
    });

    expect(listRes.statusCode).toBe(200);
    const body = JSON.parse(listRes.body) as { sessions: Array<{ id: string }> };
    expect(body.sessions.map((session) => session.id)).toEqual(["sess-b"]);
  });

  it("returns 400 for invalid archive payloads", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/archive",
      headers: authHeader(token),
      payload: { sessionIds: [] },
    });

    expect(res.statusCode).toBe(400);
  });
});
