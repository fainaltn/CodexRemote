import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";

export interface SubmissionAttachmentManifestEntry {
  relativePath: string;
  kind?: string;
  field?: "path" | "source_path";
}

export interface ReviewBundleManifest {
  summary?: string;
  candidate?: string;
  agentBundle?: string;
  bridgeContext?: string;
  skillHandoff?: string;
  skillRunbook?: string;
  extracted?: string;
  pageProposals?: string[];
}

export interface WriteSubmissionBundleOptions {
  itemDir: string;
  contract?: string;
  submissionId: string;
  submittedAt?: string;
  client?: Record<string, unknown>;
  attachments?: SubmissionAttachmentManifestEntry[];
  captureText?: string;
  payload: {
    title: string;
    kind: string;
    sourceLocator: string;
    itemId?: string | null;
  };
  captureSession?: Record<string, unknown>;
  captureSessions?: Array<Record<string, unknown>>;
  retryAttempts?: Array<Record<string, unknown>>;
  attempts?: Array<Record<string, unknown>>;
  retryPolicy?: Record<string, unknown>;
  reviewBundle?: ReviewBundleManifest;
}

export interface SubmissionSummary {
  submissionPath: string | null;
  submissionId: string | null;
  stagingDir: string | null;
  contract: string | null;
  captureSessions: Array<Record<string, unknown>>;
  retryAttempts: Array<Record<string, unknown>>;
  retryPolicy: Record<string, unknown> | null;
  hasReviewBundle: boolean;
  hasSkillRunbook: boolean;
}

export interface ImportedSubmissionFile {
  relativePath: string;
  buffer: Buffer;
}

export interface ImportedSubmissionManifest {
  normalizedFiles: ImportedSubmissionFile[];
  manifest: Record<string, unknown>;
  submissionId: string;
  submittedAt: string | null;
}

function defaultClient(source?: string): Record<string, unknown> {
  return {
    name: "CodexRemote",
    platform: source?.trim() || "unknown",
  };
}

function buildRetryPolicy(opts: {
  retryAttempts?: Array<Record<string, unknown>>;
  attempts?: Array<Record<string, unknown>>;
  reviewBundle?: ReviewBundleManifest;
}): Record<string, unknown> {
  const rawAttempts = Array.isArray(opts.retryAttempts) && opts.retryAttempts.length > 0
    ? opts.retryAttempts
    : Array.isArray(opts.attempts)
      ? opts.attempts
      : [];
  const filteredAttempts = rawAttempts.filter(
    (entry): entry is Record<string, unknown> => !!entry && typeof entry === "object",
  );
  const lastAttempt = filteredAttempts.at(-1) ?? {};
  const finalStatus = String(lastAttempt["status"] ?? (filteredAttempts.length > 0 ? "unknown" : "not_started"));
  const completed = finalStatus === "completed" || finalStatus === "success";
  const recommendedAction = completed
    ? (opts.reviewBundle ? "resume_review_bundle" : "continue_review")
    : finalStatus === "timeout" || finalStatus === "failed" || finalStatus === "error"
      ? "retry_import"
      : "manual_followup";

  return {
    attempt_count: filteredAttempts.length,
    final_status: finalStatus,
    last_error: String(lastAttempt["error"] ?? lastAttempt["status"] ?? ""),
    recommended_action: recommendedAction,
  };
}

function serializeAttachments(entries: SubmissionAttachmentManifestEntry[]): Array<string | Record<string, unknown>> {
  return entries.map((entry) => {
    const key = entry.field === "source_path" ? "source_path" : "path";
    if (!entry.kind) {
      return entry.relativePath;
    }
    return { [key]: entry.relativePath, kind: entry.kind };
  });
}

function toReviewBundle(reviewBundle: ReviewBundleManifest): Record<string, unknown> {
  return {
    summary: reviewBundle.summary ?? "",
    candidate: reviewBundle.candidate ?? "",
    agent_bundle: reviewBundle.agentBundle ?? "",
    bridge_context: reviewBundle.bridgeContext ?? "",
    skill_handoff: reviewBundle.skillHandoff ?? "",
    skill_runbook: reviewBundle.skillRunbook ?? "",
    extracted: reviewBundle.extracted ?? "",
    page_proposals: reviewBundle.pageProposals ?? [],
  };
}

