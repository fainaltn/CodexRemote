/**
 * Tests for centralised configuration parsing and validation.
 *
 * Uses parseConfig() with custom env objects so tests are independent
 * of the process-level env vars set by setup.ts.
 */

import { describe, it, expect } from "vitest";
import { parseConfig, validateParsedConfig } from "../config.js";
import { isAllowedMimeType } from "../artifacts/store.js";
import { DEFAULT_ALLOWED_UPLOAD_MIME_PATTERNS } from "@codexremote/shared";

// ── Parsing ────────────────────────────────────────────────────────

describe("config parsing", () => {
  it("returns sensible defaults when no env vars are set", () => {
    const result = parseConfig({});
    expect(result.port).toBe(3000);
    expect(result.host).toBe("127.0.0.1");
    expect(result.requestTimeoutMs).toBe(60_000);
    expect(result.connectionTimeoutMs).toBe(30_000);
    expect(result.runTimeoutMs).toBe(30 * 60 * 1000);
    expect(result.shutdownTimeoutMs).toBe(30_000);
    expect(result.sseIdleTimeoutMs).toBe(5 * 60 * 1000);
    expect(result.uploadStreamTimeoutMs).toBe(30_000);
    expect(result.maxOutputBytes).toBe(512 * 1024);
    expect(result.sseWriteBufferMax).toBe(1024 * 1024);
    expect(result.dataDir).toBe("data");
    expect(result.codexBin).toBe("codex");
    expect(result.allowedUploadMimePatterns).toEqual(
      DEFAULT_ALLOWED_UPLOAD_MIME_PATTERNS,
    );
    expect(result.issues).toEqual([]);
  });

  it("respects valid numeric overrides", () => {
    const result = parseConfig({
      PORT: "8080",
      HOST: "0.0.0.0",
      CODEXREMOTE_REQUEST_TIMEOUT_MS: "90000",
      CODEXREMOTE_CONNECTION_TIMEOUT_MS: "15000",
      CODEXREMOTE_RUN_TIMEOUT_MS: "3600000",
      CODEXREMOTE_SHUTDOWN_TIMEOUT_MS: "60000",
      CODEXREMOTE_SSE_IDLE_TIMEOUT_MS: "120000",
      CODEXREMOTE_UPLOAD_STREAM_TIMEOUT_MS: "45000",
      CODEXREMOTE_MAX_OUTPUT_BYTES: "1048576",
      CODEXREMOTE_SSE_WRITE_BUFFER_MAX: "2097152",
    });
    expect(result.port).toBe(8080);
    expect(result.host).toBe("0.0.0.0");
    expect(result.requestTimeoutMs).toBe(90_000);
    expect(result.connectionTimeoutMs).toBe(15_000);
    expect(result.runTimeoutMs).toBe(3_600_000);
    expect(result.shutdownTimeoutMs).toBe(60_000);
    expect(result.sseIdleTimeoutMs).toBe(120_000);
    expect(result.uploadStreamTimeoutMs).toBe(45_000);
    expect(result.maxOutputBytes).toBe(1_048_576);
    expect(result.sseWriteBufferMax).toBe(2_097_152);
    expect(result.issues).toEqual([]);
  });

  it("respects valid string overrides", () => {
    const result = parseConfig({
      HOST: "192.168.1.100",
      CODEXREMOTE_DATA_DIR: "/custom/data",
      CODEX_BIN: "/usr/local/bin/codex",
      CODEX_STATE_DIR: "/custom/sessions",
    });
    expect(result.host).toBe("192.168.1.100");
    expect(result.dataDir).toBe("/custom/data");
    expect(result.codexBin).toBe("/usr/local/bin/codex");
    expect(result.codexStateDir).toBe("/custom/sessions");
    expect(result.issues).toEqual([]);
  });

  it("collects issue for non-numeric PORT", () => {
    const result = parseConfig({ PORT: "not-a-port" });
    expect(result.port).toBe(3000);
    expect(result.issues).toHaveLength(1);
    expect(result.issues[0]).toContain("PORT");
    expect(result.issues[0]).toContain("not-a-port");
  });

  it("collects issue for PORT outside valid range", () => {
    const result = parseConfig({ PORT: "99999" });
    expect(result.port).toBe(3000);
    expect(result.issues).toHaveLength(1);
    expect(result.issues[0]).toContain("PORT");
  });

  it("collects issue for PORT=0", () => {
    const result = parseConfig({ PORT: "0" });
    expect(result.port).toBe(3000);
    expect(result.issues).toHaveLength(1);
  });

  it("collects issue for non-numeric timeout", () => {
    const result = parseConfig({ CODEXREMOTE_REQUEST_TIMEOUT_MS: "abc" });
    expect(result.requestTimeoutMs).toBe(60_000);
    expect(result.issues).toHaveLength(1);
    expect(result.issues[0]).toContain("CODEXREMOTE_REQUEST_TIMEOUT_MS");
    expect(result.issues[0]).toContain("abc");
  });

  it("collects issue for negative timeout", () => {
    const result = parseConfig({ CODEXREMOTE_RUN_TIMEOUT_MS: "-5" });
    expect(result.runTimeoutMs).toBe(30 * 60 * 1000);
    expect(result.issues).toHaveLength(1);
    expect(result.issues[0]).toContain("CODEXREMOTE_RUN_TIMEOUT_MS");
  });

  it("collects issue for zero value", () => {
    const result = parseConfig({ CODEXREMOTE_SHUTDOWN_TIMEOUT_MS: "0" });
    expect(result.shutdownTimeoutMs).toBe(30_000);
    expect(result.issues).toHaveLength(1);
    expect(result.issues[0]).toContain("CODEXREMOTE_SHUTDOWN_TIMEOUT_MS");
  });

  it("collects multiple issues for multiple bad values", () => {
    const result = parseConfig({
      PORT: "xyz",
      CODEXREMOTE_REQUEST_TIMEOUT_MS: "abc",
      CODEXREMOTE_RUN_TIMEOUT_MS: "-1",
      CODEXREMOTE_MAX_OUTPUT_BYTES: "0",
    });
    expect(result.issues).toHaveLength(4);
  });

  it("ignores empty string values (uses defaults)", () => {
    const result = parseConfig({
      PORT: "",
      CODEXREMOTE_REQUEST_TIMEOUT_MS: "",
    });
    expect(result.port).toBe(3000);
    expect(result.requestTimeoutMs).toBe(60_000);
    expect(result.issues).toEqual([]);
  });
});

