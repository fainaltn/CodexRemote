"use client";

import { useState, useEffect, useRef } from "react";
import { getLiveRun, liveStreamUrl, getToken, type Run } from "./api";

const RECONNECT_BASE_MS = 1_000;
const RECONNECT_MAX_MS = 15_000;
const MAX_CONSECUTIVE_ERRORS = 3;
const POLL_INTERVAL_MS = 1_500;

/**
 * Subscribe to a session's live-run SSE stream.
 *
 * Uses `fetch` + `ReadableStream` instead of the native `EventSource` API
 * so we can send the `Authorization: Bearer <token>` header that the
 * protected backend requires.  Native `EventSource` does not support
 * custom request headers, which would cause every stream request to
 * receive a 401.
 *
 * Protocol awareness:
 * - `run`        — run state snapshot (always accepted)
 * - `stream-end` — the current run lifecycle is over (no-run, completed,
 *                  failed, stopped).  The stream stays open so future runs
 *                  on the same session still arrive.
 * - `gap`        — the server detected the client missed events during a
 *                  reconnect.  The current `run` snapshot is authoritative
 *                  so no special recovery is needed.
 *
 * Reconnect: on network errors or unexpected stream closure the hook
 * reconnects with exponential back-off and sends `Last-Event-ID` so the
 * server can signal any missed events.  Permanent failures (401, 404, or
 * too many consecutive errors) stop reconnection.
 */
export function useLiveRun(sessionId: string | null): Run | null {
  const [run, setRun] = useState<Run | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!sessionId) {
      setRun(null);
      return;
    }

    let cancelled = false;
    // Capture as non-null for use inside closures (early return above
    // guarantees sessionId is non-null here, but TS can't narrow it
    // across async boundaries).
    const sid = sessionId;

    let consecutiveErrors = 0;
    let lastEventId: string | undefined;
    let reconnectMs = RECONNECT_BASE_MS;
    let pollTimer: ReturnType<typeof setInterval> | null = null;

    async function pollSnapshot(): Promise<void> {
      try {
        const snapshot = await getLiveRun(sid);
        if (!cancelled) {
          setRun(snapshot);
        }
      } catch {
        // Keep polling best-effort. SSE remains the primary transport.
      }
    }

    // ── SSE frame parser ──────────────────────────────────────────
    function processFrame(frame: string): void {
      const lines = frame.split("\n");
      let event = "";
      let data = "";
      let id: string | undefined;

      for (const line of lines) {
        if (line.startsWith("event: ")) event = line.slice(7);
        else if (line.startsWith("data: ")) data = line.slice(6);
        else if (line.startsWith("id: ")) id = line.slice(4);
        // ":" lines are comments (heartbeat) — skip.
      }

      if (id !== undefined) lastEventId = id;

      if (event === "run") {
        consecutiveErrors = 0;
        try {
          setRun(JSON.parse(data) as Run | null);
        } catch {
          // Ignore malformed frames.
        }
      } else if (event === "stream-end") {
        // Run-lifecycle signal only — does NOT suppress reconnect.
        // The server keeps the stream open after stream-end so future
        // runs arrive on the same connection.  If the transport drops
        // later, we must reconnect regardless.
        consecutiveErrors = 0;
      } else if (event === "gap") {
        consecutiveErrors = 0;
      }
    }

    // ── Reconnect scheduler ───────────────────────────────────────
    function scheduleReconnect(): void {
      if (cancelled) return;
      const delay = reconnectMs;
      reconnectMs = Math.min(reconnectMs * 2, RECONNECT_MAX_MS);
      setTimeout(() => {
        if (!cancelled) connect();
      }, delay);
    }

    // ── Main connection loop ──────────────────────────────────────
    async function connect(): Promise<void> {
      if (cancelled) return;

      const token = getToken();
      const controller = new AbortController();
      abortRef.current = controller;

      const headers: Record<string, string> = {
        Accept: "text/event-stream",
      };
      if (token) {
        headers["Authorization"] = `Bearer ${token}`;
      }
      if (lastEventId !== undefined) {
        headers["Last-Event-ID"] = lastEventId;
      }

      try {
        const res = await fetch(liveStreamUrl(sid), {
          headers,
          signal: controller.signal,
        });

        if (!res.ok || !res.body) {
          consecutiveErrors++;
          // Permanent failures — don't retry.
          if (res.status === 401 || res.status === 404) return;
          if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) return;
          scheduleReconnect();
          return;
        }

        // Connection established — reset error / backoff state.
        consecutiveErrors = 0;
        reconnectMs = RECONNECT_BASE_MS;

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        // Read until the stream closes or the component unmounts.
        while (true) {
          const { done, value } = await reader.read();
          if (done || cancelled) break;

          buffer += decoder.decode(value, { stream: true });

          // Process complete SSE frames (delimited by double-newline).
          let boundary: number;
          while ((boundary = buffer.indexOf("\n\n")) !== -1) {
            const frame = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            if (frame.trim()) processFrame(frame);
          }
        }

        // Stream ended — reconnect unless we tore down intentionally.
        // Always reconnect on transport loss, even if stream-end was
        // seen earlier (it's a run-lifecycle signal, not unsubscribe).
        if (!cancelled) {
          scheduleReconnect();
        }
      } catch (err: unknown) {
        if (cancelled) return;
        if (err instanceof DOMException && err.name === "AbortError") return;

        consecutiveErrors++;
        if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
          scheduleReconnect();
        }
      }
    }

    pollSnapshot();
    pollTimer = setInterval(() => {
      void pollSnapshot();
    }, POLL_INTERVAL_MS);

    connect();

    return () => {
      cancelled = true;
      if (pollTimer) clearInterval(pollTimer);
      abortRef.current?.abort();
      abortRef.current = null;
    };
  }, [sessionId]);

  return run;
}
