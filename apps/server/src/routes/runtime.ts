import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import {
  RuntimeCatalogResponse,
  RuntimeParams,
  RuntimeUsageResponse,
} from "@codexremote/shared";
import {
  normalizeRuntimeBridgeError,
  readRuntimeCatalog,
  readRuntimeUsage,
} from "../codex/runtime.js";
import { LOCAL_HOST_ID } from "../constants.js";

function sendRuntimeError(reply: FastifyReply, error: unknown, label: string) {
  const normalized = normalizeRuntimeBridgeError(error);
  return reply.status(502).send({
    error: `${label} failed`,
    detail: normalized,
  });
}

export function runtimeRoutes() {
  return async function register(app: FastifyInstance): Promise<void> {
    app.get("/api/hosts/:hostId/runtime/catalog", async (request: FastifyRequest, reply) => {
      const params = RuntimeParams.safeParse(request.params);
      if (!params.success) {
        return reply.status(400).send({ error: "Invalid route params" });
      }
      if (params.data.hostId !== LOCAL_HOST_ID) {
        return reply.status(404).send({ error: `Host '${params.data.hostId}' not found` });
      }
      try {
        const catalog = await readRuntimeCatalog();
        return reply.send(RuntimeCatalogResponse.parse({
          ...catalog,
          fetchedAt: new Date().toISOString(),
        }));
      } catch (error) {
        return sendRuntimeError(reply, error, "Read runtime catalog");
      }
    });

    app.get("/api/hosts/:hostId/runtime/usage", async (request: FastifyRequest, reply) => {
      const params = RuntimeParams.safeParse(request.params);
      if (!params.success) {
        return reply.status(400).send({ error: "Invalid route params" });
      }
      if (params.data.hostId !== LOCAL_HOST_ID) {
        return reply.status(404).send({ error: `Host '${params.data.hostId}' not found` });
      }
      try {
        const usage = await readRuntimeUsage();
        return reply.send(RuntimeUsageResponse.parse({
          ...usage,
          fetchedAt: new Date().toISOString(),
        }));
      } catch (error) {
        return sendRuntimeError(reply, error, "Read runtime usage");
      }
    });

    app.get("/api/hosts/:hostId/runtime/rate-limits", async (request: FastifyRequest, reply) => {
      const params = RuntimeParams.safeParse(request.params);
      if (!params.success) {
        return reply.status(400).send({ error: "Invalid route params" });
      }
      if (params.data.hostId !== LOCAL_HOST_ID) {
        return reply.status(404).send({ error: `Host '${params.data.hostId}' not found` });
      }
      try {
        const usage = await readRuntimeUsage();
        return reply.send(RuntimeUsageResponse.parse({
          rateLimits: usage.rateLimits,
          rateLimitsByLimitId: usage.rateLimitsByLimitId,
          fetchedAt: new Date().toISOString(),
        }));
      } catch (error) {
        return sendRuntimeError(reply, error, "Read runtime rate limits");
      }
    });
  };
}
