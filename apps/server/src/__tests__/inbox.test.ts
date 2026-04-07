import fs from "node:fs";
import path from "node:path";
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { cleanTables, createTestApp, authHeader, loginHelper, buildMultipartPayload } from "./helpers.js";
import { closeDb } from "../db.js";
import { listInboxItems } from "../inbox/store.js";
import { writeSubmissionBundle } from "../inbox/submission.js";

const DATA_ROOT = process.env["CODEXREMOTE_DATA_DIR"] ?? "data";
const STAGING_ROOT = process.env["CODEXREMOTE_STAGING_DIR"] ?? path.join(DATA_ROOT, "submissions");

function buildMultipartFilesPayload(
  boundary: string,
  fields: Record<string, string>,
  files: Array<{
    fieldName: string;
    filename: string;
    content: Buffer;
    contentType: string;
  }>,
): Buffer {
  const parts: Buffer[] = [];
  for (const [key, value] of Object.entries(fields)) {
    parts.push(
      Buffer.from(
        `--${boundary}\r\n` +
          `Content-Disposition: form-data; name="${key}"\r\n\r\n` +
          `${value}\r\n`,
      ),
    );
  }
  for (const file of files) {
    parts.push(
      Buffer.from(
        `--${boundary}\r\n` +
          `Content-Disposition: form-data; name="${file.fieldName}"; filename="${file.filename}"\r\n` +
          `Content-Type: ${file.contentType}\r\n\r\n`,
      ),
    );
    parts.push(file.content);
    parts.push(Buffer.from("\r\n"));
  }
  parts.push(Buffer.from(`--${boundary}--\r\n`));
  return Buffer.concat(parts);
}

