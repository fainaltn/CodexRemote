import { buildApp } from "./app.js";
import { assertPasswordConfigured } from "./auth/store.js";
import { PORT, HOST, validateConfig, logEffectiveConfig } from "./config.js";
import { initDb, closeDb } from "./db.js";
import { gracefulShutdown } from "./shutdown.js";

async function main() {
  // Fail fast if the operator forgot to set a password.
  assertPasswordConfigured();

  // Fail fast if any env-var configuration is invalid or violates
  // cross-constraints (e.g. upload timeout >= request timeout).
  validateConfig();

  // Initialise the SQLite database and run pending migrations.
  initDb();

  const { app, runManager } = await buildApp({ logger: true });

  // Log the effective configuration so operators can verify their
  // setup at a glance — especially valuable during first deployment.
  logEffectiveConfig(app.log);

  // ── Graceful shutdown ──────────────────────────────────────────
  let shuttingDown = false;

  async function shutdown(signal: string) {
    if (shuttingDown) return;
    shuttingDown = true;

    await gracefulShutdown(signal, {
      shutdownAll: () => runManager.shutdownAll(),
      closeApp: () => app.close(),
      closeDb,
      log: {
        info: (msg) => app.log.info(msg),
        error: (err, msg) => app.log.error(err, msg),
      },
      exit: (code) => process.exit(code),
    });
  }

  process.on("SIGTERM", () => shutdown("SIGTERM"));
  process.on("SIGINT", () => shutdown("SIGINT"));

  try {
    await app.listen({ port: PORT, host: HOST });
  } catch (err) {
    app.log.error(err);
    process.exit(1);
  }
}

main();
