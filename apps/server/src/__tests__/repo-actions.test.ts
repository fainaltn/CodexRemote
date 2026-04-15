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
import { RepoActionResponse } from "@codexremote/shared";

const execFileAsync = promisify(execFile);

describe("repo action route", () => {
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

  it("creates and switches to a new branch", async () => {
    const { repoDir } = await createGitRepo();
    tempDirs.push(repoDir);

    const adapter = new MockCodexAdapter();
    adapter.addSession("repo-session", { cwd: repoDir });

    const created = await createTestApp(adapter);
    app = created.app;
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/repo-session/repo-action",
      headers: authHeader(token),
      payload: { action: "createBranch", branch: "mobile-console" },
    });

    expect(res.statusCode).toBe(200);
    const body = RepoActionResponse.parse(res.json());
    expect(body.summary).toContain("mobile-console");
    expect(body.repoStatus).toMatchObject({
      isRepo: true,
      branch: "mobile-console",
    });
  });

  it("commits all current repo changes", async () => {
    const { repoDir } = await createGitRepo({ dirty: true });
    tempDirs.push(repoDir);

    const adapter = new MockCodexAdapter();
    adapter.addSession("repo-session", { cwd: repoDir });

    const created = await createTestApp(adapter);
    app = created.app;
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/repo-session/repo-action",
      headers: authHeader(token),
      payload: { action: "commit", message: "Save mobile console changes" },
    });

    expect(res.statusCode).toBe(200);
    const body = RepoActionResponse.parse(res.json());
    expect(body.repoStatus).toMatchObject({
      isRepo: true,
      dirtyCount: 0,
      untrackedCount: 0,
    });
  });

  it("pushes the current branch to origin", async () => {
    const { repoDir, remoteDir } = await createGitRepo({ dirty: true, withRemote: true });
    tempDirs.push(repoDir, remoteDir);

    const adapter = new MockCodexAdapter();
    adapter.addSession("repo-session", { cwd: repoDir });

    const created = await createTestApp(adapter);
    app = created.app;
    const token = await loginHelper(app);

    const commitRes = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/repo-session/repo-action",
      headers: authHeader(token),
      payload: { action: "commit", message: "Prepare push" },
    });
    expect(commitRes.statusCode).toBe(200);

    const pushRes = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/repo-session/repo-action",
      headers: authHeader(token),
      payload: { action: "push" },
    });

    expect(pushRes.statusCode).toBe(200);
    const body = RepoActionResponse.parse(pushRes.json());
    expect(body.summary).toContain("推送");

    const branch = await currentBranch(repoDir);
    const remoteHead = await gitOutput(remoteDir, ["rev-parse", branch]);
    expect(remoteHead).toBeTruthy();
  });

  it("returns 409 for non-repo sessions", async () => {
    const plainDir = await mkdtemp(path.join(tmpdir(), "codexremote-repo-actions-"));
    tempDirs.push(plainDir);

    const adapter = new MockCodexAdapter();
    adapter.addSession("plain-session", { cwd: plainDir });

    const created = await createTestApp(adapter);
    app = created.app;
    const token = await loginHelper(app);

    const res = await app.inject({
      method: "POST",
      url: "/api/hosts/local/sessions/plain-session/repo-action",
      headers: authHeader(token),
      payload: { action: "push" },
    });

    expect(res.statusCode).toBe(409);
  });
});

async function createGitRepo(opts: { dirty?: boolean; withRemote?: boolean } = {}) {
  const repoDir = await mkdtemp(path.join(tmpdir(), "codexremote-git-actions-"));
  let remoteDir = "";

  await runGit(repoDir, ["init"]);
  await runGit(repoDir, ["config", "user.name", "Codex Test"]);
  await runGit(repoDir, ["config", "user.email", "codex@example.com"]);

  await writeFile(path.join(repoDir, "tracked.txt"), "base\n", "utf8");
  await runGit(repoDir, ["add", "tracked.txt"]);
  await runGit(repoDir, ["commit", "-m", "initial"]);

  if (opts.withRemote) {
    remoteDir = await mkdtemp(path.join(tmpdir(), "codexremote-remote-"));
    await runGit(remoteDir, ["init", "--bare"]);
    await runGit(repoDir, ["remote", "add", "origin", remoteDir]);
  }

  if (opts.dirty ?? false) {
    await writeFile(path.join(repoDir, "tracked.txt"), "base\nmodified\n", "utf8");
    await writeFile(path.join(repoDir, "untracked.txt"), "untracked\n", "utf8");
  }

  return { repoDir, remoteDir };
}

async function runGit(cwd: string, args: string[]): Promise<void> {
  await execFileAsync("git", args, {
    cwd,
    encoding: "utf8",
    maxBuffer: 1024 * 1024,
  });
}

async function gitOutput(cwd: string, args: string[]): Promise<string> {
  const { stdout } = await execFileAsync("git", args, {
    cwd,
    encoding: "utf8",
    maxBuffer: 1024 * 1024,
  });
  return stdout.trim();
}

async function currentBranch(cwd: string): Promise<string> {
  const branch = await gitOutput(cwd, ["rev-parse", "--abbrev-ref", "HEAD"]);
  return branch;
}
