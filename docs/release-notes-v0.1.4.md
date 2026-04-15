# Release Notes v0.1.4

## Summary

`v0.1.4` focuses on front-end experience polish across web and Android, with special attention to session detail readability and a more cohesive visual system.

## Highlights

- Introduced the `Precision Console` visual direction across the product
- Upgraded web login, session navigation, session detail, and composer surfaces
- Upgraded Android login, session list, session detail timeline, and composer surfaces
- Unified empty, loading, upload, and error feedback into a more consistent treatment
- Compressed Android session-detail chrome so Codex output gets more usable screen space
- Improved Android command controls so follow-up actions and runtime knobs feel closer to a compact command bar

## Versioning

- Android `versionName`: `0.1.4`
- Android `versionCode`: `5`
- Workspace package versions synced to `0.1.4`

## Validation

- `npm run typecheck --workspace @codexremote/web`
- `npm run build --workspace @codexremote/web`
- `./gradlew :app:compileDebugKotlin`

## Delivery Artifact

- Dated APK output: `YYYY-MM-DD_CodexRemote_v0.1.4.apk`