// ── Validation ─────────────────────────────────────────────────────

describe("config validation", () => {
  it("passes for valid default config", () => {
    const result = parseConfig({});
    expect(() => validateParsedConfig(result)).not.toThrow();
  });

  it("passes when all values are explicitly set and valid", () => {
    const result = parseConfig({
      PORT: "8080",
      CODEXREMOTE_REQUEST_TIMEOUT_MS: "90000",
      CODEXREMOTE_UPLOAD_STREAM_TIMEOUT_MS: "45000",
      CODEXREMOTE_RUN_TIMEOUT_MS: "3600000",
    });
    expect(() => validateParsedConfig(result)).not.toThrow();
  });

  it("throws when parse issues exist", () => {
    const result = parseConfig({ PORT: "abc" });
    expect(() => validateParsedConfig(result)).toThrow("Invalid configuration");
    expect(() => validateParsedConfig(result)).toThrow("PORT");
  });

  it("throws listing all issues in one message", () => {
    const result = parseConfig({
      PORT: "abc",
      CODEXREMOTE_RUN_TIMEOUT_MS: "xyz",
    });
    expect(result.issues).toHaveLength(2);
    try {
      validateParsedConfig(result);
      expect.unreachable("should have thrown");
    } catch (err) {
      const msg = (err as Error).message;
      expect(msg).toContain("PORT");
      expect(msg).toContain("CODEXREMOTE_RUN_TIMEOUT_MS");
    }
  });

  it("throws when upload timeout >= request timeout", () => {
    const result = parseConfig({
      CODEXREMOTE_UPLOAD_STREAM_TIMEOUT_MS: "90000",
      CODEXREMOTE_REQUEST_TIMEOUT_MS: "60000",
    });
    expect(result.issues).toEqual([]);
    expect(() => validateParsedConfig(result)).toThrow(
      "CODEXREMOTE_UPLOAD_STREAM_TIMEOUT_MS",
    );
  });

  it("throws when upload timeout equals request timeout", () => {
    const result = parseConfig({
      CODEXREMOTE_UPLOAD_STREAM_TIMEOUT_MS: "60000",
      CODEXREMOTE_REQUEST_TIMEOUT_MS: "60000",
    });
    expect(() => validateParsedConfig(result)).toThrow("must be less than");
  });

  it("passes when upload timeout < request timeout", () => {
    const result = parseConfig({
      CODEXREMOTE_UPLOAD_STREAM_TIMEOUT_MS: "30000",
      CODEXREMOTE_REQUEST_TIMEOUT_MS: "60000",
    });
    expect(() => validateParsedConfig(result)).not.toThrow();
  });
});

// ── Allowed upload MIME type config parsing ─────────────────────────

