"use client";

import { useState, useEffect, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

export default function LoginPage() {
  const { login, isAuthenticated, isLoading } = useAuth();
  const router = useRouter();
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  // Redirect authenticated users to /sessions via an effect (not during render).
  useEffect(() => {
    if (!isLoading && isAuthenticated) {
      router.replace("/sessions");
    }
  }, [isLoading, isAuthenticated, router]);

  if (!isLoading && isAuthenticated) {
    return null;
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!password.trim()) return;

    setError("");
    setSubmitting(true);
    try {
      await login(password);
      router.replace("/sessions");
    } catch (err) {
      setError(err instanceof Error ? err.message : "登录失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="login-screen">
      <main
        className="login-layout"
        style={{
          width: "min(100%, 1120px)",
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(300px, 1fr))",
          gap: 20,
          alignItems: "stretch",
        }}
      >
        <section className="panel" aria-labelledby="login-hero-title">
          <div className="panel-header">
            <div>
              <h2 id="login-hero-title">CodexRemote</h2>
              <p className="panel-meta">Desktop-first remote control surface</p>
            </div>
            <span className="pill">PC</span>
          </div>
          <div className="stack" style={{ gap: 14 }}>
            <p style={{ color: "var(--text-secondary)", margin: 0 }}>
              Use this browser workspace to steer Codex sessions, review the inbox,
              and follow live runs without the phone-first framing.
            </p>
            <div
              style={{
                display: "flex",
                flexWrap: "wrap",
                gap: 8,
              }}
            >
              <span className="pill">Sessions</span>
              <span className="pill">Inbox</span>
              <span className="pill">Live runs</span>
              <span className="pill">Desktop</span>
            </div>
            <div className="panel-meta">
              Optimized for wide screens, utility-driven headers, and denser panels.
            </div>
          </div>
        </section>

        <section className="login-card panel" aria-labelledby="login-form-title">
          <div className="login-header">
            <h2 id="login-form-title">Sign in</h2>
            <p>Enter the host password to unlock the workspace.</p>
          </div>

          <form onSubmit={handleSubmit}>
            <input
              type="password"
              placeholder="请输入密码"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoFocus
              autoComplete="current-password"
              disabled={submitting}
            />
            {error && <div className="error-message">{error}</div>}
            <button
              type="submit"
              className="btn-primary"
              disabled={submitting || !password.trim()}
            >
              {submitting ? "登录中…" : "登录"}
            </button>
          </form>
        </section>
      </main>
    </div>
  );
}
