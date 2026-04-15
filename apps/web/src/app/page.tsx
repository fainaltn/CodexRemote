"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

/**
 * Root page — redirects to /sessions (authenticated) or /login (guest).
 */
export default function Home() {
  const { isAuthenticated, isLoading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (isLoading) return;
    router.replace(isAuthenticated ? "/sessions" : "/login");
  }, [isAuthenticated, isLoading, router]);

  return (
    <div className="loading-screen">
      <main
        className="panel"
        style={{
          width: "min(100%, 560px)",
          padding: 24,
          overflow: "hidden",
        }}
        aria-label="Preparing CodexRemote"
      >
        <div
          style={{
            display: "grid",
            gap: 18,
          }}
        >
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 14,
            }}
          >
            <div className="spinner" aria-hidden="true" />
            <div style={{ minWidth: 0 }}>
              <div
                style={{
                  fontSize: 12,
                  fontWeight: 800,
                  letterSpacing: "0.22em",
                  textTransform: "uppercase",
                  color: "var(--text-muted)",
                  marginBottom: 6,
                }}
              >
                Precision Console
              </div>
              <h1 style={{ fontSize: 28, lineHeight: 1.05, marginBottom: 8 }}>
                Preparing secure workspace
              </h1>
              <p style={{ color: "var(--text-secondary)", fontSize: 15 }}>
                Verifying access, restoring your sessions, and getting the command
                surface ready.
              </p>
            </div>
          </div>

          <div
            style={{
              display: "grid",
              gap: 10,
            }}
          >
            {[
              "Session navigation",
              "Inbox and run status",
              "Live streaming updates",
            ].map((item) => (
              <div
                key={item}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                  padding: "12px 14px",
                  borderRadius: "var(--radius-sm)",
                  border: "1px solid var(--border-subtle)",
                  background: "color-mix(in srgb, var(--surface) 82%, transparent)",
                }}
              >
                <span
                  aria-hidden="true"
                  style={{
                    width: 9,
                    height: 9,
                    borderRadius: 999,
                    background: "var(--accent)",
                    boxShadow: "0 0 0 6px color-mix(in srgb, var(--accent-muted) 60%, transparent)",
                    flexShrink: 0,
                  }}
                />
                <span style={{ fontSize: 14, fontWeight: 700 }}>{item}</span>
              </div>
            ))}
          </div>

          <div
            style={{
              display: "flex",
              flexWrap: "wrap",
              gap: 8,
              color: "var(--text-muted)",
              fontSize: 13,
            }}
          >
            <span className="pill">Graphite surfaces</span>
            <span className="pill">Electric blue signal</span>
            <span className="pill">Trusted entry</span>
          </div>
        </div>
      </main>
    </div>
  );
}
