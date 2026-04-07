/**
 * Payload size validation tests.
 *
 * Verifies that the Zod `.max()` constraints added to all API request
 * schemas correctly reject oversized payloads before they reach the
 * run manager, auth store, or child-process stdin.
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import type { FastifyInstance } from "fastify";
import {
  MAX_PROMPT_LENGTH,
  MAX_MODEL_LENGTH,
  MAX_REASONING_EFFORT_LENGTH,
  MAX_PASSWORD_LENGTH,
  MAX_DEVICE_LABEL_LENGTH,
} from "@codexremote/shared";
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

describe("Payload size validation", () => {
  beforeEach(async () => {
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-1");
    token = await loginHelper(app);
  });

  afterEach(async () => {
    if (!app) return;
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live/stop",
      headers: authHeader(token),
    });
    await app.close();
  });

  // ── Prompt length ─────────────────────────────────────────────────

  it("rejects prompt exceeding MAX_PROMPT_LENGTH with 400", async () => {
    const oversizedPrompt = "x".repeat(MAX_PROMPT_LENGTH + 1);
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: oversizedPrompt },
    });
    expect(res.statusCode).toBe(400);
  });

  it("accepts prompt at exactly MAX_PROMPT_LENGTH", async () => {
    const maxPrompt = "x".repeat(MAX_PROMPT_LENGTH);
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: maxPrompt },
    });
    // 201 = run started (accepted), not 400
    expect(res.statusCode).toBe(201);
  });

  // ── Model length ──────────────────────────────────────────────────

  it("rejects model exceeding MAX_MODEL_LENGTH with 400", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: {
        prompt: "hello",
        model: "m".repeat(MAX_MODEL_LENGTH + 1),
      },
    });
    expect(res.statusCode).toBe(400);
  });

  // ── ReasoningEffort length ────────────────────────────────────────

  it("rejects reasoningEffort exceeding MAX_REASONING_EFFORT_LENGTH with 400", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: {
        prompt: "hello",
        reasoningEffort: "r".repeat(MAX_REASONING_EFFORT_LENGTH + 1),
      },
    });
    expect(res.statusCode).toBe(400);
  });

  // ── Password length ───────────────────────────────────────────────

  it("rejects password exceeding MAX_PASSWORD_LENGTH with 400", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: { password: "p".repeat(MAX_PASSWORD_LENGTH + 1) },
    });
    expect(res.statusCode).toBe(400);
  });

  // ── Device label length ───────────────────────────────────────────

  it("rejects deviceLabel exceeding MAX_DEVICE_LABEL_LENGTH with 400", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: {
        password: "test-password",
        deviceLabel: "d".repeat(MAX_DEVICE_LABEL_LENGTH + 1),
      },
    });
    expect(res.statusCode).toBe(400);
  });

  it("accepts deviceLabel at exactly MAX_DEVICE_LABEL_LENGTH", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/auth/login",
      payload: {
        password: "test-password",
        deviceLabel: "d".repeat(MAX_DEVICE_LABEL_LENGTH),
      },
    });
    // 200 = login succeeded (password is correct), not 400
    expect(res.statusCode).toBe(200);
  });

  // ── Fastify body limit (defence-in-depth) ─────────────────────────

  it("rejects JSON body exceeding Fastify bodyLimit", async () => {
    // Construct a payload larger than 512 KB that would pass Zod length
    // checks individually but exceeds the Fastify bodyLimit.  We can do
    // this by sending a body with many extra keys (Zod .passthrough()
    // is not used, but Fastify enforces the limit before Zod runs).
    const hugePayload = JSON.stringify({
      prompt: "x",
      _padding: "y".repeat(600 * 1024),
    });

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: {
        ...authHeader(token),
        "content-type": "application/json",
      },
      body: hugePayload,
    });

    // Fastify returns 413 Payload Too Large when bodyLimit is exceeded.
    expect(res.statusCode).toBe(413);
  });
});
