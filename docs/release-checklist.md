# Release Checklist

## Release focus for v0.1.3

- Android session detail now behaves like a stronger mobile control console rather than a plain chat view
- Android supports runtime controls for model and reasoning effort before sending a turn
- Android session detail shows read-only repo status plus repo actions for branch create/switch, commit, and push
- Session timeline readability is improved with clearer waiting, degraded-stream, failure, and history-round states
- Codex session discovery now aggregates metadata and messages across multiple rollout files for one session id

## Verified in this workspace

- `cd apps/server && ../../node_modules/.bin/vitest run src/__tests__/codex-cli-spawn.test.ts src/__tests__/codex-local-new-session.test.ts src/__tests__/codex-local-start-run.test.ts`
- `cd apps/server && ../../node_modules/.bin/vitest run src/__tests__/codex-sessions.test.ts src/__tests__/repo-status.test.ts src/__tests__/repo-actions.test.ts`
- `cd apps/server && ../../node_modules/.bin/tsc --noEmit`
- `npm run build --workspace @codexremote/shared`
- `npm test --workspace @codexremote/web`
- `npm run build --workspace @codexremote/server`
- `npm run build --workspace @codexremote/web`
- `cd apps/android && ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools ./gradlew :app:assembleDebug`
- resumed-session manual check: resume an existing thread and verify it can create a file under the project root
- Android manual check: session detail shows final replies without requiring re-entry after a completed run
- Android manual check: queued follow-up prompt sends after the active run finishes
- Android manual check: runtime control chips change the next run's model / reasoning settings
- Android manual check: repo surface shows branch / dirty-state and repo actions complete successfully when no run is active
- Android manual check: settings can change the server password, the service restarts, and the app can reconnect with the new password

## Before publishing to GitHub

- set a fresh password in `.env.local`
- if you changed the password through the app, confirm `.env.local` and the generated launchd plist agree on the same value before release
- do not commit `.env.local`, `data/`, `.run/`, or build outputs
- if needed, clear local inbox history with `./scripts/clear-inbox.sh --host-id local`
- ensure generated launchd files in `~/Library/LaunchAgents` are not copied back into the repo
- ensure generated launchd launcher scripts in `~/Library/Application Support/CodexRemote/launchd/` are not copied back into the repo
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
