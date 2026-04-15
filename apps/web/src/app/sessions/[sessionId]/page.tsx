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
import { useLiveRun, type LiveRunTransportState } from "@/lib/use-sse";
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

function summarizeInlineText(text: string, maxLength = 96): string {
  const normalized = text.replace(/\s+/g, " ").trim();
  if (!normalized) {
    return "";
  }
  return normalized.length > maxLength
    ? `${normalized.slice(0, maxLength)}…`
    : normalized;
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

function runStageLabel(status: string, isRunning: boolean, hasOutput: boolean): string {
  if (status === "pending") {
    return "准备连接";
  }
  if (status === "running") {
    return hasOutput ? "流式输出" : "思考中";
  }
  if (status === "completed") {
    return "终端收束";
  }
  if (status === "failed") {
    return "终端错误";
  }
  if (status === "stopped") {
    return "已停止";
  }
  return isRunning ? "运行中" : statusLabel(status);
}

function runStageHint(status: string, isRunning: boolean, hasOutput: boolean): string {
  if (status === "pending") {
    return "等待后台建立这次运行的状态通道";
  }
  if (status === "running") {
    return hasOutput
      ? "Codex 正在持续增量输出内容"
      : "Codex 正在思考并组织下一步动作";
  }
  if (status === "completed") {
    return "本轮运行已经完成，输出保留在终端卡片中";
  }
  if (status === "failed") {
    return "本轮运行遇到错误，先查看报错再决定是否重试";
  }
  if (status === "stopped") {
    return "本轮运行已被手动终止";
  }
  return isRunning ? "运行仍在持续" : "当前没有可用的运行状态";
}

function runStageTone(
  status: string,
  hasOutput: boolean,
): "pending" | "thinking" | "streaming" | "terminal" {
  if (status === "pending") return "pending";
  if (status === "running") return hasOutput ? "streaming" : "thinking";
  return "terminal";
}

type StreamNoticeKind =
  | "connecting"
  | "recovering"
  | "degraded"
  | "recovered"
  | "terminal";

interface StreamNotice {
  kind: StreamNoticeKind;
  title: string;
  copy: string;
}

function transportStateLabel(state: LiveRunTransportState): string {
  switch (state) {
    case "connecting":
      return "同步中";
    case "recovering":
      return "静默重连";
    case "degraded":
      return "降级同步";
    case "terminal":
      return "通道停止";
    case "idle":
    case "live":
    default:
      return "实时在线";
  }
}

function transportStateNotice(
  state: LiveRunTransportState,
): StreamNotice | null {
  switch (state) {
    case "connecting":
      return {
        kind: "connecting",
        title: "正在建立实时通道",
        copy: "当前内容先由快照填充，SSE 建立后会自动切回实时流。",
      };
    case "recovering":
      return {
        kind: "recovering",
        title: "实时通道短暂中断",
        copy: "后台正在静默重连，当前会话继续由轮询快照兜底，不会打断页面。",
      };
    case "degraded":
      return {
        kind: "degraded",
        title: "实时通道已降级",
        copy: "连续重试后仍不稳定，页面已切换为轮询同步，稍后会继续尝试恢复。",
      };
    case "terminal":
      return {
        kind: "terminal",
        title: "实时通道已停止",
        copy: "如果你刚刚切换了登录状态或权限发生变化，刷新后可以重新建立连接。",
      };
    default:
      return null;
  }
}

function runtimeSelectionLabel(value: string | null): string {
  return value?.trim() || "Auto";
}

function runtimeSelectionSummary(
  model: string | null,
  reasoningEffort: string | null,
): string {
  return `下次发送将使用 ${runtimeSelectionLabel(model)} / ${runtimeSelectionLabel(reasoningEffort)}`;
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
  const previousTransportStateRef =
    useRef<LiveRunTransportState>("idle");
  const [streamNotice, setStreamNotice] = useState<StreamNotice | null>(null);
  const [selectedModel, setSelectedModel] = useState<string | null>(null);
  const [selectedReasoningEffort, setSelectedReasoningEffort] = useState<string | null>(null);
  const prefillAppliedRef = useRef(false);

  const loadSession = useCallback(async () => {
    const data = await getSessionDetail(sessionId);
    setSession(data.session);
    setMessages(data.messages);
  }, [sessionId]);

  // Only subscribe to SSE after the session has been validated by getSession().
  // This prevents endless reconnect loops for unknown session IDs.
  const liveRunState = useLiveRun(session ? sessionId : null);
  const liveRun = liveRunState.run;
  const transportState = liveRunState.transportState;
  const effectiveModel = selectedModel ?? liveRun?.model ?? null;
  const effectiveReasoningEffort =
    selectedReasoningEffort ?? liveRun?.reasoningEffort ?? null;
  const isRunning =
    liveRun?.status === "running" || liveRun?.status === "pending";
  const historyGroups = buildHistoryGroups(messages);
  const liveOutput = cleanLiveOutput(liveRun?.lastOutput ?? null);
  const latestPrompt = liveRun?.prompt.trim() ?? "";
  const reusablePrompt = sanitizePromptDisplay(liveRun?.prompt ?? "");
  const runTone = liveRun
    ? runStageTone(liveRun.status, Boolean(liveOutput))
    : null;
  const runStage = liveRun
    ? runStageLabel(liveRun.status, isRunning, Boolean(liveOutput))
    : "";
  const runStageCopy = liveRun
    ? runStageHint(liveRun.status, isRunning, Boolean(liveOutput))
    : "";
  const promptPreview = summarizeInlineText(prompt, 96);
  const activeRunPrompt = summarizeInlineText(reusablePrompt || latestPrompt, 120);
  const composerModeLabel = isRunning
    ? "运行中"
    : sending
      ? "排队中"
      : prompt.trim()
        ? "准备发送"
        : "等待输入";
  const composerModeHint = isRunning
    ? activeRunPrompt
      ? `当前运行：${activeRunPrompt}`
      : "当前运行仍在持续，停止后可以发起下一轮 follow-up。"
    : sending
      ? promptPreview
        ? `正在提交：${promptPreview}`
        : "提示词正在进入队列。"
      : pendingArtifacts.length > 0
        ? "附件已挂载到下一次提交，输入后即可一并发送。"
        : "输入 follow-up 命令，直接作为下一轮指令发送。";
  const composerInlineHint = isRunning
    ? "停止后可继续提交新的 follow-up。"
    : sending
      ? "当前指令正在发送队列中。"
      : pendingArtifacts.length > 0
        ? "附件会自动随下一次请求一起发送。"
        : "输入完成后可以直接提交。";
  const composerQueueHint = sending
    ? `队列：${promptPreview || "正在提交当前命令"}`
    : prompt.trim()
      ? `预览：${promptPreview || "当前输入"}`
      : pendingArtifacts.length > 0
        ? "附件已挂载，等待指令"
        : "可直接发送";
  const composerRuntimeSummary = runtimeSelectionSummary(
    effectiveModel,
    effectiveReasoningEffort,
  );
  const composerActionKicker = isRunning
    ? "保持控制"
    : sending
      ? "提交队列"
      : prompt.trim()
        ? "准备执行"
        : "等待输入";
  const composerActionLabel = isRunning
    ? "停止运行"
    : sending
      ? "提交中…"
      : "发送命令";
  const historyRoundCount = historyGroups.length;
  const historicalRoundCount = historyGroups.filter(
    (group) => group.isHistorical,
  ).length;

  useEffect(() => {
    const previousState = previousTransportStateRef.current;
    previousTransportStateRef.current = transportState;

    if (transportState === "idle") {
      setStreamNotice(null);
      return;
    }

    if (transportState === "live") {
      if (previousState === "recovering" || previousState === "degraded") {
        setStreamNotice({
          kind: "recovered",
          title: "实时通道已恢复",
          copy: "内容已经重新同步，页面现在回到了实时流状态。",
        });
        const timer = setTimeout(() => {
          setStreamNotice(null);
        }, 3500);
        return () => clearTimeout(timer);
      }

      setStreamNotice(null);
      return;
    }

    const notice = transportStateNotice(transportState);
    setStreamNotice(notice);
  }, [transportState]);

  const runPanelStateClass =
    transportState === "recovering"
      ? "detail-run-panel-recovering"
      : transportState === "degraded"
        ? "detail-run-panel-degraded"
        : transportState === "connecting"
          ? "detail-run-panel-connecting"
          : transportState === "terminal"
            ? "detail-run-panel-terminal"
            : "";
  const runTerminalStateClass =
    transportState === "recovering"
      ? "run-terminal-card-recovering"
      : transportState === "degraded"
        ? "run-terminal-card-degraded"
        : transportState === "connecting"
          ? "run-terminal-card-connecting"
          : transportState === "terminal"
            ? "run-terminal-card-terminal"
            : "";
  const transportBadge = transportStateLabel(transportState);

  useEffect(() => {
    if (prefillAppliedRef.current) return;
    if (!prefill.trim()) return;
    setPrompt(prefill);
    prefillAppliedRef.current = true;
  }, [prefill]);

  useEffect(() => {
    if (selectedModel === null && liveRun?.model) {
      setSelectedModel(liveRun.model);
    }
  }, [liveRun?.model, selectedModel]);

  useEffect(() => {
    if (selectedReasoningEffort === null && liveRun?.reasoningEffort) {
      setSelectedReasoningEffort(liveRun.reasoningEffort);
    }
  }, [liveRun?.reasoningEffort, selectedReasoningEffort]);

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

      await startLiveRun(sessionId, nextPrompt, {
        model: effectiveModel,
        reasoningEffort: effectiveReasoningEffort,
      });
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
      await startLiveRun(sessionId, liveRun?.prompt ?? latestPrompt, {
        model: liveRun?.model ?? effectiveModel,
        reasoningEffort: liveRun?.reasoningEffort ?? effectiveReasoningEffort,
      });
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
      await startLiveRun(sessionId, nextPrompt, {
        model: effectiveModel,
        reasoningEffort: effectiveReasoningEffort,
      });
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
          <div className="empty-state" role="alert">
            <div className="empty-state-icon">⚠</div>
            <div className="empty-state-text">会话同步失败</div>
            <div className="empty-state-sub">{error}</div>
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
            <section className="detail-overview panel">
              <div className="detail-overview-top">
                <div className="detail-overview-copy">
                  <div className="detail-overview-eyebrow">Session detail</div>
                  <h2>{session?.title || sessionId}</h2>
                  <p>{session?.cwd || "当前没有可用的工作目录信息"}</p>
                </div>
                <div className="detail-overview-badges">
                  <span className="pill pill-muted">
                    {historyRoundCount} 轮历史
                  </span>
                  <span className="pill">
                    {session?.archivedAt ? "已归档" : "活跃中"}
                  </span>
                  <span className="pill pill-accent">
                    {liveRun ? statusLabel(liveRun.status) : "无运行中任务"}
                  </span>
                </div>
              </div>
              <div className="detail-overview-stats">
                <div className="detail-overview-stat">
                  <span>消息</span>
                  <strong>{messages.length}</strong>
                </div>
                <div className="detail-overview-stat">
                  <span>历史轮次</span>
                  <strong>{historyRoundCount}</strong>
                </div>
                <div className="detail-overview-stat">
                  <span>当前可见</span>
                  <strong>{historyRoundCount - historicalRoundCount}</strong>
                </div>
              </div>
            </section>

            <section className="detail-conversation-shell">
              <div className="detail-run-grid">
                <div className="detail-history-panel">
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
                                <div className="history-folded-preview">
                                  {group.preview}
                                </div>
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
                                          <div className="history-text">
                                            {message.text}
                                          </div>
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
                      <div className="history-empty detail-history-empty">
                        <div className="history-empty-title">还没有可展示的历史消息</div>
                        <div className="history-empty-copy">
                          这次会话刚刚开始。新的提示词会先出现在右侧的运行面板中，历史上下文会在后续逐步展开。
                        </div>
                      </div>
                    )}
                  </div>
                </div>

                <div className={`detail-run-panel ${runPanelStateClass}`}>
                  {liveRun ? (
                    <>
                      <div className="run-header">
                        <span className={`status-badge status-${liveRun.status}`}>
                          {isRunning && "● "}
                          {statusLabel(liveRun.status)}
                        </span>
                        {transportState !== "live" && (
                          <span
                            className={`transport-badge transport-badge-${transportState}`}
                          >
                            {transportBadge}
                          </span>
                        )}
                        {liveRun.model && (
                          <span className="model-badge">{liveRun.model}</span>
                        )}
                        {liveRun.reasoningEffort && (
                          <span className="model-badge">
                            reasoning {liveRun.reasoningEffort}
                          </span>
                        )}
                      </div>

                      {streamNotice && (
                        <div
                          className={`sync-banner sync-banner-${streamNotice.kind}`}
                          role="status"
                          aria-live="polite"
                        >
                          <div className="sync-banner-title">
                            {streamNotice.title}
                          </div>
                          <div className="sync-banner-copy">
                            {streamNotice.copy}
                          </div>
                        </div>
                      )}

                      <div className="run-stage-rail" aria-label="运行阶段">
                        <span
                          className={`run-stage-pill ${
                            runTone === "thinking" ? "run-stage-pill-active" : ""
                          }`}
                        >
                          思考
                        </span>
                        <span
                          className={`run-stage-pill ${
                            runTone === "streaming" ? "run-stage-pill-active" : ""
                          }`}
                        >
                          流式
                        </span>
                        <span
                          className={`run-stage-pill ${
                            runTone === "terminal" ? "run-stage-pill-active" : ""
                          }`}
                        >
                          终端
                        </span>
                      </div>

                      <div className="run-state-copy">
                        <div className="run-state-title">{runStage}</div>
                        <div className="run-state-subtitle">{runStageCopy}</div>
                      </div>

                      <div className="run-snapshot">
                        <div className="run-snapshot-label">本轮提示词</div>
                        <div className="run-snapshot-text">
                          {sanitizePromptDisplay(liveRun.prompt)}
                        </div>
                      </div>

                      <div
                        className={`run-terminal-card ${
                          isRunning
                            ? "run-terminal-card-running"
                            : liveRun.status === "failed"
                              ? "run-terminal-card-error"
                              : "run-terminal-card-complete"
                        } ${runTerminalStateClass}`}
                      >
                        <div className="history-message-top">
                          <span className="history-role">Codex 输出</span>
                          <span className="history-time">
                            {transportState === "recovering"
                              ? "重连中"
                              : transportState === "degraded"
                                ? "降级同步"
                                : transportState === "connecting"
                                  ? "同步中"
                                  : transportState === "terminal"
                                    ? "通道停止"
                                    : isRunning
                                      ? "实时流"
                                      : statusLabel(liveRun.status)}
                          </span>
                        </div>
                        <div className="run-terminal-output" ref={outputRef}>
                          {liveOutput ??
                            (transportState === "recovering"
                              ? "实时通道暂时中断，正在静默重连…"
                              : transportState === "degraded"
                                ? "实时通道不稳定，已切换为轮询同步，稍后会继续尝试恢复。"
                                : transportState === "connecting"
                                  ? "正在建立实时通道，先由快照同步当前状态…"
                                  : transportState === "terminal"
                                    ? "实时通道已停止，当前仅保留已同步的会话内容。"
                                    : isRunning
                                      ? "Codex 正在思考并等待下一段输出…"
                                      : "这次运行没有可显示的终端文本")}
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

                      {liveRun.error && (
                        <div className="run-error detail-run-error">
                          <div className="run-error-title">运行错误</div>
                          <div className="run-error-copy">{liveRun.error}</div>
                        </div>
                      )}
                    </>
                  ) : transportState !== "idle" ? (
                    <div
                      className={`empty-state detail-run-empty detail-run-empty-sync detail-run-empty-${transportState}`}
                      role="status"
                      aria-live="polite"
                    >
                      <div className="empty-state-icon">↻</div>
                      <div className="empty-state-text">
                        {transportState === "recovering"
                          ? "实时通道正在恢复"
                          : transportState === "degraded"
                            ? "实时通道已降级为轮询同步"
                            : transportState === "connecting"
                              ? "实时通道正在建立"
                              : "实时通道已停止"}
                      </div>
                      <div className="empty-state-sub">
                        {transportState === "recovering"
                          ? "页面不会中断，最新内容会由快照继续兜底，稍后会自动切回实时流。"
                          : transportState === "degraded"
                            ? "当前网络不稳定，页面仍会持续同步最新快照，并继续尝试恢复 SSE。"
                            : transportState === "connecting"
                              ? "当前内容先由快照填充，实时流建立后会自动接管。"
                              : "如果你只是暂时切走了网络，这里会在恢复后重新建立连接。"}
                      </div>
                    </div>
                  ) : (
                    <div className="empty-state detail-run-empty">
                      <div className="empty-state-icon">⚡</div>
                      <div className="empty-state-text">当前没有运行中的任务</div>
                      <div className="empty-state-sub">
                        发送一条提示词后，这里会显示思考、流式输出和终端收束状态。
                      </div>
                    </div>
                  )}
                </div>
              </div>
            </section>

            {error && session && (
              <div className="error-message detail-inline-error" role="alert">{error}</div>
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
      <form className="prompt-bar composer-panel" onSubmit={handleSendPrompt}>
        <div className="composer-topline">
          <div className="composer-copy-block">
            <div className="composer-eyebrow">Command center</div>
            <h2>
              {isRunning
                ? "停止、检查或发起下一条 follow-up"
                : "准备下一条命令"}
            </h2>
            <p>{composerModeHint}</p>
          </div>
          <div className="composer-badges">
            <span
              className={`composer-pill composer-pill-${
                isRunning ? "primary" : sending ? "accent" : "muted"
              }`}
            >
              {composerModeLabel}
            </span>
            <span className="composer-pill composer-pill-muted">
              {pendingArtifacts.length > 0
                ? `${pendingArtifacts.length} 个附件`
                : "无附件"}
            </span>
            <span className="composer-pill composer-pill-muted">
              {isRunning ? "Live run" : "Ready for follow-up"}
            </span>
          </div>
        </div>

        <div className="composer-runtime-panel" aria-label="runtime controls">
          <div className="composer-runtime-copy">
            <div className="composer-runtime-title">Runtime controls</div>
            <div className="composer-runtime-summary">
              {composerRuntimeSummary}
            </div>
            <div className="composer-runtime-note">
              当前页面会把这里的选择直接带到下一次发送；如果已有运行在进行，等停止后再继续提交即可。
            </div>
          </div>
          <div className="composer-runtime-grid">
            <label className="composer-runtime-field">
              <span>Model</span>
              <select
                value={selectedModel ?? ""}
                onChange={(e) =>
                  setSelectedModel(e.target.value.trim() ? e.target.value : null)
                }
                disabled={sending}
              >
                <option value="">Auto</option>
                <option value="gpt-5.4">gpt-5.4</option>
                <option value="o4-mini">o4-mini</option>
              </select>
            </label>
            <label className="composer-runtime-field">
              <span>Reasoning</span>
              <select
                value={selectedReasoningEffort ?? ""}
                onChange={(e) =>
                  setSelectedReasoningEffort(
                    e.target.value.trim() ? e.target.value : null,
                  )
                }
                disabled={sending}
              >
                <option value="">Auto</option>
                <option value="low">low</option>
                <option value="medium">medium</option>
                <option value="high">high</option>
              </select>
            </label>
          </div>
        </div>

        {pendingArtifacts.length > 0 && (
          <div className="composer-attachments">
            <div className="composer-attachments-header">
              <span>待发送附件</span>
              <span>{pendingArtifacts.length} 个</span>
            </div>
            <div className="composer-attachment-grid">
              {pendingArtifacts.map((artifact) => (
                <div key={artifact.id} className="composer-attachment-card">
                  <div className="composer-attachment-top">
                    <div className="composer-attachment-copy">
                      <div className="composer-attachment-name">
                        {artifact.originalName}
                      </div>
                      <div className="composer-attachment-meta">
                        {artifact.mimeType} · {formatBytes(artifact.sizeBytes)}
                      </div>
                    </div>
                    <button
                      type="button"
                      className="icon-btn composer-attachment-remove"
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
                  <div className="composer-attachment-path">
                    {artifact.storedPath}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="composer-command-row">
          <button
            type="button"
            className="upload-btn composer-upload-btn"
            onClick={handleUploadClick}
            disabled={uploading}
            title="上传文件或图片"
          >
            📎
          </button>
          <div className="prompt-field">
            <input
              className="prompt-input composer-input"
              type="text"
              placeholder={isRunning ? "运行中，停止后继续…" : "输入下一条 follow-up 命令…"}
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              disabled={isRunning || sending}
            />
            <div className="prompt-support-row">
              <div className="prompt-support-copy">
                <span className="prompt-support-label">Follow-up</span>
                <span className="prompt-support-text">{composerInlineHint}</span>
              </div>
              <div className="prompt-queue-preview">{composerQueueHint}</div>
            </div>
          </div>
          {isRunning ? (
            <button
              type="button"
              className="stop-btn composer-action-btn"
              onClick={handleStop}
              title="停止运行"
            >
              <span className="composer-action-kicker">{composerActionKicker}</span>
              <span className="composer-action-label">{composerActionLabel}</span>
            </button>
          ) : (
            <button
              type="submit"
              className="send-btn composer-action-btn"
              disabled={!prompt.trim() || sending}
              title="继续处理"
            >
              <span className="composer-action-kicker">{composerActionKicker}</span>
              <span className="composer-action-label">{composerActionLabel}</span>
            </button>
          )}
        </div>

        {uploading && uploadProgress && (
          <div
            className="upload-status composer-upload-status upload-status-progress"
            role="status"
            aria-live="polite"
          >
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
          <div
            className="upload-status composer-upload-status upload-status-progress"
            role="status"
            aria-live="polite"
          >
            <div className="spinner" style={{ width: 16, height: 16 }} />
            <span>上传中…</span>
          </div>
        )}
        {uploadResult && (
          <div
            className={`upload-status composer-upload-status upload-status-${uploadResult.status}`}
            role="status"
            aria-live="polite"
          >
            <span>{uploadResult.status === "success" ? "✓" : "✗"}</span>
            <span>{uploadResult.message}</span>
          </div>
        )}
      </form>
    </div>
  );
}
