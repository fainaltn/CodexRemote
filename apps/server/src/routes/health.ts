import type { FastifyInstance } from "fastify";
import { getDb } from "../db.js";
import { auditArtifactConsistency, getDiskSpaceInfo } from "../artifacts/store.js";
import type { RunManager } from "../runs/manager.js";
import {
  countRecentAuditEvents,
  getAuditWriteErrorCount,
  getAuditReadErrorCount,
} from "../audit/log.js";

/**
 * GET /api/health
 *
 * Performs real checks against the database and artifact store so
 * operators can detect failures rather than getting a misleading "ok".
 *
 * Returns 200 with `status: "ok"` when everything is healthy, or
 * 200 with `status: "degraded"` and a `checks` object describing
 * what failed.  The endpoint itself never returns 5xx — it is the
 * observability surface, not a failing endpoint.
 */
export function healthRoutes(runManager: RunManager) {
  return async function register(app: FastifyInstance): Promise<void> {
    app.get("/api/health", async (_request, reply) => {
      const checks: Record<string, unknown> = {};
      let degraded = false;

      // ── Database connectivity ──────────────────────────────────────
      try {
        const db = getDb();
        const row = db.prepare("SELECT 1 AS ok").get() as { ok: number } | undefined;
        if (row?.ok !== 1) throw new Error("unexpected result");
        checks.database = "ok";
      } catch (err) {
        checks.database = `error: ${err instanceof Error ? err.message : String(err)}`;
        degraded = true;
      }

      // ── Artifact consistency ───────────────────────────────────────
      try {
        const report = await auditArtifactConsistency();
        checks.artifacts = {
          totalRows: report.totalDbRows,
          missingFiles: report.missingFiles.length,
          orphanedFiles: report.orphanedFiles.length,
        };
        if (report.missingFiles.length > 0 || report.orphanedFiles.length > 0) {
          degraded = true;
        }
      } catch (err) {
        checks.artifacts = `error: ${err instanceof Error ? err.message : String(err)}`;
        degraded = true;
      }

      // ── Active runs / stale run detection ──────────────────────────
      try {
        const activeRuns = runManager.getActiveRuns();
        const staleRuns = activeRuns.filter((r) => r.stale);
        checks.runs = {
          active: activeRuns.length,
          stale: staleRuns.length,
        };
        if (staleRuns.length > 0) {
          degraded = true;
          checks.staleRuns = staleRuns.map((r) => ({
            runId: r.runId,
            sessionId: r.sessionId,
            elapsedMs: r.elapsedMs,
          }));
        }
      } catch (err) {
        checks.runs = `error: ${err instanceof Error ? err.message : String(err)}`;
        degraded = true;
      }

      // ── Cumulative DB write errors ─────────────────────────────────
      const dbWriteErrors = runManager.getDbWriteErrorCount();
      checks.dbWriteErrors = dbWriteErrors;
      if (dbWriteErrors > 0) {
        degraded = true;
      }

      // ── Audit log summary (last 24 h) ─────────────────────────────
      const auditResult = countRecentAuditEvents();
      const auditWriteErrors = getAuditWriteErrorCount();
      const auditReadErrors = getAuditReadErrorCount();

      if (auditResult.ok) {
        const auditSummary: Record<string, number> = {};
        for (const row of auditResult.counts) {
          auditSummary[row.eventType] = row.count;
        }
        checks.audit24h = auditSummary;
      } else {
        checks.audit24h = `error: ${auditResult.error}`;
        degraded = true;
      }

      if (auditWriteErrors > 0 || auditReadErrors > 0) {
        checks.auditErrors = {
          writeErrors: auditWriteErrors,
          readErrors: auditReadErrors,
        };
        degraded = true;
      }

      // ── Disk space ─────────────────────────────────────────────────
      const diskInfo = await getDiskSpaceInfo();
      if (diskInfo) {
        checks.disk = {
          totalBytes: diskInfo.totalBytes,
          freeBytes: diskInfo.freeBytes,
          low: diskInfo.low,
        };
        if (diskInfo.low) {
          degraded = true;
        }
      } else {
        checks.disk = "error: unable to query filesystem stats";
        degraded = true;
      }

      // ── Storage metrics ────────────────────────────────────────────
      try {
        const db = getDb();
        const storageSums = db
          .prepare(
            `SELECT
               (SELECT COUNT(*) FROM artifacts) AS artifactCount,
               (SELECT COALESCE(SUM(size_bytes), 0) FROM artifacts) AS artifactTotalBytes,
               (SELECT COUNT(*) FROM inbox_items) AS inboxItemCount,
               (SELECT COALESCE(SUM(COALESCE(size_bytes, 0)), 0) FROM inbox_items) AS inboxTotalBytes`,
          )
          .get() as {
            artifactCount: number;
            artifactTotalBytes: number;
            inboxItemCount: number;
            inboxTotalBytes: number;
          };
        checks.storage = {
          artifactCount: storageSums.artifactCount,
          artifactTotalBytes: storageSums.artifactTotalBytes,
          inboxItemCount: storageSums.inboxItemCount,
          inboxTotalBytes: storageSums.inboxTotalBytes,
        };
      } catch (err) {
        checks.storage = `error: ${err instanceof Error ? err.message : String(err)}`;
        degraded = true;
      }

      const status = degraded ? ("degraded" as const) : ("ok" as const);
      return reply.send({ status, checks });
    });
  };
}
