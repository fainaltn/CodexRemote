import { execFile } from "node:child_process";
import { mkdtemp, realpath, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { tmpdir } from "node:os";
import { promisify } from "node:util";
import { afterEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import {
  MockCodexAdapter,
  authHeader,
  cleanTables,
  createTestApp,
  loginHelper,
} from "./helpers.js";
import { RepoStatusResponse } from "@codexremote/shared";

const execFileAsync = promisify(execFile);

describe("repo status route", () => {
  let app: FastifyInstance | null = null;
  const tempDirs: string[] = [];

  afterEach(async () => {
    cleanTables();
    await Promise.all(
      tempDirs.map((dir) => rm(dir, { recursive: true, force: true })),
    );
    tempDirs.length = 0;
    await app?.close();
    app = null;
  });

  it("returns repo metadata for a git session cwd", async () => {
    const repoDir = await createGitRepo({ dirty: true });
    const repoRoot = await realpath(repoDir);
    tempDirs.push(repoDir);

    const adapter = new MockCodexAdapter();
    adapter.addSession("repo-session", { cwd: repoDir });

    const created = await createTestApp(adapter);
    app = created.app;
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/repo-session/repo-status",
      headers: authHeader(token),
    });

    expect(res.statusCode).toBe(200);

    const body = RepoStatusResponse.parse(res.json());
    expect(body.repoStatus).toMatchObject({
      isRepo: true,
      cwd: repoDir,
      rootPath: repoRoot,
      detached: false,
      dirtyCount: 1,
      stagedCount: 0,
      unstagedCount: 1,
      untrackedCount: 1,
    });
    if (body.repoStatus.isRepo) {
      expect(body.repoStatus.branch).toBeTruthy();
      expect(body.repoStatus.aheadBy).toBeNull();
      expect(body.repoStatus.behindBy).toBeNull();
    }
  });

  it("marks detached HEAD sessions as detached", async () => {
    const repoDir = await createGitRepo({ detached: true });
    const repoRoot = await realpath(repoDir);
    tempDirs.push(repoDir);

    const adapter = new MockCodexAdapter();
    adapter.addSession("detached-session", { cwd: repoDir });

    const created = await createTestApp(adapter);
    app = created.app;
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/detached-session/repo-status",
      headers: authHeader(token),
    });

    expect(res.statusCode).toBe(200);

    const body = RepoStatusResponse.parse(res.json());
    expect(body.repoStatus).toMatchObject({
      isRepo: true,
      cwd: repoDir,
      rootPath: repoRoot,
      detached: true,
      branch: null,
      dirtyCount: 0,
      stagedCount: 0,
      unstagedCount: 0,
      untrackedCount: 0,
    });
  });

  it("returns a safe non-repo response for a plain directory", async () => {
    const plainDir = await mkdtemp(path.join(tmpdir(), "codexremote-repo-status-"));
    tempDirs.push(plainDir);

    const adapter = new MockCodexAdapter();
    adapter.addSession("plain-session", { cwd: plainDir });

    const created = await createTestApp(adapter);
    app = created.app;
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/plain-session/repo-status",
      headers: authHeader(token),
    });

    expect(res.statusCode).toBe(200);

    const body = RepoStatusResponse.parse(res.json());
    expect(body.repoStatus).toEqual({
      isRepo: false,
      cwd: plainDir,
      rootPath: null,
      branch: null,
      detached: false,
      aheadBy: null,
      behindBy: null,
      dirtyCount: 0,
      stagedCount: 0,
      unstagedCount: 0,
      untrackedCount: 0,
    });
  });

  it("returns 404 when the session is unknown", async () => {
    const adapter = new MockCodexAdapter();
    const created = await createTestApp(adapter);
    app = created.app;
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "GET",
      url: "/api/hosts/local/sessions/missing-session/repo-status",
      headers: authHeader(token),
    });

    expect(res.statusCode).toBe(404);
  });
});

async function createGitRepo(opts: { detached?: boolean; dirty?: boolean }): Promise<string> {
  const repoDir = await mkdtemp(path.join(tmpdir(), "codexremote-git-repo-"));
  await runGit(repoDir, ["init"]);
  await runGit(repoDir, ["config", "user.name", "Codex Test"]);
  await runGit(repoDir, ["config", "user.email", "codex@example.com"]);

  await writeFile(path.join(repoDir, "tracked.txt"), "base\n", "utf8");
  await runGit(repoDir, ["add", "tracked.txt"]);
  await runGit(repoDir, ["commit", "-m", "initial"]);

  if (opts.detached) {
    await runGit(repoDir, ["checkout", "--detach", "HEAD"]);
  }

  if (opts.dirty ?? false) {
    await writeFile(path.join(repoDir, "tracked.txt"), "base\nmodified\n", "utf8");
    await writeFile(path.join(repoDir, "untracked.txt"), "untracked\n", "utf8");
  }

  return repoDir;
}

async function runGit(cwd: string, args: string[]): Promise<void> {
  await execFileAsync("git", args, {
    cwd,
    encoding: "utf8",
    maxBuffer: 1024 * 1024,
  });
}
