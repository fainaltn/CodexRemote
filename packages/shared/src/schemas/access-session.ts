import { z } from "zod";

// --- Access Session entity (§6.2) ---

export const AccessSession = z.object({
  tokenId: z.string(),
  createdAt: z.string().datetime(),
  expiresAt: z.string().datetime(),
  deviceLabel: z.string().nullable(),
});
export type AccessSession = z.infer<typeof AccessSession>;
