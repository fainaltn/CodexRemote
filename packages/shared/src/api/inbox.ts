import { z } from "zod";
import { InboxItem } from "../schemas/inbox-item.js";
import {
  MAX_ID_LENGTH,
  MAX_INBOX_NOTE_LENGTH,
  MAX_INBOX_SOURCE_LENGTH,
  MAX_INBOX_TITLE_LENGTH,
  MAX_URL_LENGTH,
} from "../limits.js";

export const InboxParams = z.object({
  hostId: z.string().max(MAX_ID_LENGTH),
});
export type InboxParams = z.infer<typeof InboxParams>;

export const SubmitInboxLinkRequest = z.object({
  url: z.string().url().max(MAX_URL_LENGTH),
  title: z.string().max(MAX_INBOX_TITLE_LENGTH).optional(),
  note: z.string().max(MAX_INBOX_NOTE_LENGTH).optional(),
  source: z.string().max(MAX_INBOX_SOURCE_LENGTH).optional(),
});
export type SubmitInboxLinkRequest = z.infer<typeof SubmitInboxLinkRequest>;

export const SubmitInboxLinkResponse = InboxItem;
export type SubmitInboxLinkResponse = z.infer<typeof SubmitInboxLinkResponse>;

export const InboxFileFields = z.object({
  note: z.string().max(MAX_INBOX_NOTE_LENGTH).optional(),
  source: z.string().max(MAX_INBOX_SOURCE_LENGTH).optional(),
});
export type InboxFileFields = z.infer<typeof InboxFileFields>;

export const SubmitInboxFileResponse = InboxItem;
export type SubmitInboxFileResponse = z.infer<typeof SubmitInboxFileResponse>;

export const SubmitInboxSubmissionResponse = InboxItem;
export type SubmitInboxSubmissionResponse = z.infer<typeof SubmitInboxSubmissionResponse>;

export const ListInboxResponse = z.object({
  items: z.array(InboxItem),
});
export type ListInboxResponse = z.infer<typeof ListInboxResponse>;
