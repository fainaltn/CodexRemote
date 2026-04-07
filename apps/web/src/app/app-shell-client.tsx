"use client";

import type { ReactNode } from "react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import {
  getShellDataEventName,
  listDraftProjects,
  listSessions,
  type DraftProject,
  type Session,
} from "@/lib/api";

interface SessionTreeGroup {
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

function shortenPath(path: string | null): string {
  if (!path) return "—";
  const segments = path.split("/").filter(Boolean);
  if (segments.length <= 3) return path;
  return "…/" + segments.slice(-2).join("/");
}

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

function groupSessionsByProject(
  sessions: Session[],
  draftProjects: DraftProject[],
): SessionTreeGroup[] {
  const groups = new Map<string, SessionTreeGroup>();

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

  for (const project of draftProjects) {
    if (groups.has(project.path)) continue;
    groups.set(project.path, {
      key: `draft-${project.path}`,
      label: projectLabel(project.path),
      path: project.path,
      sessions: [],
      updatedAt: project.addedAt,
      draftOnly: true,
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

function routeProjectValue(group: SessionTreeGroup): string {
  return group.path ?? "__ungrouped__";
}

export function AppShellClient({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { logout, isAuthenticated } = useAuth();
  const isLoginPage = pathname === "/login";
  const [groups, setGroups] = useState<SessionTreeGroup[]>([]);
  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>({});
  const [selectedProject, setSelectedProject] = useState<string | null>(null);

  const refreshTree = useCallback(async () => {
    if (!isAuthenticated) {
      setGroups([]);
      return;
    }

    try {
      const data = await listSessions();
      const nextDraftProjects = listDraftProjects();
      const nextGroups = groupSessionsByProject(data.sessions, nextDraftProjects);
      setGroups(nextGroups);
      setExpandedGroups((prev) => {
        if (nextGroups.length === 0) return {};
        const next: Record<string, boolean> = { ...prev };
        if (Object.keys(next).length === 0) {
          next[nextGroups[0].key] = true;
        }
        return next;
      });
    } catch {
      // Keep the existing tree if refresh fails.
    }
  }, [isAuthenticated]);

  useEffect(() => {
    if (isLoginPage || !isAuthenticated) return;
    void refreshTree();
  }, [isLoginPage, isAuthenticated, refreshTree, pathname]);

  useEffect(() => {
    const eventName = getShellDataEventName();
    function handleChange() {
      void refreshTree();
    }

    window.addEventListener(eventName, handleChange);
    window.addEventListener("storage", handleChange);
    return () => {
      window.removeEventListener(eventName, handleChange);
      window.removeEventListener("storage", handleChange);
    };
  }, [refreshTree]);

  useEffect(() => {
    function syncProjectFromLocation() {
      const params = new URLSearchParams(window.location.search);
      setSelectedProject(params.get("project"));
    }

    function handleProjectSelected(event: Event) {
      const detail = (event as CustomEvent<string | null>).detail ?? null;
      setSelectedProject(detail);
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
  }, [pathname]);

  const currentSessionId = pathname.startsWith("/sessions/")
    ? pathname.split("/")[2] ?? null
    : null;

  const treeGroups = useMemo(() => groups, [groups]);

  async function handleLogout() {
    await logout();
    router.replace("/login");
  }

  function toggleGroup(key: string) {
    setExpandedGroups((prev) => ({ ...prev, [key]: !prev[key] }));
  }

  function selectProject(value: string) {
    setSelectedProject(value);
    window.dispatchEvent(
      new CustomEvent("codexremote-project-selected", { detail: value }),
    );
  }

  if (isLoginPage) {
    return <main className="app-shell-main">{children}</main>;
  }

  return (
    <div className="desktop-shell">
      <div className="desktop-shell-inner">
        <aside className="desktop-sidebar">
          <div className="desktop-sidebar-brand">
            <div className="desktop-sidebar-eyebrow">CodexRemote</div>
            <h1>本地控制台</h1>
          </div>

          <div className="desktop-tree-header">
            <span>线程</span>
          </div>

          <div className="desktop-project-tree" aria-label="项目与线程">
            {treeGroups.map((group) => {
              const expanded = expandedGroups[group.key] ?? false;
              const activeProject = selectedProject === routeProjectValue(group);
              const containsActiveSession = group.sessions.some(
                (session) => session.id === currentSessionId,
              );

              return (
                <section key={group.key} className="desktop-tree-group">
                  <button
                    type="button"
                    className={`desktop-tree-folder ${
                      activeProject || containsActiveSession
                        ? "desktop-tree-folder-active"
                        : ""
                    }`}
                    onClick={() => {
                      toggleGroup(group.key);
                      selectProject(routeProjectValue(group));
                      if (pathname !== "/sessions") {
                        router.push(
                          `/sessions?project=${encodeURIComponent(
                            routeProjectValue(group),
                          )}`,
                        );
                        return;
                      }
                      router.replace(
                        `/sessions?project=${encodeURIComponent(
                          routeProjectValue(group),
                        )}`,
                      );
                    }}
                  >
                    <span className="desktop-tree-chevron">
                      {expanded ? "▾" : "▸"}
                    </span>
                    <span className="desktop-tree-folder-name">{group.label}</span>
                    {group.draftOnly && <span className="desktop-tree-tag">草稿</span>}
                  </button>
                  <div className="desktop-tree-folder-path">
                    {shortenPath(group.path)}
                  </div>
                  {expanded && (
                    <div className="desktop-tree-children">
                      {group.draftOnly ? (
                        <button
                          type="button"
                          className="desktop-tree-draft"
                          onClick={() => {
                            selectProject(routeProjectValue(group));
                            router.push(
                              `/sessions?project=${encodeURIComponent(
                                routeProjectValue(group),
                              )}`,
                            );
                          }}
                        >
                          等待创建首个线程
                        </button>
                      ) : (
                        group.sessions.map((session) => (
                          <button
                            key={session.id}
                            type="button"
                            className={`desktop-tree-session ${
                              currentSessionId === session.id
                                ? "desktop-tree-session-active"
                                : ""
                            }`}
                            onClick={() => router.push(`/sessions/${session.id}`)}
                          >
                            <span className="desktop-tree-session-title">
                              {session.title || session.id}
                            </span>
                            <span className="desktop-tree-session-meta">
                              {timeAgo(session.updatedAt)}
                            </span>
                          </button>
                        ))
                      )}
                    </div>
                  )}
                </section>
              );
            })}
          </div>

          <div className="desktop-sidebar-footer">
            <div className="desktop-footer-meta">
              {groups.length} 个项目文件夹
            </div>
          </div>
        </aside>

        <div className="desktop-content-shell">
          <div className="desktop-topbar">
            <div className="desktop-topbar-spacer" />
            <div className="desktop-topbar-actions">
              <button
                type="button"
                className="desktop-topbar-btn"
                onClick={() => router.push("/sessions/new")}
              >
                新线程
              </button>
              <button
                type="button"
                className="desktop-topbar-btn"
                onClick={() => router.push("/inbox")}
              >
                Inbox
              </button>
              <button
                type="button"
                className="desktop-topbar-btn"
                onClick={() => void handleLogout()}
              >
                退出
              </button>
            </div>
          </div>
          <main className="desktop-content">{children}</main>
        </div>
      </div>
    </div>
  );
}