export function inferAttachmentKind(input: {
  mimeType?: string | null;
  fileName?: string | null;
}): string {
  const mimeType = input.mimeType?.toLowerCase() ?? "";
  const fileName = input.fileName?.toLowerCase() ?? "";
  if (mimeType === "text/markdown" || fileName.endsWith(".md")) {
    return "markdown";
  }
  if (mimeType.startsWith("text/") || fileName.endsWith(".txt")) {
    return "text";
  }
  if (mimeType.startsWith("image/")) {
    return "image";
  }
  if (mimeType === "application/json" || fileName.endsWith(".json")) {
    return "json";
  }
  return "file";
}

export async function writeSubmissionBundle(
  opts: WriteSubmissionBundleOptions,
): Promise<string> {
  const contract = opts.contract ?? (opts.reviewBundle ? "codexremote_review_bundle_v1" : "codexremote_v1");
  const submittedAt = opts.submittedAt ?? new Date().toISOString();
  const payload = {
    title: opts.payload.title,
    kind: opts.payload.kind,
    source_locator: opts.payload.sourceLocator,
    ...(opts.payload.itemId ? { item_id: opts.payload.itemId } : {}),
  };
  const retryPolicy = opts.retryPolicy ?? buildRetryPolicy(opts);
  const manifest: Record<string, unknown> = {
    contract,
    submission_id: opts.submissionId,
    submitted_at: submittedAt,
    client: opts.client ?? defaultClient(),
    attachments: serializeAttachments(opts.attachments ?? []),
    capture_text: opts.captureText ?? "",
    payload,
  };

  if (opts.captureSession) {
    manifest["capture_session"] = opts.captureSession;
  }
  if (opts.captureSessions) {
    manifest["capture_sessions"] = opts.captureSessions;
  }
  if (opts.retryAttempts) {
    manifest["retry_attempts"] = opts.retryAttempts;
  }
  if (opts.attempts) {
    manifest["attempts"] = opts.attempts;
  }
  if (retryPolicy && Object.keys(retryPolicy).length > 0) {
    manifest["retry_policy"] = retryPolicy;
  }
  if (opts.reviewBundle) {
    manifest["review_bundle"] = toReviewBundle(opts.reviewBundle);
  }

  await fsp.mkdir(opts.itemDir, { recursive: true });
  const submissionPath = path.join(opts.itemDir, "submission.json");
  await fsp.writeFile(
    submissionPath,
    JSON.stringify(manifest, null, 2) + "\n",
    "utf-8",
  );
  if (typeof opts.captureText === "string" && opts.captureText.trim()) {
    await fsp.writeFile(
      path.join(opts.itemDir, "extracted.md"),
      opts.captureText.trimEnd() + "\n",
      "utf-8",
    );
  }
  return submissionPath;
}

export function resolveSubmissionPath(storedPath: string | null): string | null {
  if (!storedPath) {
    return null;
  }
  if (!fs.existsSync(storedPath)) {
    return null;
  }
  const stat = fs.statSync(storedPath);
  if (stat.isDirectory()) {
    const direct = path.join(storedPath, "submission.json");
    return fs.existsSync(direct) ? direct : null;
  }
  if (path.basename(storedPath) === "submission.json") {
    return storedPath;
  }

  let current = path.dirname(storedPath);
  for (let depth = 0; depth < 4; depth += 1) {
    const candidate = path.join(current, "submission.json");
    if (fs.existsSync(candidate)) {
      return candidate;
    }
    const parent = path.dirname(current);
    if (parent === current) {
      break;
    }
    current = parent;
  }
  return null;
}

export function resolveStagingDir(storedPath: string | null): string | null {
  const submissionPath = resolveSubmissionPath(storedPath);
  return submissionPath ? path.dirname(submissionPath) : null;
}

function normalizeRelativePath(input: string): string {
  const normalized = input.replaceAll("\\", "/").trim().replace(/^\/+/, "");
  const parts = normalized.split("/").filter(Boolean);
  if (parts.length === 0) {
    throw new Error("Invalid empty relative path");
  }
  if (parts.some((part) => part === "." || part === "..")) {
    throw new Error(`Invalid relative path '${input}'`);
  }
  return parts.join("/");
}

