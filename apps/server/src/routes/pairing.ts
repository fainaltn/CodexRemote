import { networkInterfaces } from "node:os";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import {
  PairingCodeResponse,
  PairingClaimRequest,
  PairingClaimResponse,
  TrustedReconnectRequest,
  TrustedReconnectResponse,
} from "@codexremote/shared";
import {
  claimPairingCode,
  createPairingCode,
  reconnectTrustedClient,
} from "../pairing/store.js";

function isLoopbackAddress(address: string | undefined): boolean {
  if (!address) return false;
  const normalized = address.toLowerCase().replace(/^::ffff:/, "");
  return (
    normalized === "127.0.0.1" ||
    normalized === "::1" ||
    normalized === "0:0:0:0:0:0:0:1"
  );
}

function normalizeAddress(address: string | undefined): string | null {
  if (!address) return null;
  return address.toLowerCase().replace(/^::ffff:/, "");
}

function getLocalInterfaceAddresses(): Set<string> {
  const interfaces = networkInterfaces();
  const addresses = new Set<string>();

  for (const entries of Object.values(interfaces)) {
    for (const entry of entries ?? []) {
      const candidate = normalizeAddress(entry.address);
      if (candidate) addresses.add(candidate);
    }
  }

  return addresses;
}

function isLocalInterfaceAddress(
  address: string | undefined,
  localInterfaceAddresses: ReadonlySet<string> = getLocalInterfaceAddresses(),
): boolean {
  const normalized = normalizeAddress(address);
  if (!normalized) return false;
  return localInterfaceAddresses.has(normalized);
}

export function isLocalOperatorAddress(
  localIp: string | undefined,
  socketIp: string | undefined,
  localInterfaceAddresses: ReadonlySet<string> = getLocalInterfaceAddresses(),
): boolean {
  return (
    isLoopbackAddress(localIp) ||
    isLoopbackAddress(socketIp) ||
    isLocalInterfaceAddress(localIp, localInterfaceAddresses) ||
    isLocalInterfaceAddress(socketIp, localInterfaceAddresses)
  );
}

function requireLocalOperator(
  request: FastifyRequest,
  reply: FastifyReply,
): boolean {
  const localIp = request.ip;
  const socketIp = request.raw.socket.remoteAddress;
  if (isLocalOperatorAddress(localIp, socketIp)) {
    return true;
  }

  reply.status(403).send({
    error: "Pairing code generation is local-only",
  });
  return false;
}

function tokenResponse(
  token: { tokenId: string; expiresAt: string },
): { token: string; expiresAt: string } {
  return {
    token: token.tokenId,
    expiresAt: token.expiresAt,
  };
}

export function pairingRoutes() {
  return async function register(app: FastifyInstance): Promise<void> {
    // --- POST /api/pairing/code ---
    app.post("/api/pairing/code", async (request, reply) => {
      if (!requireLocalOperator(request, reply)) return;
      const offer = createPairingCode();
      const body: PairingCodeResponse = {
        code: offer.code,
        expiresAt: offer.expiresAt,
      };
      return reply.send(body);
    });

    // --- POST /api/pairing/claim ---
    app.post("/api/pairing/claim", async (request, reply) => {
      const parsed = PairingClaimRequest.safeParse(request.body);
      if (!parsed.success) {
        return reply.status(400).send({ error: "Invalid request body" });
      }

      const result = claimPairingCode(
        parsed.data.code,
        parsed.data.deviceLabel,
      );
      if (!result) {
        return reply.status(404).send({ error: "Pairing code not found or expired" });
      }

      const body: PairingClaimResponse = {
        ...tokenResponse(result.token),
        trustedClient: result.trustedClient,
      };
      return reply.send(body);
    });

    // --- POST /api/auth/reconnect ---
    app.post("/api/auth/reconnect", async (request, reply) => {
      const parsed = TrustedReconnectRequest.safeParse(request.body);
      if (!parsed.success) {
        return reply.status(400).send({ error: "Invalid request body" });
      }

      const result = reconnectTrustedClient(
        parsed.data.clientId,
        parsed.data.clientSecret,
        parsed.data.deviceLabel,
      );
      if (!result) {
        return reply
          .status(401)
          .send({ error: "Trusted client credentials are invalid" });
      }

      const body: TrustedReconnectResponse = {
        ...tokenResponse(result.token),
        trustedClient: result.trustedClient,
      };
      return reply.send(body);
    });
  };
}
