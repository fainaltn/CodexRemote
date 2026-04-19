import { networkInterfaces } from "node:os";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import {
  PairingCodeResponse,
  PairingClaimFailureResponse,
  PairingClaimRequest,
  PairingClaimResponse,
  TrustedReconnectRequest,
  TrustedReconnectResponse,
} from "@findeck/shared";
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
      if (!result.ok) {
        const body: PairingClaimFailureResponse = {
          error: pairingClaimFailureMessage(result.reason),
          reason: result.reason,
        };
        app.log.warn(
          {
            event: "pairing_claim_failed",
            reason: result.reason,
            deviceLabel: parsed.data.deviceLabel ?? null,
          },
          "Pairing claim failed",
        );
        return reply.status(pairingClaimFailureStatus(result.reason)).send(body);
      }

      const body: PairingClaimResponse = {
        ...tokenResponse(result.token),
        trustedClient: result.trustedClient,
      };
      app.log.info(
        {
          event: "pairing_claim_succeeded",
          clientId: result.trustedClient.clientId,
          deviceLabel: result.trustedClient.deviceLabel ?? null,
        },
        "Pairing claim succeeded",
      );
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
      if (!result.ok) {
        const body = {
          error: trustedReconnectFailureMessage(result.reason),
          reason: result.reason,
          recoveryAction: "re_pair" as const,
        };
        app.log.warn(
          {
            event: "trusted_reconnect_failed",
            reason: result.reason,
            recoveryAction: body.recoveryAction,
            clientId: parsed.data.clientId,
            deviceLabel: parsed.data.deviceLabel ?? null,
          },
          "Trusted reconnect failed",
        );
        return reply.status(401).send(body);
      }

      const body: TrustedReconnectResponse = {
        ...tokenResponse(result.token),
        trustedClient: result.trustedClient,
      };
      app.log.info(
        {
          event: "trusted_reconnect_succeeded",
          clientId: result.trustedClient.clientId,
          deviceLabel: result.trustedClient.deviceLabel ?? null,
        },
        "Trusted reconnect succeeded",
      );
      return reply.send(body);
    });
  };
}

function pairingClaimFailureStatus(
  reason: "invalid_code_format" | "code_not_found" | "code_expired" | "code_already_claimed",
): number {
  switch (reason) {
    case "invalid_code_format":
      return 400;
    case "code_not_found":
      return 404;
    case "code_expired":
      return 410;
    case "code_already_claimed":
      return 409;
  }
}

function pairingClaimFailureMessage(
  reason: "invalid_code_format" | "code_not_found" | "code_expired" | "code_already_claimed",
): string {
  switch (reason) {
    case "invalid_code_format":
      return "Pairing code format is invalid";
    case "code_not_found":
      return "Pairing code was not found on this host";
    case "code_expired":
      return "Pairing code has expired";
    case "code_already_claimed":
      return "Pairing code was already claimed";
  }
}

function trustedReconnectFailureMessage(
  reason: "client_not_found" | "client_revoked" | "client_secret_mismatch",
): string {
  switch (reason) {
    case "client_not_found":
      return "Trusted reconnect is not registered on this host";
    case "client_revoked":
      return "Trusted reconnect was revoked on this host";
    case "client_secret_mismatch":
      return "Trusted reconnect secret no longer matches this host";
  }
}
