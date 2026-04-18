// ── Types (mirrors @codexremote/shared — kept local to avoid build coupling) ──

export interface Session {
  id: string;
  hostId: string;
  provider: "codex";
  codexSessionId: string | null;
  title: string;
  cwd: string | null;
  createdAt: string;
  updatedAt: string;
  lastPreview: string | null;
  archivedAt: string | null;
}

export interface SessionMessage {
  id: string;
  role: "user" | "assistant" | "system";
  kind: "message" | "reasoning";
  turnId?: string;
  itemId?: string;
  orderIndex: number;
  isStreaming?: boolean;
  text: string;
  createdAt: string;
}

export interface SessionDetail {
  session: Session;
  messages: SessionMessage[];
}

export interface SessionSummaryPayload {
  session: Session;
}

export interface SessionMessagesPayload {
  messages: SessionMessage[];
  limit: number;
  hasMore: boolean;
  nextBeforeOrderIndex: number | null;
}

export interface SessionBootstrapPayload {
  session: Session;
  messages: SessionMessage[];
  hasMore: boolean;
  nextBeforeOrderIndex: number | null;
  fetchedAt: number;
}

export interface SessionTransitionPayload {
  session: Session;
  startedAt: number;
}

export interface ProjectDirectory {
  name: string;
  path: string;
}

export interface BrowseProjectsResponse {
  rootPath: string;
  currentPath: string;
  parentPath: string | null;
  entries: ProjectDirectory[];
}

export interface DraftProject {
  path: string;
  addedAt: string;
}

export interface ActiveSessionMarker {
  sessionId: string;
  cwd: string | null;
  title: string;
  updatedAt: string;
  focusedAt: number;
}

export type RunStatus = "pending" | "running" | "completed" | "failed" | "stopped";

export interface Run {
  id: string;
  sessionId: string;
  status: RunStatus;
  prompt: string;
  model: string | null;
  reasoningEffort: string | null;
  startedAt: string;
  finishedAt: string | null;
  lastOutput: string | null;
  error: string | null;
}

export type ArtifactKind = "image" | "file";

export interface Artifact {
  id: string;
  sessionId: string;
  runId: string | null;
  kind: ArtifactKind;
  originalName: string;
  storedPath: string;
  mimeType: string;
  sizeBytes: number;
  createdAt: string;
}

export type InboxItemKind = "link" | "file";
export type InboxItemStatus = "received";

export interface InboxItem {
  id: string;
  hostId: string;
  kind: InboxItemKind;
  status: InboxItemStatus;
  url: string | null;
  title: string | null;
  originalName: string | null;
  note: string | null;
  source: string | null;
  storedPath: string | null;
  mimeType: string | null;
  sizeBytes: number | null;
  createdAt: string;
  submissionPath?: string | null;
  submissionId?: string | null;
  stagingDir?: string | null;
  contract?: string | null;
  captureSessions?: Array<Record<string, unknown>>;
  retryAttempts?: Array<Record<string, unknown>>;
  retryPolicy?: Record<string, unknown> | null;
  hasReviewBundle?: boolean;
  hasSkillRunbook?: boolean;
}

// ── Token persistence ──────────────────────────────────────────────

const TOKEN_KEY = "codexremote_token";
const EXPIRES_KEY = "codexremote_token_expires";
const DRAFT_PROJECTS_KEY = "codexremote_draft_projects";
const ACTIVE_SESSION_KEY = "codexremote_active_session";
const SHELL_DATA_EVENT = "codexremote-shell-data-changed";
const SESSION_BOOTSTRAP_CACHE_TTL_MS = 15_000;
const SESSION_TRANSITION_TTL_MS = 15_000;
const sessionBootstrapCache = new Map<string, SessionBootstrapPayload>();
const sessionBootstrapInflight = new Map<string, Promise<SessionBootstrapPayload>>();
const sessionTransitionCache = new Map<string, SessionTransitionPayload>();
let activeSessionCache: ActiveSessionMarker | null = null;

export function notifyShellDataChanged(): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new Event(SHELL_DATA_EVENT));
}

export function getShellDataEventName(): string {
  return SHELL_DATA_EVENT;
}

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(TOKEN_KEY);
}

function setToken(token: string, expiresAt: string): void {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(EXPIRES_KEY, expiresAt);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(EXPIRES_KEY);
}

