import { afterEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";

const runtimeMocks = {
  readRuntimeCatalog: vi.fn(),
  readRuntimeUsage: vi.fn(),
  normalizeRuntimeBridgeError: vi.fn((error: unknown) => ({
    message: error instanceof Error ? error.message : String(error),
  })),
};

vi.mock("../codex/runtime.js", () => runtimeMocks);

describe("runtime routes", () => {
  let app: FastifyInstance | null = null;

  afterEach(async () => {
    await app?.close();
    app = null;
    vi.clearAllMocks();
  });

  it("rejects runtime requests without authentication", async () => {
    const { createTestApp, cleanTables } = await import("./helpers.js");
    cleanTables();

    const created = await createTestApp();
    app = created.app;

    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/runtime/catalog",
    });

    expect(res.statusCode).toBe(401);
  });

  it("returns the runtime catalog and usage for authenticated clients", async () => {
    runtimeMocks.readRuntimeCatalog.mockResolvedValue({
      models: [
        {
          id: "gpt-5.4",
          model: "gpt-5.4",
          upgrade: null,
          upgradeInfo: null,
          availabilityNux: null,
          displayName: "GPT-5.4",
          description: "Latest frontier agentic coding model.",
          hidden: false,
          supportedReasoningEfforts: [
            { reasoningEffort: "medium", description: "Balanced" },
            { reasoningEffort: "xhigh", description: "Extra high" },
          ],
          defaultReasoningEffort: "medium",
          inputModalities: ["text", "image"],
          supportsPersonality: true,
          isDefault: true,
        },
      ],
      nextCursor: null,
    });
    runtimeMocks.readRuntimeUsage.mockResolvedValue({
      rateLimits: {
        limitId: "codex",
        limitName: null,
        primary: {
          usedPercent: 2,
          windowDurationMins: 300,
          resetsAt: 1776298222,
        },
        secondary: {
          usedPercent: 36,
          windowDurationMins: 10080,
          resetsAt: 1776386549,
        },
        credits: {
          hasCredits: false,
          unlimited: false,
          balance: "0",
        },
        planType: "pro",
      },
      rateLimitsByLimitId: {
        codex: {
          limitId: "codex",
          limitName: null,
          primary: {
            usedPercent: 2,
            windowDurationMins: 300,
            resetsAt: 1776298222,
          },
          secondary: {
            usedPercent: 36,
            windowDurationMins: 10080,
            resetsAt: 1776386549,
          },
          credits: {
            hasCredits: false,
            unlimited: false,
            balance: "0",
          },
          planType: "pro",
        },
      },
    });

    const { createTestApp, cleanTables, loginHelper, authHeader } = await import(
      "./helpers.js"
    );
    cleanTables();

    const created = await createTestApp();
    app = created.app;
    const token = await loginHelper(app);

    const catalogRes = await app.inject({
      method: "GET",
      url: "/api/hosts/local/runtime/catalog",
      headers: authHeader(token),
    });
    expect(catalogRes.statusCode).toBe(200);
    expect(catalogRes.json()).toMatchObject({
      models: [
        expect.objectContaining({
          id: "gpt-5.4",
          defaultReasoningEffort: "medium",
        }),
      ],
    });

    const usageRes = await app.inject({
      method: "GET",
      url: "/api/hosts/local/runtime/usage",
      headers: authHeader(token),
    });
    expect(usageRes.statusCode).toBe(200);
    expect(usageRes.json()).toMatchObject({
      rateLimits: expect.objectContaining({
        limitId: "codex",
        planType: "pro",
      }),
      rateLimitsByLimitId: {
        codex: expect.objectContaining({
          limitId: "codex",
        }),
      },
    });
  });

  it("maps runtime bridge failures to a 502 response", async () => {
    runtimeMocks.readRuntimeUsage.mockRejectedValue(
      new Error("codex app-server unavailable"),
    );

    const { createTestApp, cleanTables, loginHelper, authHeader } = await import(
      "./helpers.js"
    );
    cleanTables();

    const created = await createTestApp();
    app = created.app;
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/runtime/usage",
      headers: authHeader(token),
    });

    expect(res.statusCode).toBe(502);
    expect(res.json()).toMatchObject({
      error: "Read runtime usage failed",
      detail: {
        message: "codex app-server unavailable",
      },
    });
  });
});
