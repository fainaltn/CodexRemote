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
          width: "min(100%, 1180px)",
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(320px, 1fr))",
          gap: 20,
          alignItems: "stretch",
        }}
      >
        <section
          className="panel"
          aria-labelledby="login-hero-title"
          style={{
            padding: 24,
            position: "relative",
            overflow: "hidden",
          }}
        >
          <div
            aria-hidden="true"
            style={{
              position: "absolute",
              inset: -1,
              background:
                "radial-gradient(circle at top right, color-mix(in srgb, var(--primary-muted) 40%, transparent), transparent 32%), radial-gradient(circle at bottom left, color-mix(in srgb, var(--accent-muted) 28%, transparent), transparent 26%)",
              pointerEvents: "none",
            }}
          />
          <div className="panel-header" style={{ position: "relative" }}>
            <div>
              <div
                style={{
                  fontSize: 12,
                  fontWeight: 800,
                  letterSpacing: "0.22em",
                  textTransform: "uppercase",
                  color: "var(--text-muted)",
                  marginBottom: 8,
                }}
              >
                Precision Console
              </div>
              <h1 id="login-hero-title" style={{ fontSize: 34, lineHeight: 1.02, marginBottom: 10 }}>
                Unlock a secure remote workspace
              </h1>
              <p
                style={{
                  maxWidth: 38 * 16,
                  color: "var(--text-secondary)",
                  fontSize: 16,
                }}
              >
                CodexRemote turns the browser into a calm command surface for
                sessions, inbox review, and live run supervision.
              </p>
            </div>
            <span className="pill">Web</span>
          </div>
          <div className="stack" style={{ gap: 14, position: "relative" }}>
            <div
              style={{
                display: "grid",
                gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
                gap: 12,
              }}
            >
              {[
                { title: "Curated sessions", meta: "Find the current thread fast" },
                { title: "Live execution", meta: "Track streaming work in context" },
                { title: "Inbox review", meta: "Separate follow-ups from history" },
              ].map((item) => (
                <div
                  key={item.title}
                  style={{
                    padding: 14,
                    borderRadius: "var(--radius-sm)",
                    border: "1px solid var(--border-subtle)",
                    background: "color-mix(in srgb, var(--surface) 82%, transparent)",
                  }}
                >
                  <div style={{ fontSize: 15, fontWeight: 800, marginBottom: 6 }}>{item.title}</div>
                  <div style={{ color: "var(--text-muted)", fontSize: 13, lineHeight: 1.5 }}>{item.meta}</div>
                </div>
              ))}
            </div>
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
              <span className="pill">Workspace unlock</span>
            </div>
            <div className="panel-meta">
              Built for precise control, clear hierarchy, and low-friction entry.
            </div>
          </div>
        </section>

        <section
          className="login-card panel"
          aria-labelledby="login-form-title"
          style={{
            padding: 24,
          }}
        >
          <div className="login-header" style={{ textAlign: "left", marginBottom: 24 }}>
            <div
              style={{
                fontSize: 12,
                fontWeight: 800,
                letterSpacing: "0.22em",
                textTransform: "uppercase",
                color: "var(--text-muted)",
                marginBottom: 8,
              }}
            >
              Secure entry
            </div>
            <h2 id="login-form-title" style={{ fontSize: 24, marginBottom: 8 }}>
              Enter host password
            </h2>
            <p>
              This unlocks the workspace and keeps the control surface gated until
              the host is verified.
            </p>
          </div>

          <form onSubmit={handleSubmit} style={{ gap: 14 }}>
            <div style={{ display: "grid", gap: 8 }}>
              <label
                htmlFor="workspace-password"
                style={{
                  fontSize: 13,
                  fontWeight: 700,
                  color: "var(--text-secondary)",
                }}
              >
                Host password
              </label>
              <input
                id="workspace-password"
                type="password"
                placeholder="Enter password to unlock"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoFocus
                autoComplete="current-password"
                disabled={submitting}
              />
            </div>
            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                gap: 12,
                flexWrap: "wrap",
                color: "var(--text-muted)",
                fontSize: 13,
              }}
            >
              <span>Press Enter to unlock once the password is filled.</span>
              <span className="pill">Protected session access</span>
            </div>
            {error && <div className="error-message">{error}</div>}
            <button type="submit" className="btn-primary" disabled={submitting || !password.trim()}>
              {submitting ? "Unlocking workspace…" : "Unlock workspace"}
            </button>
            <div className="panel-meta" style={{ textAlign: "center" }}>
              Need the wrong host? Check the server password before retrying.
            </div>
          </form>
        </section>
      </main>
    </div>
  );
}
