"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/lib/auth";
import {
  browseProjects,
  upsertDraftProject as addDraftProject,
  type BrowseProjectsResponse,
} from "@/lib/api";

function NewSessionPageInner() {
  const { isAuthenticated, isLoading: authLoading } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();
  const initialCwd = searchParams.get("cwd") ?? undefined;

  const [browser, setBrowser] = useState<BrowseProjectsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [adding, setAdding] = useState(false);

  async function load(path?: string) {
    try {
      setLoading(true);
      setError("");
      const next = await browseProjects(path);
      setBrowser(next);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载目录失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (authLoading) return;
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    void load(initialCwd);
  }, [authLoading, initialCwd, isAuthenticated, router]);

  async function handleAddProject() {
    if (!browser || adding) return;
    try {
      setAdding(true);
      setError("");
      addDraftProject(browser.currentPath);
      router.replace("/sessions");
    } catch (err) {
      setError(err instanceof Error ? err.message : "加入会话列表失败");
    } finally {
      setAdding(false);
    }
  }

  return (
    <div className="screen new-session-screen">
      <header className="header new-session-header" role="banner">
        <div className="header-left new-session-header-left">
          <button className="back-btn" onClick={() => router.push("/sessions")}>
            ←
          </button>
          <h1>选择项目</h1>
        </div>
      </header>

      <div className="content new-session-content">
        <div className="new-session-layout new-session-workspace">
          <section className="project-browser-card new-session-browser">
            <div className="project-browser-top">
              <div>
                <div className="project-browser-label">当前项目目录</div>
                <div className="project-browser-path">
                  {browser?.currentPath ?? "加载中…"}
                </div>
              </div>
              <div className="project-browser-actions">
                {browser?.parentPath && (
                  <button
                    className="icon-btn"
                    onClick={() => void load(browser.parentPath ?? undefined)}
                    title="上一级"
                  >
                    ↑
                  </button>
                )}
                <button
                  className="icon-btn"
                  onClick={() => void load(browser?.currentPath)}
                  title="刷新目录"
                >
                  ↻
                </button>
              </div>
            </div>

            {loading ? (
              <div className="history-empty">
                <div className="history-empty-title">正在读取主机目录</div>
                <div className="history-empty-copy">
                  会话会从当前 workspace 的可进入目录里生成，完成后你可以先加入草稿列表。
                </div>
              </div>
            ) : (
              <div className="project-entry-list">
                {browser?.entries.map((entry) => (
                  <button
                    key={entry.path}
                    className="project-entry-btn"
                    onClick={() => void load(entry.path)}
                  >
                    <span>{entry.name}</span>
                    <span>›</span>
                  </button>
                ))}
                {browser?.entries.length === 0 && (
                  <div className="history-empty">
                    <div className="history-empty-title">
                      这个目录下没有可进入的子文件夹
                    </div>
                    <div className="history-empty-copy">
                      返回上一级继续浏览，或者直接把当前目录加入会话列表。
                    </div>
                  </div>
                )}
              </div>
            )}
          </section>

          <aside className="project-browser-card new-session-inspector">
            <div className="history-header">
              <span>项目草稿</span>
              <span className="history-count">先加入列表，之后再创建首个线程</span>
            </div>

            <div className="stack">
              <div className="history-empty">
                <div className="history-empty-title">先把目录放进会话列表</div>
                <div className="history-empty-copy">
                  右上角的 + 用来挑选主机目录。选定当前目录后，它会先作为草稿保留，等你回到会话页再创建首个线程。
                </div>
              </div>
              <div className="panel" style={{ padding: 12 }}>
                <div className="panel-header">
                  <h2>桌面流程</h2>
                  <span className="panel-meta">{browser?.currentPath ? "已选目录" : "未加载"}</span>
                </div>
                <div className="stack">
                  <div className="meta-row">
                    <span className="meta-label">浏览</span>
                    <span className="meta-value">在左侧目录树里切换</span>
                  </div>
                  <div className="meta-row">
                    <span className="meta-label">加入</span>
                    <span className="meta-value">把当前目录先放进会话列表</span>
                  </div>
                  <div className="meta-row">
                    <span className="meta-label">创建</span>
                    <span className="meta-value">回到会话页再开首个线程</span>
                  </div>
                </div>
              </div>

              {error && <div className="error-message" role="alert">{error}</div>}

              <button
                className="btn-primary new-session-submit"
                onClick={handleAddProject}
                disabled={!browser || adding}
              >
                {adding ? "正在加入…" : "将当前目录加入会话列表"}
              </button>
            </div>
          </aside>
        </div>
      </div>
    </div>
  );
}

export default function NewSessionPage() {
  return (
    <Suspense
      fallback={
        <div className="loading-screen">
          <div className="spinner" />
        </div>
      }
    >
      <NewSessionPageInner />
    </Suspense>
  );
}
