import { z } from "zod";
import { Host } from "../schemas/host.js";
import { MAX_ID_LENGTH } from "../limits.js";

// --- GET /api/hosts ---

export const ListHostsResponse = z.object({
  hosts: z.array(Host),
});
export type ListHostsResponse = z.infer<typeof ListHostsResponse>;

// --- GET /api/hosts/:hostId ---

export const GetHostResponse = Host;
export type GetHostResponse = z.infer<typeof GetHostResponse>;

// --- Route params ---

export const HostParams = z.object({
  hostId: z.string().max(MAX_ID_LENGTH),
});
export type HostParams = z.infer<typeof HostParams>;
