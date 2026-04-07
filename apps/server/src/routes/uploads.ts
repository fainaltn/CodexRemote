import type { FastifyInstance, FastifyRequest, FastifyReply } from "fastify";
import {
  UploadParams,
  AttachArtifactParams,
  AttachArtifactRequest,
  type UploadResponse,
  type AttachArtifactResponse,
} from "@codexremote/shared";
import { LOCAL_HOST_ID } from "../constants.js";
import {
  createArtifact,
  getArtifact,
  attachArtifact,
  MAX_UPLOAD_BYTES,
  InsufficientDiskError,
  DisallowedFileTypeError,
  isAllowedMimeType,
} from "../artifacts/store.js";
import type { CodexAdapter } from "../codex/index.js";
import type { RunManager } from "../runs/manager.js";
import { UPLOAD_STREAM_TIMEOUT_MS, ALLOWED_UPLOAD_MIME_PATTERNS } from "../config.js";
import { extractBearerToken } from "../auth/middleware.js";
import { verifyToken } from "../auth/store.js";
import { writeAuditLog } from "../audit/log.js";

/**
 * Upload route group.
 *
 * - POST /api/hosts/:hostId/uploads        — multipart file upload
 * - POST /api/hosts/:hostId/sessions/:sessionId/artifacts — attach artifact
 *
 * Both endpoints validate that the target session exists via the adapter
 * before persisting any data, matching the 404 semantics of the session
 * and live-run route groups.
 */