function sameActiveSessionMarker(
  left: ActiveSessionMarker | null,
  right: ActiveSessionMarker | null,
): boolean {
  if (left === right) return true;
  if (!left || !right) return false;
  return (
    left.sessionId === right.sessionId &&
    left.cwd === right.cwd &&
    left.title === right.title &&
    left.updatedAt === right.updatedAt &&
    left.focusedAt === right.focusedAt
  );
}

function readActiveSessionMarker(): ActiveSessionMarker | null {
  if (typeof window === "undefined") return null;

  const raw = localStorage.getItem(ACTIVE_SESSION_KEY);
  if (!raw) return null;

  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== "object") return null;

    const sessionId = (parsed as { sessionId?: unknown }).sessionId;
    const cwd = (parsed as { cwd?: unknown }).cwd;
    const title = (parsed as { title?: unknown }).title;
    const updatedAt = (parsed as { updatedAt?: unknown }).updatedAt;
    const focusedAt = (parsed as { focusedAt?: unknown }).focusedAt;

    if (
      typeof sessionId !== "string" ||
      (cwd !== null && cwd !== undefined && typeof cwd !== "string") ||
      typeof title !== "string" ||
      typeof updatedAt !== "string" ||
      typeof focusedAt !== "number"
    ) {
      return null;
    }

    return {
      sessionId,
      cwd: typeof cwd === "string" ? cwd : null,
      title,
      updatedAt,
      focusedAt,
    };
  } catch {
    return null;
  }
}

export function getRememberedActiveSession(): ActiveSessionMarker | null {
  if (activeSessionCache) {
    return activeSessionCache;
  }
  const marker = readActiveSessionMarker();
  activeSessionCache = marker;
  return marker;
}

export function rememberActiveSession(session: Session): ActiveSessionMarker {
  const next: ActiveSessionMarker = {
    sessionId: session.id,
    cwd: session.cwd,
    title: session.title,
    updatedAt: session.updatedAt,
    focusedAt: Date.now(),
  };

  activeSessionCache = next;

  if (typeof window !== "undefined") {
    const previous = readActiveSessionMarker();
    if (!sameActiveSessionMarker(previous, next)) {
      localStorage.setItem(ACTIVE_SESSION_KEY, JSON.stringify(next));
      notifyShellDataChanged();
    }
  }

  return next;
}

export function clearRememberedActiveSession(sessionId?: string): void {
  const current = getRememberedActiveSession();
  if (sessionId && current?.sessionId !== sessionId) {
    return;
  }

  activeSessionCache = null;
  if (typeof window === "undefined") return;
  localStorage.removeItem(ACTIVE_SESSION_KEY);
  notifyShellDataChanged();
}

function normalizeProjectPath(path: string): string {
  const trimmed = path.trim();
  if (!trimmed) return "";
  return trimmed.replace(/\/+$/, "") || trimmed;
}

function readDraftProjects(): DraftProject[] {
  if (typeof window === "undefined") return [];

  const raw = localStorage.getItem(DRAFT_PROJECTS_KEY);
  if (!raw) return [];

  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];

    return parsed
      .map((entry) => {
        if (
          !entry ||
          typeof entry !== "object" ||
          typeof (entry as { path?: unknown }).path !== "string" ||
          typeof (entry as { addedAt?: unknown }).addedAt !== "string"
        ) {
          return null;
        }

        const path = normalizeProjectPath((entry as { path: string }).path);
        if (!path) return null;

        return {
          path,
          addedAt: (entry as { addedAt: string }).addedAt,
        };
      })
      .filter((entry): entry is DraftProject => entry !== null)
      .sort((a, b) => b.addedAt.localeCompare(a.addedAt));
  } catch {
    return [];
  }
}

function writeDraftProjects(projects: DraftProject[]): void {
  if (typeof window === "undefined") return;
  localStorage.setItem(DRAFT_PROJECTS_KEY, JSON.stringify(projects));
  notifyShellDataChanged();
}

export function listDraftProjects(): DraftProject[] {
  return readDraftProjects();
}

export function upsertDraftProject(path: string): DraftProject[] {
  const normalizedPath = normalizeProjectPath(path);
  if (!normalizedPath) return readDraftProjects();

  const draftProject = {
    path: normalizedPath,
    addedAt: new Date().toISOString(),
  };

  const next = [
    draftProject,
    ...readDraftProjects().filter((entry) => entry.path !== normalizedPath),
  ];
  writeDraftProjects(next);
  return next;
}

