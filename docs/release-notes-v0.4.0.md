# Release Notes v0.4.0

## Summary

`v0.4.0` is the release where findeck Android becomes much closer to a deliberate mobile product: trusted reconnect is now real, archived-session management is first-class, the composer can reference real files and skills, and settings have expanded beyond password changes into a usable preference surface.

## Highlights

- Added one-time pairing codes plus trusted-client reconnect for Android
- Promoted archived sessions into a dedicated mobile flow with restore support
- Added lightweight smart composer suggestions for `/`, `@`, and `$`
- Wired `@` suggestions to real workspace file browsing and search
- Wired `$` suggestions to real skill discovery from repo-local and user-home sources
- Upgraded several high-frequency slash commands into native mobile actions
- Expanded Android settings with appearance, notification status, runtime defaults, and richer pairing metadata
- Added calmer in-app completion and repo-success feedback for foreground usage

## Versioning

- Android `versionName`: `0.4.0`
- Android `versionCode`: `10`
- Workspace package versions synced to `0.4.0`

## Validation

- `npm run build --workspace @findeck/shared`
- `npm run build --workspace @findeck/server`
- `npm run test --workspace @findeck/server -- src/__tests__/pairing-store.test.ts src/__tests__/pairing.test.ts src/__tests__/session-archive.test.ts src/__tests__/composer-ux.test.ts`
- `cd apps/android && ./gradlew :app:compileDebugKotlin`
- `cd apps/android && ./gradlew :app:testDebugUnitTest --tests app.findeck.mobile.ui.sessions.ComposerSuggestionsTest`
- `cd apps/android && ./gradlew :app:assembleDebug`

## Known Follow-up

- Voice input on some devices, including the Samsung Z Fold7 / Android 16 test path, still needs a compatibility follow-up.
- The mic button remains in the UI, but the device-specific speech-service integration should be treated as a `v0.4.x` follow-up instead of a `v0.4.0` release blocker.

## Delivery Artifact

- Dated APK output: `YYYY-MM-DD_findeck_v0.4.0.apk`