describe("inbox routes", () => {
  beforeEach(() => {
    cleanTables();
    fs.rmSync(STAGING_ROOT, { recursive: true, force: true });
  });

  afterEach(async () => {
    closeDb();
    fs.rmSync(STAGING_ROOT, { recursive: true, force: true });
  });

  it("submits a link successfully", async () => {
    const { app } = await createTestApp();
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/inbox/link",
      headers: authHeader(token),
      payload: {
        url: "https://example.com/article",
        title: "Example",
        note: "read later",
        source: "android",
      },
    });

    expect(res.statusCode).toBe(201);
    const body = JSON.parse(res.body);
    expect(body.kind).toBe("link");
    expect(body.status).toBe("received");
    expect(body.url).toBe("https://example.com/article");
    expect(body.title).toBe("Example");
    expect(body.source).toBe("android");
    expect(body.contract).toBe("codexremote_v1");
    expect(body.submissionId).toBe(body.id);
    expect(body.stagingDir).toContain("/submissions/local/");
    expect(body.submissionPath).toContain("/submission.json");
    expect(fs.existsSync(body.submissionPath)).toBe(true);
    const manifest = JSON.parse(fs.readFileSync(body.submissionPath, "utf-8"));
    expect(manifest.contract).toBe("codexremote_v1");
    expect(manifest.payload.source_locator).toBe("https://example.com/article");
    expect(manifest.capture_sessions[0].file_count).toBe(0);
  });

  it("uploads an inbox file successfully", async () => {
    const { app } = await createTestApp();
    const token = await loginHelper(app);
    const boundary = "----inbox-boundary";
    const payload = buildMultipartPayload(
      boundary,
      { note: "invoice", source: "web" },
      {
        fieldName: "file",
        filename: "note.txt",
        content: Buffer.from("hello inbox"),
        contentType: "text/plain",
      },
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/inbox/file",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${boundary}`,
      },
      payload,
    });

    expect(res.statusCode).toBe(201);
    const body = JSON.parse(res.body);
    expect(body.kind).toBe("file");
    expect(body.originalName).toBe("note.txt");
    expect(body.note).toBe("invoice");
    expect(body.source).toBe("web");
    expect(body.stagingDir).toContain("/submissions/local/");
    expect(body.storedPath).toBe(body.submissionPath);
    expect(fs.existsSync(body.stagingDir)).toBe(true);
    expect(body.submissionPath).toContain("/submission.json");
    expect(fs.existsSync(body.submissionPath)).toBe(true);
    const manifest = JSON.parse(fs.readFileSync(body.submissionPath, "utf-8"));
    expect(manifest.contract).toBe("codexremote_v1");
    expect(manifest.attachments).toEqual([
      { path: "attachments/note.txt", kind: "text" },
    ]);
    expect(manifest.capture_sessions[0].file_count).toBe(1);
    expect(manifest.retry_attempts[0].status).toBe("completed");
  });

  it("rejects unauthorized inbox requests", async () => {
    const { app } = await createTestApp();
    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/inbox",
    });

    expect(res.statusCode).toBe(401);
  });

  it("rejects invalid link input", async () => {
    const { app } = await createTestApp();
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/inbox/link",
      headers: authHeader(token),
      payload: { url: "not-a-url" },
    });

    expect(res.statusCode).toBe(400);
  });

  it("lists recent inbox items", async () => {
    const { app } = await createTestApp();
    const token = await loginHelper(app);

    await app.inject({
      method: "POST",
      url: "/api/hosts/local/inbox/link",
      headers: authHeader(token),
      payload: { url: "https://example.com/one", source: "web" },
    });

    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/inbox",
      headers: authHeader(token),
    });

    expect(res.statusCode).toBe(200);
    const body = JSON.parse(res.body);
    expect(body.items).toHaveLength(1);
    expect(body.items[0].url).toBe("https://example.com/one");
    expect(body.items[0].stagingDir).toContain("/submissions/local/");
    expect(body.items[0].submissionPath).toContain("/submission.json");
    expect(body.items[0].contract).toBe("codexremote_v1");
  });

  it("writes a submission bundle with multi-attachment and retry metadata", async () => {
    const itemDir = path.join(STAGING_ROOT, "local", "manual-multi");
    const attachmentDir = path.join(itemDir, "attachments", "session-a");
    fs.mkdirSync(attachmentDir, { recursive: true });
    fs.writeFileSync(path.join(attachmentDir, "clip.txt"), "remote artifact", "utf-8");
    fs.writeFileSync(path.join(attachmentDir, "page.md"), "# Captured page\n", "utf-8");

    const submissionPath = await writeSubmissionBundle({
      itemDir,
      submissionId: "manual-multi",
      payload: {
        title: "Remote Session Capture",
        kind: "note",
        sourceLocator: "codexremote://submission/manual-multi",
        itemId: "manual-multi",
      },
      attachments: [
        { relativePath: "attachments/session-a/clip.txt", kind: "text" },
        {
          relativePath: "attachments/session-a/page.md",
          kind: "markdown",
          field: "source_path",
        },
      ],
      captureSessions: [{ session_id: "session-a", file_count: 2 }],
      retryAttempts: [
        { attempt: 1, status: "timeout" },
        { attempt: 2, status: "completed" },
      ],
    });

    const manifest = JSON.parse(fs.readFileSync(submissionPath, "utf-8"));
    expect(manifest.contract).toBe("codexremote_v1");
    expect(manifest.attachments).toEqual([
      { path: "attachments/session-a/clip.txt", kind: "text" },
      { source_path: "attachments/session-a/page.md", kind: "markdown" },
    ]);
    expect(manifest.capture_sessions[0].session_id).toBe("session-a");
    expect(manifest.retry_attempts[1].status).toBe("completed");
    expect(manifest.retry_policy.recommended_action).toBe("continue_review");
    expect(fs.existsSync(path.join(itemDir, "attachments", "session-a", "clip.txt"))).toBe(true);
    expect(fs.existsSync(path.join(itemDir, "attachments", "session-a", "page.md"))).toBe(true);
  });

  it("imports a submission bundle directory into one inbox item", async () => {
    const { app } = await createTestApp();
    const token = await loginHelper(app);
    const boundary = "----bundle-boundary";
    const payload = buildMultipartFilesPayload(boundary, {}, [
      {
        fieldName: "file:remote-submission%2Fsubmission.json",
        filename: "remote-submission/submission.json",
        content: Buffer.from(
          JSON.stringify({
            contract: "codexremote_review_bundle_v1",
            submission_id: "remote-bundle-1",
            submitted_at: "2026-04-06T00:00:00.000Z",
            client: { name: "CodexRemote", platform: "android" },
            attachments: [{ path: "attachments/session-a/clip.txt", kind: "text" }],
            capture_text: "# Remote Capture",
            payload: {
              title: "Bundle Import",
              kind: "note",
              source_locator: "codexremote://submission/remote-bundle-1",
            },
            capture_sessions: [{ session_id: "session-a", file_count: 1 }],
            retry_attempts: [{ attempt: 1, status: "completed" }],
            review_bundle: {
              summary: "summary.md",
              skill_handoff: "skill-handoff.json",
              skill_runbook: "skill-runbook.md",
            },
          }),
        ),
        contentType: "application/json",
      },
      {
        fieldName: "file:remote-submission%2Fattachments%2Fsession-a%2Fclip.txt",
        filename: "remote-submission/attachments/session-a/clip.txt",
        content: Buffer.from("clip body"),
        contentType: "text/plain",
      },
      {
        fieldName: "file:remote-submission%2Fsummary.md",
        filename: "remote-submission/summary.md",
        content: Buffer.from("# Summary\n"),
        contentType: "text/markdown",
      },
      {
        fieldName: "file:remote-submission%2Fskill-handoff.json",
        filename: "remote-submission/skill-handoff.json",
        content: Buffer.from("{\"handoff\":true}\n"),
        contentType: "application/json",
      },
      {
        fieldName: "file:remote-submission%2Fskill-runbook.md",
        filename: "remote-submission/skill-runbook.md",
        content: Buffer.from("# Runbook\n"),
        contentType: "text/markdown",
      },
    ]);

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/inbox/submission",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${boundary}`,
      },
      payload,
    });

    expect(res.statusCode).toBe(201);
    const body = JSON.parse(res.body);
    expect(body.submissionId).toBe("remote-bundle-1");
    expect(body.stagingDir).toBe(path.join(STAGING_ROOT, "local", "remote-bundle-1"));
    expect(body.contract).toBe("codexremote_review_bundle_v1");
    expect(body.hasReviewBundle).toBe(true);
    expect(body.hasSkillRunbook).toBe(true);
    const itemDir = path.dirname(body.submissionPath);
    expect(fs.existsSync(path.join(itemDir, "attachments", "session-a", "clip.txt"))).toBe(true);
    expect(fs.existsSync(path.join(itemDir, "skill-handoff.json"))).toBe(true);
    expect(fs.existsSync(path.join(itemDir, "skill-runbook.md"))).toBe(true);
  });

  it("uploads multiple files into one submission item", async () => {
    const { app } = await createTestApp();
    const token = await loginHelper(app);
    const boundary = "----multi-files-boundary";
    const payload = buildMultipartFilesPayload(
      boundary,
      { note: "batch upload", source: "web" },
      [
        {
          fieldName: "file",
          filename: "alpha.txt",
          content: Buffer.from("alpha"),
          contentType: "text/plain",
        },
        {
          fieldName: "file",
          filename: "beta.md",
          content: Buffer.from("# beta\n"),
          contentType: "text/markdown",
        },
      ],
    );

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/inbox/files",
      headers: {
        ...authHeader(token),
        "content-type": `multipart/form-data; boundary=${boundary}`,
      },
      payload,
    });

    expect(res.statusCode).toBe(201);
    const body = JSON.parse(res.body);
    expect(body.kind).toBe("file");
    expect(body.originalName).toBe("2 files");
    expect(body.note).toBe("batch upload");
    expect(body.source).toBe("web");
    expect(body.stagingDir).toContain("/submissions/local/");
    expect(body.submissionPath).toContain("/submission.json");
    const manifest = JSON.parse(fs.readFileSync(body.submissionPath, "utf-8"));
    expect(manifest.contract).toBe("codexremote_v1");
    expect(manifest.payload.title).toBe("2 files");
    expect(manifest.capture_sessions[0].file_count).toBe(2);
    expect(manifest.attachments).toEqual([
      { path: "attachments/alpha.txt", kind: "text" },
      { path: "attachments/beta.md", kind: "markdown" },
    ]);
    const itemDir = path.dirname(body.submissionPath);
    expect(fs.existsSync(path.join(itemDir, "attachments", "alpha.txt"))).toBe(true);
    expect(fs.existsSync(path.join(itemDir, "attachments", "beta.md"))).toBe(true);
  });

  it("surfaces review bundle metadata and preserves runbook files", async () => {
    const { app } = await createTestApp();
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/inbox/link",
      headers: authHeader(token),
      payload: {
        url: "https://example.com/review",
        title: "Review Roundtrip",
        source: "web",
      },
    });

    const body = JSON.parse(res.body);
    const itemDir = path.dirname(body.submissionPath);
    fs.writeFileSync(path.join(itemDir, "summary.md"), "# Summary\n", "utf-8");
    fs.writeFileSync(path.join(itemDir, "candidate.json"), "{\n  \"item_id\": \"candidate\"\n}\n", "utf-8");
    fs.writeFileSync(path.join(itemDir, "agent-bundle.json"), "{\n  \"bundle\": true\n}\n", "utf-8");
    fs.writeFileSync(path.join(itemDir, "bridge-context.json"), "{\n  \"context\": true\n}\n", "utf-8");
    fs.writeFileSync(path.join(itemDir, "skill-handoff.json"), "{\n  \"handoff\": true\n}\n", "utf-8");
    fs.writeFileSync(path.join(itemDir, "skill-runbook.md"), "# Runbook\n", "utf-8");
    fs.mkdirSync(path.join(itemDir, "page-proposals"), { recursive: true });
    fs.writeFileSync(path.join(itemDir, "page-proposals", "proposal.md"), "# Proposal\n", "utf-8");

    await writeSubmissionBundle({
      itemDir,
      contract: "codexremote_review_bundle_v1",
      submissionId: body.id,
      payload: {
        title: "Review Roundtrip",
        kind: "link",
        sourceLocator: "codexremote://submission/review-roundtrip",
        itemId: body.id,
      },
      attachments: [],
      captureSessions: [{ session_id: body.id, file_count: 0 }],
      retryAttempts: [{ attempt: 1, status: "completed" }],
      reviewBundle: {
        summary: "summary.md",
        candidate: "candidate.json",
        agentBundle: "agent-bundle.json",
        bridgeContext: "bridge-context.json",
        skillHandoff: "skill-handoff.json",
        skillRunbook: "skill-runbook.md",
        pageProposals: ["page-proposals/proposal.md"],
      },
    });

    const items = listInboxItems("local");
    expect(items).toHaveLength(1);
    expect(items[0].contract).toBe("codexremote_review_bundle_v1");
    expect(items[0].stagingDir).toBe(itemDir);
    expect(items[0].hasReviewBundle).toBe(true);
    expect(items[0].hasSkillRunbook).toBe(true);
    expect(fs.existsSync(path.join(itemDir, "skill-handoff.json"))).toBe(true);
    expect(fs.existsSync(path.join(itemDir, "skill-runbook.md"))).toBe(true);
  });
});
