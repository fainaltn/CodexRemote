import type { FastifyInstance, FastifyRequest, FastifyReply } from "fastify";
import {
  LiveRunParams,
  StartLiveRunRequest,
  type StartLiveRunResponse,
  type GetLiveRunResponse,
  type StopLiveRunResponse,
  type Run,
} from "@codexremote/shared";
import { LOCAL_HOST_ID } from "../constants.js";
import type { RunManager } from "../runs/manager.js";
import { SSE_IDLE_TIMEOUT_MS, SSE_WRITE_BUFFER_MAX } from "../config.js";

const SSE_HEARTBEAT_MS = 15_000;
const TERMINAL_STATUSES = new Set(["completed", "failed", "stopped"]);

/**
 * Validate the shared :hostId/:sessionId params.
 * Returns the parsed data or sends an error reply and returns null.
 */
function parseParams(
  request: FastifyRequest,
  reply: FastifyReply,
): { hostId: string; sessionId: string } | null {
  const parsed = LiveRunParams.safeParse(request.params);
  if (!parsed.success) {
    reply.status(400).send({ error: "Invalid route params" });
    return null;
  }
  if (parsed.data.hostId !== LOCAL_HOST_ID) {
    reply
      .status(404)
      .send({ error: `Host '${parsed.data.hostId}' not found` });
    return null;
  }
  return parsed.data;
}

/**
 * Verify that `sessionId` maps to an actual Codex session.
 * Sends 404 and returns false if it doesn't.
 */
async function requireSession(
  runManager: RunManager,
  sessionId: string,
  reply: FastifyReply,
): Promise<boolean> {
  if (await runManager.sessionExists(sessionId)) return true;
  reply.status(404).send({ error: `Session '${sessionId}' not found` });
  return false;
}

/**
 * Live-run route group.
 *
 * Provides start / get / stop / stream for a single session's
 * active Codex run.  Uses the RunManager for lifecycle tracking
 * so the adapter boundary stays clean.
 */
