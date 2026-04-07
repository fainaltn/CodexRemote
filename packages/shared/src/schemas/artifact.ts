import { z } from "zod";

// --- Enums ---

export const ArtifactKind = z.enum(["image", "file"]);
export type ArtifactKind = z.infer<typeof ArtifactKind>;

// --- Artifact entity (§6.2) ---

export const Artifact = z.object({
  id: z.string(),
  sessionId: z.string(),
  runId: z.string().nullable(),
  kind: ArtifactKind,
  originalName: z.string(),
  storedPath: z.string(),
  mimeType: z.string(),
  sizeBytes: z.number().int().nonnegative(),
  createdAt: z.string().datetime(),
});
export type Artifact = z.infer<typeof Artifact>;
