import { z } from "zod";

export const SessionMessage = z.object({
  id: z.string(),
  role: z.enum(["user", "assistant", "system"]),
  kind: z.enum(["message", "reasoning"]).default("message"),
  turnId: z.string().min(1).optional(),
  itemId: z.string().min(1).optional(),
  orderIndex: z.number().int().nonnegative(),
  isStreaming: z.boolean().default(false),
  text: z.string(),
  createdAt: z.string().datetime(),
});
export type SessionMessage = z.infer<typeof SessionMessage>;
