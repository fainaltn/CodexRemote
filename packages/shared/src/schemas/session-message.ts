import { z } from "zod";

export const SessionMessage = z.object({
  id: z.string(),
  role: z.enum(["user", "assistant", "system"]),
  kind: z.enum(["message", "reasoning"]).default("message"),
  text: z.string(),
  createdAt: z.string().datetime(),
});
export type SessionMessage = z.infer<typeof SessionMessage>;
