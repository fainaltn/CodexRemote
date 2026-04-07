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
      <div className="panel" style={{ width: "min(100%, 420px)" }}>
        <div className="stack" style={{ alignItems: "center", gap: 12 }}>
          <div className="spinner" />
          <div style={{ textAlign: "center" }}>
            <div className="panel-header" style={{ justifyContent: "center" }}>
              <h2>CodexRemote</h2>
            </div>
            <div className="panel-meta">Preparing the workspace...</div>
          </div>
        </div>
      </div>
    </div>
  );
}