export function removeDraftProject(path: string): DraftProject[] {
  const normalizedPath = normalizeProjectPath(path);
  if (!normalizedPath) return readDraftProjects();

  const next = readDraftProjects().filter((entry) => entry.path !== normalizedPath);
  writeDraftProjects(next);
  return next;
}

export function clearDraftProjects(): void {
  if (typeof window === "undefined") return;
  localStorage.removeItem(DRAFT_PROJECTS_KEY);
  notifyShellDataChanged();
}

// ── Fetch wrapper ──────────────────────────────────────────────────

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function apiFetch<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options.headers as Record<string, string>),
  };
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  const res = await fetch(path, { ...options, headers });

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, body.error ?? res.statusText);
  }

  return res.json() as Promise<T>;
}

// ── Auth ────────────────────────────────────────────────────────────

export async function login(
  password: string,
  deviceLabel?: string,
): Promise<{ token: string; expiresAt: string }> {
  const data = await apiFetch<{ token: string; expiresAt: string }>(
    "/api/auth/login",
    {
      method: "POST",
      body: JSON.stringify({ password, deviceLabel }),
    },
  );
  setToken(data.token, data.expiresAt);
  return data;
}

export async function logout(): Promise<void> {
  try {
    await apiFetch("/api/auth/logout", { method: "POST" });
  } finally {
    clearToken();
  }
}

export async function checkSession(): Promise<{
  tokenId: string;
  expiresAt: string;
  deviceLabel: string | null;
}> {
  return apiFetch("/api/auth/session");
}

// ── Sessions ────────────────────────────────────────────────────────

const HOST = "local";

export async function listSessions(): Promise<{ sessions: Session[] }> {
  return apiFetch(`/api/hosts/${HOST}/sessions`);
}

export async function getSessionDetail(sessionId: string): Promise<SessionDetail> {
  return apiFetch(`/api/hosts/${HOST}/sessions/${encodeURIComponent(sessionId)}`);
}

export async function getSessionSummary(
  sessionId: string,
): Promise<SessionSummaryPayload> {
  return apiFetch(`/api/hosts/${HOST}/sessions/${encodeURIComponent(sessionId)}/summary`);
}

export async function getSessionMessages(
  sessionId: string,
  opts?: { limit?: number; beforeOrderIndex?: number | null },
): Promise<SessionMessagesPayload> {
  const search = new URLSearchParams();
  if (opts?.limit) {
    search.set("limit", String(opts.limit));
  }
  if (opts?.beforeOrderIndex !== undefined && opts.beforeOrderIndex !== null) {
    search.set("beforeOrderIndex", String(opts.beforeOrderIndex));
  }
  const suffix = search.size > 0 ? `?${search.toString()}` : "";
  return apiFetch(
    `/api/hosts/${HOST}/sessions/${encodeURIComponent(sessionId)}/messages${suffix}`,
  );
}

export function getCachedSessionBootstrap(
  sessionId: string,
): SessionBootstrapPayload | null {
  const cached = sessionBootstrapCache.get(sessionId);
  if (!cached) return null;
  if (Date.now() - cached.fetchedAt > SESSION_BOOTSTRAP_CACHE_TTL_MS) {
    sessionBootstrapCache.delete(sessionId);
    return null;
  }
  return cached;
}

export async function prefetchSessionBootstrap(
  sessionId: string,
  opts?: { limit?: number },
): Promise<SessionBootstrapPayload> {
  const cached = getCachedSessionBootstrap(sessionId);
  if (cached) return cached;

  const inflight = sessionBootstrapInflight.get(sessionId);
  if (inflight) return inflight;

  const request = Promise.all([
    getSessionSummary(sessionId),
    getSessionMessages(sessionId, { limit: opts?.limit ?? 40 }),
  ]).then(([summary, tail]) => {
    const payload: SessionBootstrapPayload = {
      session: summary.session,
      messages: tail.messages,
      hasMore: tail.hasMore,
      nextBeforeOrderIndex: tail.nextBeforeOrderIndex,
      fetchedAt: Date.now(),
    };
    sessionBootstrapCache.set(sessionId, payload);
    sessionBootstrapInflight.delete(sessionId);
    return payload;
  }).catch((error) => {
    sessionBootstrapInflight.delete(sessionId);
    throw error;
  });

  sessionBootstrapInflight.set(sessionId, request);
  return request;
}