function stripSharedRoot(files: ImportedSubmissionFile[]): ImportedSubmissionFile[] {
  if (files.length === 0) return files;
  const firstParts = files.map((file) => normalizeRelativePath(file.relativePath).split("/"));
  const commonRoot = firstParts.every((parts) => parts.length > 1 && parts[0] === firstParts[0]?.[0])
    ? firstParts[0]?.[0]
    : null;
  if (!commonRoot) return files.map((file) => ({ ...file, relativePath: normalizeRelativePath(file.relativePath) }));
  const hasManifestUnderRoot = files.some((file) => normalizeRelativePath(file.relativePath) === `${commonRoot}/submission.json`);
  if (!hasManifestUnderRoot) {
    return files.map((file) => ({ ...file, relativePath: normalizeRelativePath(file.relativePath) }));
  }
  return files.map((file) => {
    const normalized = normalizeRelativePath(file.relativePath);
    return {
      ...file,
      relativePath: normalized.startsWith(`${commonRoot}/`)
        ? normalized.slice(commonRoot.length + 1)
        : normalized,
    };
  });
}

export function readImportedSubmissionManifest(
  files: ImportedSubmissionFile[],
): ImportedSubmissionManifest {
  const normalizedFiles = stripSharedRoot(files);
  const manifestFile = normalizedFiles.find((file) => normalizeRelativePath(file.relativePath) === "submission.json");
  if (!manifestFile) {
    throw new Error("Imported bundle must include submission.json");
  }

  const manifest = JSON.parse(manifestFile.buffer.toString("utf-8")) as Record<string, unknown>;
  const submissionId = typeof manifest["submission_id"] === "string"
    ? manifest["submission_id"].trim()
    : "";
  if (!submissionId) {
    throw new Error("Imported bundle submission.json must include submission_id");
  }

  return {
    normalizedFiles,
    manifest,
    submissionId,
    submittedAt: typeof manifest["submitted_at"] === "string" ? manifest["submitted_at"] : null,
  };
}

export async function importSubmissionBundle(opts: {
  itemDir: string;
  files: ImportedSubmissionFile[];
}): Promise<string> {
  const normalizedFiles = stripSharedRoot(opts.files);
  await fsp.mkdir(opts.itemDir, { recursive: true });
  for (const file of normalizedFiles) {
    const relativePath = normalizeRelativePath(file.relativePath);
    const destination = path.join(opts.itemDir, relativePath);
    const parent = path.dirname(destination);
    await fsp.mkdir(parent, { recursive: true });
    await fsp.writeFile(destination, file.buffer);
  }
  const submissionPath = path.join(opts.itemDir, "submission.json");
  if (!fs.existsSync(submissionPath)) {
    throw new Error("Imported bundle must include submission.json");
  }
  return submissionPath;
}

export function readSubmissionSummary(storedPath: string | null): SubmissionSummary {
  const submissionPath = resolveSubmissionPath(storedPath);
  if (!submissionPath) {
    return {
      submissionPath: null,
      submissionId: null,
      stagingDir: null,
      contract: null,
      captureSessions: [],
      retryAttempts: [],
      retryPolicy: null,
      hasReviewBundle: false,
      hasSkillRunbook: false,
    };
  }

  try {
    const raw = JSON.parse(fs.readFileSync(submissionPath, "utf-8")) as Record<string, unknown>;
    const reviewBundle = raw["review_bundle"];
    const itemDir = path.dirname(submissionPath);
    const skillRunbook = path.join(itemDir, "skill-runbook.md");
    return {
      submissionPath,
      submissionId: typeof raw["submission_id"] === "string" ? raw["submission_id"] : null,
      stagingDir: itemDir,
      contract: typeof raw["contract"] === "string" ? raw["contract"] : null,
      captureSessions: Array.isArray(raw["capture_sessions"])
        ? raw["capture_sessions"].filter((entry): entry is Record<string, unknown> => !!entry && typeof entry === "object")
        : [],
      retryAttempts: Array.isArray(raw["retry_attempts"])
        ? raw["retry_attempts"].filter((entry): entry is Record<string, unknown> => !!entry && typeof entry === "object")
        : Array.isArray(raw["attempts"])
          ? raw["attempts"].filter((entry): entry is Record<string, unknown> => !!entry && typeof entry === "object")
          : [],
      retryPolicy: raw["retry_policy"] && typeof raw["retry_policy"] === "object"
        ? raw["retry_policy"] as Record<string, unknown>
        : null,
      hasReviewBundle: !!reviewBundle && typeof reviewBundle === "object",
      hasSkillRunbook: fs.existsSync(skillRunbook),
    };
  } catch {
    return {
      submissionPath,
      submissionId: null,
      stagingDir: path.dirname(submissionPath),
      contract: null,
      captureSessions: [],
      retryAttempts: [],
      retryPolicy: null,
      hasReviewBundle: false,
      hasSkillRunbook: false,
    };
  }
}
