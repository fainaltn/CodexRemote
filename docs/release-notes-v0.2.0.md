# Release Notes v0.2.0

## Summary

`v0.2.0` turns the Android client into a more complete mobile workbench: bilingual UI, smoother background recovery, richer repo controls, media attachment intake, and voice input that feels native to the session-detail workflow.

## Highlights

- Added Chinese / English system-language switching across the Android main flow
- Upgraded Android session detail with gentler lock-screen and background recovery
- Added background run-completion notifications with session-detail routing
- Added voice input with an in-app recording capsule, waveform feedback, and unified transcript handling
- Added gallery and camera attachment entry points alongside the existing file picker
- Expanded Android Git controls with `pull`, `stash`, and recent commit log

## Versioning

- Android `versionName`: `0.2.0`
- Android `versionCode`: `6`
- Workspace package versions synced to `0.2.0`

## Validation

- `npm run build --workspace @codexremote/shared`
- `npm run build --workspace @codexremote/server`
- `npm run test --workspace @codexremote/server -- repo-actions.test.ts`
- `cd apps/android && ./gradlew :app:compileDebugKotlin`

## Delivery Artifact

- Dated APK output: `YYYY-MM-DD_CodexRemote_v0.2.0.apk`
