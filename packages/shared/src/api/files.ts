import { z } from "zod";
import {
  MAX_ID_LENGTH,
  MAX_PATH_LENGTH,
  MAX_FILE_SEARCH_QUERY_LENGTH,
} from "../limits.js";

// --- Shared file-browser contract ---

export const FileKind = z.enum(["file", "directory"]);
export type FileKind = z.infer<typeof FileKind>;

export const FileEntry = z.object({
  name: z.string(),
  path: z.string(),
  relativePath: z.string(),
  kind: FileKind,
});
export type FileEntry = z.infer<typeof FileEntry>;

export const FileScopeQuery = z.object({
  sessionId: z.string().max(MAX_ID_LENGTH).optional(),
  cwd: z.string().min(1).max(MAX_PATH_LENGTH).optional(),
  path: z.string().max(MAX_PATH_LENGTH).optional(),
});
export type FileScopeQuery = z.infer<typeof FileScopeQuery>;

export const ListFilesQuery = FileScopeQuery;
export type ListFilesQuery = z.infer<typeof ListFilesQuery>;

export const ListFilesResponse = z.object({
  rootPath: z.string(),
  currentPath: z.string(),
  parentPath: z.string().nullable(),
  entries: z.array(FileEntry),
});
export type ListFilesResponse = z.infer<typeof ListFilesResponse>;

export const SearchFilesQuery = FileScopeQuery.extend({
  query: z.string().min(1).max(MAX_FILE_SEARCH_QUERY_LENGTH),
  limit: z.number().int().positive().max(100).optional(),
});
export type SearchFilesQuery = z.infer<typeof SearchFilesQuery>;

export const SearchFilesResponse = z.object({
  rootPath: z.string(),
  currentPath: z.string(),
  parentPath: z.string().nullable(),
  query: z.string(),
  limit: z.number().int().positive(),
  results: z.array(FileEntry),
});
export type SearchFilesResponse = z.infer<typeof SearchFilesResponse>;

// --- GET /api/hosts/:hostId/files/download ---

export const FileDownloadSource = z.enum(["cwd", "artifact", "absolute"]);
export type FileDownloadSource = z.infer<typeof FileDownloadSource>;

const CwdFileDownloadQuery = z
  .object({
    source: z.literal("cwd"),
    sessionId: z.string().max(MAX_ID_LENGTH),
    path: z.string().min(1).max(MAX_PATH_LENGTH),
  })
  .strict();

const ArtifactFileDownloadQuery = z
  .object({
    source: z.literal("artifact"),
    sessionId: z.string().max(MAX_ID_LENGTH),
    artifactId: z.string().max(MAX_ID_LENGTH),
  })
  .strict();

const AbsoluteFileDownloadQuery = z
  .object({
    source: z.literal("absolute"),
    sessionId: z.string().max(MAX_ID_LENGTH),
    path: z.string().min(1).max(MAX_PATH_LENGTH),
  })
  .strict();

export const FileDownloadQuery = z.discriminatedUnion("source", [
  CwdFileDownloadQuery,
  ArtifactFileDownloadQuery,
  AbsoluteFileDownloadQuery,
]);
export type FileDownloadQuery = z.infer<typeof FileDownloadQuery>;

export const FileDownloadMetadata = z.object({
  source: FileDownloadSource,
  sessionId: z.string().max(MAX_ID_LENGTH),
  artifactId: z.string().max(MAX_ID_LENGTH).nullable(),
  fileName: z.string(),
  mimeType: z.string(),
  sizeBytes: z.number().int().nonnegative(),
});
export type FileDownloadMetadata = z.infer<typeof FileDownloadMetadata>;
