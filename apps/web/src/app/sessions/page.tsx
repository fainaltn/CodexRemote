"use client";

import { useState, useEffect, useCallback, useMemo, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import {
  archiveSessions,
  clearRememberedActiveSession,
  createSession,
  getRememberedActiveSession,
  listDraftProjects,
  listSessions,
  markSessionTransition,
  notifyShellDataChanged,
  prefetchSessionBootstrap,
  rememberActiveSession,
  removeDraftProject,
  renameSession,
  type ActiveSessionMarker,
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

function isFreshActivity(dateStr: string, minutes = 60): boolean {
  return Date.now() - new Date(dateStr).getTime() <= minutes * 60_000;
}

type Signal = {
  label: string;
  tone: "primary" | "accent" | "muted";
};

type ProjectSectionKey = "current" | "draft" | "recent" | "history";
type SessionSectionKey = "current" | "recent" | "history";

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

function getGroupSignal(
  group: SessionGroup,
  projectParam: string | null,
  activeGroupKey: string | null,
  activeSessionId: string | null,
): Signal {
  const routeProject = group.path ?? "__ungrouped__";
  const containsActiveSession =
    activeSessionId !== null &&
    group.sessions.some((session) => session.id === activeSessionId);
  if (
    containsActiveSession ||
    projectParam === routeProject ||
    activeGroupKey === group.key
  ) {
    return { label: "当前项目", tone: "primary" };
  }
  if (group.draftOnly && group.sessions.length === 0) {
    return { label: "草稿", tone: "muted" };
  }
  if (isFreshActivity(group.updatedAt, 120)) {
    return { label: "最近活跃", tone: "accent" };
  }
  return { label: "历史", tone: "muted" };
}

function getSessionSignal(
  session: Session,
  index: number,
  activeSessionId: string | null,
): Signal {
  if (session.id === activeSessionId) {
    return { label: "当前会话", tone: "primary" };
  }
  if (session.archivedAt) {
    return { label: "已归档", tone: "muted" };
  }
  if (index === 0 && isFreshActivity(session.updatedAt, 30)) {
    return { label: "最新", tone: "primary" };
  }
  if (isFreshActivity(session.updatedAt, 60)) {
    return { label: "最近活跃", tone: "accent" };
  }
  return { label: "历史", tone: "muted" };
}

function rankGroups(
  groups: SessionGroup[],
  projectParam: string | null,
  activeGroupKey: string | null,
  activeSessionId: string | null,
): SessionGroup[] {
  const weight = (signal: Signal) => {
    if (signal.label === "当前项目") return 0;
    if (signal.label === "草稿") return 1;
    if (signal.label === "最近活跃") return 2;
    return 3;
  };

  return [...groups].sort((a, b) => {
    const aSignal = getGroupSignal(a, projectParam, activeGroupKey, activeSessionId);
    const bSignal = getGroupSignal(b, projectParam, activeGroupKey, activeSessionId);
    return (
      weight(aSignal) - weight(bSignal) ||
      b.updatedAt.localeCompare(a.updatedAt) ||
      a.label.localeCompare(b.label)
    );
  });
}

function groupSectionKey(signal: Signal): ProjectSectionKey {
  switch (signal.label) {
    case "当前项目":
      return "current";
    case "草稿":
      return "draft";
    case "最近活跃":
      return "recent";
    default:
      return "history";
  }
}

function sessionSectionKey(signal: Signal): SessionSectionKey {
  switch (signal.label) {
    case "当前会话":
      return "current";
    case "最新":
    case "最近活跃":
      return "recent";
    default:
      return "history";
  }
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
  const [activeSessionMarker, setActiveSessionMarker] =
    useState<ActiveSessionMarker | null>(() => getRememberedActiveSession());

  const warmSession = useCallback((sessionId: string) => {
    void prefetchSessionBootstrap(sessionId).catch(() => {
      // Best-effort warm path only.
    });
  }, []);

  const enterSession = useCallback((session: Session) => {
    const marker = rememberActiveSession(session);
    setActiveSessionMarker(marker);
    markSessionTransition(session);
    void prefetchSessionBootstrap(session.id).catch(() => {
      // Best-effort warm path only.
    });
    router.push(`/sessions/${session.id}`);
  }, [router]);

  const fetchSessions = useCallback(async () => {
    try {
      setLoading(true);
      setError("");
      const data = await listSessions();
      const nextDraftProjects = listDraftProjects();
      setSessions(data.sessions);
      setDraftProjects(nextDraftProjects);
      const remembered = getRememberedActiveSession();
      if (remembered && !data.sessions.some((session) => session.id === remembered.sessionId)) {
        clearRememberedActiveSession(remembered.sessionId);
        setActiveSessionMarker(null);
      } else {
        setActiveSessionMarker(remembered);
      }
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
      clearRememberedActiveSession(sessionId);
      setActiveSessionMarker((previous) =>
        previous?.sessionId === sessionId ? null : previous,
      );
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
      const activeSessionId = activeSessionMarker?.sessionId ?? null;
      if (activeSessionId && selectedSessionIds.includes(activeSessionId)) {
        clearRememberedActiveSession(activeSessionId);
        setActiveSessionMarker(null);
      }
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

  const groups = useMemo(
    () => mergeDraftProjects(sessions, draftProjects),
    [sessions, draftProjects],
  );
  const activeSessionId = activeSessionMarker?.sessionId ?? null;
  const orderedGroups = useMemo(
    () => rankGroups(groups, projectParam, activeGroupKey, activeSessionId),
    [groups, projectParam, activeGroupKey, activeSessionId],
  );
  const selectionActive = batchMode || selectedSessionIds.length > 0;
  const selectedCount = selectedSessionIds.length;
  const activeGroup =
    orderedGroups.find((group) => {
      if (projectParam === "__ungrouped__") {
        return group.path === null;
      }
      if (projectParam) {
        return group.path === projectParam;
      }
      if (activeSessionId) {
        return group.sessions.some((session) => session.id === activeSessionId);
      }
      return group.key === activeGroupKey;
    }) ??
    (activeSessionId
      ? orderedGroups.find((group) =>
          group.sessions.some((session) => session.id === activeSessionId),
        )
      : null) ??
    orderedGroups.find((group) => group.key === activeGroupKey) ??
    orderedGroups[0] ??
    null;
  const currentProjectCount = orderedGroups.length;
  const totalSessionCount = sessions.length;
  const draftProjectCount = orderedGroups.filter((group) => group.draftOnly).length;
  const recentSessionCount = sessions.filter((session) =>
    isFreshActivity(session.updatedAt, 180),
  ).length;
  const currentGroupSignal = activeGroup
    ? getGroupSignal(activeGroup, projectParam, activeGroupKey, activeSessionId)
    : null;
  const projectSections = useMemo(() => {
    const sections: Array<{
      key: ProjectSectionKey;
      label: string;
      description: string;
      groups: SessionGroup[];
    }> = [
      {
        key: "current",
        label: "当前项目",
        description: "持续保留当前会话所在项目",
        groups: [],
      },
      {
        key: "draft",
        label: "当前项目草稿",
        description: "已加入但尚未开线程的目录",
        groups: [],
      },
      {
        key: "recent",
        label: "最近活跃",
        description: "最近 2 小时内仍在流转的项目",
        groups: [],
      },
      {
        key: "history",
        label: "历史项目",
        description: "其余可回溯的会话目录",
        groups: [],
      },
    ];

    for (const group of orderedGroups) {
      const signal = getGroupSignal(
        group,
        projectParam,
        activeGroupKey,
        activeSessionId,
      );
      sections.find((section) => section.key === groupSectionKey(signal))?.groups.push(group);
    }

    return sections.filter((section) => section.groups.length > 0);
  }, [orderedGroups, projectParam, activeGroupKey, activeSessionId]);
  const activeSession = activeGroup?.sessions.find(
    (session) => session.id === activeSessionId,
  ) ?? null;
  const sessionSections = useMemo(() => {
    if (!activeGroup || activeGroup.draftOnly) return [];

    const sections: Array<{
      key: SessionSectionKey;
      label: string;
      description: string;
      sessions: Session[];
    }> = [
      {
        key: "current",
        label: "当前会话",
        description: "保持可持续识别，优先留在最前面",
        sessions: [],
      },
      {
        key: "recent",
        label: "最近活跃",
        description: "最近在这个项目里有动作的线程",
        sessions: [],
      },
      {
        key: "history",
        label: "历史会话",
        description: "较早但仍可继续进入的线程",
        sessions: [],
      },
    ];

    activeGroup.sessions.forEach((session, index) => {
      const signal = getSessionSignal(session, index, activeSessionId);
      sections.find((section) => section.key === sessionSectionKey(signal))?.sessions.push(session);
    });

    return sections.filter((section) => section.sessions.length > 0);
  }, [activeGroup, activeSessionId]);

  useEffect(() => {
    function syncProjectFromLocation() {
      const params = new URLSearchParams(window.location.search);
      setProjectParam(params.get("project"));
      setActiveSessionMarker(getRememberedActiveSession());
    }

    function handleProjectSelected(event: Event) {
      const detail = (event as CustomEvent<string | null>).detail ?? null;
      setProjectParam(detail);
    }

    function handleWindowFocus() {
      setActiveSessionMarker(getRememberedActiveSession());
    }

    syncProjectFromLocation();
    window.addEventListener("popstate", syncProjectFromLocation);
    window.addEventListener("focus", handleWindowFocus);
    window.addEventListener(
      "codexremote-project-selected",
      handleProjectSelected as EventListener,
    );
    return () => {
      window.removeEventListener("popstate", syncProjectFromLocation);
      window.removeEventListener("focus", handleWindowFocus);
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

    if (activeSessionId) {
      const currentSessionGroup = groups.find((group) =>
        group.sessions.some((session) => session.id === activeSessionId),
      );
      if (currentSessionGroup) {
        setActiveGroupKey(currentSessionGroup.key);
        return;
      }
    }

    if (
      !activeGroupKey ||
      !groups.some((group) => group.key === activeGroupKey)
    ) {
      setActiveGroupKey(groups[0].key);
    }
  }, [groups, activeGroupKey, projectParam, activeSessionId]);

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
          <aside className="sessions-project-rail panel" aria-label="项目导航">
            <div className="sessions-project-rail-header">
              <div>
                <div className="sessions-sidebar-eyebrow">Precision Console</div>
                <h2>精选项目</h2>
                <div className="sessions-project-rail-copy">
                  先锁定工作目录，再进入线程；草稿目录和最近活跃项目都会被抬到前面。
                </div>
              </div>
              <div className="sessions-project-rail-count">
                {currentProjectCount} 个
              </div>
            </div>
            <div className="sessions-project-rail-stats">
              <div className="sessions-project-rail-stat">
                <span>焦点</span>
                <strong>{activeGroup ? activeGroup.label : "无"}</strong>
              </div>
              <div className="sessions-project-rail-stat">
                <span>同步</span>
                <strong>{activeGroup ? timeAgo(activeGroup.updatedAt) : "—"}</strong>
              </div>
              <div className="sessions-project-rail-stat">
                <span>当前会话</span>
                <strong>{activeSession?.title || activeSessionMarker?.title || "未锁定"}</strong>
              </div>
            </div>
            <div className="sessions-project-list">
              {orderedGroups.length === 0 ? (
                <div className="sessions-project-empty">还没有发现项目。</div>
              ) : (
                projectSections.map((section) => (
                  <section
                    key={section.key}
                    className="sessions-project-section"
                    aria-label={section.label}
                  >
                    <div className="sessions-project-section-header">
                      <div>
                        <div className="sessions-project-section-title">
                          {section.label}
                        </div>
                        <div className="sessions-project-section-copy">
                          {section.description}
                        </div>
                      </div>
                      <span className="sessions-project-section-count">
                        {section.groups.length}
                      </span>
                    </div>
                    <div className="sessions-project-section-list">
                      {section.groups.map((group) => {
                        const isActive = activeGroup?.key === group.key;
                        const groupSignal = getGroupSignal(
                          group,
                          projectParam,
                          activeGroupKey,
                          activeSessionId,
                        );
                        const containsCurrentSession =
                          activeSessionId !== null &&
                          group.sessions.some((session) => session.id === activeSessionId);

                        return (
                          <button
                            key={group.key}
                            type="button"
                            className={`sessions-project-card ${
                              isActive ? "sessions-project-card-active" : ""
                            } ${group.draftOnly ? "sessions-project-card-draft" : ""} ${
                              containsCurrentSession
                                ? "sessions-project-card-current-session"
                                : ""
                            }`}
                            onClick={() => {
                              setActiveGroupKey(group.key);
                              const value = group.path ?? "__ungrouped__";
                              setProjectParam(value);
                              window.dispatchEvent(
                                new CustomEvent("codexremote-project-selected", {
                                  detail: value,
                                }),
                              );
                              if (group.path) {
                                router.replace(`/sessions?project=${encodeURIComponent(value)}`);
                              } else {
                                router.replace("/sessions?project=__ungrouped__");
                              }
                            }}
                          >
                            <div className="sessions-project-card-top">
                              <span className="sessions-project-card-title">
                                {group.label}
                              </span>
                              <span
                                className={`sessions-project-card-state sessions-project-card-state-${groupSignal.tone}`}
                              >
                                {groupSignal.label}
                              </span>
                            </div>
                            <div className="sessions-project-card-path">
                              {shortenPath(group.path)}
                            </div>
                            <div className="sessions-project-card-meta">
                              <span>
                                {group.draftOnly
                                  ? "草稿目录"
                                  : containsCurrentSession
                                    ? "含当前会话"
                                    : `${group.sessions.length} 个线程`}
                              </span>
                              <span>{timeAgo(group.updatedAt)}</span>
                            </div>
                          </button>
                        );
                      })}
                    </div>
                  </section>
                ))
              )}
            </div>
          </aside>

          <main className="sessions-workbench panel" aria-label="会话工作区">
            {loading && sessions.length === 0 ? (
              <div className="empty-state">
                <div className="spinner" />
                <div className="empty-state-text">正在重建 Precision Console</div>
                <div className="empty-state-sub">
                  会同步当前项目、草稿目录和最近活跃线程，稍后把工作区恢复到可进入状态。
                </div>
              </div>
            ) : error ? (
              <div className="empty-state" role="alert">
                <div className="empty-state-icon">⚠</div>
                <div className="empty-state-text">项目导航同步失败</div>
                <div className="empty-state-sub">{error}</div>
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
                  先在主机上启动或恢复一个 Codex 会话，或从“选择项目”加入草稿目录。
                </div>
              </div>
            ) : (
              <>
                <section className="sessions-overview-band">
                  <div className="sessions-overview-copy">
                    <div className="sessions-sidebar-eyebrow">
                      {selectionActive ? "Batch Selection" : "Workspace Overview"}
                    </div>
                    <h2>把项目、草稿与线程收成一个可操作的工作台</h2>
                    <p>
                      当前工作区会优先突出最近活跃目录、当前焦点项目和可继续推进的线程，
                      让你能更快回到正确的上下文。
                    </p>
                  </div>
                  <div className="sessions-overview-metrics" aria-label="工作区概览">
                    <div className="sessions-overview-metric">
                      <span>项目</span>
                      <strong>{currentProjectCount}</strong>
                    </div>
                    <div className="sessions-overview-metric">
                      <span>线程</span>
                      <strong>{totalSessionCount}</strong>
                    </div>
                    <div className="sessions-overview-metric">
                      <span>草稿</span>
                      <strong>{draftProjectCount}</strong>
                    </div>
                    <div className="sessions-overview-metric">
                      <span>近 3 小时</span>
                      <strong>{recentSessionCount}</strong>
                    </div>
                  </div>
                </section>

                <section className="sessions-workbench-header">
                  <div className="sessions-workbench-title">
                    <div className="sessions-sidebar-eyebrow">
                      {selectionActive ? "Batch Selection" : "Project Workspace"}
                    </div>
                    <div className="session-group-title-row">
                      <h2>{activeGroup.label}</h2>
                      {currentGroupSignal && (
                        <span
                          className={`pill pill-${currentGroupSignal.tone}`}
                        >
                          {currentGroupSignal.label}
                        </span>
                      )}
                      {activeGroup.draftOnly && <span className="pill">草稿</span>}
                    </div>
                    <div className="session-group-path">
                      {shortenPath(activeGroup.path)}
                    </div>
                    <div className="sessions-workbench-note">
                      {activeGroup.draftOnly
                        ? "这个项目还只是草稿目录。点击创建首个线程后，会进入真正的会话工作区。"
                        : activeSession
                          ? `${activeGroup.sessions.length} 个会话 · 当前会话保持高亮 · 最近更新 ${timeAgo(activeGroup.updatedAt)}`
                          : `${activeGroup.sessions.length} 个会话 · 最近更新 ${timeAgo(activeGroup.updatedAt)}`}
                    </div>
                  </div>

                  <div className="sessions-workbench-actions">
                    {selectionActive && (
                      <span className="pill pill-muted">
                        批量中 · {selectedCount}
                      </span>
                    )}
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
                  </div>
                </section>

                <section className="sessions-workbench-meta">
                  <div className="sessions-workbench-stat">
                    <span>项目文件夹</span>
                    <strong>{currentProjectCount} 个</strong>
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
                </section>

                <section className="sessions-operator-note" aria-label="工作台提示">
                  <div className="sessions-operator-note-title">工作台提示</div>
                  <div className="sessions-operator-note-copy">
                    {activeGroup.draftOnly
                      ? "先为这个目录创建首个线程，之后它就会和其他活跃项目一样进入正常的工作流排序。"
                      : "如果你刚从手机或其他入口切回来，这里会保留项目层级，并把最近活跃线程留在最易进入的位置。"}
                  </div>
                </section>

                {activeGroup.draftOnly ? (
                  <div className="sessions-draft-panel">
                    这个项目还只是草稿目录。点击上方“创建首个线程”后，会进入真正的会话工作区。
                  </div>
                ) : (
                  <div className="session-list-stack">
                    {sessionSections.map((section) => (
                      <section
                        key={section.key}
                        className="session-list-section"
                        aria-label={section.label}
                      >
                        <div className="session-list-section-header">
                          <div>
                            <div className="session-list-section-title">
                              {section.label}
                            </div>
                            <div className="session-list-section-copy">
                              {section.description}
                            </div>
                          </div>
                          <span className="session-list-section-count">
                            {section.sessions.length}
                          </span>
                        </div>
                        <div className="session-list session-list-desktop">
                          {section.sessions.map((session) => {
                            const sessionIndex = activeGroup.sessions.findIndex(
                              (candidate) => candidate.id === session.id,
                            );
                            const sessionSignal = getSessionSignal(
                              session,
                              sessionIndex,
                              activeSessionId,
                            );
                            const isCurrentSession = session.id === activeSessionId;

                            return (
                              <div
                                key={session.id}
                                className={`session-card ${
                                  selectionActive && selectedSessionIds.includes(session.id)
                                    ? "session-card-selected"
                                    : ""
                                } ${isCurrentSession ? "session-card-current" : ""}`}
                                role="button"
                                tabIndex={0}
                                onMouseEnter={() => warmSession(session.id)}
                                onFocus={() => warmSession(session.id)}
                                aria-pressed={
                                  selectionActive && selectedSessionIds.includes(session.id)
                                }
                                onClick={() => {
                                  if (selectionActive) {
                                    toggleSelected(session.id);
                                    return;
                                  }
                                  enterSession(session);
                                }}
                                onKeyDown={(e) => {
                                  if (e.key === "Enter" || e.key === " ") {
                                    e.preventDefault();
                                    if (selectionActive) {
                                      toggleSelected(session.id);
                                      return;
                                    }
                                    enterSession(session);
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
                                        {selectedSessionIds.includes(session.id)
                                          ? "☑"
                                          : "☐"}
                                      </button>
                                    )}
                                    <span className="session-card-title">
                                      {session.title || session.id}
                                    </span>
                                  </div>
                                  <div className="session-card-header-actions">
                                    <span
                                      className={`session-card-state status-chip status-chip-${sessionSignal.tone} session-card-state-${sessionSignal.tone}`}
                                    >
                                      {sessionSignal.label}
                                    </span>
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
                                <div className="session-card-cwd">
                                  {shortenPath(session.cwd)}
                                </div>
                                {session.lastPreview && (
                                  <div className="session-card-preview">
                                    {session.lastPreview}
                                  </div>
                                )}
                              </div>
                            );
                          })}
                        </div>
                      </section>
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
