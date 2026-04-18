import { spawn } from "node:child_process";
import { access } from "node:fs/promises";
import { constants as fsConstants } from "node:fs";
import { BoundedOutputBuffer, codexBin } from "./cli.js";

const APP_SERVER_STDIO_URL = "stdio://";
const APP_SERVER_INIT_REQUEST_ID = 1;
const APP_SERVER_REQUEST_ID = 2;
const APP_SERVER_PROTOCOL_VERSION = "2025-06-18";
const DESKTOP_BUNDLED_CODEX_BIN =
  "/Applications/Codex.app/Contents/Resources/codex";

export interface RuntimeModelReasoningEffort {
  reasoningEffort: string;
  description: string;
}

export interface RuntimeModel {
  id: string;
  model: string;
  upgrade: string | null;
  upgradeInfo: unknown | null;
  availabilityNux: unknown | null;
  displayName: string;
  description: string;
  hidden: boolean;
  supportedReasoningEfforts: RuntimeModelReasoningEffort[];
  defaultReasoningEffort: string | null;
  inputModalities: string[];
  supportsPersonality: boolean;
  isDefault: boolean;
}

export interface RuntimeCatalogResult {
  models: RuntimeModel[];
  nextCursor: string | null;
}

export interface RuntimeRateLimitWindow {
  usedPercent: number;
  windowDurationMins: number;
  resetsAt: number;
}

export interface RuntimeCredits {
  hasCredits: boolean;
  unlimited: boolean;
  balance: string;
}

export interface RuntimeRateLimit {
  limitId: string;
  limitName: string | null;
  primary: RuntimeRateLimitWindow;
  secondary: RuntimeRateLimitWindow;
  credits: RuntimeCredits | null;
  planType: string | null;
}

export interface RuntimeUsageResult {
  rateLimits: RuntimeRateLimit | null;
  rateLimitsByLimitId: Record<string, RuntimeRateLimit>;
}

export interface RuntimeBridgeErrorShape {
  message: string;
  code?: number | null;
  detail?: unknown;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object";
}

