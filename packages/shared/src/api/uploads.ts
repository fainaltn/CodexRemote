import { z } from "zod";
import { Artifact } from "../schemas/artifact.js";
import { MAX_ID_LENGTH } from "../limits.js";

// --- Route params ---

/** POST /api/hosts/:hostId/uploads */
export const UploadParams = z.object({
  hostId: z.string().max(MAX_ID_LENGTH),
});
export type UploadParams = z.infer<typeof UploadParams>;

/** POST /api/hosts/:hostId/sessions/:sessionId/artifacts */
export const AttachArtifactParams = z.object({
  hostId: z.string().max(MAX_ID_LENGTH),
  sessionId: z.string().max(MAX_ID_LENGTH),
});
export type AttachArtifactParams = z.infer<typeof AttachArtifactParams>;

// --- POST /api/hosts/:hostId/uploads ---
// Multipart upload — request body is form-data, not JSON.
// Response returns the stored artifact metadata.

export const UploadResponse = Artifact;
export type UploadResponse = z.infer<typeof UploadResponse>;

// --- POST /api/hosts/:hostId/sessions/:sessionId/artifacts ---
// Attach an already-uploaded artifact to a session/run.

export const AttachArtifactRequest = z.object({
  artifactId: z.string().max(MAX_ID_LENGTH),
  runId: z.string().max(MAX_ID_LENGTH).optional(),
});
export type AttachArtifactRequest = z.infer<typeof AttachArtifactRequest>;

export const AttachArtifactResponse = Artifact;
export type AttachArtifactResponse = z.infer<typeof AttachArtifactResponse>;
