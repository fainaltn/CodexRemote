import { beforeEach, describe, expect, it } from "vitest";
import { closeDb, getDb, initDb } from "../db.js";
import {
  claimPairingCode,
  createPairingCode,
  reconnectTrustedClient,
  revokeTrustedClient,
  listTrustedClients,
} from "../pairing/store.js";
import { cleanTables } from "./helpers.js";

describe("Pairing store", () => {
  beforeEach(() => {
    initDb();
    cleanTables();
  });

  it("creates a one-time pairing code and persists only the hashed form", () => {
    const offer = createPairingCode();

    expect(offer.code).toMatch(/^[A-Z0-9]{4}-[A-Z0-9]{4}$/);

    const db = getDb();
    const row = db
      .prepare(
        "SELECT code_hash, code_display, claimed_at FROM pairing_codes WHERE code_display = ?",
      )
      .get(offer.code) as
      | { code_hash: string; code_display: string; claimed_at: string | null }
      | undefined;

    expect(row).toBeDefined();
    expect(row!.code_display).toBe(offer.code);
    expect(row!.code_hash).not.toBe(offer.code);
    expect(row!.claimed_at).toBeNull();
  });

  it("claims a pairing code exactly once and issues a trusted client", () => {
    const offer = createPairingCode();

    const firstClaim = claimPairingCode(offer.code, "Pixel 9");
    expect(firstClaim.ok).toBe(true);
    if (!firstClaim.ok) {
      throw new Error("expected claim success");
    }
    expect(firstClaim.trustedClient.deviceLabel).toBe("Pixel 9");
    expect(firstClaim.trustedClient.clientSecret).toBeDefined();
    expect(firstClaim.token.tokenId).toBeDefined();

    const secondClaim = claimPairingCode(offer.code, "Pixel 9");
    expect(secondClaim).toEqual({
      ok: false,
      reason: "code_already_claimed",
    });

    const clients = listTrustedClients();
    expect(clients).toHaveLength(1);
    expect(clients[0].deviceLabel).toBe("Pixel 9");
  });

  it("reconnects with the persisted trusted client secret", () => {
    const offer = createPairingCode();
    const claim = claimPairingCode(offer.code, "Pixel 9");
    expect(claim.ok).toBe(true);
    if (!claim.ok) {
      throw new Error("expected claim success");
    }

    const reconnect = reconnectTrustedClient(
      claim.trustedClient.clientId,
      claim.trustedClient.clientSecret,
      "Pixel 9 Pro",
    );

    expect(reconnect.ok).toBe(true);
    if (!reconnect.ok) {
      throw new Error("expected reconnect success");
    }
    expect(reconnect.trustedClient.clientId).toBe(claim.trustedClient.clientId);
    expect(reconnect.trustedClient.deviceLabel).toBe("Pixel 9 Pro");
    expect(reconnect.token.tokenId).not.toBe(claim.token.tokenId);
  });

  it("rejects reconnect when the trusted client is revoked", () => {
    const offer = createPairingCode();
    const claim = claimPairingCode(offer.code, "Pixel 9");
    expect(claim.ok).toBe(true);
    if (!claim.ok) {
      throw new Error("expected claim success");
    }

    expect(revokeTrustedClient(claim.trustedClient.clientId)).toBe(true);
    expect(reconnectTrustedClient(
      claim.trustedClient.clientId,
      claim.trustedClient.clientSecret,
    )).toEqual({
      ok: false,
      reason: "client_revoked",
    });
  });

  it("reports secret mismatch separately from missing clients", () => {
    const offer = createPairingCode();
    const claim = claimPairingCode(offer.code, "Pixel 9");
    expect(claim.ok).toBe(true);
    if (!claim.ok) {
      throw new Error("expected claim success");
    }

    expect(reconnectTrustedClient(
      claim.trustedClient.clientId,
      "wrong-secret",
    )).toEqual({
      ok: false,
      reason: "client_secret_mismatch",
    });
  });

  it("reports missing trusted clients distinctly", () => {
    expect(reconnectTrustedClient(
      "missing-client",
      "missing-secret",
    )).toEqual({
      ok: false,
      reason: "client_not_found",
    });
  });

  it("reports invalid and expired pairing codes separately", () => {
    expect(claimPairingCode("nope")).toEqual({
      ok: false,
      reason: "invalid_code_format",
    });

    const offer = createPairingCode();
    getDb()
      .prepare(`UPDATE pairing_codes SET expires_at = ? WHERE code_display = ?`)
      .run(new Date(Date.now() - 60_000).toISOString(), offer.code);

    expect(claimPairingCode(offer.code, "Pixel 9")).toEqual({
      ok: false,
      reason: "code_expired",
    });
  });

  it("reconnect still works after a simulated service restart", () => {
    const offer = createPairingCode();
    const claim = claimPairingCode(offer.code, "Pixel 9");
    expect(claim.ok).toBe(true);
    if (!claim.ok) {
      throw new Error("expected claim success");
    }

    closeDb();
    initDb();

    const reconnect = reconnectTrustedClient(
      claim.trustedClient.clientId,
      claim.trustedClient.clientSecret,
      "Pixel 9 Pro",
    );

    expect(reconnect.ok).toBe(true);
    if (!reconnect.ok) {
      throw new Error("expected reconnect success after restart");
    }
    expect(reconnect.trustedClient.clientId).toBe(claim.trustedClient.clientId);
  });
});
