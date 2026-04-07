# Release Checklist

## Verified in this workspace

- `cd apps/server && ../../node_modules/.bin/vitest run src/__tests__/codex-cli-spawn.test.ts src/__tests__/codex-local-new-session.test.ts src/__tests__/codex-local-start-run.test.ts`
- `cd apps/server && ../../node_modules/.bin/tsc --noEmit`
- `npm test --workspace @codexremote/web`
- `npm run build --workspace @codexremote/server`
- `npm run build --workspace @codexremote/web`
- `cd apps/android && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools gradle :app:assembleDebug`
- resumed-session manual check: resume an existing thread and verify it can create a file under the project root

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
