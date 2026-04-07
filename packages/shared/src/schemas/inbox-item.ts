import { z } from "zod";

export const InboxItemKind = z.enum(["link", "file"]);
export type InboxItemKind = z.infer<typeof InboxItemKind>;

export const InboxItemStatus = z.enum(["received"]);
export type InboxItemStatus = z.infer<typeof InboxItemStatus>;

export const InboxItem = z.object({
  id: z.string(),
  hostId: z.string(),
  kind: InboxItemKind,
  status: InboxItemStatus,
  url: z.string().nullable(),
  title: z.string().nullable(),
  originalName: z.string().nullable(),
  note: z.string().nullable(),
  source: z.string().nullable(),
  storedPath: z.string().nullable(),
  mimeType: z.string().nullable(),
  sizeBytes: z.number().int().nonnegative().nullable(),
  createdAt: z.string().datetime(),
  submissionPath: z.string().nullable().optional(),
  submissionId: z.string().nullable().optional(),
  stagingDir: z.string().nullable().optional(),
  contract: z.string().nullable().optional(),
  captureSessions: z.array(z.record(z.string(), z.unknown())).optional(),
  retryAttempts: z.array(z.record(z.string(), z.unknown())).optional(),
  retryPolicy: z.record(z.string(), z.unknown()).nullable().optional(),
  hasReviewBundle: z.boolean().optional(),
  hasSkillRunbook: z.boolean().optional(),
});
export type InboxItem = z.infer<typeof InboxItem>;
