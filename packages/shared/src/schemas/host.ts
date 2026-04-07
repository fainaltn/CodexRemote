import { z } from "zod";

// --- Enums ---

export const HostKind = z.enum(["local", "remote"]);
export type HostKind = z.infer<typeof HostKind>;

export const HostStatus = z.enum(["online", "offline", "unknown"]);
export type HostStatus = z.infer<typeof HostStatus>;

// --- Host entity (§6.2) ---

export const Host = z.object({
  id: z.string(),
  label: z.string(),
  kind: HostKind,
  baseUrl: z.string().url().nullable(),
  tailscaleIp: z.string().nullable(),
  status: HostStatus,
  lastSeenAt: z.string().datetime().nullable(),
});
export type Host = z.infer<typeof Host>;
