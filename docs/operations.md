# Operations

## Environment

Copy `.env.example` to `.env.local` and set:

- `CODEXREMOTE_PASSWORD`: required
- `HOST`: optional, defaults to `0.0.0.0`
- `PORT`: optional, defaults to `31807`
- `CODEXREMOTE_API_URL`: web runtime target, defaults to `http://127.0.0.1:31807`

## Local startup

```bash
npm install
npm run codexremote -- doctor
npm run codexremote -- up
```

The unified helper will build missing production artifacts before installing or refreshing the local launchd services on macOS.

Development:

```bash
npm run dev --workspace @codexremote/server
npm run dev --workspace @codexremote/web -- --port 31817
```

Production-style local run:

```bash
./scripts/codexremote-server.sh
./scripts/codexremote-web.sh
```

## Unified Control

The repo-local operator entrypoint is:

```bash
npm run codexremote -- <command>
```

Supported commands:

- `up`
- `status`
- `logs`
- `restart`
- `web`
- `doctor`

Examples:

```bash
npm run codexremote -- status
npm run codexremote -- logs
npm run codexremote -- web
```

## launchd

Install launch agents:

```bash
npm run codexremote -- up
```

Inspect services:

```bash
npm run codexremote -- status
npm run codexremote -- logs
```

launchd launchers and logs live under:

- `~/Library/Application Support/CodexRemote/launchd/`
- `~/Library/Logs/CodexRemote/`

This avoids macOS permission issues caused by pointing launchd directly at scripts and logs inside the repository tree.

## Inbox cleanup

Recent inbox/import history is backend state. To clear it, you only need to clean
the backend database rows and corresponding staging directories:

```bash
./scripts/clear-inbox.sh --host-id local
```

Use `--dry-run` to inspect matches first, and add `--submission-id` or
`--title-contains` for narrower cleanup. This does not require an APK update
unless you want a visible delete button in the app.
