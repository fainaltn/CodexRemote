import { afterEach, describe, expect, it } from "vitest";
import { mkdtemp, mkdir, writeFile, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { scanSessionDirs, readSessionMeta, readSessionMessages } from "../codex/cli.js";
import { LocalCodexAdapter } from "../codex/local.js";

async function makeSessionFile(base: string, datePath: string, lines: unknown[]) {
  const dir = join(base, ...datePath.split("/"));
  await mkdir(dir, { recursive: true });
  const filePath = join(dir, "rollout-demo.jsonl");
  await writeFile(
    filePath,
    lines.map((line) => JSON.stringify(line)).join("\n") + "\n",
    "utf-8",
  );
  return filePath;
}

describe("codex session discovery", () => {
  const createdDirs: string[] = [];

  afterEach(async () => {
    delete process.env["CODEX_STATE_DIR"];
    await Promise.all(createdDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })));
  });

  it("discovers nested jsonl sessions instead of year directories", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T07:39:13.149Z",
        type: "session_meta",
        payload: {
          id: "019d61ba-25df-7990-b4bf-62b32a9b066e",
          timestamp: "2026-04-06T07:38:02.597Z",
          cwd: "/workspace/ForgeOS",
        },
      },
      {
        timestamp: "2026-04-06T07:39:20.000Z",
        type: "response_item",
        payload: {
          role: "user",
          content: [{ type: "input_text", text: "修一下登录失败提示" }],
        },
      },
      {
        timestamp: "2026-04-06T07:39:40.000Z",
        type: "response_item",
        payload: {
          role: "assistant",
          content: [{ type: "output_text", text: "我先检查 Android 登录错误分支。" }],
        },
      },
    ]);

    const sessions = await scanSessionDirs();
    expect(sessions).toHaveLength(1);
    expect(sessions[0]).toMatchObject({
      id: "019d61ba-25df-7990-b4bf-62b32a9b066e",
      cwd: "/workspace/ForgeOS",
      title: "修一下登录失败提示",
      lastPreview: "我先检查 Android 登录错误分支。",
    });
  });

  it("returns null for unknown session ids and detail for known ids", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/05", [
      {
        timestamp: "2026-04-05T10:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "known-session-id",
          timestamp: "2026-04-05T09:58:00.000Z",
          cwd: "/tmp/project",
        },
      },
      {
        timestamp: "2026-04-05T10:01:00.000Z",
        type: "response_item",
        payload: {
          role: "user",
          content: [{ type: "input_text", text: "检查运行日志" }],
        },
      },
    ]);

    const adapter = new LocalCodexAdapter();
    await expect(adapter.getSessionDetail("missing-session")).resolves.toBeNull();
    await expect(adapter.getSessionDetail("known-session-id")).resolves.toMatchObject({
      codexSessionId: "known-session-id",
      cwd: "/tmp/project",
      title: "检查运行日志",
    });
  });

  it("readSessionMeta resolves nested session files by actual codex id", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/04", [
      {
        timestamp: "2026-04-04T11:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "detail-id",
          timestamp: "2026-04-04T10:55:00.000Z",
          cwd: "/tmp/detail",
        },
      },
      {
        timestamp: "2026-04-04T11:05:00.000Z",
        type: "response_item",
        payload: {
          role: "assistant",
          content: [{ type: "output_text", text: "最后一条预览内容" }],
        },
      },
    ]);

    const meta = await readSessionMeta("detail-id");
    expect(meta).toMatchObject({
      cwd: "/tmp/detail",
      title: "detail",
      lastPreview: "最后一条预览内容",
      lastActivityAt: "2026-04-04T11:05:00.000Z",
    });
  });

  it("deduplicates multiple rollout files for the same codex session id", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/05", [
      {
        timestamp: "2026-04-05T10:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "duplicate-session-id",
          timestamp: "2026-04-05T09:58:00.000Z",
          cwd: "/workspace/FirstProject",
        },
      },
      {
        timestamp: "2026-04-05T10:01:00.000Z",
        type: "response_item",
        payload: {
          role: "assistant",
          content: [{ type: "output_text", text: "第一次回复" }],
        },
      },
    ]);

    const dir = join(base, "2026", "04", "06");
    await mkdir(dir, { recursive: true });
    await writeFile(
      join(dir, "rollout-second.jsonl"),
      [
        {
          timestamp: "2026-04-06T12:00:00.000Z",
          type: "session_meta",
          payload: {
            id: "duplicate-session-id",
            timestamp: "2026-04-06T11:59:00.000Z",
            cwd: "/workspace/SecondProject",
          },
        },
        {
          timestamp: "2026-04-06T12:01:00.000Z",
          type: "response_item",
          payload: {
            role: "user",
            content: [{ type: "input_text", text: "修复重复会话问题" }],
          },
        },
        {
          timestamp: "2026-04-06T12:02:00.000Z",
          type: "response_item",
          payload: {
            role: "assistant",
            content: [{ type: "output_text", text: "最新一次回复预览" }],
          },
        },
      ]
        .map((line) => JSON.stringify(line))
        .join("\n") + "\n",
      "utf-8",
    );

    const sessions = await scanSessionDirs();
    expect(sessions).toHaveLength(1);
    expect(sessions[0]).toMatchObject({
      id: "duplicate-session-id",
      cwd: "/workspace/SecondProject",
      title: "修复重复会话问题",
      lastPreview: "最新一次回复预览",
      lastActivityAt: "2026-04-06T12:02:00.000Z",
    });
  });

  it("keeps a parent session visible when subagent rollouts repeat the same thread id", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "parent-thread-id",
          timestamp: "2026-04-06T12:00:00.000Z",
          cwd: "/workspace/CodexRemote",
          source: "vscode",
        },
      },
      {
        timestamp: "2026-04-06T12:01:00.000Z",
        type: "response_item",
        payload: {
          role: "user",
          content: [{ type: "input_text", text: "主线程消息" }],
        },
      },
      {
        timestamp: "2026-04-06T12:02:00.000Z",
        type: "response_item",
        payload: {
          role: "assistant",
          content: [{ type: "output_text", text: "主线程预览" }],
        },
      },
    ]);

    await makeSessionFile(base, "2026/04/07", [
      {
        timestamp: "2026-04-07T12:03:00.000Z",
        type: "session_meta",
        payload: {
          id: "parent-thread-id",
          timestamp: "2026-04-07T12:03:00.000Z",
          cwd: "/workspace/CodexRemote",
          source: {
            subagent: {
              thread_spawn: {
                parent_thread_id: "parent-thread-id",
                depth: 1,
              },
            },
          },
        },
      },
      {
        timestamp: "2026-04-07T12:04:00.000Z",
        type: "response_item",
        payload: {
          role: "assistant",
          content: [{ type: "output_text", text: "子代理副本预览" }],
        },
      },
    ]);

    const sessions = await scanSessionDirs();
    expect(sessions).toEqual([
      expect.objectContaining({
        id: "parent-thread-id",
        cwd: "/workspace/CodexRemote",
        title: "主线程消息",
        isSubagent: false,
      }),
    ]);
  });

  it("reads visible session message history from codex rollout files", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "history-session-id",
          cwd: "/tmp/history",
        },
      },
      {
        timestamp: "2026-04-06T12:01:00.000Z",
        type: "event_msg",
        payload: {
          type: "user_message",
          message: "先帮我看看历史上下文",
        },
      },
      {
        timestamp: "2026-04-06T12:01:02.000Z",
        type: "response_item",
        payload: {
          type: "message",
          role: "assistant",
          content: [{ type: "output_text", text: "我先把最近消息读出来。" }],
        },
      },
      {
        timestamp: "2026-04-06T12:01:03.000Z",
        type: "response_item",
        payload: {
          type: "message",
          role: "assistant",
          content: [{ type: "output_text", text: "我先把最近消息读出来。" }],
        },
      },
    ]);

    const messages = await readSessionMessages("history-session-id");
    expect(messages).toHaveLength(2);
    expect(messages[0]).toMatchObject({
      role: "user",
      kind: "message",
      text: "先帮我看看历史上下文",
    });
    expect(messages[1]).toMatchObject({
      role: "assistant",
      kind: "message",
      text: "我先把最近消息读出来。",
    });
  });

  it("prefers structured response messages over duplicate event_msg assistant output", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "dedupe-session-id",
          cwd: "/workspace/CodexRemote",
        },
      },
      {
        timestamp: "2026-04-06T12:00:01.000Z",
        type: "event_msg",
        payload: {
          type: "agent_reasoning",
          text: "先看错误，再整理最终结论。",
        },
      },
      {
        timestamp: "2026-04-06T12:00:02.000Z",
        type: "event_msg",
        payload: {
          type: "agent_message",
          message: "最终答案放在这里。",
        },
      },
      {
        timestamp: "2026-04-06T12:00:03.000Z",
        type: "response_item",
        payload: {
          type: "message",
          role: "assistant",
          content: [{ type: "output_text", text: "最终答案放在这里。" }],
        },
      },
    ]);

    const messages = await readSessionMessages("dedupe-session-id");
    expect(messages).toEqual([
      expect.objectContaining({
        role: "assistant",
        kind: "reasoning",
        text: "先看错误，再整理最终结论。",
      }),
      expect.objectContaining({
        role: "assistant",
        kind: "message",
        text: "最终答案放在这里。",
      }),
    ]);
  });

  it("filters the internal empty-session bootstrap prompt from message history", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "bootstrap-session-id",
          cwd: "/workspace/CodexRemote",
        },
      },
      {
        timestamp: "2026-04-06T12:00:01.000Z",
        type: "response_item",
        payload: {
          type: "message",
          role: "user",
          content: [{
            type: "input_text",
            text: "Do not respond. Create the session and exit immediately without any assistant-visible text.",
          }],
        },
      },
      {
        timestamp: "2026-04-06T12:00:02.000Z",
        type: "event_msg",
        payload: {
          type: "user_message",
          message: "Do not respond. Create the session and exit immediately without any assistant-visible text.",
        },
      },
    ]);

    const messages = await readSessionMessages("bootstrap-session-id");
    expect(messages).toEqual([]);
    const meta = await readSessionMeta("bootstrap-session-id");
    expect(meta.title).toBe("未命名会话");
  });

  it("uses session_index thread_name for bootstrap-only sessions", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;
    const sessionIndexPath = join(base, "session_index.jsonl");
    process.env["CODEX_SESSION_INDEX_FILE"] = sessionIndexPath;
    await writeFile(
      sessionIndexPath,
      `${JSON.stringify({
        id: "bootstrap-session-indexed-id",
        thread_name: "CodexRemote",
        updated_at: "2026-04-06T12:00:03.000Z",
      })}\n`,
      "utf-8",
    );

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "bootstrap-session-indexed-id",
          cwd: "/workspace/CodexRemote",
        },
      },
      {
        timestamp: "2026-04-06T12:00:01.000Z",
        type: "response_item",
        payload: {
          type: "message",
          role: "user",
          content: [{
            type: "input_text",
            text: "Do not respond. Create the session and exit immediately without any assistant-visible text.",
          }],
        },
      },
    ]);

    const meta = await readSessionMeta("bootstrap-session-indexed-id");
    expect(meta.title).toBe("CodexRemote");
  });

  it("filters tmp-based sessions from the default visible list", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "tmp-session-id",
          cwd: "/tmp/codexremote-smoke",
        },
      },
    ]);

    await makeSessionFile(base, "2026/04/07", [
      {
        timestamp: "2026-04-07T12:01:00.000Z",
        type: "session_meta",
        payload: {
          id: "real-session-id",
          cwd: "/workspace/CodexRemote",
        },
      },
    ]);

    const sessions = await scanSessionDirs();
    expect(sessions.map((entry) => entry.id)).toEqual(["real-session-id"]);
  });

  it("filters macOS private temp folders and tmp-prefixed project names", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "private-var-temp-session-id",
          cwd: "/private/var/folders/ky/0njrpl9s0db98075s3_99g780000gn/T/tmp.EIOPDOSKzy",
        },
      },
    ]);

    await makeSessionFile(base, "2026/04/07", [
      {
        timestamp: "2026-04-07T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "tmp-prefixed-project-session-id",
          cwd: "/workspace/tmp.ProjectScratch",
        },
      },
    ]);

    await makeSessionFile(base, "2026/04/08", [
      {
        timestamp: "2026-04-08T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "real-project-session-id",
          cwd: "/workspace/CodexRemote",
        },
      },
    ]);

    const sessions = await scanSessionDirs();
    expect(sessions.map((entry) => entry.id)).toEqual(["real-project-session-id"]);
  });

  it("extracts reasoning blocks as foldable history items", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "reasoning-session-id",
          cwd: "/workspace/CodexRemote",
        },
      },
      {
        timestamp: "2026-04-06T12:00:01.000Z",
        type: "event_msg",
        payload: {
          type: "agent_reasoning",
          text: "先定位登录失败，再检查 Android 返回值处理。",
        },
      },
    ]);

    const messages = await readSessionMessages("reasoning-session-id");
    expect(messages).toHaveLength(1);
    expect(messages[0]).toMatchObject({
      role: "assistant",
      kind: "reasoning",
      text: "先定位登录失败，再检查 Android 返回值处理。",
    });
  });

  it("deduplicates assistant replies that differ only by whitespace formatting", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "whitespace-dedupe-session-id",
          cwd: "/workspace/CodexRemote",
        },
      },
      {
        timestamp: "2026-04-06T12:00:02.000Z",
        type: "response_item",
        payload: {
          type: "message",
          role: "assistant",
          content: [{ type: "output_text", text: "这条消息里没有附带图片，所以我现在看不到图片。\n\n如果你上传图片，我可以看。" }],
        },
      },
      {
        timestamp: "2026-04-06T12:00:02.100Z",
        type: "response_item",
        payload: {
          type: "message",
          role: "assistant",
          content: [{ type: "output_text", text: "这条消息里没有附带图片，所以我现在看不到图片。 如果你上传图片，我可以看。" }],
        },
      },
    ]);

    const messages = await readSessionMessages("whitespace-dedupe-session-id");
    expect(messages).toHaveLength(1);
    expect(messages[0]).toMatchObject({
      role: "assistant",
      kind: "message",
    });
  });

  it("filters internal environment and permissions context from message history", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "internal-context-filter-session-id",
          cwd: "/workspace/CodexRemote",
        },
      },
      {
        timestamp: "2026-04-06T12:00:01.000Z",
        type: "event_msg",
        payload: {
          type: "system_message",
          message: "<permissions instructions> Filesystem sandboxing defines which files can be read or written.",
        },
      },
      {
        timestamp: "2026-04-06T12:00:02.000Z",
        type: "event_msg",
        payload: {
          type: "user_message",
          message: "<environment_context> <cwd>/workspace/CodexRemote</cwd>",
        },
      },
      {
        timestamp: "2026-04-06T12:00:03.000Z",
        type: "event_msg",
        payload: {
          type: "user_message",
          message: "真正的用户问题",
        },
      },
    ]);

    const messages = await readSessionMessages("internal-context-filter-session-id");
    expect(messages).toEqual([
      expect.objectContaining({
        role: "user",
        text: "真正的用户问题",
      }),
    ]);
  });

  it("shows only the user request text for attachment-injected prompts", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "attachment-wrapper-session-id",
          cwd: "/workspace/CodexRemote",
        },
      },
      {
        timestamp: "2026-04-06T12:00:01.000Z",
        type: "response_item",
        payload: {
          type: "message",
          role: "user",
          content: [{
            type: "input_text",
            text: "You have access to these uploaded session artifacts on the local filesystem.\nInspect them directly if relevant before answering.\n\n[Attachment 1] foo.png (image/png) at path: data/artifacts/local/foo.png\n\nUser request: 这张图里有什么",
          }],
        },
      },
    ]);

    const messages = await readSessionMessages("attachment-wrapper-session-id");
    expect(messages).toEqual([
      expect.objectContaining({
        role: "user",
        text: "这张图里有什么",
      }),
    ]);
  });

  it("drops truncated attachment wrapper fragments when the user request marker is missing", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "attachment-wrapper-fragment-session-id",
          cwd: "/workspace/CodexRemote",
        },
      },
      {
        timestamp: "2026-04-06T12:00:01.000Z",
        type: "response_item",
        payload: {
          type: "message",
          role: "user",
          content: [{
            type: "input_text",
            text: "You have access to these uploaded session artifacts on the local filesys...",
          }],
        },
      },
    ]);

    const messages = await readSessionMessages("attachment-wrapper-fragment-session-id");
    expect(messages).toEqual([]);

    const meta = await readSessionMeta("attachment-wrapper-fragment-session-id");
    expect(meta.title).toBe("未命名会话");
  });

  it("uses the user request text for attachment-injected session titles", async () => {
    const base = await mkdtemp(join(tmpdir(), "codexremote-sessions-"));
    createdDirs.push(base);
    process.env["CODEX_STATE_DIR"] = base;

    await makeSessionFile(base, "2026/04/06", [
      {
        timestamp: "2026-04-06T12:00:00.000Z",
        type: "session_meta",
        payload: {
          id: "attachment-wrapper-title-session-id",
          cwd: "/workspace/CodexRemote",
        },
      },
      {
        timestamp: "2026-04-06T12:00:01.000Z",
        type: "response_item",
        payload: {
          type: "message",
          role: "user",
          content: [{
            type: "input_text",
            text: "You have access to these uploaded session artifacts on the local filesystem.\nInspect them directly if relevant before answering.\n\n[Attachment 1] foo.png (image/png) at path: data/artifacts/local/foo.png\n\nUser request: 这张图里有什么",
          }],
        },
      },
    ]);

    const meta = await readSessionMeta("attachment-wrapper-title-session-id");
    expect(meta.title).toBe("这张图里有什么");
  });
});
