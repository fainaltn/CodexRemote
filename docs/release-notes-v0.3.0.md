# Release Notes v0.3.0

## Summary

`v0.3.0` is the release where CodexRemote starts feeling more like a deliberate product than a loose set of tools: startup is clearer, Android session detail is calmer and more message-first, background recovery is easier to trust, and release handoff is more repeatable.

## Highlights

- Added a clearer repo-local operator flow around CodexRemote startup, status, logs, restart, web, and doctor entry points
- Reframed Android and web weak-network handling around recovery, degraded, and catch-up states instead of treating every interruption as a hard failure
- Upgraded Android session detail into a message-first current-turn timeline with stable collapsed reply groups and calmer live-tail rendering
- Added stronger message identity plumbing with `turnId`, `itemId`, and `orderIndex` across shared schemas, server extraction, and Android projection logic
- Expanded Android notification handling into more explicit attention tiers and deep-link-friendly background return behavior
- Added release and APK handoff automation for GitHub releases and LAN SMB delivery artifacts

## Versioning

- Android `versionName`: `0.3.0`
- Android `versionCode`: `9`
- Workspace package versions synced to `0.3.0`

## Validation

- `npm run typecheck --workspace @codexremote/web`
- `npm run build --workspace @codexremote/web`
- `cd apps/android && ./gradlew :app:compileDebugKotlin`

## Delivery Artifact

- Dated APK output: `YYYY-MM-DD_CodexRemote_v0.3.0.apk`
