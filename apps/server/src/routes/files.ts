import { execFile } from "node:child_process";
import { createReadStream } from "node:fs";
import { readdir, realpath, stat } from "node:fs/promises";
import { homedir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import {
  FileDownloadMetadata,
  FileDownloadQuery,
  HostParams,
  ListFilesQuery,
  SearchFilesQuery,
  type FileEntry,
  type ListFilesResponse,
  type SearchFilesResponse,
} from "@codexremote/shared";
import { LOCAL_HOST_ID } from "../constants.js";
import type { CodexAdapter } from "../codex/interface.js";
import { resolveArtifactFileForDownload } from "../artifacts/store.js";

const execFileAsync = promisify(execFile);
const FILE_SEARCH_LIMIT_DEFAULT = 25;

interface FileScope {
  rootPath: string;
  currentPath: string;
  parentPath: string | null;
}

interface DownloadScope {
  filePath: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
}

function isWithinRoot(rootPath: string, candidatePath: string): boolean {
  return (
    candidatePath === rootPath ||
    candidatePath.startsWith(`${rootPath}${path.sep}`)
  );
}

function normalizeRelativePath(inputPath: string | undefined): string {
  const trimmed = inputPath?.trim();
  if (!trimmed) return ".";
  return trimmed;
}

async function realpathIfDirectory(inputPath: string): Promise<string | null> {
  const resolved = path.resolve(inputPath);
  const canonical = await realpath(resolved).catch(() => null);
  if (!canonical) return null;
  const info = await stat(canonical).catch(() => null);
  if (!info || !info.isDirectory()) return null;
  return canonical;
}

async function realpathIfFile(inputPath: string): Promise<string | null> {
  const resolved = path.resolve(inputPath);
  const canonical = await realpath(resolved).catch(() => null);
  if (!canonical) return null;
  const info = await stat(canonical).catch(() => null);
  if (!info || !info.isFile()) return null;
  return canonical;
}

async function resolveFileScope(
  adapter: CodexAdapter,
  parsed: {
    sessionId?: string;
    cwd?: string;
    path?: string;
  },
): Promise<FileScope | null> {
  const hasSessionId = Boolean(parsed.sessionId?.trim());
  const hasCwd = Boolean(parsed.cwd?.trim());

  if (hasSessionId === hasCwd) {
    return null;
  }

  let rootCandidate: string | null = null;
  if (hasSessionId) {
    const detail = await adapter.getSessionDetail(parsed.sessionId!.trim());
    rootCandidate = detail?.cwd?.trim() ?? null;
  } else if (hasCwd) {
    rootCandidate = parsed.cwd!.trim();
  }

  if (!rootCandidate) return null;

  const rootPath = await realpathIfDirectory(rootCandidate);
  if (!rootPath) return null;

  const requestedRelative = normalizeRelativePath(parsed.path);
  const requestedCurrentPath = path.resolve(rootPath, requestedRelative);
  const requestedCanonicalPath = isWithinRoot(rootPath, requestedCurrentPath)
    ? await realpathIfDirectory(requestedCurrentPath)
    : rootPath;

  if (!requestedCanonicalPath) return null;
  const currentPath = isWithinRoot(rootPath, requestedCanonicalPath)
    ? requestedCanonicalPath
    : rootPath;

  const parentCandidate = path.dirname(currentPath);
  const parentPath =
    currentPath === rootPath || !isWithinRoot(rootPath, parentCandidate)
      ? null
      : parentCandidate;

  return { rootPath, currentPath, parentPath };
}

async function resolveSessionFileDownload(
  adapter: CodexAdapter,
  sessionId: string,
  relativePath: string,
): Promise<DownloadScope | null> {
  const detail = await adapter.getSessionDetail(sessionId);
  if (!detail?.cwd?.trim()) return null;

  const rootPath = await realpathIfDirectory(detail.cwd.trim());
  if (!rootPath) return null;

  const requestedRelative = relativePath.trim();
  if (!requestedRelative) return null;

  const requestedPath = path.resolve(rootPath, requestedRelative);
  if (!isWithinRoot(rootPath, requestedPath)) return null;

  const canonicalPath = await realpathIfFile(requestedPath);
  if (!canonicalPath || !isWithinRoot(rootPath, canonicalPath)) return null;

  const info = await stat(canonicalPath).catch(() => null);
  if (!info || !info.isFile()) return null;

  return {
    filePath: canonicalPath,
    fileName: path.basename(canonicalPath),
    mimeType: "application/octet-stream",
    sizeBytes: info.size,
  };
}

async function resolveAllowedAbsoluteDownload(
  absolutePath: string,
): Promise<DownloadScope | null> {
  const requested = absolutePath.trim();
  if (!requested) return null;

  const canonicalPath = await realpathIfFile(requested);
  if (!canonicalPath) return null;

  const configuredRoots = (
    process.env["CODEXREMOTE_ALLOWED_ABSOLUTE_DOWNLOAD_ROOTS"] ??
    path.join(homedir(), "smb")
  )
    .split(",")
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);

  const allowedRoots: string[] = [];
  for (const root of configuredRoots) {
    const canonicalRoot = await realpathIfDirectory(root);
    if (canonicalRoot) {
      allowedRoots.push(canonicalRoot);
    }
  }

  const withinAllowedRoot = allowedRoots.some((root) =>
    isWithinRoot(root, canonicalPath),
  );
  if (!withinAllowedRoot) return null;

  const info = await stat(canonicalPath).catch(() => null);
  if (!info || !info.isFile()) return null;

  return {
    filePath: canonicalPath,
    fileName: path.basename(canonicalPath),
    mimeType: "application/octet-stream",
    sizeBytes: info.size,
  };
}

