/**
 * Shared auth middleware for route protection.
 *
 * Provides a Fastify preHandler hook ({@link requireAuth}) that enforces
 * Bearer-token authentication on any route or scope it is attached to.
 *
 * The helper {@link extractBearerToken} is also exported so that routes
 * which need the raw token value (e.g. logout, session-check) can reuse
 * the same parsing logic without duplicating it.
 */

import type { FastifyRequest, FastifyReply } from "fastify";
import { verifyToken } from "./store.js";

/**
 * Extract the Bearer token from the Authorization header.
 * Returns null when the header is missing or not in `Bearer <token>` form.
 */
export function extractBearerToken(request: FastifyRequest): string | null {
  const header = request.headers.authorization;
  if (!header?.startsWith("Bearer ")) return null;
  return header.slice(7);
}

/**
 * Fastify `preHandler` hook that gates access behind a valid auth token.
 *
 * Attach to a scope or individual route:
 *
 * ```ts
 * app.addHook("preHandler", requireAuth);
 * ```
 *
 * On success the request continues normally.
 * On failure a `401` response is sent with the same error shape used by
 * the auth route group so clients see a consistent contract everywhere.
 */
export async function requireAuth(
  request: FastifyRequest,
  reply: FastifyReply,
): Promise<void> {
  const tokenId = extractBearerToken(request);
  if (!tokenId) {
    return reply.status(401).send({ error: "Missing or invalid token" });
  }

  const session = verifyToken(tokenId);
  if (!session) {
    return reply.status(401).send({ error: "Token expired or invalid" });
  }
}
