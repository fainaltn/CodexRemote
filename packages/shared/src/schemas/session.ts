import { z } from "zod";

// --- Session entity (§6.2) ---

export const Session = z.object({
  id: z.string(),
  hostId: z.string(),
  provider: z.literal("codex"),
  codexSessionId: z.string().nullable(),
  title: z.string(),
  cwd: z.string().nullable(),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
  lastPreview: z.string().nullable(),
  archivedAt: z.string().datetime().nullable(),
});
export type Session = z.infer<typeof Session>;
