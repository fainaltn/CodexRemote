import { z } from "zod";
import { AccessSession } from "../schemas/access-session.js";
import {
  MAX_PASSWORD_LENGTH,
  MAX_DEVICE_LABEL_LENGTH,
  MAX_PAIRING_CODE_LENGTH,
  MAX_TRUSTED_CLIENT_ID_LENGTH,
  MAX_TRUSTED_CLIENT_SECRET_LENGTH,
} from "../limits.js";
import {
  TrustedClient,
  TrustedClientCredentials,
} from "../schemas/trusted-client.js";

// --- POST /api/auth/login ---

export const LoginRequest = z.object({
  password: z.string().min(1).max(MAX_PASSWORD_LENGTH),
  deviceLabel: z.string().max(MAX_DEVICE_LABEL_LENGTH).optional(),
});
export type LoginRequest = z.infer<typeof LoginRequest>;

export const LoginResponse = z.object({
  token: z.string(),
  expiresAt: z.string().datetime(),
});
export type LoginResponse = z.infer<typeof LoginResponse>;

// --- POST /api/auth/password ---

export const ChangePasswordRequest = z.object({
  currentPassword: z.string().min(1).max(MAX_PASSWORD_LENGTH),
  newPassword: z.string().min(1).max(MAX_PASSWORD_LENGTH),
});
export type ChangePasswordRequest = z.infer<typeof ChangePasswordRequest>;

export const ChangePasswordResponse = z.object({
  ok: z.literal(true),
  restartScheduled: z.literal(true),
});
export type ChangePasswordResponse = z.infer<typeof ChangePasswordResponse>;

// --- POST /api/auth/logout ---

// No request body required (token comes from header/cookie).
// Response is a simple acknowledgment.
export const LogoutResponse = z.object({
  ok: z.literal(true),
});
export type LogoutResponse = z.infer<typeof LogoutResponse>;

// --- GET /api/auth/session ---

export const GetAuthSessionResponse = AccessSession;
export type GetAuthSessionResponse = z.infer<typeof GetAuthSessionResponse>;

// --- POST /api/pairing/code ---

export const PairingCodeResponse = z.object({
  code: z.string(),
  expiresAt: z.string().datetime(),
});
export type PairingCodeResponse = z.infer<typeof PairingCodeResponse>;

// --- POST /api/pairing/claim ---

export const PairingClaimRequest = z.object({
  code: z.string().min(1).max(MAX_PAIRING_CODE_LENGTH),
  deviceLabel: z.string().max(MAX_DEVICE_LABEL_LENGTH).optional(),
});
export type PairingClaimRequest = z.infer<typeof PairingClaimRequest>;

export const PairingClaimResponse = z.object({
  token: z.string(),
  expiresAt: z.string().datetime(),
  trustedClient: TrustedClientCredentials,
});
export type PairingClaimResponse = z.infer<typeof PairingClaimResponse>;

export const PairingClaimFailureReason = z.enum([
  "invalid_code_format",
  "code_not_found",
  "code_expired",
  "code_already_claimed",
]);
export type PairingClaimFailureReason = z.infer<typeof PairingClaimFailureReason>;

export const PairingClaimFailureResponse = z.object({
  error: z.string(),
  reason: PairingClaimFailureReason,
});
export type PairingClaimFailureResponse = z.infer<typeof PairingClaimFailureResponse>;

// --- POST /api/auth/reconnect ---

export const TrustedReconnectRequest = z.object({
  clientId: z.string().min(1).max(MAX_TRUSTED_CLIENT_ID_LENGTH),
  clientSecret: z.string().min(1).max(MAX_TRUSTED_CLIENT_SECRET_LENGTH),
  deviceLabel: z.string().max(MAX_DEVICE_LABEL_LENGTH).optional(),
});
export type TrustedReconnectRequest = z.infer<typeof TrustedReconnectRequest>;

export const TrustedReconnectResponse = z.object({
  token: z.string(),
  expiresAt: z.string().datetime(),
  trustedClient: TrustedClient,
});
export type TrustedReconnectResponse = z.infer<typeof TrustedReconnectResponse>;

export const TrustedReconnectFailureReason = z.enum([
  "client_not_found",
  "client_revoked",
  "client_secret_mismatch",
]);
export type TrustedReconnectFailureReason = z.infer<typeof TrustedReconnectFailureReason>;

export const TrustedReconnectRecoveryAction = z.enum(["re_pair"]);
export type TrustedReconnectRecoveryAction = z.infer<typeof TrustedReconnectRecoveryAction>;

export const TrustedReconnectFailureResponse = z.object({
  error: z.string(),
  reason: TrustedReconnectFailureReason,
  recoveryAction: TrustedReconnectRecoveryAction,
});
export type TrustedReconnectFailureResponse = z.infer<typeof TrustedReconnectFailureResponse>;