describe("config — allowed upload MIME patterns", () => {
  it("uses defaults when env var is not set", () => {
    const result = parseConfig({});
    expect(result.allowedUploadMimePatterns).toEqual(
      DEFAULT_ALLOWED_UPLOAD_MIME_PATTERNS,
    );
  });

  it("uses defaults when env var is empty", () => {
    const result = parseConfig({ CODEXREMOTE_ALLOWED_UPLOAD_TYPES: "" });
    expect(result.allowedUploadMimePatterns).toEqual(
      DEFAULT_ALLOWED_UPLOAD_MIME_PATTERNS,
    );
  });

  it("merges operator additions with defaults", () => {
    const result = parseConfig({
      CODEXREMOTE_ALLOWED_UPLOAD_TYPES:
        "application/x-custom, video/mp4",
    });
    expect(result.allowedUploadMimePatterns).toContain("image/*");
    expect(result.allowedUploadMimePatterns).toContain("application/x-custom");
    expect(result.allowedUploadMimePatterns).toContain("video/mp4");
  });

  it("deduplicates patterns that overlap with defaults", () => {
    const result = parseConfig({
      CODEXREMOTE_ALLOWED_UPLOAD_TYPES: "image/*, application/pdf",
    });
    const count = result.allowedUploadMimePatterns.filter(
      (p) => p === "image/*",
    ).length;
    expect(count).toBe(1);
  });

  it("strips whitespace from entries", () => {
    const result = parseConfig({
      CODEXREMOTE_ALLOWED_UPLOAD_TYPES: "  video/mp4 , audio/mpeg  ",
    });
    expect(result.allowedUploadMimePatterns).toContain("video/mp4");
    expect(result.allowedUploadMimePatterns).toContain("audio/mpeg");
  });

  it("ignores empty entries from trailing commas", () => {
    const result = parseConfig({
      CODEXREMOTE_ALLOWED_UPLOAD_TYPES: "video/mp4,,",
    });
    const empties = result.allowedUploadMimePatterns.filter(
      (p) => p.length === 0,
    );
    expect(empties).toHaveLength(0);
  });
});

// ── isAllowedMimeType unit tests ──────────────────────────────────

describe("isAllowedMimeType", () => {
  it("matches exact MIME type", () => {
    expect(isAllowedMimeType("application/pdf", ["application/pdf"])).toBe(
      true,
    );
  });

  it("rejects non-matching exact type", () => {
    expect(isAllowedMimeType("application/zip", ["application/pdf"])).toBe(
      false,
    );
  });

  it("matches wildcard pattern (image/*)", () => {
    expect(isAllowedMimeType("image/png", ["image/*"])).toBe(true);
    expect(isAllowedMimeType("image/jpeg", ["image/*"])).toBe(true);
    expect(isAllowedMimeType("image/webp", ["image/*"])).toBe(true);
  });

  it("wildcard does not match across major type", () => {
    expect(isAllowedMimeType("text/plain", ["image/*"])).toBe(false);
  });

  it("matching is case-insensitive", () => {
    expect(isAllowedMimeType("IMAGE/PNG", ["image/*"])).toBe(true);
    expect(isAllowedMimeType("Application/PDF", ["application/pdf"])).toBe(
      true,
    );
  });

  it("returns false for empty patterns list", () => {
    expect(isAllowedMimeType("image/png", [])).toBe(false);
  });

  it("matches the default allowlist for common Codex types", () => {
    const patterns = DEFAULT_ALLOWED_UPLOAD_MIME_PATTERNS;
    // Images
    expect(isAllowedMimeType("image/png", patterns)).toBe(true);
    expect(isAllowedMimeType("image/jpeg", patterns)).toBe(true);
    expect(isAllowedMimeType("image/heic", patterns)).toBe(true);
    // Text/code
    expect(isAllowedMimeType("text/plain", patterns)).toBe(true);
    expect(isAllowedMimeType("text/javascript", patterns)).toBe(true);
    // Documents
    expect(isAllowedMimeType("application/pdf", patterns)).toBe(true);
    expect(isAllowedMimeType("application/json", patterns)).toBe(true);
    // Archives
    expect(isAllowedMimeType("application/zip", patterns)).toBe(true);
    // Generic binary
    expect(isAllowedMimeType("application/octet-stream", patterns)).toBe(true);
  });

  it("rejects dangerous types against the default allowlist", () => {
    const patterns = DEFAULT_ALLOWED_UPLOAD_MIME_PATTERNS;
    expect(isAllowedMimeType("application/x-msdownload", patterns)).toBe(
      false,
    );
    expect(
      isAllowedMimeType(
        "application/vnd.android.package-archive",
        patterns,
      ),
    ).toBe(false);
    expect(isAllowedMimeType("application/x-executable", patterns)).toBe(
      false,
    );
    expect(isAllowedMimeType("application/x-deb", patterns)).toBe(false);
  });
});