export function liveRunRoutes(runManager: RunManager, overrides?: { sseIdleTimeoutMs?: number; sseWriteBufferMax?: number }) {
  return async function register(app: FastifyInstance): Promise<void> {
    // ── GET  .../live ──────────────────────────────────────────────
    app.get(
      "/api/hosts/:hostId/sessions/:sessionId/live",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const params = parseParams(request, reply);
        if (!params) return;
        if (!(await requireSession(runManager, params.sessionId, reply)))
          return;

        const run = runManager.getRun(params.sessionId);
        const body: GetLiveRunResponse = run ?? null;
        return reply.send(body);
      },
    );

    // ── POST .../live ──────────────────────────────────────────────
    app.post(
      "/api/hosts/:hostId/sessions/:sessionId/live",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const params = parseParams(request, reply);
        if (!params) return;
        if (!(await requireSession(runManager, params.sessionId, reply)))
          return;

        const bodyParsed = StartLiveRunRequest.safeParse(request.body);
        if (!bodyParsed.success) {
          return reply.status(400).send({ error: "Invalid request body" });
        }

        try {
          const run = await runManager.startRun(params.sessionId, {
            prompt: bodyParsed.data.prompt,
            model: bodyParsed.data.model,
            reasoningEffort: bodyParsed.data.reasoningEffort,
          });

          const body: StartLiveRunResponse = { runId: run.id };
          return reply.status(201).send(body);
        } catch (err) {
          const message =
            err instanceof Error ? err.message : "Failed to start run";
          // Distinguish session-level conflicts (409) from internal
          // failures like DB write errors (500).
          const isConflict =
            message.includes("active run") ||
            message.includes("being terminated");
          return reply
            .status(isConflict ? 409 : 500)
            .send({ error: message });
        }
      },
    );

    // ── POST .../live/stop ─────────────────────────────────────────
    app.post(
      "/api/hosts/:hostId/sessions/:sessionId/live/stop",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const params = parseParams(request, reply);
        if (!params) return;
        if (!(await requireSession(runManager, params.sessionId, reply)))
          return;

        await runManager.stopRun(params.sessionId);

        const body: StopLiveRunResponse = { ok: true };
        return reply.send(body);
      },
    );

    // ── GET  .../live/stream (SSE) ─────────────────────────────────
    app.get(
      "/api/hosts/:hostId/sessions/:sessionId/live/stream",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const params = parseParams(request, reply);
        if (!params) return;
        if (!(await requireSession(runManager, params.sessionId, reply)))
          return;

        // Take over the raw response for SSE.
        reply.hijack();
        const raw = reply.raw;

        // SSE connections are long-lived by design — disable the
        // server-level request timeout on this socket so it isn't
        // killed prematurely.  The SSE idle timer (below) provides
        // the appropriate inactivity bound instead.
        request.raw.setTimeout(0);

        raw.writeHead(200, {
          "Content-Type": "text/event-stream",
          "Cache-Control": "no-cache",
          Connection: "keep-alive",
          "X-Accel-Buffering": "no",
        });

        // ── Cleanup guard: runs at most once ───────────────────────
        let cleaned = false;
        let heartbeat: NodeJS.Timeout | null = null;
        let idleTimer: NodeJS.Timeout | null = null;
        let unsubscribe: (() => void) | null = null;

        const idleTimeoutMs = overrides?.sseIdleTimeoutMs ?? SSE_IDLE_TIMEOUT_MS;
        const writeBufferMax = overrides?.sseWriteBufferMax ?? SSE_WRITE_BUFFER_MAX;

        function cleanup(force = false): void {
          if (cleaned) return;
          cleaned = true;
          if (heartbeat) {
            clearInterval(heartbeat);
            heartbeat = null;
          }
          if (idleTimer) {
            clearTimeout(idleTimer);
            idleTimer = null;
          }
          if (unsubscribe) {
            unsubscribe();
            unsubscribe = null;
          }
          if (!raw.destroyed) {
            // force: used by the backpressure guard — raw.end() would try
            // to flush the overflowing buffer, which can't complete for a
            // stuck client.  raw.destroy() tears down the socket immediately.
            if (force) {
              raw.destroy();
            } else {
              raw.end();
            }
          }
        }

        /** Reset the idle timer — called after every real data event. */
        function touchIdle(): void {
          if (idleTimer) clearTimeout(idleTimer);
          idleTimer = setTimeout(() => {
            // Send a final event so the client knows the close is intentional.
            send("idle-timeout", { timeoutMs: idleTimeoutMs });
            cleanup();
          }, idleTimeoutMs);
        }

        // ── Helper to write one SSE frame with event ID ────────────
        const send = (eventName: string, data: unknown, id?: number): boolean => {
          if (raw.destroyed || cleaned) {
            cleanup();
            return false;
          }
          let frame = "";
          if (id !== undefined) frame += `id: ${id}\n`;
          frame += `event: ${eventName}\ndata: ${JSON.stringify(data)}\n\n`;
          try {
            raw.write(frame);
          } catch {
            cleanup();
            return false;
          }
          // Slow-client guard: check both response-level and socket-level
          // buffer accumulation.  The socket-level buffer is the primary
          // indicator — response-level queuing only happens when the
          // socket itself becomes non-writable (rare on active sockets).
          const buffered = raw.writableLength + (raw.socket?.writableLength ?? 0);
          if (buffered > writeBufferMax) {
            request.log.warn(
              { buffered, limit: writeBufferMax },
              "SSE backpressure limit exceeded — closing slow client stream",
            );
            cleanup(true);
            return false;
          }
          return true;
        };

        // ── Read Last-Event-ID for reconnect awareness ─────────────
        const lastEventIdHeader = request.headers["last-event-id"];
        const lastEventId =
          typeof lastEventIdHeader === "string"
            ? parseInt(lastEventIdHeader, 10)
            : NaN;

        // ── Send current state immediately ─────────────────────────
        const current = runManager.getRun(params.sessionId);
        const currentSeq = runManager.getSeq(params.sessionId);

        if (!send("run", current, currentSeq)) return;
        touchIdle();

        // If the client is reconnecting and missed events, signal it.
        if (!Number.isNaN(lastEventId) && lastEventId < currentSeq) {
          send("gap", { missedFrom: lastEventId + 1, currentSeq });
        }

        // If the run is already terminal (or null), signal it.
        // The stream stays open so future runs are still delivered.
        if (!current || TERMINAL_STATUSES.has(current.status)) {
          send("stream-end", { reason: current ? current.status : "no-run" });
        }

        // ── Subscribe to future updates ────────────────────────────
        unsubscribe = runManager.subscribe(params.sessionId, (run: Run, seq: number) => {
          if (!send("run", run, seq)) return;
          touchIdle();

          // Signal stream-end when the run reaches a terminal state.
          // The stream stays open for subsequent runs.
          if (TERMINAL_STATUSES.has(run.status)) {
            send("stream-end", { reason: run.status });
          }
        });

        // Heartbeat keeps the connection alive through proxies.
        heartbeat = setInterval(() => {
          if (raw.destroyed || cleaned) {
            cleanup();
            return;
          }
          try {
            raw.write(": heartbeat\n\n");
          } catch {
            cleanup();
            return;
          }
          if (raw.writableLength + (raw.socket?.writableLength ?? 0) > writeBufferMax) {
            request.log.warn(
              { buffered: raw.writableLength + (raw.socket?.writableLength ?? 0), limit: writeBufferMax },
              "SSE backpressure limit exceeded during heartbeat — closing slow client stream",
            );
            cleanup(true);
          }
        }, SSE_HEARTBEAT_MS);

        // Clean up when the client disconnects or errors.
        request.raw.on("close", cleanup);
        raw.on("error", cleanup);
      },
    );
  };
}
