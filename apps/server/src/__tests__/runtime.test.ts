import { EventEmitter } from "node:events";
import Fastify, { type FastifyInstance } from "fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const childProcessMocks = vi.hoisted(() => ({
  spawn: vi.fn(),
}));

const runtimeRouteMocks = vi.hoisted(() => ({
  readRuntimeCatalog: vi.fn(),
  readRuntimeUsage: vi.fn(),
}));

vi.mock("node:child_process", () => ({
  spawn: (...args: unknown[]) => childProcessMocks.spawn(...args),
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

function emitJsonLine(child: FakeChild, payload: unknown): void {
  child.stdout.emit("data", Buffer.from(`${JSON.stringify(payload)}\n`));
}

function parseWrite(call: unknown): Record<string, unknown> {
  return JSON.parse(String(call).trim()) as Record<string, unknown>;
}

describe("runtime helper", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    process.env["CODEX_APP_SERVER_BIN"] = "/tmp/codex-app-server-bin";
  });

  afterEach(() => {
    delete process.env["CODEX_APP_SERVER_BIN"];
    vi.restoreAllMocks();
  });

  it("reads model/list through JSON-RPC and normalizes the catalog response", async () => {
    const child = new FakeChild();
    childProcessMocks.spawn.mockReturnValue(child);

    const { readRuntimeCatalog } = await import("../codex/runtime.js");
    const catalogPromise = readRuntimeCatalog();
    await Promise.resolve();
    await Promise.resolve();

    expect(childProcessMocks.spawn).toHaveBeenCalledTimes(1);
    expect(childProcessMocks.spawn).toHaveBeenCalledWith(
      "/tmp/codex-app-server-bin",
      ["app-server", "--listen", "stdio://"],
      expect.objectContaining({
        stdio: ["pipe", "pipe", "pipe"],
      }),
    );

    const writesBeforeInit = child.stdin.write.mock.calls.map((args) =>
      parseWrite(args[0]),
    );
    expect(writesBeforeInit).toHaveLength(1);
    expect(writesBeforeInit[0]).toMatchObject({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: expect.objectContaining({
        protocolVersion: "2025-06-18",
        clientInfo: {
          name: "CodexRemote",
          version: "0.0.0",
        },
        capabilities: {
          experimentalApi: true,
        },
      }),
    });

    emitJsonLine(child, { id: 1, result: { ok: true } });

    const writesAfterInit = child.stdin.write.mock.calls.map((args) =>
      parseWrite(args[0]),
    );
    expect(writesAfterInit).toHaveLength(2);
    expect(writesAfterInit[1]).toMatchObject({
      jsonrpc: "2.0",
      id: 2,
      method: "model/list",
      params: {},
    });

    emitJsonLine(child, {
      id: 2,
      result: {
        data: [
          {
            id: "gpt-5.4",
            displayName: "GPT-5.4",
            supportedReasoningEfforts: [
              { reasoningEffort: "medium", description: "Balanced" },
              { reasoningEffort: "xhigh", description: "Extra high" },
            ],
            defaultReasoningEffort: "medium",
            hidden: false,
            isDefault: true,
          },
        ],
        nextCursor: "cursor-123",
      },
    });

    await expect(catalogPromise).resolves.toMatchObject({
      models: [
        expect.objectContaining({
          id: "gpt-5.4",
          displayName: "GPT-5.4",
          defaultReasoningEffort: "medium",
          supportedReasoningEfforts: expect.arrayContaining([
            expect.objectContaining({ reasoningEffort: "xhigh" }),
          ]),
        }),
      ],
      nextCursor: "cursor-123",
    });
  });

  it("reads account/rateLimits/read through JSON-RPC and normalizes the usage response", async () => {
    const child = new FakeChild();
    childProcessMocks.spawn.mockReturnValue(child);

    const { readRuntimeUsage } = await import("../codex/runtime.js");
    const usagePromise = readRuntimeUsage();
    await Promise.resolve();
    await Promise.resolve();

    emitJsonLine(child, { id: 1, result: { ok: true } });
    emitJsonLine(child, {
      id: 2,
      result: {
        rateLimits: {
          limitId: "codex",
          limitName: null,
          primary: {
            usedPercent: 12,
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
              usedPercent: 12,
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
      },
    });

    await expect(usagePromise).resolves.toMatchObject({
      rateLimits: expect.objectContaining({
        limitId: "codex",
        planType: "pro",
        credits: expect.objectContaining({ balance: "0" }),
      }),
      rateLimitsByLimitId: {
        codex: expect.objectContaining({
          limitId: "codex",
        }),
      },
    });
  });

  it("rejects when the app-server returns a JSON-RPC error payload", async () => {
    const child = new FakeChild();
    childProcessMocks.spawn.mockReturnValue(child);

    const { readRuntimeCatalog } = await import("../codex/runtime.js");
    const catalogPromise = readRuntimeCatalog();
    await Promise.resolve();
    await Promise.resolve();

    emitJsonLine(child, { id: 1, result: { ok: true } });
    emitJsonLine(child, {
      id: 2,
      error: {
        message: "model list unavailable",
        code: 503,
        detail: { retryable: true },
      },
    });

    await expect(catalogPromise).rejects.toMatchObject({
      message: "codex app-server model/list failed",
      detail: {
        message: "model list unavailable",
        code: 503,
        detail: { retryable: true },
      },
    });
  });
});

describe("runtime routes", () => {
  let app: FastifyInstance | null = null;

  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    vi.doMock("../codex/runtime.js", async () => {
      const actual = await vi.importActual<typeof import("../codex/runtime.js")>(
        "../codex/runtime.js",
      );
      return {
        ...actual,
        readRuntimeCatalog: runtimeRouteMocks.readRuntimeCatalog,
        readRuntimeUsage: runtimeRouteMocks.readRuntimeUsage,
      };
    });
  });

  afterEach(async () => {
    await app?.close();
    app = null;
    vi.doUnmock("../codex/runtime.js");
    vi.restoreAllMocks();
  });

  async function buildProtectedRuntimeApp(): Promise<FastifyInstance> {
    const { runtimeRoutes } = await import("../routes/runtime.js");
    const fastify = Fastify({ logger: false });
    fastify.addHook("preHandler", async (request, reply) => {
      if (request.headers.authorization !== "Bearer test-token") {
        return reply.status(401).send({ error: "Missing or invalid token" });
      }
    });
    await fastify.register(runtimeRoutes());
    app = fastify;
    return fastify;
  }

  it("rejects runtime requests without authentication", async () => {
    const fastify = await buildProtectedRuntimeApp();
    const res = await fastify.inject({
      method: "GET",
      url: "/api/hosts/local/runtime/catalog",
    });

    expect(res.statusCode).toBe(401);
  });

  it("returns the runtime catalog and rate limits for authenticated clients", async () => {
    runtimeRouteMocks.readRuntimeCatalog.mockResolvedValue({
      models: [
        {
          id: "gpt-5.4",
          model: "gpt-5.4",
          upgrade: null,
          upgradeInfo: null,
          availabilityNux: null,
          displayName: "GPT-5.4",
          description: "Latest frontier coding model.",
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
      nextCursor: "cursor-123",
    });
    runtimeRouteMocks.readRuntimeUsage.mockResolvedValue({
      rateLimits: {
        limitId: "codex",
        limitName: null,
        primary: {
          usedPercent: 12,
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
            usedPercent: 12,
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

    const fastify = await buildProtectedRuntimeApp();
    const headers = { authorization: "Bearer test-token" };

    const catalogRes = await fastify.inject({
      method: "GET",
      url: "/api/hosts/local/runtime/catalog",
      headers,
    });
    expect(catalogRes.statusCode).toBe(200);
    expect(catalogRes.json()).toMatchObject({
      models: [
        expect.objectContaining({
          id: "gpt-5.4",
          defaultReasoningEffort: "medium",
          supportedReasoningEfforts: expect.arrayContaining([
            expect.objectContaining({ reasoningEffort: "xhigh" }),
          ]),
        }),
      ],
      nextCursor: "cursor-123",
      fetchedAt: expect.any(String),
    });

    const usageRes = await fastify.inject({
      method: "GET",
      url: "/api/hosts/local/runtime/usage",
      headers,
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
      fetchedAt: expect.any(String),
    });

    const rateLimitsRes = await fastify.inject({
      method: "GET",
      url: "/api/hosts/local/runtime/rate-limits",
      headers,
    });
    expect(rateLimitsRes.statusCode).toBe(200);
    expect(rateLimitsRes.json()).toMatchObject({
      rateLimits: expect.objectContaining({
        limitId: "codex",
      }),
      rateLimitsByLimitId: {
        codex: expect.objectContaining({
          limitId: "codex",
        }),
      },
      fetchedAt: expect.any(String),
    });
  });

  it("maps runtime bridge failures to a 502 response", async () => {
    runtimeRouteMocks.readRuntimeUsage.mockRejectedValue(
      new Error("codex app-server unavailable"),
    );

    const fastify = await buildProtectedRuntimeApp();
    const res = await fastify.inject({
      method: "GET",
      url: "/api/hosts/local/runtime/usage",
      headers: { authorization: "Bearer test-token" },
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
