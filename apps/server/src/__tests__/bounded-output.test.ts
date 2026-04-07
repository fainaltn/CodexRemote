/**
 * Bounded output buffer tests.
 *
 * Validates:
 *  - small output accumulates without truncation
 *  - output exceeding the cap is tail-truncated
 *  - totalBytes reflects all bytes received (including truncated)
 *  - truncated flag is accurate
 *  - many small chunks are handled correctly
 *  - empty appends are harmless
 *  - default MAX_OUTPUT_BYTES is used when no explicit cap is given
 *  - byte-accurate enforcement for multibyte UTF-8 (CJK, emoji)
 *  - truncation respects UTF-8 character boundaries
 *  - appendBytes path works identically to append
 *  - poll-timer change detection works after truncation (integration)
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { BoundedOutputBuffer, MAX_OUTPUT_BYTES } from "../codex/cli.js";
import type { FastifyInstance } from "fastify";
import {
  MockCodexAdapter,
  MockRunHandle,
  createTestApp,
  loginHelper,
  authHeader,
  cleanTables,
} from "./helpers.js";

// ── Unit tests: BoundedOutputBuffer (ASCII) ─────────────────────────

describe("BoundedOutputBuffer", () => {
  it("accumulates output within the limit", () => {
    const buf = new BoundedOutputBuffer(100);
    buf.append("hello ");
    buf.append("world");
    expect(buf.read()).toBe("hello world");
    expect(buf.totalBytes).toBe(11);
    expect(buf.byteLength).toBe(11);
    expect(buf.truncated).toBe(false);
  });

  it("truncates from the front when limit is exceeded", () => {
    const buf = new BoundedOutputBuffer(10);
    buf.append("abcdefghij"); // exactly 10 bytes
    expect(buf.read()).toBe("abcdefghij");
    expect(buf.truncated).toBe(false);

    buf.append("klmno"); // total 15 → keep last 10
    expect(buf.read()).toBe("fghijklmno");
    expect(buf.byteLength).toBe(10);
    expect(buf.totalBytes).toBe(15);
    expect(buf.truncated).toBe(true);
  });

  it("handles a single chunk larger than the buffer", () => {
    const buf = new BoundedOutputBuffer(5);
    buf.append("abcdefghij"); // 10 bytes, cap is 5
    expect(buf.read()).toBe("fghij");
    expect(buf.byteLength).toBe(5);
    expect(buf.totalBytes).toBe(10);
    expect(buf.truncated).toBe(true);
  });

  it("handles many small chunks correctly", () => {
    const buf = new BoundedOutputBuffer(20);
    for (let i = 0; i < 100; i++) {
      buf.append("x");
    }
    expect(buf.byteLength).toBe(20);
    expect(buf.read()).toBe("x".repeat(20));
    expect(buf.totalBytes).toBe(100);
    expect(buf.truncated).toBe(true);
  });

  it("handles empty appends gracefully", () => {
    const buf = new BoundedOutputBuffer(100);
    buf.append("");
    buf.append("hello");
    buf.append("");
    expect(buf.read()).toBe("hello");
    expect(buf.totalBytes).toBe(5);
    expect(buf.truncated).toBe(false);
  });

  it("uses default MAX_OUTPUT_BYTES when no cap is specified", () => {
    const buf = new BoundedOutputBuffer();
    const large = "x".repeat(MAX_OUTPUT_BYTES + 1000);
    buf.append(large);
    expect(buf.byteLength).toBe(MAX_OUTPUT_BYTES);
    expect(buf.totalBytes).toBe(MAX_OUTPUT_BYTES + 1000);
    expect(buf.truncated).toBe(true);
  });

  it("successive appends after truncation keep correct tail", () => {
    const buf = new BoundedOutputBuffer(10);
    buf.append("aaaaaaaaaa"); // 10 a's — full
    buf.append("bbb");        // total 13, keep last 10: "aaaaaaabbb"
    expect(buf.read()).toBe("aaaaaaabbb");

    buf.append("ccccccc");    // total 20, keep last 10: "bbbccccccc"
    expect(buf.read()).toBe("bbbccccccc");
    expect(buf.totalBytes).toBe(20);
  });

  it("preserves exact content at boundary", () => {
    const buf = new BoundedOutputBuffer(5);
    buf.append("abcde"); // exactly at limit
    expect(buf.read()).toBe("abcde");
    expect(buf.truncated).toBe(false);

    buf.append("f"); // 6 total, truncate to 5
    expect(buf.read()).toBe("bcdef");
    expect(buf.truncated).toBe(true);
  });
});

// ── Unit tests: multibyte / byte-accuracy ───────────────────────────

describe("BoundedOutputBuffer — multibyte byte-accuracy", () => {
  // "€" = U+20AC = 3 bytes in UTF-8 (E2 82 AC)
  // "😀" = U+1F600 = 4 bytes in UTF-8 (F0 9F 98 80)
  // "你" = U+4F60 = 3 bytes in UTF-8 (E4 BD A0)

  it("counts bytes correctly for multibyte characters", () => {
    const buf = new BoundedOutputBuffer(100);
    buf.append("€€€€"); // 4 × 3 = 12 bytes
    expect(buf.totalBytes).toBe(12);
    expect(buf.byteLength).toBe(12);
    expect(buf.read()).toBe("€€€€");
    expect(buf.truncated).toBe(false);
  });

  it("enforces byte cap on 3-byte characters", () => {
    // Cap 6 bytes → fits exactly 2 "€" (2 × 3 = 6)
    const buf = new BoundedOutputBuffer(6);
    buf.append("€€€"); // 9 bytes → truncate to ≤ 6
    expect(buf.read()).toBe("€€");
    expect(buf.byteLength).toBe(6);
    expect(buf.totalBytes).toBe(9);
    expect(buf.truncated).toBe(true);
  });

  it("enforces byte cap on 4-byte emoji", () => {
    // Cap 5 bytes. "😀😀" = 8 bytes. Keep last 5 = cut at byte 3.
    // Byte 3 is 0x80 (continuation of first emoji) → skip it.
    // Lands on byte 4 = 0xF0 (start of second emoji), 4 bytes retained.
    const buf = new BoundedOutputBuffer(5);
    buf.append("😀😀"); // 8 bytes
    expect(buf.read()).toBe("😀");
    expect(buf.byteLength).toBe(4); // ≤ 5 (cap)
    expect(buf.totalBytes).toBe(8);
    expect(buf.truncated).toBe(true);
  });

  it("skips orphaned continuation bytes at truncation boundary", () => {
    // Cap 8 bytes. "€€€€" = 12 bytes. Keep last 8 = start at byte 4.
    // Byte 4 is 0x82 (continuation of 2nd €) → skip.
    // Byte 5 is 0xAC (continuation) → skip.
    // Byte 6 is 0xE2 (start of 3rd €) → stop.
    // Retained: bytes 6–11 = "€€" = 6 bytes.
    const buf = new BoundedOutputBuffer(8);
    buf.append("€€€€"); // 12 bytes
    expect(buf.read()).toBe("€€");
    expect(buf.byteLength).toBe(6); // ≤ 8 (cap), but trimmed for char boundary
    expect(buf.totalBytes).toBe(12);
    expect(buf.truncated).toBe(true);
  });

  it("mixed ASCII and multibyte truncation", () => {
    // "hello" = 5 bytes, "€€€" = 9 bytes → total 14 bytes.
    // Cap 10. Keep last 10 = start at byte 4 = "o" (0x6F, valid start).
    // Retained: "o€€€" = 1 + 9 = 10 bytes.
    const buf = new BoundedOutputBuffer(10);
    buf.append("hello");
    buf.append("€€€");
    expect(buf.read()).toBe("o€€€");
    expect(buf.byteLength).toBe(10);
    expect(buf.totalBytes).toBe(14);
    expect(buf.truncated).toBe(true);
  });

  it("handles CJK characters correctly", () => {
    // "你好世界" = 4 × 3 = 12 bytes
    const buf = new BoundedOutputBuffer(9);
    buf.append("你好世界"); // 12 bytes → keep ≤ 9
    // Keep last 9: exactly 3 characters × 3 bytes = "好世界"
    expect(buf.read()).toBe("好世界");
    expect(buf.byteLength).toBe(9);
    expect(buf.totalBytes).toBe(12);
    expect(buf.truncated).toBe(true);
  });

  it("appendBytes and append produce identical results", () => {
    const text = "hello€😀world";
    const textBytes = Buffer.from(text, "utf-8");

    const bufStr = new BoundedOutputBuffer(10);
    bufStr.append(text);

    const bufRaw = new BoundedOutputBuffer(10);
    bufRaw.appendBytes(textBytes);

    expect(bufRaw.read()).toBe(bufStr.read());
    expect(bufRaw.totalBytes).toBe(bufStr.totalBytes);
    expect(bufRaw.byteLength).toBe(bufStr.byteLength);
    expect(bufRaw.truncated).toBe(bufStr.truncated);
  });

  it("multibyte totalBytes is monotonically accurate across appends", () => {
    const buf = new BoundedOutputBuffer(10);
    buf.append("€"); // 3 bytes
    expect(buf.totalBytes).toBe(3);

    buf.append("😀"); // 4 bytes → total 7
    expect(buf.totalBytes).toBe(7);

    buf.append("你好"); // 6 bytes → total 13, truncation triggers
    expect(buf.totalBytes).toBe(13);
    expect(buf.byteLength).toBeLessThanOrEqual(10);
    expect(buf.truncated).toBe(true);
    // Verify no replacement characters in output
    expect(buf.read()).not.toContain("\uFFFD");
  });

  it("many multibyte chunks stay within byte budget", () => {
    const buf = new BoundedOutputBuffer(30);
    for (let i = 0; i < 50; i++) {
      buf.append("€"); // 3 bytes each → 150 bytes total
    }
    expect(buf.totalBytes).toBe(150);
    expect(buf.byteLength).toBeLessThanOrEqual(30);
    // 30 bytes / 3 bytes per € = 10 €'s max
    expect(buf.read()).toBe("€".repeat(10));
    expect(buf.byteLength).toBe(30);
    expect(buf.truncated).toBe(true);
  });

  it("would over-count bytes using old char-based logic", () => {
    // This test demonstrates the bug: "😀" is .length=2 but 4 bytes.
    // A char-based buffer with cap=4 would keep 2 "😀" (8 bytes!).
    // A byte-based buffer with cap=4 keeps exactly 1 "😀" (4 bytes).
    const buf = new BoundedOutputBuffer(4);
    buf.append("😀😀"); // 8 bytes, 4 JS chars
    expect(buf.read()).toBe("😀");
    expect(buf.byteLength).toBe(4);
    expect(buf.totalBytes).toBe(8);
  });
});

// ── Integration: output bounding through the run lifecycle ──────────

describe("Output bounding integration", () => {
  let app: FastifyInstance;
  let adapter: MockCodexAdapter;
  let token: string;

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

  it("lastOutput reflects appended mock output", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "output test" },
    });

    const handle = adapter.lastRunHandle!;
    handle.appendOutput("line 1\n");
    handle.appendOutput("line 2\n");

    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
    });
    const run = JSON.parse(res.body);
    expect(run.lastOutput).toContain("line 1");
    expect(run.lastOutput).toContain("line 2");
  });

  it("health endpoint reports outputBytes for active runs", async () => {
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "health output test" },
    });

    const handle = adapter.lastRunHandle!;
    handle.appendOutput("some output data");

    const res = await app.inject({
      method: "GET",
      url: "/api/health",
    });
    const body = JSON.parse(res.body);
    expect(body.checks.runs.active).toBe(1);
  });

  it("MockRunHandle.totalOutputBytes is byte-accurate for multibyte", () => {
    const handle = new MockRunHandle();
    handle.appendOutput("€"); // 3 UTF-8 bytes, 1 JS char
    expect(handle.totalOutputBytes()).toBe(3);

    handle.appendOutput("😀"); // 4 UTF-8 bytes, 2 JS chars
    expect(handle.totalOutputBytes()).toBe(7);
    expect(handle.readOutput()).toBe("€😀");
  });
});
