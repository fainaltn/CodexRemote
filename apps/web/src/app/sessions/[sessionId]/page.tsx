"use client";

import {
  useState,
  useEffect,
  useRef,
  useCallback,
  type FormEvent,
} from "react";
import { useRouter, useParams } from "next/navigation";
import { useAuth } from "@/lib/auth";
import {
  archiveSessions,
  getSessionDetail,
  startLiveRun,
  stopLiveRun,
  uploadArtifact,
  type Session,
  type SessionMessage,
  type Artifact,
  type UploadProgress,
} from "@/lib/api";
import { useLiveRun } from "@/lib/use-sse";
import { useSearchParams } from "next/navigation";

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString("zh-CN", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function cleanLiveOutput(output: string | null): string | null {
  if (!output) return null;
  const filtered = output
    .split("\n")
    .map((line) => line.trimEnd())
    .filter((line) => {
      const trimmed = line.trim();
      if (!trimmed) return false;
      if (trimmed.startsWith("2026-") && trimmed.includes(" WARN ")) return false;
      if (trimmed.startsWith("Reading prompt from stdin")) return false;
      if (trimmed.startsWith("OpenAI Codex")) return false;
      if (trimmed === "--------") return false;
      if (trimmed.startsWith("workdir: ")) return false;
      if (trimmed.startsWith("model: ")) return false;
      if (trimmed.startsWith("provider: ")) return false;
      if (trimmed.startsWith("approval: ")) return false;
      if (trimmed.startsWith("sandbox: ")) return false;
      if (trimmed.startsWith("reasoning effort: ")) return false;
      if (trimmed.startsWith("reasoning summaries: ")) return false;
      if (trimmed.startsWith("session id: ")) return false;
      if (trimmed === "mcp: figma starting") return false;
      if (trimmed === "mcp: playwright starting") return false;
      if (trimmed === "mcp: figma ready") return false;
      if (trimmed === "mcp: playwright ready") return false;
      if (trimmed.startsWith("mcp startup: ")) return false;
      if (trimmed === "user") return false;
      if (trimmed === "codex") return false;
      if (trimmed === "tokens used") return false;
      if (/^\d[\d,]*$/.test(trimmed)) return false;
      return true;
    });

  if (filtered.length === 0) return null;
  const deduped: string[] = [];
  for (const line of filtered) {
    const normalized = line.replace(/\s+/g, " ").trim();
    const prev = deduped.at(-1);
    if (prev && prev.replace(/\s+/g, " ").trim() === normalized) {
      continue;
    }
    deduped.push(line);
  }
  return deduped.join("\n").trim() || null;
}

function sanitizePromptDisplay(prompt: string): string {
  const trimmed = prompt.trim();
  if (!trimmed) {
    return trimmed;
  }

  const lines = trimmed.split("\n");
  if (
    lines[0]?.startsWith(
      "You have access to these uploaded session artifacts",
    )
  ) {
    const marker = "User request:";
    const markerIndex = lines.findIndex((line) => line.startsWith(marker));
    if (markerIndex !== -1) {
      return lines
        .slice(markerIndex)
        .join("\n")
        .replace(/^User request:\s*/, "")
        .trim();
    }

    return lines
      .filter((line) => {
        const text = line.trim();
        if (!text) return false;
        if (text.startsWith("You have access to these uploaded session artifacts")) {
          return false;
        }
        if (text.startsWith("Inspect them directly if relevant before answering.")) {
          return false;
        }
        if (text.startsWith("[Attachment ")) {
          return false;
        }
        return true;
      })
      .join("\n")
      .trim();
  }
  return trimmed;
}

function statusLabel(status: string): string {
  switch (status) {
    case "pending":
      return "等待中";
    case "running":
      return "运行中";
    case "completed":
      return "已完成";
    case "failed":
      return "失败";
    case "stopped":
      return "已停止";
    default:
      return status;
  }
}

interface HistoryGroup {
  id: string;
  messages: SessionMessage[];
  primaryMessages: SessionMessage[];
  foldedMessages: SessionMessage[];
  preview: string;
  title: string;
  folded: boolean;
  isHistorical: boolean;
}

function summarizeGroupTitle(text: string | undefined): string {
  const raw = (text ?? "").replace(/\s+/g, " ").trim();
  if (!raw) return "历史对话";
  return raw.length > 20 ? `${raw.slice(0, 20)}…` : raw;
}

function summarizeHistoryTitle(group: SessionMessage[]): string {
  const firstUser = group.find((message) => message.role === "user");
  const lastAssistant = [...group]
    .reverse()
    .find((message) => message.role === "assistant" && message.kind === "message");
  return summarizeGroupTitle(firstUser?.text ?? lastAssistant?.text);
}

function messageRoleLabel(message: SessionMessage): string {
  if (message.kind === "reasoning") {
    return "Codex 过程";
  }

  switch (message.role) {
    case "user":
      return "你";
    case "assistant":
      return "Codex";
    default:
      return "系统";
  }
}

function messageClassName(message: SessionMessage): string {
  const reasoningClass =
    message.kind === "reasoning" ? " history-message-reasoning" : "";
  return `history-message history-message-${message.role}${reasoningClass}`;
}

function buildHistoryGroups(messages: SessionMessage[]): HistoryGroup[] {
  const lastUserIndex = [...messages]
    .map((message, index) => ({ message, index }))
    .filter(({ message }) => message.role === "user")
    .at(-1)?.index ?? -1;
  const groups: HistoryGroup[] = [];
  let current: SessionMessage[] = [];
  let currentKind: "round" | "reasoning" = "round";

  const flush = () => {
    if (current.length === 0) return;
    const firstUser = current.find((message) => message.role === "user");
    const assistantMessages = current.filter(
      (message) => message.role === "assistant" && message.kind === "message",
    );
    const lastAssistant = [...assistantMessages]
      .reverse()
      .find(Boolean);
    const previewSource = lastAssistant?.text ?? firstUser?.text ?? current[0]?.text ?? "";
    const firstIndex = messages.findIndex((entry) => entry.id === current[0]!.id);
    const isHistorical = firstIndex < lastUserIndex;
    const isLatestVisibleReply =
      currentKind !== "reasoning" && !isHistorical;
    let primaryMessages = current;
    let foldedMessages: SessionMessage[] = [];

    if (currentKind !== "reasoning" && assistantMessages.length > 1) {
      const finalAssistant = assistantMessages.at(-1)!;
      primaryMessages = current.filter((message) => {
        if (message.role !== "assistant" || message.kind !== "message") {
          return true;
        }
        return message.id === finalAssistant.id;
      });
      foldedMessages = current.filter((message) => {
        if (message.role !== "assistant" || message.kind !== "message") {
          return false;
        }
        return message.id !== finalAssistant.id;
      });
    }

    groups.push({
      id: current[0]!.id,
      messages: current,
      primaryMessages,
      foldedMessages,
      title:
        currentKind === "reasoning"
          ? "Codex 思考"
          : isLatestVisibleReply
            ? "当前对话"
            : firstUser
            ? summarizeHistoryTitle(current)
            : "系统上下文",
      folded: currentKind === "reasoning" || isHistorical,
      isHistorical,
      preview: previewSource.length > 72
        ? `${previewSource.slice(0, 72)}…`
        : previewSource,
    });
    current = [];
  };

  for (const message of messages) {
    const nextKind = message.kind === "reasoning" ? "reasoning" : "round";
    if (
      current.length > 0 &&
      (message.role === "user" || nextKind !== currentKind)
    ) {
      flush();
    }
    currentKind = nextKind;
    current.push(message);
  }
  flush();

  return groups;
}

export default function SessionDetailPage() {
  const { isAuthenticated, isLoading: authLoading } = useAuth();
  const router = useRouter();
  const params = useParams();
  const searchParams = useSearchParams();
  const sessionId = params.sessionId as string;
  const embedded = searchParams.get("embed") === "1";
  const prefill = searchParams.get("prefill") ?? "";

  const [session, setSession] = useState<Session | null>(null);
  const [messages, setMessages] = useState<SessionMessage[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [prompt, setPrompt] = useState("");
  const [sending, setSending] = useState(false);
  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>(
    {},
  );
  const outputRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Upload state
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<UploadProgress | null>(
    null,
  );
  const [uploadResult, setUploadResult] = useState<{
    status: "success" | "error";
    message: string;
  } | null>(null);
  const [pendingArtifacts, setPendingArtifacts] = useState<Artifact[]>([]);
  const [archiving, setArchiving] = useState(false);
  const [editingMessageId, setEditingMessageId] = useState<string | null>(
    null,
  );
  const [editingText, setEditingText] = useState("");
  const lastRefreshedRunIdRef = useRef<string | null>(null);
  const previousRunStatusRef = useRef<string | null>(null);
  const prefillAppliedRef = useRef(false);

  const loadSession = useCallback(async () => {
    const data = await getSessionDetail(sessionId);
    setSession(data.session);
    setMessages(data.messages);
  }, [sessionId]);

  // Only subscribe to SSE after the session has been validated by getSession().
  // This prevents endless reconnect loops for unknown session IDs.
  const liveRun = useLiveRun(session ? sessionId : null);
  const isRunning =
    liveRun?.status === "running" || liveRun?.status === "pending";
  const historyGroups = buildHistoryGroups(messages);
  const liveOutput = cleanLiveOutput(liveRun?.lastOutput ?? null);
  const latestPrompt = liveRun?.prompt.trim() ?? "";
  const reusablePrompt = sanitizePromptDisplay(liveRun?.prompt ?? "");

  useEffect(() => {
    if (prefillAppliedRef.current) return;
    if (!prefill.trim()) return;
    setPrompt(prefill);
    prefillAppliedRef.current = true;
  }, [prefill]);

  // Fetch session metadata.
  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }

    let cancelled = false;
    (async () => {
      try {
        setLoading(true);
        const data = await getSessionDetail(sessionId);
        if (!cancelled) {
          setSession(data.session);
          setMessages(data.messages);
        }
      } catch (err) {
        if (!cancelled)
          setError(
            err instanceof Error ? err.message : "加载会话失败",
          );
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [sessionId, isAuthenticated, authLoading, router]);

  useEffect(() => {
    if (!liveRun) {
      previousRunStatusRef.current = null;
      return;
    }

    const prevStatus = previousRunStatusRef.current;
    previousRunStatusRef.current = liveRun.status;
    const isTerminal =
      liveRun.status === "completed" ||
      liveRun.status === "failed" ||
      liveRun.status === "stopped";
    const justSettled =
      isTerminal &&
      prevStatus !== liveRun.status &&
      prevStatus !== null;

    if (!justSettled || lastRefreshedRunIdRef.current === liveRun.id) {
      return;
    }

    lastRefreshedRunIdRef.current = liveRun.id;
    void loadSession().catch(() => {
      // Keep the last visible state if the refresh fails transiently.
    });
  }, [liveRun, loadSession]);

  // Auto-scroll output when new content arrives.
  useEffect(() => {
    if (outputRef.current && liveRun?.lastOutput) {
      outputRef.current.scrollTop = outputRef.current.scrollHeight;
    }
  }, [liveRun?.lastOutput]);

  // Clear upload result after a timeout.
  useEffect(() => {
    if (!uploadResult) return;
    const timer = setTimeout(() => setUploadResult(null), 5000);
    return () => clearTimeout(timer);
  }, [uploadResult]);

  const handleUploadClick = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  const handleFileChange = useCallback(
    async (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (!file) return;

      // Reset the input so the same file can be re-selected.
      e.target.value = "";

      setUploading(true);
      setUploadProgress(null);
      setUploadResult(null);

      try {
        const artifact = await uploadArtifact(sessionId, file, (progress) => {
          setUploadProgress(progress);
        });
        setPendingArtifacts((prev) => [...prev, artifact]);
        setUploadResult({
          status: "success",
          message: `已上传 ${artifact.originalName}（${formatBytes(artifact.sizeBytes)}），下一条消息会自动附带`,
        });
      } catch (err) {
        setUploadResult({
          status: "error",
          message: err instanceof Error ? err.message : "上传失败",
        });
      } finally {
        setUploading(false);
        setUploadProgress(null);
      }
    },
    [sessionId],
  );

  async function handleSendPrompt(e: FormEvent) {
    e.preventDefault();
    if (!prompt.trim() || sending || isRunning) return;

    setSending(true);
    setError("");
    try {
      const nextPrompt =
        pendingArtifacts.length > 0
          ? [
              "You have access to these uploaded session artifacts on the local filesystem.",
              "Inspect them directly if relevant before answering.",
              "",
              ...pendingArtifacts.map((artifact, index) =>
                `[Attachment ${index + 1}] ${artifact.originalName} (${artifact.mimeType}) at path: ${artifact.storedPath}`,
              ),
              "",
              `User request: ${prompt.trim()}`,
            ].join("\n")
          : prompt.trim();

      await startLiveRun(sessionId, nextPrompt);
      setPrompt("");
      setPendingArtifacts([]);
      await loadSession();
    } catch (err) {
      setError(err instanceof Error ? err.message : "启动运行失败");
    } finally {
      setSending(false);
    }
  }

  async function handleArchiveSession() {
    if (!session || session.archivedAt || archiving) return;

    try {
      setArchiving(true);
      setError("");
      await archiveSessions([sessionId]);
      router.replace("/sessions");
    } catch (err) {
      setError(err instanceof Error ? err.message : "归档会话失败");
    } finally {
      setArchiving(false);
    }
  }

  async function handleRetryLatestPrompt() {
    if (!latestPrompt || sending || isRunning) return;

    try {
      setSending(true);
      setError("");
      await startLiveRun(sessionId, liveRun?.prompt ?? latestPrompt);
      await loadSession();
    } catch (err) {
      setError(err instanceof Error ? err.message : "重试运行失败");
    } finally {
      setSending(false);
    }
  }

  function handleReuseLatestPrompt() {
    if (!reusablePrompt.trim()) return;

    setError("");
    setPrompt(reusablePrompt);
  }

  function handleStartEditingMessage(message: SessionMessage) {
    setEditingMessageId(message.id);
    setEditingText(message.text);
    setError("");
  }

  function handleCancelEditingMessage() {
    setEditingMessageId(null);
    setEditingText("");
  }

  async function handleSubmitEditedPrompt(promptText: string) {
    if (sending || isRunning) return;

    const nextPrompt = promptText.trim();
    if (!nextPrompt) return;

    try {
      setSending(true);
      setError("");
      await startLiveRun(sessionId, nextPrompt);
      handleCancelEditingMessage();
      await loadSession();
    } catch (err) {
      setError(err instanceof Error ? err.message : "启动运行失败");
    } finally {
      setSending(false);
    }
  }

  async function handleStop() {
    try {
      await stopLiveRun(sessionId);
    } catch {
      // Best-effort stop — errors are non-critical.
    }
  }

  function toggleHistoryGroup(groupId: string) {
    setExpandedGroups((prev) => ({
      ...prev,
      [groupId]: !prev[groupId],
    }));
  }

  // ── Loading / error guards ──────────────────────────────────────

  if (authLoading || loading) {
    return (
      <div className="loading-screen">
        <div className="spinner" />
      </div>
    );
  }

  if (error && !session) {
    return (
      <div className="screen">
        <div className="header">
          <div className="header-left">
            <button
              className="back-btn"
              onClick={() => router.push("/sessions")}
            >
              ←
            </button>
            <h1>错误</h1>
          </div>
        </div>
        <div className="content">
          <div className="empty-state">
            <div className="empty-state-icon">⚠</div>
            <div className="empty-state-text">{error}</div>
          </div>
        </div>
      </div>
    );
  }

  // ── Main render ─────────────────────────────────────────────────

  return (
    <div className="detail-screen detail-shell">
      {!embedded && (
        <header className="header detail-header" role="banner">
          <div className="header-left detail-header-left">
            <button
              className="back-btn"
              onClick={() => router.push("/sessions")}
            >
              ←
            </button>
            <h1
              style={{
                maxWidth: "calc(100vw - 120px)",
                whiteSpace: "nowrap",
                overflow: "hidden",
                textOverflow: "ellipsis",
              }}
            >
              {session?.title || sessionId}
              </h1>
            </div>
          <div
            className="detail-header-actions"
            style={{ display: "flex", alignItems: "center", gap: 6 }}
          >
            {session && !session.archivedAt && (
              <button
                className="icon-btn"
                type="button"
                title="归档会话"
                onClick={() => void handleArchiveSession()}
                disabled={archiving}
              >
                {archiving ? "…" : "⌫"}
              </button>
            )}
            {isRunning && <div className="pulse-dot" />}
          </div>
        </header>
      )}

      {/* Scrollable body */}
      <div
        className={`detail-content ${embedded ? "detail-content-embedded" : ""}`}
      >
        <div className="detail-workspace detail-workspace-single">
          {embedded && session && (
            <div className="session-shell-header">
              <div>
                <div className="session-shell-title">{session.title || sessionId}</div>
                <div className="session-shell-subtitle">
                  {session.cwd || "Codex 会话"}
                </div>
              </div>
              <div
                className="session-shell-actions"
                style={{ display: "flex", alignItems: "center", gap: 6 }}
              >
                {session && !session.archivedAt && (
                  <button
                    className="icon-btn"
                    type="button"
                    title="归档会话"
                    onClick={() => void handleArchiveSession()}
                    disabled={archiving}
                  >
                    {archiving ? "…" : "⌫"}
                  </button>
                )}
                {isRunning && <div className="pulse-dot" />}
              </div>
            </div>
          )}

          <main className="detail-main detail-main-single" aria-label="会话详情">
            <section className="detail-conversation-shell">
              <div className="history-section">
                <div className="history-header">
                  <span>历史上下文</span>
                  <span className="history-count">
                    {historyGroups.length} 轮 / {messages.length} 条
                  </span>
                </div>
                {historyGroups.length > 0 ? (
                  <div className="history-list">
                    {historyGroups.map((group) => {
                      const expanded = !group.folded || expandedGroups[group.id];
                      if (!expanded) {
                        return (
                          <button
                            key={group.id}
                            type="button"
                            className={`history-folded-card ${
                              group.isHistorical
                                ? "history-folded-card-historical"
                                : "history-folded-card-current"
                            }`}
                            onClick={() => toggleHistoryGroup(group.id)}
                          >
                            <div className="history-folded-top">
                              <span>{group.title}</span>
                              <span>{group.messages.length} 条</span>
                            </div>
                            <div className="history-folded-preview">{group.preview}</div>
                          </button>
                        );
                      }

                      return (
                        <div
                          key={group.id}
                          className={`history-group-expanded ${
                            group.isHistorical
                              ? "history-group-expanded-historical"
                              : "history-group-expanded-current"
                          }`}
                        >
                          {group.folded && (
                            <button
                              type="button"
                              className="history-collapse-btn"
                              onClick={() => toggleHistoryGroup(group.id)}
                            >
                              收起这轮历史
                            </button>
                          )}
                          {group.primaryMessages.map((message, index) => {
                            const isLastPrimary =
                              index === group.primaryMessages.length - 1;
                            const shouldInsertProcessCard =
                              group.foldedMessages.length > 0 &&
                              isLastPrimary &&
                              message.role === "assistant";
                            const isHistoricalUserMessage =
                              group.isHistorical &&
                              message.role === "user" &&
                              message.kind === "message";
                            const isEditingThisMessage =
                              editingMessageId === message.id;

                            return (
                              <div key={message.id}>
                                {shouldInsertProcessCard && (
                                  <>
                                    <button
                                      type="button"
                                      className="history-folded-card history-folded-card-current"
                                      onClick={() =>
                                        toggleHistoryGroup(`${group.id}-process`)
                                      }
                                      style={{ marginBottom: 12 }}
                                    >
                                      <div className="history-folded-top">
                                        <span>Codex 过程</span>
                                        <span>{group.foldedMessages.length} 条</span>
                                      </div>
                                      <div className="history-folded-preview">
                                        {expandedGroups[`${group.id}-process`]
                                          ? "点击收起过程"
                                          : summarizeGroupTitle(
                                              group.foldedMessages[0]?.text,
                                            )}
                                      </div>
                                    </button>
                                    {expandedGroups[`${group.id}-process`] &&
                                      group.foldedMessages.map((foldedMessage) => (
                                        <div
                                          key={foldedMessage.id}
                                          className="history-message history-message-process"
                                          style={{ marginBottom: 10 }}
                                        >
                                          <div className="history-message-top">
                                            <span className="history-role">
                                              Codex 过程
                                            </span>
                                            <span className="history-time">
                                              {formatDate(foldedMessage.createdAt)}
                                            </span>
                                          </div>
                                          <div className="history-text">
                                            {foldedMessage.text}
                                          </div>
                                        </div>
                                      ))}
                                  </>
                                )}
                                <div
                                  className={`${messageClassName(message)} ${
                                    isEditingThisMessage
                                      ? " history-message-editing"
                                      : ""
                                  }`}
                                >
                                  <div className="history-message-top">
                                    <span className="history-role">
                                      {messageRoleLabel(message)}
                                    </span>
                                    <span className="history-time">
                                      {formatDate(message.createdAt)}
                                    </span>
                                  </div>
                                  {isEditingThisMessage ? (
                                    <>
                                      <textarea
                                        className="history-edit-textarea"
                                        value={editingText}
                                        onChange={(event) =>
                                          setEditingText(event.target.value)
                                        }
                                        rows={4}
                                        autoFocus
                                        disabled={sending || isRunning}
                                      />
                                      <div className="history-message-actions">
                                        <button
                                          type="button"
                                          className="history-message-secondary-btn"
                                          onClick={handleCancelEditingMessage}
                                          disabled={sending || isRunning}
                                        >
                                          取消
                                        </button>
                                        <button
                                          type="button"
                                          className="history-message-primary-btn"
                                          onClick={() =>
                                            void handleSubmitEditedPrompt(
                                              editingText,
                                            )
                                          }
                                          disabled={
                                            sending ||
                                            isRunning ||
                                            !editingText.trim()
                                          }
                                        >
                                          {sending ? "发送中…" : "重新发送"}
                                        </button>
                                      </div>
                                    </>
                                  ) : (
                                    <>
                                      <div className="history-text">{message.text}</div>
                                      {isHistoricalUserMessage && (
                                        <div className="history-message-actions">
                                          <button
                                            type="button"
                                            className="history-message-secondary-btn"
                                            onClick={() =>
                                              handleStartEditingMessage(message)
                                            }
                                            disabled={sending || isRunning}
                                          >
                                            编辑并重发
                                          </button>
                                        </div>
                                      )}
                                    </>
                                  )}
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <div className="history-empty">还没有可展示的历史消息</div>
                )}
              </div>

              {liveRun ? (
                <div className="run-section">
                  <div className="run-header">
                    <span className={`status-badge status-${liveRun.status}`}>
                      {isRunning && "● "}
                      {statusLabel(liveRun.status)}
                    </span>
                    {liveRun.model && (
                      <span className="model-badge">{liveRun.model}</span>
                    )}
                  </div>

                  <div className="history-group-expanded run-panel">
                    <div className="history-message history-message-user">
                      <div className="history-message-top">
                        <span className="history-role">你</span>
                        <span className="history-time">
                          {formatDate(liveRun.startedAt)}
                        </span>
                      </div>
                      <div className="history-text">
                        {sanitizePromptDisplay(liveRun.prompt)}
                      </div>
                    </div>
                    <div
                      className={`history-message history-message-assistant ${
                        !isRunning ? "history-message-terminal" : ""
                      }`}
                    >
                      <div className="history-message-top">
                        <span className="history-role">Codex</span>
                        <span className="history-time">
                          {isRunning ? "流式回复中" : statusLabel(liveRun.status)}
                        </span>
                      </div>
                      <div className="history-text run-output" ref={outputRef}>
                        {liveOutput ??
                          (isRunning
                            ? "Codex 正在思考…"
                            : "这次运行没有可显示的文本输出")}
                      </div>
                    </div>
                  </div>

                  {!isRunning && latestPrompt && (
                    <div className="run-actions">
                      <button
                        type="button"
                        className="history-message-secondary-btn"
                        onClick={handleReuseLatestPrompt}
                        disabled={sending}
                      >
                        回填提示词
                      </button>
                      <button
                        type="button"
                        className="history-message-primary-btn"
                        onClick={() => void handleRetryLatestPrompt()}
                        disabled={sending}
                      >
                        {sending ? "发送中…" : "重试本轮"}
                      </button>
                    </div>
                  )}

                  {liveRun.error && <div className="run-error">{liveRun.error}</div>}
                </div>
              ) : (
                <div className="empty-state">
                  <div className="empty-state-icon">⚡</div>
                  <div className="empty-state-text">当前没有运行中的任务</div>
                  <div className="empty-state-sub">
                    发送一条提示词来继续处理这个 Codex 会话
                  </div>
                </div>
              )}
            </section>

            {pendingArtifacts.length > 0 && (
              <section className="detail-attachments panel">
                <div className="panel-header">
                  <h2>待发送附件</h2>
                  <span className="history-count">{pendingArtifacts.length} 个</span>
                </div>
                <div className="session-list">
                  {pendingArtifacts.map((artifact) => (
                    <div key={artifact.id} className="session-card">
                      <div className="session-card-header">
                        <span className="session-card-title">
                          {artifact.originalName}
                        </span>
                        <button
                          type="button"
                          className="icon-btn"
                          title="移除附件"
                          onClick={() =>
                            setPendingArtifacts((prev) =>
                              prev.filter((entry) => entry.id !== artifact.id),
                            )
                          }
                        >
                          ×
                        </button>
                      </div>
                      <div className="session-card-preview">
                        {artifact.mimeType} · {formatBytes(artifact.sizeBytes)}
                      </div>
                    </div>
                  ))}
                </div>
              </section>
            )}

            {error && session && (
              <div className="error-message detail-inline-error">{error}</div>
            )}

            {uploading && uploadProgress && (
              <div className="upload-status upload-status-progress">
                <span>上传中…</span>
                <div className="upload-progress-bar">
                  <div
                    className="upload-progress-fill"
                    style={{
                      width: `${Math.round(
                        (uploadProgress.loaded / uploadProgress.total) * 100,
                      )}%`,
                    }}
                  />
                </div>
                <span>
                  {Math.round((uploadProgress.loaded / uploadProgress.total) * 100)}
                  %
                </span>
              </div>
            )}
            {uploading && !uploadProgress && (
              <div className="upload-status upload-status-progress">
                <div className="spinner" style={{ width: 16, height: 16 }} />
                <span>上传中…</span>
              </div>
            )}
            {uploadResult && (
              <div className={`upload-status upload-status-${uploadResult.status}`}>
                <span>{uploadResult.status === "success" ? "✓" : "✗"}</span>
                <span>{uploadResult.message}</span>
              </div>
            )}
          </main>
        </div>
      </div>

      {/* Hidden file input for uploads */}
      <input
        ref={fileInputRef}
        type="file"
        accept="image/*,*/*"
        style={{ display: "none" }}
        onChange={handleFileChange}
      />

      {/* Sticky prompt bar */}
      <form className="prompt-bar" onSubmit={handleSendPrompt}>
        <button
          type="button"
          className="upload-btn"
          onClick={handleUploadClick}
          disabled={uploading}
          title="上传文件或图片"
        >
          📎
        </button>
        <input
          className="prompt-input"
          type="text"
          placeholder={isRunning ? "任务进行中…" : "继续处理这个会话…"}
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          disabled={isRunning || sending}
        />
        {isRunning ? (
          <button
            type="button"
            className="stop-btn"
            onClick={handleStop}
            title="停止运行"
          >
            ■
          </button>
        ) : (
          <button
            type="submit"
            className="send-btn"
            disabled={!prompt.trim() || sending}
            title="继续处理"
          >
            ▶
          </button>
        )}
      </form>
    </div>
  );
}