function stringifyError(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

async function resolveCodexAppServerBin(): Promise<string> {
  const override = process.env["CODEX_APP_SERVER_BIN"];
  if (override) {
    return override;
  }

  try {
    await access(DESKTOP_BUNDLED_CODEX_BIN, fsConstants.X_OK);
    return DESKTOP_BUNDLED_CODEX_BIN;
  } catch {
    return codexBin();
  }
}

async function readJsonRpcResponse<T>(
  method: string,
  params: Record<string, unknown>,
): Promise<T> {
  const appServerBin = await resolveCodexAppServerBin();
  const child = spawn(appServerBin, ["app-server", "--listen", APP_SERVER_STDIO_URL], {
    stdio: ["pipe", "pipe", "pipe"],
    env: { ...process.env },
  });

  const buffer = new BoundedOutputBuffer(64 * 1024);
  let stdoutRemainder = "";
  let settled = false;
  let shutdownTimer: NodeJS.Timeout | null = null;

  const completion = new Promise<T>((resolve, reject) => {
    const scheduleShutdown = () => {
      if (child.exitCode !== null || child.signalCode !== null) {
        return;
      }
      child.stdin?.end();
      if (shutdownTimer) {
        clearTimeout(shutdownTimer);
      }
      shutdownTimer = setTimeout(() => {
        if (child.exitCode === null && child.signalCode === null) {
          child.kill("SIGTERM");
        }
      }, 2_000);
      shutdownTimer.unref();
    };

    const fail = (message: string, detail?: unknown) => {
      if (settled) return;
      settled = true;
      reject(Object.assign(new Error(message), { detail }));
      if (child.exitCode === null && child.signalCode === null) {
        child.kill("SIGTERM");
      }
      scheduleShutdown();
    };

    const succeed = (result: T) => {
      if (settled) return;
      settled = true;
      resolve(result);
      scheduleShutdown();
    };

    const handleMessage = (line: string) => {
      const trimmed = line.trim();
      if (!trimmed) return;

      let message: Record<string, unknown>;
      try {
        message = JSON.parse(trimmed) as Record<string, unknown>;
      } catch {
        buffer.append(`${trimmed}\n`);
        return;
      }

      const id = typeof message["id"] === "number" ? message["id"] : null;
      const responseError = message["error"];

      if (id === APP_SERVER_INIT_REQUEST_ID) {
        if (responseError) {
          fail("codex app-server initialize failed", responseError);
          return;
        }
        child.stdin?.write(
          `${JSON.stringify({
            jsonrpc: "2.0",
            id: APP_SERVER_REQUEST_ID,
            method,
            params,
          })}\n`,
        );
        return;
      }

      if (id === APP_SERVER_REQUEST_ID) {
        if (responseError) {
          fail(`codex app-server ${method} failed`, responseError);
          return;
        }
        if (!isObject(message["result"])) {
          fail(
            `codex app-server ${method} returned an unexpected payload`,
            message["result"],
          );
          return;
        }
        succeed(message["result"] as T);
      }
    };

    child.stdout?.on("data", (chunk: Buffer) => {
      stdoutRemainder += chunk.toString("utf-8");
      const lines = stdoutRemainder.split("\n");
      stdoutRemainder = lines.pop() ?? "";
      for (const line of lines) {
        handleMessage(line);
      }
    });

    child.stderr?.on("data", (chunk: Buffer) => {
      buffer.appendBytes(chunk);
    });

    child.on("error", (err) => {
      fail(`Failed to start codex app-server: ${err.message}`);
    });

    child.on("close", (code) => {
      if (settled) {
        return;
      }
      if (stdoutRemainder.trim()) {
        handleMessage(stdoutRemainder);
        stdoutRemainder = "";
      }
      const stderr = buffer.read().trim();
      fail(
        `codex app-server exited before ${method} completed${
          code === null ? "" : ` (code ${code})`
        }${stderr ? `: ${stderr}` : ""}`,
      );
    });
  });

  child.stdin?.write(
    `${JSON.stringify({
      jsonrpc: "2.0",
      id: APP_SERVER_INIT_REQUEST_ID,
      method: "initialize",
      params: {
        protocolVersion: APP_SERVER_PROTOCOL_VERSION,
        clientInfo: {
          name: "CodexRemote",
          version: "0.0.0",
        },
        capabilities: {
          experimentalApi: true,
        },
      },
    })}\n`,
  );

  return completion;
}

export async function readRuntimeCatalog(): Promise<RuntimeCatalogResult> {
  const result = await readJsonRpcResponse<{
    data?: RuntimeModel[];
    nextCursor?: string | null;
  }>("model/list", {});

  return {
    models: Array.isArray(result.data) ? result.data : [],
    nextCursor: result.nextCursor ?? null,
  };
}

export async function readRuntimeUsage(): Promise<RuntimeUsageResult> {
  const result = await readJsonRpcResponse<{
    rateLimits?: RuntimeRateLimit | null;
    rateLimitsByLimitId?: Record<string, RuntimeRateLimit>;
  }>("account/rateLimits/read", {});

  return {
    rateLimits: result.rateLimits ?? null,
    rateLimitsByLimitId: result.rateLimitsByLimitId ?? {},
  };
}

export function normalizeRuntimeBridgeError(error: unknown): RuntimeBridgeErrorShape {
  if (error instanceof Error) {
    const bridgeError: RuntimeBridgeErrorShape = { message: error.message };
    const detail = (error as Error & { detail?: unknown }).detail;
    if (detail !== undefined) {
      bridgeError.detail = detail;
    }
    return bridgeError;
  }

  if (isObject(error)) {
    const message =
      typeof error["message"] === "string"
        ? error["message"]
        : "Runtime bridge request failed";
    const bridgeError: RuntimeBridgeErrorShape = { message };
    if (error["code"] !== undefined) {
      bridgeError.code = typeof error["code"] === "number" ? error["code"] : null;
    }
    if (error["detail"] !== undefined) {
      bridgeError.detail = error["detail"];
    }
    return bridgeError;
  }

  return { message: stringifyError(error) };
}