function contentDisposition(fileName: string): string {
  const safeName = path.basename(fileName).replace(/[\r\n"]/g, "_") || "download";
  const encoded = encodeURIComponent(safeName);
  return `attachment; filename="${safeName}"; filename*=UTF-8''${encoded}`;
}

function applyDownloadHeaders(
  reply: FastifyReply,
  metadata: FileDownloadMetadata,
): void {
  reply.header("Content-Disposition", contentDisposition(metadata.fileName));
  reply.header("Content-Length", metadata.sizeBytes.toString());
  reply.header("X-Codex-Download-Source", metadata.source);
  reply.header("X-Codex-Session-Id", metadata.sessionId);
  reply.header("X-Codex-File-Name", metadata.fileName);
  reply.header("X-Codex-File-Size-Bytes", metadata.sizeBytes.toString());
  if (metadata.artifactId) {
    reply.header("X-Codex-Artifact-Id", metadata.artifactId);
  }
}

async function listDirectoryEntries(
  rootPath: string,
  currentPath: string,
): Promise<FileEntry[]> {
  const dirents = await readdir(currentPath, { withFileTypes: true }).catch(() => null);
  if (!dirents) return [];

  const entries = dirents.map((entry) => {
    const absolutePath = path.join(currentPath, entry.name);
    return {
      name: entry.name,
      path: absolutePath,
      relativePath: path.relative(rootPath, absolutePath) || entry.name,
      kind: entry.isDirectory() ? ("directory" as const) : ("file" as const),
    };
  });

  return entries.sort((a, b) => {
    if (a.kind !== b.kind) {
      return a.kind === "directory" ? -1 : 1;
    }
    return a.name.localeCompare(b.name, "zh-CN");
  });
}

async function collectFilePaths(rootPath: string): Promise<string[]> {
  const rgArgs = [
    "--files",
    "--hidden",
    "--glob",
    "!**/.git/**",
    "--glob",
    "!**/node_modules/**",
  ];

  try {
    const result = await execFileAsync("rg", rgArgs, {
      cwd: rootPath,
      maxBuffer: 20 * 1024 * 1024,
    });
    return result.stdout
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line.length > 0);
  } catch (err) {
    const error = err as NodeJS.ErrnoException & { stdout?: string };
    if (error.code !== "ENOENT") {
      const stdout = typeof error.stdout === "string" ? error.stdout : "";
      if (stdout) {
        return stdout
          .split(/\r?\n/)
          .map((line) => line.trim())
          .filter((line) => line.length > 0);
      }
    }

    const fallback = await execFileAsync("find", [".", "-type", "f"], {
      cwd: rootPath,
      maxBuffer: 20 * 1024 * 1024,
    });
    return fallback.stdout
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line.length > 0)
      .map((line) => (line.startsWith("./") ? line.slice(2) : line));
  }
}

function normalizeSearchTerms(query: string): string[] {
  return query
    .toLowerCase()
    .split(/\s+/)
    .map((term) => term.trim())
    .filter((term) => term.length > 0);
}

function scoreFilePath(relativePath: string, query: string): number {
  const normalized = query.toLowerCase();
  const lowerRelativePath = relativePath.toLowerCase();
  const baseName = path.basename(relativePath).toLowerCase();

  if (baseName === normalized) return 0;
  if (baseName.startsWith(normalized)) return 1;
  if (lowerRelativePath.startsWith(normalized)) return 2;
  if (lowerRelativePath.includes(normalized)) return 3;
  return 4;
}

function matchesSearchTerms(relativePath: string, terms: string[]): boolean {
  const lowerRelativePath = relativePath.toLowerCase();
  return terms.every((term) => lowerRelativePath.includes(term));
}

function toSearchEntry(currentPath: string, relativePath: string): FileEntry {
  const absolutePath = path.join(currentPath, relativePath);
  return {
    name: path.basename(relativePath),
    path: absolutePath,
    relativePath,
    kind: "file",
  };
}