export function markSessionTransition(session: Session): void {
  sessionTransitionCache.set(session.id, {
    session,
    startedAt: Date.now(),
  });
}

export function getSessionTransition(sessionId: string): SessionTransitionPayload | null {
  const cached = sessionTransitionCache.get(sessionId);
  if (!cached) return null;
  if (Date.now() - cached.startedAt > SESSION_TRANSITION_TTL_MS) {
    sessionTransitionCache.delete(sessionId);
    return null;
  }
  return cached;
}

export function clearSessionTransition(sessionId: string): void {
  sessionTransitionCache.delete(sessionId);
}

export async function createSession(
  cwd: string,
  prompt?: string,
): Promise<{ sessionId: string; runId: string }> {
  return apiFetch(`/api/hosts/${HOST}/sessions`, {
    method: "POST",
    body: JSON.stringify(prompt ? { cwd, prompt } : { cwd }),
  });
}

export async function renameSession(
  sessionId: string,
  title: string,
): Promise<{ ok: true }> {
  return apiFetch(`/api/hosts/${HOST}/sessions/${encodeURIComponent(sessionId)}/title`, {
    method: "PATCH",
    body: JSON.stringify({ title }),
  });
}

export async function archiveSessions(
  sessionIds: string[],
): Promise<{ ok: true; archivedCount: number }> {
  return apiFetch(`/api/hosts/${HOST}/sessions/archive`, {
    method: "POST",
    body: JSON.stringify({ sessionIds }),
  });
}

export async function browseProjects(
  currentPath?: string,
): Promise<BrowseProjectsResponse> {
  const query = currentPath
    ? `?path=${encodeURIComponent(currentPath)}`
    : "";
  return apiFetch(`/api/hosts/${HOST}/projects/browse${query}`);
}

// ── Live Runs ───────────────────────────────────────────────────────

export async function getLiveRun(sessionId: string): Promise<Run | null> {
  return apiFetch(`/api/hosts/${HOST}/sessions/${encodeURIComponent(sessionId)}/live`);
}

export async function startLiveRun(
  sessionId: string,
  prompt: string,
  opts?: {
    model?: string | null;
    reasoningEffort?: string | null;
  },
): Promise<{ runId: string }> {
  return apiFetch(
    `/api/hosts/${HOST}/sessions/${encodeURIComponent(sessionId)}/live`,
    {
      method: "POST",
      body: JSON.stringify({
        prompt,
        model: opts?.model ?? undefined,
        reasoningEffort: opts?.reasoningEffort ?? undefined,
      }),
    },
  );
}

export async function stopLiveRun(sessionId: string): Promise<{ ok: true }> {
  return apiFetch(
    `/api/hosts/${HOST}/sessions/${encodeURIComponent(sessionId)}/live/stop`,
    { method: "POST" },
  );
}

// ── SSE stream URL ──────────────────────────────────────────────────

export function liveStreamUrl(sessionId: string): string {
  return `/api/hosts/${HOST}/sessions/${encodeURIComponent(sessionId)}/live/stream`;
}

// ── Uploads ─────────────────────────────────────────────────────────

export interface UploadProgress {
  loaded: number;
  total: number;
}

/**
 * Upload a file for a session. Uses XMLHttpRequest for progress tracking.
 * Returns the created Artifact metadata on success.
 */
export function uploadArtifact(
  sessionId: string,
  file: File,
  onProgress?: (progress: UploadProgress) => void,
): Promise<Artifact> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const url = `/api/hosts/${HOST}/uploads`;

    const formData = new FormData();
    formData.append("sessionId", sessionId);
    formData.append("file", file);

    xhr.open("POST", url);

    const token = getToken();
    if (token) {
      xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    }

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress({ loaded: e.loaded, total: e.total });
      }
    };

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText) as Artifact);
        } catch {
          reject(new ApiError(xhr.status, "Invalid response"));
        }
      } else {
        let message = xhr.statusText;
        try {
          const body = JSON.parse(xhr.responseText);
          if (body.error) message = body.error;
        } catch {
          // Use status text
        }
        reject(new ApiError(xhr.status, message));
      }
    };

    xhr.onerror = () => {
      reject(new ApiError(0, "Network error"));
    };

    xhr.send(formData);
  });
}

