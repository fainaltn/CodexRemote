# Release Notes v0.2.1

## Summary

`v0.2.1` continues the Android-native console push with a steadier two-phase reply experience, richer mobile input flows, stronger background recovery, and repo controls that are more useful during real session work.

## Highlights

- Added bilingual Android string resources across the main entry, session list, session detail, media, notifications, and control surfaces
- Smoothed Android session-detail live output so the current round keeps its streaming context visible through thinking, streaming, and final-settle transitions
- Added Android voice input, recording capsule feedback, notification routing, and photo / camera attachment entry points
- Expanded Android repo tools with `pull`, `stash`, and recent commit log access
- Added server support and tests for repo log, pull, stash, and more durable artifact path resolution

## Versioning

- Android `versionName`: `0.2.1`
- Android `versionCode`: `7`
- Workspace package versions synced to `0.2.1`

## Validation

- `npm run typecheck --workspace @codexremote/web`
- `npm run build --workspace @codexremote/web`
- `cd apps/android && ./gradlew :app:compileDebugKotlin`

## Delivery Artifact

- Dated APK output: `YYYY-MM-DD_CodexRemote_v0.2.1.apk`
