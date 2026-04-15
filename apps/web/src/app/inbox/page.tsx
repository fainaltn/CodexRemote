"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import {
  type InboxItem,
  listInboxItems,
  submitInboxLink,
  uploadInboxFiles,
  uploadInboxSubmissionBundle,
} from "@/lib/api";

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleString("zh-CN");
}

function itemLabel(item: InboxItem): string {
  if (item.title) return item.title;
  if (item.kind === "link") return item.title || item.url || item.id;
  return item.originalName || item.id;
}

function kindLabel(kind: InboxItem["kind"]): string {
  return kind === "link" ? "链接" : "文件";
}

function retrySummary(item: InboxItem): string | null {
  const attemptCount = typeof item.retryPolicy?.attempt_count === "number"
    ? item.retryPolicy.attempt_count
    : item.retryAttempts?.length ?? 0;
  const finalStatus = typeof item.retryPolicy?.final_status === "string"
    ? item.retryPolicy.final_status
    : null;
  if (!attemptCount && !finalStatus) return null;
  return `重试：${attemptCount || 0} 次${finalStatus ? ` · ${finalStatus}` : ""}`;
}

export default function InboxPage() {
  const { isAuthenticated, isLoading: authLoading } = useAuth();
  const router = useRouter();
  const [items, setItems] = useState<InboxItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [url, setUrl] = useState("");
  const [title, setTitle] = useState("");
  const [note, setNote] = useState("");
  const [uploadNote, setUploadNote] = useState("");
  const [submittingLink, setSubmittingLink] = useState(false);
  const [uploadingFile, setUploadingFile] = useState(false);
  const [uploadingBundle, setUploadingBundle] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);

  const loadItems = useCallback(async () => {
    try {
      setLoading(true);
      setError("");
      const data = await listInboxItems();
      setItems(data.items);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载收件箱失败");
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
    loadItems();
  }, [authLoading, isAuthenticated, loadItems, router]);

  async function handleLinkSubmit(event: FormEvent) {
    event.preventDefault();
    try {
      setSubmittingLink(true);
      setError("");
      const item = await submitInboxLink({
        url,
        title: title || undefined,
        note: note || undefined,
        source: "web",
      });
      setItems((prev) => [item, ...prev].slice(0, 20));
      setUrl("");
      setTitle("");
      setNote("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "提交链接失败");
    } finally {
      setSubmittingLink(false);
    }
  }

  async function handleFileChange(files: FileList | null) {
    if (!files || files.length === 0) return;
    try {
      setUploadingFile(true);
      setUploadProgress(0);
      setError("");
      const item = await uploadInboxFiles(files, {
        note: uploadNote || undefined,
        source: "web",
        onProgress: (progress) => {
          const pct = progress.total > 0
            ? Math.round((progress.loaded / progress.total) * 100)
            : 0;
          setUploadProgress(pct);
        },
      });
      setItems((prev) => [item, ...prev].slice(0, 20));
      setUploadNote("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "上传文件失败");
    } finally {
      setUploadingFile(false);
      setUploadProgress(null);
    }
  }

  async function handleBundleChange(files: FileList | null) {
    if (!files || files.length === 0) return;
    try {
      setUploadingBundle(true);
      setUploadProgress(0);
      setError("");
      const item = await uploadInboxSubmissionBundle(files, {
        onProgress: (progress) => {
          const pct = progress.total > 0
            ? Math.round((progress.loaded / progress.total) * 100)
            : 0;
          setUploadProgress(pct);
        },
      });
      setItems((prev) => [item, ...prev].slice(0, 20));
    } catch (err) {
      setError(err instanceof Error ? err.message : "导入 submission bundle 失败");
    } finally {
      setUploadingBundle(false);
      setUploadProgress(null);
    }
  }

  if (authLoading) {
    return (
      <div className="loading-screen">
        <div className="spinner" />
      </div>
    );
  }

  return (
    <div className="screen">
      <div className="header">
        <div className="header-left">
          <button className="back-btn" onClick={() => router.push("/sessions")}>
            ←
          </button>
          <h1>收件箱</h1>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span className="panel-meta">Desktop inbox</span>
          <button className="icon-btn" onClick={loadItems} title="刷新">
            ↻
          </button>
        </div>
      </div>

      <div className="content inbox-page">
        {error && <div className="error-message" role="alert">{error}</div>}

        <section className="panel" aria-labelledby="inbox-link-title">
          <div className="panel-header">
            <h2 id="inbox-link-title">提交链接</h2>
            <span className="panel-meta">来自网页或分享面板</span>
          </div>
          <form className="stack" onSubmit={handleLinkSubmit}>
            <input
              className="text-input"
              type="url"
              placeholder="https://example.com/article"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              required
            />
            <input
              className="text-input"
              type="text"
              placeholder="标题（可选）"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
            <textarea
              className="text-area"
              placeholder="备注（可选）"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              rows={3}
            />
            <button className="btn-primary" disabled={submittingLink}>
              {submittingLink ? "提交中…" : "投递链接到收件箱"}
            </button>
          </form>
        </section>

        <section className="panel" aria-labelledby="inbox-upload-title">
          <div className="panel-header">
            <h2 id="inbox-upload-title">上传文件</h2>
            <span className="panel-meta">多选与目录 bundle</span>
          </div>
          <div className="stack">
            <textarea
              className="text-area"
              placeholder="备注（可选）"
              value={uploadNote}
              onChange={(e) => setUploadNote(e.target.value)}
              rows={3}
            />
            <label className="upload-picker">
              <input
                type="file"
                hidden
                multiple
                onChange={(e) => {
                  void handleFileChange(e.target.files);
                  e.currentTarget.value = "";
                }}
              />
              <span>{uploadingFile ? "上传中…" : "选择文件（可多选）"}</span>
            </label>
            <label className="upload-picker">
              <input
                type="file"
                hidden
                multiple
                {...({ webkitdirectory: "", directory: "" } as Record<string, string>)}
                onChange={(e) => {
                  void handleBundleChange(e.target.files);
                  e.currentTarget.value = "";
                }}
              />
              <span>{uploadingBundle ? "导入中…" : "导入 submission bundle 目录"}</span>
            </label>
            {uploadProgress !== null && (
              <div className="upload-progress">上传 {uploadProgress}%</div>
            )}
          </div>
        </section>

        <section className="panel" aria-labelledby="inbox-recent-title">
          <div className="panel-header">
            <h2 id="inbox-recent-title">最近投递</h2>
            <span className="panel-meta">显示 {items.length} 条</span>
          </div>

          {loading ? (
            <div className="empty-state">
              <div className="spinner" />
              <div className="empty-state-text">正在同步最近投递</div>
              <div className="empty-state-sub">
                链接、文件和 submission bundle 会重新拉回收件箱。
              </div>
            </div>
          ) : items.length === 0 ? (
            <div className="empty-state">
              <div className="empty-state-icon">📥</div>
              <div className="empty-state-text">收件箱还是空的</div>
              <div className="empty-state-sub">
                来自网页或 Android 的链接、文件和 submission bundle 会出现在这里
              </div>
            </div>
          ) : (
            <div className="inbox-list">
              {items.map((item) => (
                <article key={item.id} className="inbox-card">
                  <div className="inbox-card-top">
                    <span className="pill">{kindLabel(item.kind)}</span>
                    <span className="inbox-time">{formatTimestamp(item.createdAt)}</span>
                  </div>
                  <div className="inbox-title">{itemLabel(item)}</div>
                  {item.url && (
                    <a className="inbox-link" href={item.url} target="_blank" rel="noreferrer">
                      {item.url}
                    </a>
                  )}
                  {item.note && <div className="inbox-note">{item.note}</div>}
                  {(item.contract || item.hasReviewBundle || item.hasSkillRunbook) && (
                    <div className="inbox-note">
                      {item.submissionId ? `submission: ${item.submissionId}` : item.id}
                      {item.contract ? ` · ${item.contract}` : ""}
                      {item.hasReviewBundle ? " · 含 review bundle" : ""}
                      {item.hasSkillRunbook ? " · 含 skill-runbook" : ""}
                    </div>
                  )}
                  {item.captureSessions && item.captureSessions.length > 0 && (
                    <div className="inbox-note">
                      capture sessions: {item.captureSessions.length}
                    </div>
                  )}
                  {retrySummary(item) && (
                    <div className="inbox-note">{retrySummary(item)}</div>
                  )}
                  <div className="inbox-meta">
                    {item.source ? `来源：${item.source}` : "来源：未知"}
                    {item.sizeBytes !== null ? ` · ${item.sizeBytes} 字节` : ""}
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
