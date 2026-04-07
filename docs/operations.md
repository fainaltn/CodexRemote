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
npm run build --workspace @codexremote/shared
npm run build --workspace @codexremote/server
npm run build --workspace @codexremote/web
```

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

## launchd

Install launch agents:

```bash
./scripts/install-launchd.sh
```

Inspect services:

```bash
./scripts/codexremotectl.sh status
./scripts/codexremotectl.sh logs
```

## Inbox cleanup

Recent inbox/import history is backend state. To clear it, you only need to clean
the backend database rows and corresponding staging directories:

```bash
./scripts/clear-inbox.sh --host-id local
```

Use `--dry-run` to inspect matches first, and add `--submission-id` or
`--title-contains` for narrower cleanup. This does not require an APK update
unless you want a visible delete button in the app.