// ── Inbox ───────────────────────────────────────────────────────────

export async function listInboxItems(): Promise<{ items: InboxItem[] }> {
  return apiFetch(`/api/hosts/${HOST}/inbox`);
}

export async function submitInboxLink(input: {
  url: string;
  title?: string;
  note?: string;
  source?: string;
}): Promise<InboxItem> {
  return apiFetch(`/api/hosts/${HOST}/inbox/link`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function uploadInboxFile(
  file: File,
  options?: {
    note?: string;
    source?: string;
    onProgress?: (progress: UploadProgress) => void;
  },
): Promise<InboxItem> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const url = `/api/hosts/${HOST}/inbox/file`;
    const formData = new FormData();
    formData.append("file", file);
    if (options?.note) formData.append("note", options.note);
    if (options?.source) formData.append("source", options.source);

    xhr.open("POST", url);

    const token = getToken();
    if (token) {
      xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    }

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && options?.onProgress) {
        options.onProgress({ loaded: e.loaded, total: e.total });
      }
    };

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText) as InboxItem);
        } catch {
          reject(new ApiError(xhr.status, "Invalid response"));
        }
      } else {
        let message = xhr.statusText;
        try {
          const body = JSON.parse(xhr.responseText);
          if (body.error) message = body.error;
        } catch {
          // Use status text
        }
        reject(new ApiError(xhr.status, message));
      }
    };

    xhr.onerror = () => {
      reject(new ApiError(0, "Network error"));
    };

    xhr.send(formData);
  });
}

export function uploadInboxFiles(
  files: FileList | File[],
  options?: {
    note?: string;
    source?: string;
    onProgress?: (progress: UploadProgress) => void;
  },
): Promise<InboxItem> {
  return new Promise((resolve, reject) => {
    const list = Array.from(files);
    if (list.length === 0) {
      reject(new ApiError(400, "No files selected"));
      return;
    }

    const xhr = new XMLHttpRequest();
    const url = `/api/hosts/${HOST}/inbox/files`;
    const formData = new FormData();
    for (const file of list) {
      formData.append("file", file, file.name);
    }
    if (options?.note) formData.append("note", options.note);
    if (options?.source) formData.append("source", options.source);

    xhr.open("POST", url);

    const token = getToken();
    if (token) {
      xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    }

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && options?.onProgress) {
        options.onProgress({ loaded: e.loaded, total: e.total });
      }
    };

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText) as InboxItem);
        } catch {
          reject(new ApiError(xhr.status, "Invalid response"));
        }
      } else {
        let message = xhr.statusText;
        try {
          const body = JSON.parse(xhr.responseText);
          if (body.error) message = body.error;
        } catch {
          // Use status text
        }
        reject(new ApiError(xhr.status, message));
      }
    };

    xhr.onerror = () => {
      reject(new ApiError(0, "Network error"));
    };

    xhr.send(formData);
  });
}

export function uploadInboxSubmissionBundle(
  files: FileList | File[],
  options?: {
    onProgress?: (progress: UploadProgress) => void;
  },
): Promise<InboxItem> {
  return new Promise((resolve, reject) => {
    const list = Array.from(files);
    if (list.length === 0) {
      reject(new ApiError(400, "No files selected"));
      return;
    }

    const xhr = new XMLHttpRequest();
    const url = `/api/hosts/${HOST}/inbox/submission`;
    const formData = new FormData();
    for (const file of list) {
      const relativePath =
        "webkitRelativePath" in file && typeof file.webkitRelativePath === "string" && file.webkitRelativePath
          ? file.webkitRelativePath
          : file.name;
      formData.append(`file:${encodeURIComponent(relativePath)}`, file, file.name);
    }

    xhr.open("POST", url);

    const token = getToken();
    if (token) {
      xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    }

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && options?.onProgress) {
        options.onProgress({ loaded: e.loaded, total: e.total });
      }
    };

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText) as InboxItem);
        } catch {
          reject(new ApiError(xhr.status, "Invalid response"));
        }
      } else {
        let message = xhr.statusText;
        try {
          const body = JSON.parse(xhr.responseText);
          if (body.error) message = body.error;
        } catch {
          // Use status text
        }
        reject(new ApiError(xhr.status, message));
      }
    };

    xhr.onerror = () => {
      reject(new ApiError(0, "Network error"));
    };

    xhr.send(formData);
  });
}
