# Release Checklist

## Verified in this workspace

- `npm test --workspace @codexremote/server`
- `npm test --workspace @codexremote/web`
- `npm run build --workspace @codexremote/server`
- `npm run build --workspace @codexremote/web`
- `cd apps/android && ./gradlew assembleDebug`

## Before publishing to GitHub

- set a fresh password in `.env.local`
- do not commit `.env.local`, `data/`, `.run/`, or build outputs
- if needed, clear local inbox history with `./scripts/clear-inbox.sh --host-id local`
- ensure generated launchd files in `~/Library/LaunchAgents` are not copied back into the repo
- review Android `local.properties` stays local-only
- keep `apps/android/local.properties.example` as the committed template, not your real SDK path

## Expected ignored outputs

- `node_modules/`
- `apps/web/.next/`
- `apps/server/dist/`
- `packages/shared/dist/`
- `apps/android/.gradle/`
- `apps/android/app/build/`
- `data/`
- `.run/`
