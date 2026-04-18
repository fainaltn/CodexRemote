import type {
  CodexSessionSummary,
  CodexSessionDetail,
  CodexSessionMessage,
  StartRunOptions,
  RunHandle,
  NewRunHandle,
} from "./types.js";

/**
 * Boundary interface for all Codex CLI integration.
 *
 * Routes and services program against this interface — never against
 * shell commands or file-system paths directly.  Phase 1 provides a
 * single {@link LocalCodexAdapter} implementation; Phase 3 will add a
 * remote variant that proxies through the host-agent API.
 *
 * Operations that are still uncertain or stubbed are marked in the
 * JSDoc of each method so callers know what to expect.
 */
export interface CodexAdapter {
  /**
   * List all known Codex sessions on this host.
   *
   * Phase 1 strategy: scan the local Codex state directory
   * (`~/.codex/sessions/` or equivalent) and return summaries.
   */
  listSessions(): Promise<CodexSessionSummary[]>;

  /**
   * Fetch richer detail for a single session.
   *
   * Phase 1 strategy: read session metadata from local state and
   * supplement with any stored information in our own SQLite.
   */
  getSessionDetail(
    codexSessionId: string,
  ): Promise<CodexSessionDetail | null>;

  /**
   * Read the visible message history for a single session.
   *
   * Phase 1 strategy: parse the local Codex JSONL rollout and extract
   * user/assistant/system messages that are suitable for display.
   */
  getSessionMessages(
    codexSessionId: string,
    options?: {
      limit?: number;
      beforeOrderIndex?: number;
    },
  ): Promise<CodexSessionMessage[]>;

  /**
   * Start (or resume) a Codex run inside the given session.
   *
   * Phase 1 strategy: spawn `codex exec resume <sessionId>` with
   * the provided prompt piped to stdin.
   *
   * Returns a {@link RunHandle} that the run-manager can use to
   * read incremental output and stop the process.
   */
  startRun(
    codexSessionId: string,
    options: StartRunOptions,
  ): Promise<RunHandle>;

  /**
   * Start a brand-new Codex session in the given working directory.
   *
   * Used by the mobile/web "new project / new thread" flow so clients
   * can create a thread on the host without pre-existing session state.
   *
   * `options.startupMode = "create-only"` means "create an empty
   * thread/shell and return". `create-and-run` preserves the existing
   * "create the session and immediately execute the first prompt"
   * behavior used by the live-run path.
   */
  startNewRun(cwd: string, options: StartRunOptions): Promise<NewRunHandle>;

  /**
   * Archive an existing Codex session/thread on the host.
   *
   * Implementations should prefer the host's native thread archive
   * mechanism when available so external clients like Codex Desktop
   * observe the same archived state.
   */
  archiveSession(codexSessionId: string): Promise<void>;

  /**
   * Attempt to stop an active Codex process by PID.
   *
   * ⚠️  PLACEHOLDER — sends SIGTERM and resolves.  A more graceful
   * shutdown protocol may be needed once Codex CLI conventions are
   * confirmed.
   */
  stopRun(pid: number): Promise<void>;
}
