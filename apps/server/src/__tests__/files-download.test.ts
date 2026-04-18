import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import {
  createArtifact,
  type Artifact,
} from "../artifacts/store.js";
import {
  MockCodexAdapter,
  authHeader,
  cleanTables,
  createTestApp,
  loginHelper,
} from "./helpers.js";
import { mkdtempSync, mkdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

let app: FastifyInstance;
let adapter: MockCodexAdapter;
let token: string;
let projectRoot: string;
let externalRoot: string;
let smbRoot: string;
let createdArtifacts: Artifact[] = [];

describe("File download routes", () => {
  beforeEach(async () => {
    cleanTables();
    projectRoot = mkdtempSync(path.join(tmpdir(), "codexremote-download-"));
    externalRoot = mkdtempSync(path.join(tmpdir(), "codexremote-external-"));
    smbRoot = mkdtempSync(path.join(tmpdir(), "codexremote-smb-"));
    process.env["CODEXREMOTE_ALLOWED_ABSOLUTE_DOWNLOAD_ROOTS"] = smbRoot;

    mkdirSync(path.join(projectRoot, "src"), { recursive: true });
    writeFileSync(path.join(projectRoot, "README.md"), "hello world\n", "utf8");
    writeFileSync(path.join(externalRoot, "secret.txt"), "top secret\n", "utf8");
    writeFileSync(path.join(smbRoot, "package.apk"), "apk payload\n", "utf8");
    symlinkSync(
      path.join(externalRoot, "secret.txt"),
      path.join(projectRoot, "escape.txt"),
    );
    writeFileSync(path.join(projectRoot, "src", "note.txt"), "nested note\n", "utf8");

    ({ app, adapter } = await createTestApp());
    adapter.addSession("sess-files", { cwd: projectRoot });
    adapter.addSession("sess-other", { cwd: projectRoot });
    token = await loginHelper(app);
    createdArtifacts = [];
  });

  afterEach(async () => {
    if (app) {
      await app.close();
    }
    for (const artifact of createdArtifacts) {
      rmSync(artifact.storedPath, { force: true });
    }
    rmSync(projectRoot, { recursive: true, force: true });
    rmSync(externalRoot, { recursive: true, force: true });
    rmSync(smbRoot, { recursive: true, force: true });
    delete process.env["CODEXREMOTE_ALLOWED_ABSOLUTE_DOWNLOAD_ROOTS"];
  });

  it("streams a cwd file inside the session root", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/files/download?source=cwd&sessionId=sess-files&path=README.md",
      headers: authHeader(token),
    });

    expect(res.statusCode).toBe(200);
    expect(res.headers["content-type"]).toContain("application/octet-stream");
    expect(res.headers["content-disposition"]).toContain("README.md");
    expect(res.headers["x-codex-download-source"]).toBe("cwd");
    expect(res.headers["x-codex-session-id"]).toBe("sess-files");
    expect(res.body).toBe("hello world\n");
  });

  it("blocks cwd path traversal and symlink escape", async () => {
    const traversalRes = await app.inject({
      method: "GET",
      url: "/api/hosts/local/files/download?source=cwd&sessionId=sess-files&path=../secret.txt",
      headers: authHeader(token),
    });

    const symlinkRes = await app.inject({
      method: "GET",
      url: "/api/hosts/local/files/download?source=cwd&sessionId=sess-files&path=escape.txt",
      headers: authHeader(token),
    });

    expect(traversalRes.statusCode).toBe(404);
    expect(symlinkRes.statusCode).toBe(404);
  });

  it("streams a registered artifact only for the owning session", async () => {
    const artifact = await createArtifact({
      hostId: "local",
      sessionId: "sess-files",
      originalName: "artifact-note.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("artifact payload\n", "utf8"),
    });
    createdArtifacts.push(artifact);

    const okRes = await app.inject({
      method: "GET",
      url: `/api/hosts/local/files/download?source=artifact&sessionId=sess-files&artifactId=${artifact.id}`,
      headers: authHeader(token),
    });

    expect(okRes.statusCode).toBe(200);
    expect(okRes.headers["content-type"]).toContain("text/plain");
    expect(okRes.headers["content-disposition"]).toContain("artifact-note.txt");
    expect(okRes.headers["x-codex-download-source"]).toBe("artifact");
    expect(okRes.headers["x-codex-artifact-id"]).toBe(artifact.id);
    expect(okRes.body).toBe("artifact payload\n");

    const wrongSessionRes = await app.inject({
      method: "GET",
      url: `/api/hosts/local/files/download?source=artifact&sessionId=sess-other&artifactId=${artifact.id}`,
      headers: authHeader(token),
    });

    expect(wrongSessionRes.statusCode).toBe(404);
  });

  it("streams an allowed absolute-path download under configured roots", async () => {
    const absolutePath = path.join(smbRoot, "package.apk");
    const okRes = await app.inject({
      method: "GET",
      url: `/api/hosts/local/files/download?source=absolute&sessionId=sess-files&path=${encodeURIComponent(absolutePath)}`,
      headers: authHeader(token),
    });

    expect(okRes.statusCode).toBe(200);
    expect(okRes.headers["x-codex-download-source"]).toBe("absolute");
    expect(okRes.headers["content-disposition"]).toContain("package.apk");
    expect(okRes.body).toBe("apk payload\n");

    const blockedPath = path.join(externalRoot, "secret.txt");
    const blockedRes = await app.inject({
      method: "GET",
      url: `/api/hosts/local/files/download?source=absolute&sessionId=sess-files&path=${encodeURIComponent(blockedPath)}`,
      headers: authHeader(token),
    });

    expect(blockedRes.statusCode).toBe(404);
  });

  it("requires authentication", async () => {
    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/files/download?source=cwd&sessionId=sess-files&path=README.md",
    });

    expect(res.statusCode).toBe(401);
  });
});