export function uploadRoutes(adapter: CodexAdapter, runManager: RunManager, overrides?: { uploadStreamTimeoutMs?: number; allowedUploadMimePatterns?: readonly string[] }) {
  return async function register(app: FastifyInstance): Promise<void> {
    // ── POST /api/hosts/:hostId/uploads ───────────────────────────────
    app.post(
      "/api/hosts/:hostId/uploads",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const paramsParsed = UploadParams.safeParse(request.params);
        if (!paramsParsed.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }

        if (paramsParsed.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${paramsParsed.data.hostId}' not found` });
        }

        // Parse multipart — expect one file field and a required sessionId field.
        let file: Awaited<ReturnType<FastifyRequest["file"]>> | undefined;
        try {
          file = await request.file();
        } catch {
          return reply.status(400).send({ error: "Invalid multipart request" });
        }

        if (!file) {
          return reply.status(400).send({ error: "No file provided" });
        }

        // ── File-type policy check (§11 security model) ──────────────
        // Validate the MIME type before consuming the stream to save
        // bandwidth on rejected uploads.  The MIME type comes from the
        // Content-Type header of the multipart part (client-supplied).
        const mimePatterns = overrides?.allowedUploadMimePatterns ?? ALLOWED_UPLOAD_MIME_PATTERNS;
        if (!isAllowedMimeType(file.mimetype, mimePatterns)) {
          // Drain the file stream to prevent busboy hanging on unconsumed data.
          file.file.resume();

          const tokenId = extractBearerToken(request);
          const tokenInfo = tokenId ? verifyToken(tokenId) : null;
          writeAuditLog({
            eventType: "upload_failure",
            ip: request.ip,
            tokenId,
            deviceLabel: tokenInfo?.deviceLabel ?? null,
            detail: `disallowed_file_type: ${file.mimetype}`,
          });
          return reply.status(415).send({
            error: `File type '${file.mimetype}' is not allowed`,
          });
        }

        // Extract sessionId from form fields before consuming the file stream.
        const sessionIdField = file.fields["sessionId"];
        let sessionId: string | undefined;

        if (
          sessionIdField &&
          typeof sessionIdField === "object" &&
          "value" in sessionIdField
        ) {
          sessionId = (sessionIdField as { value: string }).value;
        }

        if (!sessionId) {
          return reply
            .status(400)
            .send({ error: "sessionId is required as a form field" });
        }

        // Validate the session exists before persisting anything.
        const sessionDetail = await adapter.getSessionDetail(sessionId);
        if (!sessionDetail) {
          return reply
            .status(404)
            .send({ error: `Session '${sessionId}' not found` });
        }

        // Disable the socket-level idle timeout (connectionTimeout) for
        // the file-streaming phase.  The route-level stall timer below is
        // the intended bound for uploads; without this, the global
        // connectionTimeout (default 30 s) could pre-empt it.
        // (Mirrors the SSE pattern in live-runs.ts.)
        // Guard: app.inject() tests have no real socket.
        if (typeof request.raw.setTimeout === "function") {
          request.raw.setTimeout(0);
        }

        // Read the file buffer. @fastify/multipart streams, so we consume it.
        // Wrapped in try-catch so a client disconnect that destroys the
        // underlying socket mid-stream is caught rather than persisted as
        // a partial artifact — a real risk on mobile-over-Tailscale.
        // An additional timeout ensures a stalled stream (no data arriving
        // but the socket still open) doesn't hang the handler forever.
        const chunks: Buffer[] = [];
        let totalBytes = 0;
        let streamError: unknown = null;
        const streamTimeoutMs = overrides?.uploadStreamTimeoutMs ?? UPLOAD_STREAM_TIMEOUT_MS;

        try {
          await new Promise<void>((resolve, reject) => {
            const timer = setTimeout(() => {
              // Destroy the file stream so the `for await` loop breaks
              // and the multipart parser releases the connection.
              // Also destroy the underlying TCP socket — a stalled client
              // can't receive a proper HTTP response anyway, so a
              // connection reset is the correct signal.
              const err = new Error(`Upload stream stalled — no data for ${streamTimeoutMs}ms`);
              file.file.destroy(err);
              if (request.raw.socket && !request.raw.socket.destroyed) {
                request.raw.socket.destroy();
              }
            }, streamTimeoutMs);

            // Reset the timer on every chunk so a slow-but-progressing
            // upload isn't killed prematurely.
            // NOTE: only use timer.refresh() here — clearTimeout() would
            // permanently deactivate the timer (refresh() cannot re-arm a
            // cleared timer in Node.js).
            const consume = async () => {
              try {
                for await (const chunk of file.file) {
                  totalBytes += chunk.length;
                  if (totalBytes > MAX_UPLOAD_BYTES) {
                    clearTimeout(timer);
                    resolve(); // size guard below handles the 413
                    return;
                  }
                  chunks.push(chunk as Buffer);
                  // Re-arm the timer after each chunk.
                  timer.refresh();
                }
                clearTimeout(timer);
                resolve();
              } catch (err) {
                clearTimeout(timer);
                reject(err);
              }
            };
            consume();
          });
        } catch (err) {
          streamError = err;
        }

        // Check if the stream was truncated by the multipart limit or
        // our own byte-counting guard.
        if (totalBytes > MAX_UPLOAD_BYTES || file.file.truncated) {
          return reply.status(413).send({
            error: `File exceeds maximum upload size of ${MAX_UPLOAD_BYTES} bytes`,
          });
        }

        // Guard: discard the upload if the client disconnected mid-stream.
        // When the underlying socket is destroyed during multipart
        // consumption, busboy's file stream emits an error caught above.
        // This prevents persisting an artifact with truncated content.
        if (streamError) {
          request.log.warn(
            {
              sessionId,
              bytesReceived: totalBytes,
              streamError:
                streamError instanceof Error
                  ? streamError.message
                  : undefined,
            },
            "Upload interrupted — discarding partial data",
          );
          const tokenId = extractBearerToken(request);
          const tokenInfo = tokenId ? verifyToken(tokenId) : null;
          writeAuditLog({
            eventType: "upload_failure",
            ip: request.ip,
            tokenId,
            deviceLabel: tokenInfo?.deviceLabel ?? null,
            sessionId,
            detail: "stream_interrupted",
          });
          return reply.status(400).send({ error: "Upload interrupted" });
        }

        const buffer = Buffer.concat(chunks);

        // If the session has an active run, link the artifact to it.
        const activeRun = runManager.getRun(sessionId);
        const runId =
          activeRun &&
          (activeRun.status === "running" || activeRun.status === "pending")
            ? activeRun.id
            : undefined;

        let artifact;
        try {
          artifact = await createArtifact({
            hostId: paramsParsed.data.hostId,
            sessionId,
            runId,
            originalName: file.filename,
            mimeType: file.mimetype,
            buffer,
          });
        } catch (err) {
          if (err instanceof InsufficientDiskError) {
            request.log.warn(
              {
                sessionId,
                availableBytes: err.availableBytes,
                requiredBytes: err.requiredBytes,
              },
              "Upload rejected — insufficient disk space",
            );
            const tokenId = extractBearerToken(request);
            const tokenInfo = tokenId ? verifyToken(tokenId) : null;
            writeAuditLog({
              eventType: "upload_failure",
              ip: request.ip,
              tokenId,
              deviceLabel: tokenInfo?.deviceLabel ?? null,
              sessionId,
              detail: "insufficient_disk_space",
            });
            return reply.status(507).send({
              error: "Insufficient disk space for upload",
              availableBytes: err.availableBytes,
              requiredBytes: err.requiredBytes,
            });
          }
          throw err;
        }

        const tokenId = extractBearerToken(request);
        const tokenInfo = tokenId ? verifyToken(tokenId) : null;
        writeAuditLog({
          eventType: "upload_success",
          ip: request.ip,
          tokenId,
          deviceLabel: tokenInfo?.deviceLabel ?? null,
          sessionId,
          artifactId: artifact.id,
          detail: `${artifact.sizeBytes}B ${artifact.mimeType}`,
        });

        const body: UploadResponse = artifact;
        return reply.status(201).send(body);
      },
    );

    // ── POST /api/hosts/:hostId/sessions/:sessionId/artifacts ─────────
    app.post(
      "/api/hosts/:hostId/sessions/:sessionId/artifacts",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const paramsParsed = AttachArtifactParams.safeParse(request.params);
        if (!paramsParsed.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }

        if (paramsParsed.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${paramsParsed.data.hostId}' not found` });
        }

        // Validate the target session exists.
        const sessionDetail = await adapter.getSessionDetail(
          paramsParsed.data.sessionId,
        );
        if (!sessionDetail) {
          return reply.status(404).send({
            error: `Session '${paramsParsed.data.sessionId}' not found`,
          });
        }

        const bodyParsed = AttachArtifactRequest.safeParse(request.body);
        if (!bodyParsed.success) {
          return reply.status(400).send({ error: "Invalid request body" });
        }

        const existing = getArtifact(bodyParsed.data.artifactId);
        if (!existing) {
          return reply.status(404).send({
            error: `Artifact '${bodyParsed.data.artifactId}' not found`,
          });
        }

        const updated = attachArtifact(
          bodyParsed.data.artifactId,
          paramsParsed.data.sessionId,
          bodyParsed.data.runId,
        );

        if (!updated) {
          return reply.status(404).send({ error: "Artifact not found" });
        }

        const body: AttachArtifactResponse = updated;
        return reply.send(body);
      },
    );
  };
}
