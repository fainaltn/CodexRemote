"use client";

import { useState, useEffect, useCallback, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import {
  archiveSessions,
  createSession,
  listDraftProjects,
  listSessions,
  notifyShellDataChanged,
  removeDraftProject,
  renameSession,
  type DraftProject,
  type Session,
} from "@/lib/api";

function timeAgo(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return "刚刚";
  if (mins < 60) return `${mins} 分钟前`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  return `${days} 天前`;
}

function shortenPath(path: string | null): string {
  if (!path) return "—";
  const segments = path.split("/").filter(Boolean);
  if (segments.length <= 3) return path;
  return "…/" + segments.slice(-2).join("/");
}

interface SessionGroup {
  key: string;
  label: string;
  path: string | null;
  sessions: Session[];
  updatedAt: string;
  draftOnly: boolean;
}

function projectLabel(path: string | null): string {
  if (!path) return "未归类";
  const segments = path.split("/").filter(Boolean);
  return segments.at(-1) ?? path;
}

function groupSessionsByProject(sessions: Session[]): SessionGroup[] {
  const groups = new Map<string, SessionGroup>();
  for (const session of sessions) {
    const key = session.cwd ?? "__ungrouped__";
    const existing = groups.get(key);
    if (existing) {
      existing.sessions.push(session);
      if (session.updatedAt > existing.updatedAt) {
        existing.updatedAt = session.updatedAt;
      }
      continue;
    }
    groups.set(key, {
      key,
      label: projectLabel(session.cwd),
      path: session.cwd,
      sessions: [session],
      updatedAt: session.updatedAt,
      draftOnly: false,
    });
  }

  return [...groups.values()]
    .map((group) => ({
      ...group,
      sessions: [...group.sessions].sort((a, b) =>
        b.updatedAt.localeCompare(a.updatedAt),
      ),
    }))
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
}

function mergeDraftProjects(
  sessions: Session[],
  draftProjects: DraftProject[],
): SessionGroup[] {
  const sessionGroups = groupSessionsByProject(sessions);
  const existingPaths = new Set(
    sessionGroups
      .map((group) => group.path)
      .filter((path): path is string => Boolean(path)),
  );

  const draftGroups = draftProjects
    .filter((project) => !existingPaths.has(project.path))
    .map((project) => ({
      key: `draft-${project.path}`,
      label: projectLabel(project.path),
      path: project.path,
      sessions: [],
      updatedAt: project.addedAt,
      draftOnly: true,
    }));

  return [...sessionGroups, ...draftGroups].sort((a, b) =>
    b.updatedAt.localeCompare(a.updatedAt),
  );
}

export default function SessionsPage() {
  const { isAuthenticated, isLoading: authLoading } = useAuth();
  const router = useRouter();
  const [sessions, setSessions] = useState<Session[]>([]);
  const [draftProjects, setDraftProjects] = useState<DraftProject[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [creatingPath, setCreatingPath] = useState<string | null>(null);
  const [archivingId, setArchivingId] = useState<string | null>(null);
  const [batchMode, setBatchMode] = useState(false);
  const [selectedSessionIds, setSelectedSessionIds] = useState<string[]>([]);
  const [renamingSession, setRenamingSession] = useState<Session | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [renaming, setRenaming] = useState(false);
  const [activeGroupKey, setActiveGroupKey] = useState<string | null>(null);
  const [projectParam, setProjectParam] = useState<string | null>(null);

  const fetchSessions = useCallback(async () => {
    try {
      setLoading(true);
      setError("");
      const data = await listSessions();
      const nextDraftProjects = listDraftProjects();
      setSessions(data.sessions);
      setDraftProjects(nextDraftProjects);
      notifyShellDataChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载会话失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    fetchSessions();
  }, [isAuthenticated, authLoading, router, fetchSessions]);

  async function handleCreateThread(cwd: string | null, draftOnly = false) {
    if (!cwd || creatingPath) return;
    try {
      setCreatingPath(cwd);
      setError("");
      const created = await createSession(cwd);
      if (draftOnly) {
        setDraftProjects(removeDraftProject(cwd));
      }
      notifyShellDataChanged();
      router.push(`/sessions/${created.sessionId}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "新建线程失败");
    } finally {
      setCreatingPath(null);
    }
  }

  async function handleArchive(sessionId: string) {
    if (archivingId) return;
    try {
      setArchivingId(sessionId);
      setError("");
      await archiveSessions([sessionId]);
      await fetchSessions();
    } catch (err) {
      setError(err instanceof Error ? err.message : "归档线程失败");
    } finally {
      setArchivingId(null);
    }
  }

  async function handleArchiveSelected() {
    if (selectedSessionIds.length === 0 || archivingId) return;
    try {
      setArchivingId("batch");
      setError("");
      await archiveSessions(selectedSessionIds);
      setSelectedSessionIds([]);
      setBatchMode(false);
      await fetchSessions();
    } catch (err) {
      setError(err instanceof Error ? err.message : "归档线程失败");
    } finally {
      setArchivingId(null);
    }
  }

  function toggleSelected(sessionId: string) {
    setSelectedSessionIds((prev) =>
      prev.includes(sessionId)
        ? prev.filter((id) => id !== sessionId)
        : [...prev, sessionId],
    );
  }

  function startRename(session: Session) {
    setRenamingSession(session);
    setRenameValue(session.title || "");
  }

  function closeRename() {
    if (renaming) return;
    setRenamingSession(null);
    setRenameValue("");
  }

  async function handleRenameSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!renamingSession || renaming) return;

    const nextTitle = renameValue.trim();
    if (!nextTitle) return;

    try {
      setRenaming(true);
      setError("");
      await renameSession(renamingSession.id, nextTitle);
      setRenamingSession(null);
      setRenameValue("");
      await fetchSessions();
    } catch (err) {
      setError(err instanceof Error ? err.message : "重命名失败");
    } finally {
      setRenaming(false);
    }
  }

  const groups = mergeDraftProjects(sessions, draftProjects);
  const selectionActive = batchMode || selectedSessionIds.length > 0;
  const activeSessionCount = sessions.length;
  const selectedCount = selectedSessionIds.length;
  const activeGroup =
    groups.find((group) => {
      if (projectParam === "__ungrouped__") {
        return group.path === null;
      }
      if (projectParam) {
        return group.path === projectParam;
      }
      return group.key === activeGroupKey;
    }) ??
    groups.find((group) => group.key === activeGroupKey) ??
    groups[0] ??
    null;

  useEffect(() => {
    function syncProjectFromLocation() {
      const params = new URLSearchParams(window.location.search);
      setProjectParam(params.get("project"));
    }

    function handleProjectSelected(event: Event) {
      const detail = (event as CustomEvent<string | null>).detail ?? null;
      setProjectParam(detail);
    }

    syncProjectFromLocation();
    window.addEventListener("popstate", syncProjectFromLocation);
    window.addEventListener(
      "codexremote-project-selected",
      handleProjectSelected as EventListener,
    );
    return () => {
      window.removeEventListener("popstate", syncProjectFromLocation);
      window.removeEventListener(
        "codexremote-project-selected",
        handleProjectSelected as EventListener,
      );
    };
  }, []);

  useEffect(() => {
    if (groups.length === 0) {
      setActiveGroupKey(null);
      return;
    }

    if (projectParam) {
      const matchedGroup = groups.find((group) =>
        projectParam === "__ungrouped__"
          ? group.path === null
          : group.path === projectParam,
      );
      if (matchedGroup) {
        setActiveGroupKey(matchedGroup.key);
        return;
      }
    }

    if (
      !activeGroupKey ||
      !groups.some((group) => group.key === activeGroupKey)
    ) {
      setActiveGroupKey(groups[0].key);
    }
  }, [groups, activeGroupKey, projectParam]);

  if (authLoading) {
    return (
      <div className="loading-screen">
        <div className="spinner" />
      </div>
    );
  }

  return (
    <div className="screen sessions-screen">
      <header className="header sessions-header" role="banner">
        <div className="header-left sessions-header-left">
          <h1>会话</h1>
          {selectionActive && (
            <span className="pill">{selectedSessionIds.length} 已选择</span>
          )}
        </div>
        <div
          className="sessions-toolbar"
          role="toolbar"
          aria-label="会话操作"
          style={{ display: "flex", gap: 4 }}
        >
          {selectionActive ? (
            <>
              <button
                className="session-group-new-btn"
                onClick={() => {
                  setSelectedSessionIds([]);
                  setBatchMode(false);
                }}
                disabled={archivingId === "batch"}
              >
                取消
              </button>
              <button
                className="session-group-new-btn"
                onClick={handleArchiveSelected}
                disabled={selectedSessionIds.length === 0 || archivingId === "batch"}
              >
                {archivingId === "batch" ? "归档中…" : "归档选中"}
              </button>
            </>
          ) : (
            <button
              className="session-group-new-btn"
              onClick={() => setBatchMode(true)}
            >
              批量
            </button>
          )}
          <button className="icon-btn" onClick={fetchSessions} title="刷新">
            ↻
          </button>
        </div>
      </header>

      <div className="content sessions-content">
        <div className="sessions-desktop-shell">
          <main className="sessions-workbench panel" aria-label="会话工作区">
            {loading && sessions.length === 0 ? (
              <div className="empty-state">
                <div className="spinner" />
              </div>
            ) : error ? (
              <div className="empty-state">
                <div className="empty-state-icon">⚠</div>
                <div className="empty-state-text">{error}</div>
                <button
                  className="btn-primary"
                  style={{
                    marginTop: 16,
                    width: "auto",
                    height: 40,
                    padding: "0 24px",
                    fontSize: 14,
                  }}
                  onClick={fetchSessions}
                >
                  重试
                </button>
              </div>
            ) : !activeGroup ? (
              <div className="empty-state">
                <div className="empty-state-icon">📋</div>
                <div className="empty-state-text">还没有发现会话</div>
                <div className="empty-state-sub">
                  请先在主机上启动或恢复一个 Codex 会话
                </div>
              </div>
            ) : (
              <>
                <section className="sessions-workbench-header">
                  <div className="sessions-workbench-title">
                    <div className="sessions-sidebar-eyebrow">
                      {selectionActive ? "Batch Selection" : "Project Workspace"}
                    </div>
                    <div className="session-group-title-row">
                      <h2>{activeGroup.label}</h2>
                      {activeGroup.draftOnly && <span className="pill">草稿</span>}
                    </div>
                    <div className="session-group-path">
                      {shortenPath(activeGroup.path)}
                    </div>
                  </div>

                  <div className="sessions-workbench-actions">
                    <button
                      className="session-group-new-btn"
                      onClick={() =>
                        void handleCreateThread(
                          activeGroup.path,
                          activeGroup.draftOnly,
                        )
                      }
                      disabled={
                        !activeGroup.path || creatingPath === activeGroup.path
                      }
                    >
                      {creatingPath === activeGroup.path
                        ? "创建中…"
                        : activeGroup.draftOnly
                          ? "创建首个线程"
                          : "新线程"}
                    </button>
                    {selectionActive ? (
                      <>
                        <button
                          className="session-group-new-btn"
                          onClick={() => {
                            setSelectedSessionIds([]);
                            setBatchMode(false);
                          }}
                          disabled={archivingId === "batch"}
                        >
                          取消
                        </button>
                        <button
                          className="session-group-new-btn"
                          onClick={handleArchiveSelected}
                          disabled={
                            selectedSessionIds.length === 0 ||
                            archivingId === "batch"
                          }
                        >
                          {archivingId === "batch" ? "归档中…" : "归档选中"}
                        </button>
                      </>
                    ) : (
                      <button
                        className="session-group-new-btn"
                        onClick={() => setBatchMode(true)}
                      >
                        批量选择
                      </button>
                    )}
                  </div>
                </section>

                <section className="sessions-workbench-meta">
                  <div className="sessions-workbench-stat">
                    <span>项目文件夹</span>
                    <strong>{groups.length} 个</strong>
                  </div>
                  <div className="sessions-workbench-stat">
                    <span>线程数</span>
                    <strong>
                      {activeGroup.draftOnly
                        ? "尚未创建"
                        : `${activeGroup.sessions.length} 个`}
                    </strong>
                  </div>
                  <div className="sessions-workbench-stat">
                    <span>最近活跃</span>
                    <strong>{timeAgo(activeGroup.updatedAt)}</strong>
                  </div>
                  <div className="sessions-workbench-stat">
                    <span>选择状态</span>
                    <strong>{selectionActive ? `${selectedCount} 已选` : "浏览中"}</strong>
                  </div>
                  <div className="sessions-workbench-stat">
                    <span>总会话</span>
                    <strong>{activeSessionCount} 个</strong>
                  </div>
                </section>

                {activeGroup.draftOnly ? (
                  <div className="sessions-draft-panel">
                    这个项目还只是草稿目录。点击上方“创建首个线程”后，会进入真正的会话工作区。
                  </div>
                ) : (
                  <div className="session-list session-list-desktop">
                    {activeGroup.sessions.map((session) => (
                      <div
                        key={session.id}
                        className={`session-card ${
                          selectionActive && selectedSessionIds.includes(session.id)
                            ? "session-card-selected"
                            : ""
                        }`}
                        role="button"
                        tabIndex={0}
                        aria-pressed={
                          selectionActive && selectedSessionIds.includes(session.id)
                        }
                        onClick={() => {
                          if (selectionActive) {
                            toggleSelected(session.id);
                            return;
                          }
                          router.push(`/sessions/${session.id}`);
                        }}
                        onKeyDown={(e) => {
                          if (e.key === "Enter" || e.key === " ") {
                            e.preventDefault();
                            if (selectionActive) {
                              toggleSelected(session.id);
                              return;
                            }
                            router.push(`/sessions/${session.id}`);
                          }
                        }}
                      >
                        <div className="session-card-header">
                          <div className="session-card-header-left">
                            {selectionActive && (
                              <button
                                type="button"
                                className="session-card-select-btn"
                                aria-label={
                                  selectedSessionIds.includes(session.id)
                                    ? "取消选择"
                                    : "选择会话"
                                }
                                onClick={(e) => {
                                  e.stopPropagation();
                                  toggleSelected(session.id);
                                }}
                              >
                                {selectedSessionIds.includes(session.id) ? "☑" : "☐"}
                              </button>
                            )}
                            <span className="session-card-title">
                              {session.title || session.id}
                            </span>
                          </div>
                          <div className="session-card-header-actions">
                            <span className="session-card-time">
                              {timeAgo(session.updatedAt)}
                            </span>
                            {!selectionActive && (
                              <button
                                className="session-card-action-btn"
                                type="button"
                                title="重命名"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  startRename(session);
                                }}
                              >
                                ✎
                              </button>
                            )}
                            <button
                              className="session-card-action-btn"
                              type="button"
                              title="归档"
                              onClick={(e) => {
                                e.stopPropagation();
                                void handleArchive(session.id);
                              }}
                              disabled={archivingId !== null}
                            >
                              {archivingId === session.id ? "…" : "⌫"}
                            </button>
                          </div>
                        </div>
                        {session.lastPreview && (
                          <div className="session-card-preview">
                            {session.lastPreview}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </>
            )}
          </main>
        </div>
      </div>

      {renamingSession && (
        <div className="modal-backdrop" onClick={closeRename}>
          <form
            className="modal-sheet"
            onClick={(e) => e.stopPropagation()}
            onSubmit={(e) => void handleRenameSubmit(e)}
          >
            <div className="modal-title">重命名会话</div>
            <div className="modal-subtitle">
              {renamingSession.cwd || renamingSession.id}
            </div>
            <input
              className="text-input"
              value={renameValue}
              onChange={(e) => setRenameValue(e.target.value)}
              placeholder="输入新标题"
              autoFocus
            />
            <div className="modal-actions">
              <button
                type="button"
                className="session-group-new-btn"
                onClick={closeRename}
                disabled={renaming}
              >
                取消
              </button>
              <button
                type="submit"
                className="btn-primary"
                disabled={renaming || !renameValue.trim()}
                style={{ height: 40, padding: "0 16px", fontSize: 14 }}
              >
                {renaming ? "保存中…" : "保存"}
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