export function fileRoutes(adapter: CodexAdapter) {
  return async function register(app: FastifyInstance): Promise<void> {
    app.get(
      "/api/hosts/:hostId/files/download",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const params = HostParams.safeParse(request.params);
        if (!params.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }
        if (params.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${params.data.hostId}' not found` });
        }

        const query = FileDownloadQuery.safeParse(request.query);
        if (!query.success) {
          return reply.status(400).send({ error: "Invalid query params" });
        }

        let download: DownloadScope | null = null;
        let metadata: FileDownloadMetadata | null = null;

        const downloadQuery = query.data;

        if (downloadQuery.source === "cwd") {
          download = await resolveSessionFileDownload(
            adapter,
            downloadQuery.sessionId,
            downloadQuery.path,
          );
          if (download) {
            metadata = {
              source: "cwd",
              sessionId: downloadQuery.sessionId,
              artifactId: null,
              fileName: download.fileName,
              mimeType: download.mimeType,
              sizeBytes: download.sizeBytes,
            };
          }
        } else if ("path" in downloadQuery && downloadQuery.source === "absolute") {
          const detail = await adapter.getSessionDetail(downloadQuery.sessionId);
          if (!detail) {
            return reply.status(404).send({
              error: `Session '${downloadQuery.sessionId}' not found`,
            });
          }

          download = await resolveAllowedAbsoluteDownload(downloadQuery.path);
          if (download) {
            metadata = {
              source: "absolute" as const,
              sessionId: downloadQuery.sessionId,
              artifactId: null,
              fileName: download.fileName,
              mimeType: download.mimeType,
              sizeBytes: download.sizeBytes,
            };
          }
        } else {
          const detail = await adapter.getSessionDetail(downloadQuery.sessionId);
          if (!detail) {
            return reply.status(404).send({
              error: `Session '${downloadQuery.sessionId}' not found`,
            });
          }

          const resolved = await resolveArtifactFileForDownload({
            hostId: params.data.hostId,
            sessionId: downloadQuery.sessionId,
            artifactId: downloadQuery.artifactId,
          });

          if (resolved) {
            download = {
              filePath: resolved.filePath,
              fileName: resolved.artifact.originalName,
              mimeType: resolved.artifact.mimeType,
              sizeBytes: resolved.artifact.sizeBytes,
            };
            metadata = {
              source: "artifact",
              sessionId: downloadQuery.sessionId,
              artifactId: downloadQuery.artifactId,
              fileName: resolved.artifact.originalName,
              mimeType: resolved.artifact.mimeType,
              sizeBytes: resolved.artifact.sizeBytes,
            };
          }
        }

        if (!download || !metadata) {
          return reply.status(404).send({ error: "File not found" });
        }

        // Downloads are stream-backed and may outlive the default socket idle
        // timeout on slower mobile connections.
        if (typeof request.raw.setTimeout === "function") {
          request.raw.setTimeout(0);
        }

        applyDownloadHeaders(reply, metadata);
        reply.type(metadata.mimeType);
        return reply.send(createReadStream(download.filePath));
      },
    );

    app.get(
      "/api/hosts/:hostId/files",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const params = HostParams.safeParse(request.params);
        if (!params.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }
        if (params.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${params.data.hostId}' not found` });
        }

        const query = ListFilesQuery.safeParse(request.query);
        if (!query.success) {
          return reply.status(400).send({ error: "Invalid query params" });
        }

        const scope = await resolveFileScope(adapter, query.data);
        if (!scope) {
          return reply.status(404).send({ error: "File scope not found" });
        }

        const entries = await listDirectoryEntries(scope.rootPath, scope.currentPath);
        const body: ListFilesResponse = {
          rootPath: scope.rootPath,
          currentPath: scope.currentPath,
          parentPath: scope.parentPath,
          entries,
        };
        return reply.send(body);
      },
    );

    app.get(
      "/api/hosts/:hostId/files/search",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const params = HostParams.safeParse(request.params);
        if (!params.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }
        if (params.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${params.data.hostId}' not found` });
        }

        const query = SearchFilesQuery.safeParse(request.query);
        if (!query.success) {
          return reply.status(400).send({ error: "Invalid query params" });
        }

        const scope = await resolveFileScope(adapter, query.data);
        if (!scope) {
          return reply.status(404).send({ error: "File scope not found" });
        }

        const limit = query.data.limit ?? FILE_SEARCH_LIMIT_DEFAULT;
        const terms = normalizeSearchTerms(query.data.query);
        const allRelativePaths = await collectFilePaths(scope.currentPath);
        const results = allRelativePaths
          .filter((relativePath) => matchesSearchTerms(relativePath, terms))
          .sort((left, right) => {
            const leftScore = scoreFilePath(left, query.data.query);
            const rightScore = scoreFilePath(right, query.data.query);
            if (leftScore !== rightScore) {
              return leftScore - rightScore;
            }
            return left.localeCompare(right, "zh-CN");
          })
          .slice(0, limit)
          .map((relativePath) => toSearchEntry(scope.currentPath, relativePath));

        const body: SearchFilesResponse = {
          rootPath: scope.rootPath,
          currentPath: scope.currentPath,
          parentPath: scope.parentPath,
          query: query.data.query,
          limit,
          results,
        };
        return reply.send(body);
      },
    );
  };
}
