import crypto from "node:crypto";
import { createToken, type StoredToken } from "../auth/store.js";
import { getDb } from "../db.js";

export interface PairingCodeRecord {
  code: string;
  expiresAt: string;
  createdAt: string;
}

export interface TrustedClientRecord {
  clientId: string;
  deviceLabel: string | null;
  createdAt: string;
  updatedAt: string;
  lastSeenAt: string | null;
  revokedAt: string | null;
}

export interface TrustedClientCredentials extends TrustedClientRecord {
  clientSecret: string;
}

export interface PairingClaimRecord {
  token: StoredToken;
  trustedClient: TrustedClientCredentials;
}

export type PairingClaimFailureReason =
  | "invalid_code_format"
  | "code_not_found"
  | "code_expired"
  | "code_already_claimed";

export interface PairingClaimFailure {
  ok: false;
  reason: PairingClaimFailureReason;
}

export interface PairingClaimSuccess {
  ok: true;
  token: StoredToken;
  trustedClient: TrustedClientCredentials;
}

export type PairingClaimResult = PairingClaimFailure | PairingClaimSuccess;

export interface TrustedReconnectRecord {
  token: StoredToken;
  trustedClient: TrustedClientRecord;
}

export type TrustedReconnectFailureReason =
  | "client_not_found"
  | "client_revoked"
  | "client_secret_mismatch";

export interface TrustedReconnectFailure {
  ok: false;
  reason: TrustedReconnectFailureReason;
}

export interface TrustedReconnectSuccess {
  ok: true;
  token: StoredToken;
  trustedClient: TrustedClientRecord;
}

export type TrustedReconnectResult =
  | TrustedReconnectFailure
  | TrustedReconnectSuccess;

const PAIRING_CODE_LENGTH = 8;
const PAIRING_CODE_TTL_MS = 15 * 60 * 1000;
const TRUSTED_CLIENT_SECRET_BYTES = 32;

const PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const PAIRING_CODE_RE = /^[A-Z0-9]+$/;

function nowIso(): string {
  return new Date().toISOString();
}

