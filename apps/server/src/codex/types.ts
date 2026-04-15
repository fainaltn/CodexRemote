/**
 * Adapter-local types for Codex integration.
 *
 * These represent what the adapter returns before the route layer
 * maps them to the shared API response schemas.  Keeping adapter
 * types separate lets us absorb Codex CLI changes without touching
 * API contracts.
 */

/** Lightweight session summary returned by a "list sessions" scan. */
export interface CodexSessionSummary {
  /** Codex-native session identifier (directory name / CLI id). */
  codexSessionId: string;
  /** Working directory where the session was started, if known. */
  cwd: string | null;
  /** ISO-8601 timestamp of the most recent activity, if available. */
  lastActivityAt: string | null;
  /** First user-visible label we can recover for the session. */
  title?: string | null;
  /** Last visible preview snippet, if recoverable. */
  lastPreview?: string | null;
}

/** Richer detail for a single session. */
export interface CodexSessionDetail extends CodexSessionSummary {
  /** First user prompt or auto-generated title. */
  title: string | null;
  /** Last model output snippet, if recoverable. */
  lastPreview: string | null;
}

export interface CodexSessionMessage {
  id: string;
  role: "user" | "assistant" | "system";
  kind: "message" | "reasoning";
  turnId?: string;
  itemId?: string;
  orderIndex?: number;
  isStreaming?: boolean;
  text: string;
  createdAt: string;
}

/** Options passed when starting (or resuming) a Codex run. */
export interface StartRunOptions {
  prompt: string;
  model?: string;
  reasoningEffort?: string;
  /**
   * Distinguishes "create an empty shell session" from
   * "create a session and immediately run the first prompt".
   *
   * The conservative thread/app-server creation strategy is only used
   * for `create-only` because `create-and-run` still depends on the
   * existing `codex exec` run lifecycle.
   */
  startupMode?: "create-only" | "create-and-run";
}

/** Handle returned after spawning a Codex process. */
export interface RunHandle {
  /** Process ID of the spawned Codex CLI process. */
  pid: number;
  /** Callback to read accumulated stdout so far (may be truncated to a tail window). */
  readOutput: () => string;
  /** Total bytes received across stdout + stderr since spawn (always monotonically increasing). */
  totalOutputBytes: () => number;
  /** Callback to stop the process. Resolves once the process exits. */
  stop: () => Promise<void>;
  /** Register a callback that fires when the child process exits. */
  onExit: (cb: (code: number | null) => void) => void;
}

export interface NewRunHandle {
  sessionId: string;
  handle: RunHandle;
}
