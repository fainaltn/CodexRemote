/**
 * Upload flow tests.
 *
 * Validates:
 *  - multipart upload success with session validation
 *  - missing sessionId → 400
 *  - missing file → 400
 *  - unknown session → 404
 *  - unknown host → 404
 *  - artifact attachment to session
 *  - attachment of non-existent artifact → 404
 *  - upload auto-links to active run when one exists
 *  - MIME-based kind inference (image vs file)
 *  - file-type policy enforcement (allowed → 201, disallowed → 415)
 *  - custom allowlist override via AppOptions
 *  - audit logging of disallowed file-type rejections
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import type { FastifyInstance } from "fastify";
import {
  MockCodexAdapter,
  createTestApp,
  loginHelper,
  authHeader,
  cleanTables,
  buildMultipartPayload,
} from "./helpers.js";

const BOUNDARY = "----TestBoundary123456";

let app: FastifyInstance;
let adapter: MockCodexAdapter;
let token: string;

describe("Upload routes", () => {
  beforeEach(async () => {
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-1");
    token = await loginHelper(app);
  });

  afterEach(async () => {
    if (!app) return;
    // Stop any active run to clean up timers.
    await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live/stop",
      headers: authHeader(token),
    });
    await app.close();
  });

  // ── Successful upload ─────────────────────────────────────────────

  it("POST /api/hosts/local/uploads stores artifact and returns 201", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "photo.png",
        content: Buffer.from("fake-png-bytes"),
        contentType: "image/png",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(201);

    const body = JSON.parse(res.body);
    expect(body).toHaveProperty("id");
    expect(body.sessionId).toBe("sess-1");
    expect(body.originalName).toBe("photo.png");
    expect(body.mimeType).toBe("image/png");
    expect(body.kind).toBe("image");
    expect(body.sizeBytes).toBe(Buffer.from("fake-png-bytes").length);
  });

  it("upload of non-image file gets kind 'file'", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "notes.txt",
        content: Buffer.from("some text"),
        contentType: "text/plain",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(201);
    expect(JSON.parse(res.body).kind).toBe("file");
  });

  // ── Missing fields ────────────────────────────────────────────────

  it("upload without sessionId returns 400", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      {}, // no sessionId
      {
        fieldName: "file",
        filename: "photo.png",
        content: Buffer.from("data"),
        contentType: "image/png",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(400);
  });

  // ── Unknown session / host ────────────────────────────────────────

  it("upload for unknown session returns 404", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "no-such-session" },
      {
        fieldName: "file",
        filename: "photo.png",
        content: Buffer.from("data"),
        contentType: "image/png",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(404);
  });

  it("upload for unknown host returns 404", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "photo.png",
        content: Buffer.from("data"),
        contentType: "image/png",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/remote-99/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(404);
  });

  // ── Auth enforcement ──────────────────────────────────────────────

  it("upload without token returns 401", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "photo.png",
        content: Buffer.from("data"),
        contentType: "image/png",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(401);
  });

  // ── Artifact attachment ───────────────────────────────────────────

  it("POST attach links artifact to session", async () => {
    // First upload an artifact.
    const uploadPayload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "doc.pdf",
        content: Buffer.from("pdf-data"),
        contentType: "application/pdf",
      },
    );

    const uploadRes = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload: uploadPayload,
    });
    const artifact = JSON.parse(uploadRes.body);

    // Attach the artifact to the same session (no-op re-attach).
    const attachRes = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/artifacts",
      headers: authHeader(token),
      payload: { artifactId: artifact.id },
    });
    expect(attachRes.statusCode).toBe(200);
    expect(JSON.parse(attachRes.body).id).toBe(artifact.id);
  });

  it("POST attach with non-existent artifact returns 404", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/artifacts",
      headers: authHeader(token),
      payload: { artifactId: "no-such-artifact" },
    });
    expect(res.statusCode).toBe(404);
  });

  it("POST attach for unknown session returns 404", async () => {
    // Upload to a valid session first so we have an artifact id.
    const uploadPayload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "file.bin",
        content: Buffer.from("data"),
        contentType: "application/octet-stream",
      },
    );
    const uploadRes = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload: uploadPayload,
    });
    const artifact = JSON.parse(uploadRes.body);

    // Try to attach to a session that doesn't exist in the adapter.
    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/ghost-session/artifacts",
      headers: authHeader(token),
      payload: { artifactId: artifact.id },
    });
    expect(res.statusCode).toBe(404);
  });

  // ── Upload auto-links to active run ───────────────────────────────

  it("upload auto-links artifact to active run when present", async () => {
    // Start a run on the session.
    const startRes = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/sess-1/live",
      headers: authHeader(token),
      payload: { prompt: "process this image" },
    });
    const { runId } = JSON.parse(startRes.body);

    // Upload while the run is active.
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "screenshot.jpg",
        content: Buffer.from("jpeg-bytes"),
        contentType: "image/jpeg",
      },
    );

    const uploadRes = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(uploadRes.statusCode).toBe(201);

    const artifact = JSON.parse(uploadRes.body);
    expect(artifact.runId).toBe(runId);
  });

  it("upload without active run leaves runId null", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "file.txt",
        content: Buffer.from("data"),
        contentType: "text/plain",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(201);
    expect(JSON.parse(res.body).runId).toBeNull();
  });

  // ── File-type policy enforcement ──────────────────────────────────

  it("upload with disallowed MIME type returns 415", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "malware.exe",
        content: Buffer.from("MZ-fake-executable"),
        contentType: "application/x-msdownload",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(415);
    const body = JSON.parse(res.body);
    expect(body.error).toContain("application/x-msdownload");
    expect(body.error).toContain("not allowed");
  });

  it("upload with Android APK MIME type returns 415", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "app.apk",
        content: Buffer.from("PK-fake-apk"),
        contentType: "application/vnd.android.package-archive",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(415);
  });

  it("upload with allowed wildcard pattern (image/*) succeeds", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "photo.webp",
        content: Buffer.from("fake-webp"),
        contentType: "image/webp",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(201);
    expect(JSON.parse(res.body).mimeType).toBe("image/webp");
  });

  it("upload with allowed exact MIME type (application/pdf) succeeds", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "doc.pdf",
        content: Buffer.from("fake-pdf"),
        contentType: "application/pdf",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(201);
  });

  it("upload with application/octet-stream succeeds (generic binary)", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "data.bin",
        content: Buffer.from("raw-bytes"),
        contentType: "application/octet-stream",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(201);
  });
});

// ── File-type policy: custom allowlist ───────────────────────────────

describe("Upload routes — custom MIME allowlist", () => {
  let restrictedApp: FastifyInstance;
  let restrictedAdapter: MockCodexAdapter;
  let restrictedToken: string;

  beforeEach(async () => {
    cleanTables();
    restrictedAdapter = new MockCodexAdapter();
    ({ app: restrictedApp } = await createTestApp(restrictedAdapter, {
      // Very restrictive: only image/png allowed.
      allowedUploadMimePatterns: ["image/png"],
    }));
    restrictedAdapter.addSession("sess-1");
    restrictedToken = await loginHelper(restrictedApp);
  });

  afterEach(async () => {
    if (restrictedApp) await restrictedApp.close();
  });

  it("allows upload matching the custom allowlist", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "photo.png",
        content: Buffer.from("png-data"),
        contentType: "image/png",
      },
    );

    const res = await restrictedApp.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(restrictedToken),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(201);
  });

  it("rejects upload not in the custom allowlist", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "doc.pdf",
        content: Buffer.from("pdf-data"),
        contentType: "application/pdf",
      },
    );

    const res = await restrictedApp.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(restrictedToken),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(415);
  });

  it("rejects image/jpeg when only image/png is allowed", async () => {
    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "photo.jpg",
        content: Buffer.from("jpeg-data"),
        contentType: "image/jpeg",
      },
    );

    const res = await restrictedApp.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(restrictedToken),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(415);
  });
});

// ── File-type policy: audit logging ──────────────────────────────────

describe("Upload routes — file-type audit logging", () => {
  beforeEach(async () => {
    cleanTables();
    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-1");
    token = await loginHelper(app);
  });

  afterEach(async () => {
    if (app) await app.close();
  });

  it("logs upload_failure audit event for disallowed file type", async () => {
    const { getDb } = await import("../db.js");

    const payload = buildMultipartPayload(
      BOUNDARY,
      { sessionId: "sess-1" },
      {
        fieldName: "file",
        filename: "bad.exe",
        content: Buffer.from("evil"),
        contentType: "application/x-msdownload",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/uploads",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${BOUNDARY}`,
      },
      payload,
    });
    expect(res.statusCode).toBe(415);

    const db = getDb();
    const row = db
      .prepare(
        "SELECT * FROM audit_log WHERE event_type = 'upload_failure' ORDER BY timestamp DESC LIMIT 1",
      )
      .get() as { detail: string; event_type: string } | undefined;

    expect(row).toBeDefined();
    expect(row!.detail).toContain("disallowed_file_type");
    expect(row!.detail).toContain("application/x-msdownload");
  });
});