function hashValue(value: string): string {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function normalizePairingCode(code: string): string {
  return code.toUpperCase().replace(/[^A-Z0-9]/g, "");
}

function formatPairingCode(code: string): string {
  if (code.length <= 4) return code;
  return `${code.slice(0, 4)}-${code.slice(4)}`;
}

function generatePairingCode(): string {
  const bytes = crypto.randomBytes(PAIRING_CODE_LENGTH);
  let code = "";
  for (let i = 0; i < PAIRING_CODE_LENGTH; i++) {
    code += PAIRING_ALPHABET[bytes[i] % PAIRING_ALPHABET.length];
  }
  return code;
}

function generateClientSecret(): string {
  return crypto.randomBytes(TRUSTED_CLIENT_SECRET_BYTES).toString("base64url");
}

function isPairingCodeValid(code: string): boolean {
  return code.length === PAIRING_CODE_LENGTH && PAIRING_CODE_RE.test(code);
}

function pruneExpiredPairingCodes(db: ReturnType<typeof getDb>, now: string): void {
  db.prepare(
    `DELETE FROM pairing_codes
     WHERE claimed_at IS NULL AND expires_at <= ?`,
  ).run(now);
}

function readTrustedClientRow(
  row: {
    client_id: string;
    device_label: string | null;
    created_at: string;
    updated_at: string;
    last_seen_at: string | null;
    revoked_at: string | null;
  },
): TrustedClientRecord {
  return {
    clientId: row.client_id,
    deviceLabel: row.device_label,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    lastSeenAt: row.last_seen_at,
    revokedAt: row.revoked_at,
  };
}

export function createPairingCode(): PairingCodeRecord {
  const db = getDb();
  const createdAt = nowIso();
  const expiresAt = new Date(Date.now() + PAIRING_CODE_TTL_MS).toISOString();
  const code = generatePairingCode();
  const codeHash = hashValue(code);
  const codeId = crypto.randomUUID();

  db.transaction(() => {
    db.prepare(`DELETE FROM pairing_codes WHERE claimed_at IS NULL`).run();
    db.prepare(
      `INSERT INTO pairing_codes (
        code_id, code_hash, code_display, created_at, expires_at
      ) VALUES (?, ?, ?, ?, ?)`,
    ).run(codeId, codeHash, formatPairingCode(code), createdAt, expiresAt);
  })();

  return {
    code: formatPairingCode(code),
    expiresAt,
    createdAt,
  };
}

export function claimPairingCode(
  codeInput: string,
  deviceLabel?: string,
): PairingClaimResult {
  const db = getDb();
  const normalizedCode = normalizePairingCode(codeInput);
  if (!isPairingCodeValid(normalizedCode)) {
    return {
      ok: false,
      reason: "invalid_code_format",
    };
  }

  const now = nowIso();
  const codeHash = hashValue(normalizedCode);

  return db.transaction(() => {
    const pairingRow = db
      .prepare(
        `SELECT code_id, code_display, created_at, expires_at, claimed_at
         FROM pairing_codes
         WHERE code_hash = ?`,
      )
      .get(codeHash) as
      | {
          code_id: string;
          code_display: string;
          created_at: string;
          expires_at: string;
          claimed_at: string | null;
        }
      | undefined;

    if (!pairingRow) {
      const failure: PairingClaimFailure = {
        ok: false,
        reason: "code_not_found",
      };
      return failure;
    }

    if (pairingRow.claimed_at !== null) {
      const failure: PairingClaimFailure = {
        ok: false,
        reason: "code_already_claimed",
      };
      return failure;
    }

    if (new Date(pairingRow.expires_at) <= new Date(now)) {
      db.prepare(`DELETE FROM pairing_codes WHERE code_id = ?`).run(pairingRow.code_id);
      const failure: PairingClaimFailure = {
        ok: false,
        reason: "code_expired",
      };
      return failure;
    }

    const clientId = crypto.randomUUID();
    const clientSecret = generateClientSecret();
    const clientSecretHash = hashValue(clientSecret);
    const trustedDeviceLabel = deviceLabel ?? null;

    db.prepare(
      `INSERT INTO trusted_clients (
        client_id, client_secret_hash, device_label,
        created_at, updated_at, last_seen_at, revoked_at
      ) VALUES (?, ?, ?, ?, ?, ?, NULL)`,
    ).run(
      clientId,
      clientSecretHash,
      trustedDeviceLabel,
      now,
      now,
      now,
    );

    db.prepare(
      `UPDATE pairing_codes
       SET claimed_at = ?, claimed_by_client_id = ?
       WHERE code_id = ?`,
    ).run(now, clientId, pairingRow.code_id);

    const token = createToken(trustedDeviceLabel ?? undefined);
    const success: PairingClaimSuccess = {
      ok: true,
      token,
      trustedClient: {
        clientId,
        clientSecret,
        deviceLabel: trustedDeviceLabel,
        createdAt: now,
        updatedAt: now,
        lastSeenAt: now,
        revokedAt: null,
      },
    };

    pruneExpiredPairingCodes(db, now);
    return success;
  })();
}

export function reconnectTrustedClient(
  clientId: string,
  clientSecret: string,
  deviceLabel?: string,
): TrustedReconnectResult {
  const db = getDb();
  const now = nowIso();
  const secretHash = hashValue(clientSecret);

  return db.transaction(() => {
    const trustedRow = db
      .prepare(
        `SELECT client_id, client_secret_hash, device_label, created_at, updated_at, last_seen_at, revoked_at
         FROM trusted_clients
         WHERE client_id = ?`,
      )
      .get(clientId) as
      | {
          client_id: string;
          client_secret_hash: string;
          device_label: string | null;
          created_at: string;
          updated_at: string;
          last_seen_at: string | null;
          revoked_at: string | null;
        }
      | undefined;

    if (!trustedRow) {
      const failure: TrustedReconnectFailure = {
        ok: false,
        reason: "client_not_found",
      };
      return failure;
    }

    if (trustedRow.revoked_at !== null) {
      const failure: TrustedReconnectFailure = {
        ok: false,
        reason: "client_revoked",
      };
      return failure;
    }

    const provided = Buffer.from(secretHash, "hex");
    const stored = Buffer.from(trustedRow.client_secret_hash, "hex");
    if (provided.length !== stored.length || !crypto.timingSafeEqual(provided, stored)) {
      const failure: TrustedReconnectFailure = {
        ok: false,
        reason: "client_secret_mismatch",
      };
      return failure;
    }

    const nextLabel = deviceLabel ?? trustedRow.device_label;
    db.prepare(
      `UPDATE trusted_clients
       SET device_label = ?, updated_at = ?, last_seen_at = ?
       WHERE client_id = ?`,
    ).run(nextLabel, now, now, clientId);

    const token = createToken(nextLabel ?? undefined);
    const success: TrustedReconnectSuccess = {
      ok: true,
      token,
      trustedClient: {
        clientId: trustedRow.client_id,
        deviceLabel: nextLabel,
        createdAt: trustedRow.created_at,
        updatedAt: now,
        lastSeenAt: now,
        revokedAt: null,
      },
    };
    return success;
  })();
}

export function listTrustedClients(): TrustedClientRecord[] {
  const db = getDb();
  const rows = db
    .prepare(
      `SELECT client_id, device_label, created_at, updated_at, last_seen_at, revoked_at
       FROM trusted_clients
       ORDER BY last_seen_at DESC, created_at DESC`,
    )
    .all() as Array<{
    client_id: string;
    device_label: string | null;
    created_at: string;
    updated_at: string;
    last_seen_at: string | null;
    revoked_at: string | null;
  }>;
  return rows.map(readTrustedClientRow);
}

export function revokeTrustedClient(clientId: string): boolean {
  const db = getDb();
  const now = nowIso();
  const result = db
    .prepare(
      `UPDATE trusted_clients
       SET revoked_at = ?, updated_at = ?
       WHERE client_id = ? AND revoked_at IS NULL`,
    )
    .run(now, now, clientId);
  return result.changes > 0;
}
