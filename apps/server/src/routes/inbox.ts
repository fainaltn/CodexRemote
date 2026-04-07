import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import {
  InboxParams,
  InboxFileFields,
  ListInboxResponse,
  SubmitInboxLinkRequest,
  type SubmitInboxFileResponse,
  type SubmitInboxLinkResponse,
} from "@codexremote/shared";
import { LOCAL_HOST_ID } from "../constants.js";
import {
  createInboxFileItem,
  createInboxFilesItem,
  createInboxLinkItem,
  createInboxSubmissionItem,
  listInboxItems,
} from "../inbox/store.js";
import {
  DisallowedFileTypeError,
  InsufficientDiskError,
  isAllowedMimeType,
  MAX_UPLOAD_BYTES,
} from "../artifacts/store.js";
import { UPLOAD_STREAM_TIMEOUT_MS, ALLOWED_UPLOAD_MIME_PATTERNS } from "../config.js";

export function inboxRoutes(overrides?: {
  uploadStreamTimeoutMs?: number;
  allowedUploadMimePatterns?: readonly string[];
}) {
  return async function register(app: FastifyInstance): Promise<void> {
    app.get("/api/hosts/:hostId/inbox", async (request, reply) => {
      const paramsParsed = InboxParams.safeParse(request.params);
      if (!paramsParsed.success) {
        return reply.status(400).send({ error: "Invalid route params" });
      }

      if (paramsParsed.data.hostId !== LOCAL_HOST_ID) {
        return reply
          .status(404)
          .send({ error: `Host '${paramsParsed.data.hostId}' not found` });
      }

      const body: ListInboxResponse = {
        items: listInboxItems(paramsParsed.data.hostId),
      };
      return reply.send(body);
    });

    app.post("/api/hosts/:hostId/inbox/link", async (request, reply) => {
      const paramsParsed = InboxParams.safeParse(request.params);
      if (!paramsParsed.success) {
        return reply.status(400).send({ error: "Invalid route params" });
      }

      if (paramsParsed.data.hostId !== LOCAL_HOST_ID) {
        return reply
          .status(404)
          .send({ error: `Host '${paramsParsed.data.hostId}' not found` });
      }

      const bodyParsed = SubmitInboxLinkRequest.safeParse(request.body);
      if (!bodyParsed.success) {
        return reply.status(400).send({ error: "Invalid request body" });
      }

      const item = await createInboxLinkItem({
        hostId: paramsParsed.data.hostId,
        ...bodyParsed.data,
      });

      const body: SubmitInboxLinkResponse = item;
      return reply.status(201).send(body);
    });

    app.post(
      "/api/hosts/:hostId/inbox/files",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const paramsParsed = InboxParams.safeParse(request.params);
        if (!paramsParsed.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }

        if (paramsParsed.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${paramsParsed.data.hostId}' not found` });
        }

        let parts;
        try {
          parts = request.parts();
        } catch {
          return reply.status(400).send({ error: "Invalid multipart request" });
        }

        if (typeof request.raw.setTimeout === "function") {
          request.raw.setTimeout(0);
        }

        const files: Array<{
          originalName: string;
          mimeType: string;
          buffer: Buffer;
        }> = [];
        let totalBytes = 0;
        let note: string | undefined;
        let source: string | undefined;

        for await (const part of parts) {
          if (part.type === "field") {
            if (part.fieldname === "note" && typeof part.value === "string") {
              note = part.value;
            }
            if (part.fieldname === "source" && typeof part.value === "string") {
              source = part.value;
            }
            continue;
          }

          if (part.type !== "file") {
            continue;
          }

          const mimePatterns =
            overrides?.allowedUploadMimePatterns ?? ALLOWED_UPLOAD_MIME_PATTERNS;
          if (!isAllowedMimeType(part.mimetype, mimePatterns)) {
            part.file.resume();
            return reply.status(415).send({
              error: `File type '${part.mimetype}' is not allowed`,
            });
          }

          const chunks: Buffer[] = [];
          for await (const chunk of part.file) {
            totalBytes += chunk.length;
            if (totalBytes > MAX_UPLOAD_BYTES) {
              return reply.status(413).send({
                error: `File exceeds maximum upload size of ${MAX_UPLOAD_BYTES} bytes`,
              });
            }
            chunks.push(chunk as Buffer);
          }
          files.push({
            originalName: part.filename,
            mimeType: part.mimetype,
            buffer: Buffer.concat(chunks),
          });
        }

        const fieldsResult = InboxFileFields.safeParse({ note, source });
        if (!fieldsResult.success) {
          return reply.status(400).send({ error: "Invalid form fields" });
        }

        if (files.length === 0) {
          return reply.status(400).send({ error: "No files provided" });
        }

        try {
          const item = await createInboxFilesItem({
            hostId: paramsParsed.data.hostId,
            files,
            ...fieldsResult.data,
          });
          return reply.status(201).send(item);
        } catch (err) {
          if (err instanceof InsufficientDiskError) {
            return reply.status(507).send({
              error: "Insufficient disk space for upload",
              availableBytes: err.availableBytes,
              requiredBytes: err.requiredBytes,
            });
          }
          if (err instanceof DisallowedFileTypeError) {
            return reply.status(415).send({ error: err.message });
          }
          return reply.status(400).send({
            error: err instanceof Error ? err.message : "Failed to upload inbox files",
          });
        }
      },
    );

    app.post(
      "/api/hosts/:hostId/inbox/submission",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const paramsParsed = InboxParams.safeParse(request.params);
        if (!paramsParsed.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }

        if (paramsParsed.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${paramsParsed.data.hostId}' not found` });
        }

        let parts;
        try {
          parts = request.parts();
        } catch {
          return reply.status(400).send({ error: "Invalid multipart request" });
        }

        if (typeof request.raw.setTimeout === "function") {
          request.raw.setTimeout(0);
        }

        const files: Array<{ relativePath: string; buffer: Buffer }> = [];
        let totalBytes = 0;

        for await (const part of parts) {
          if (part.type !== "file") {
            continue;
          }
          const fieldRelativePath = part.fieldname.startsWith("file:")
            ? decodeURIComponent(part.fieldname.slice("file:".length))
            : null;
          const fileName = fieldRelativePath || part.filename?.trim();
          if (!fileName) {
            part.file.resume();
            continue;
          }

          const mimePatterns =
            overrides?.allowedUploadMimePatterns ?? ALLOWED_UPLOAD_MIME_PATTERNS;
          if (
            fileName.endsWith(".json") ||
            fileName.endsWith(".md") ||
            isAllowedMimeType(part.mimetype, mimePatterns)
          ) {
            const chunks: Buffer[] = [];
            for await (const chunk of part.file) {
              totalBytes += chunk.length;
              if (totalBytes > MAX_UPLOAD_BYTES) {
                return reply.status(413).send({
                  error: `File exceeds maximum upload size of ${MAX_UPLOAD_BYTES} bytes`,
                });
              }
              chunks.push(chunk as Buffer);
            }
            files.push({
              relativePath: fileName,
              buffer: Buffer.concat(chunks),
            });
            continue;
          }

          part.file.resume();
          return reply.status(415).send({
            error: `File type '${part.mimetype}' is not allowed`,
          });
        }

        if (files.length === 0) {
          return reply.status(400).send({ error: "No files provided" });
        }

        try {
          const item = await createInboxSubmissionItem({
            hostId: paramsParsed.data.hostId,
            files,
            source: "bundle",
          });
          return reply.status(201).send(item);
        } catch (err) {
          if (err instanceof InsufficientDiskError) {
            return reply.status(507).send({
              error: "Insufficient disk space for upload",
              availableBytes: err.availableBytes,
              requiredBytes: err.requiredBytes,
            });
          }
          if (err instanceof DisallowedFileTypeError) {
            return reply.status(415).send({ error: err.message });
          }
          return reply.status(400).send({
            error: err instanceof Error ? err.message : "Failed to import submission bundle",
          });
        }
      },
    );

    app.post(
      "/api/hosts/:hostId/inbox/file",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const paramsParsed = InboxParams.safeParse(request.params);
        if (!paramsParsed.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }

        if (paramsParsed.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${paramsParsed.data.hostId}' not found` });
        }

        let file: Awaited<ReturnType<FastifyRequest["file"]>> | undefined;
        try {
          file = await request.file();
        } catch {
          return reply.status(400).send({ error: "Invalid multipart request" });
        }

        if (!file) {
          return reply.status(400).send({ error: "No file provided" });
        }

        const mimePatterns =
          overrides?.allowedUploadMimePatterns ?? ALLOWED_UPLOAD_MIME_PATTERNS;
        if (!isAllowedMimeType(file.mimetype, mimePatterns)) {
          file.file.resume();
          return reply.status(415).send({
            error: `File type '${file.mimetype}' is not allowed`,
          });
        }

        const fieldsResult = InboxFileFields.safeParse({
          note:
            file.fields["note"] &&
            typeof file.fields["note"] === "object" &&
            "value" in file.fields["note"]
              ? (file.fields["note"] as { value: string }).value
              : undefined,
          source:
            file.fields["source"] &&
            typeof file.fields["source"] === "object" &&
            "value" in file.fields["source"]
              ? (file.fields["source"] as { value: string }).value
              : undefined,
        });

        if (!fieldsResult.success) {
          file.file.resume();
          return reply.status(400).send({ error: "Invalid form fields" });
        }

        if (typeof request.raw.setTimeout === "function") {
          request.raw.setTimeout(0);
        }

        const chunks: Buffer[] = [];
        let totalBytes = 0;
        let streamError: unknown = null;
        const streamTimeoutMs =
          overrides?.uploadStreamTimeoutMs ?? UPLOAD_STREAM_TIMEOUT_MS;

        try {
          await new Promise<void>((resolve, reject) => {
            const timer = setTimeout(() => {
              const err = new Error(
                `Upload stream stalled — no data for ${streamTimeoutMs}ms`,
              );
              file.file.destroy(err);
              if (request.raw.socket && !request.raw.socket.destroyed) {
                request.raw.socket.destroy();
              }
            }, streamTimeoutMs);

            const consume = async () => {
              try {
                for await (const chunk of file.file) {
                  totalBytes += chunk.length;
                  if (totalBytes > MAX_UPLOAD_BYTES) {
                    clearTimeout(timer);
                    resolve();
                    return;
                  }
                  chunks.push(chunk as Buffer);
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

        if (totalBytes > MAX_UPLOAD_BYTES || file.file.truncated) {
          return reply.status(413).send({
            error: `File exceeds maximum upload size of ${MAX_UPLOAD_BYTES} bytes`,
          });
        }

        if (streamError) {
          request.log.warn(
            {
              bytesReceived: totalBytes,
              streamError:
                streamError instanceof Error
                  ? streamError.message
                  : undefined,
            },
            "Inbox upload interrupted — discarding partial data",
          );
          return reply.status(400).send({ error: "Upload interrupted" });
        }

        try {
          const item = await createInboxFileItem({
            hostId: paramsParsed.data.hostId,
            originalName: file.filename,
            mimeType: file.mimetype,
            buffer: Buffer.concat(chunks),
            ...fieldsResult.data,
          });

          const body: SubmitInboxFileResponse = item;
          return reply.status(201).send(body);
        } catch (err) {
          if (err instanceof InsufficientDiskError) {
            return reply.status(507).send({
              error: "Insufficient disk space for upload",
              availableBytes: err.availableBytes,
              requiredBytes: err.requiredBytes,
            });
          }
          if (err instanceof DisallowedFileTypeError) {
            return reply.status(415).send({ error: err.message });
          }
          throw err;
        }
      },
    );
  };
}
