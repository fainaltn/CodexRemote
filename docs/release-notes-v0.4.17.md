# Release Notes v0.4.17

## Summary

`v0.4.17` closes the remaining Android-only next-phase plan items after `v0.4.11` and prepares the Android line for the upcoming `findeck` `0.5` package migration.

This release focuses on content fidelity, quieter first-screen presentation, settings-language consistency, and a clear stopping line for Android feature expansion.

## Highlights

- Added markdown table parsing plus a dedicated full-screen table preview flow for Android session output
- Added a minimum `2` second splash hold so cold launch feels intentional instead of flashing past
- Reduced phone-facing branding and top navigation noise to lighter `控制台 / Console` language and a tag-only home-top summary
- Unified settings-sheet model and reasoning descriptions with the same bilingual copy strategy used in session detail
- Aligned the Android line with the `findeck` brand and the package target `app.findeck.mobile`
- Closed the Android pause-line decision: the app now sits in a maintain-and-polish state rather than an open-ended feature-expansion phase

## User-Facing Improvements

- Common desktop-generated markdown tables now keep their structure on Android instead of collapsing into plain paragraphs.
- Table output now opens through a dedicated preview window backed by native `WebView`, so full tables can be viewed, zoomed, and centered more reliably on mobile.
- Cold launch now stays visible long enough to feel stable while still moving on as soon as the minimum splash window is satisfied.
- Splash and login copy are shorter, lighter, and more phone-friendly.
- The project navigation block at the top of the session list is reduced to just three compact summary tags.
- Settings-sheet model and reasoning descriptions now follow locale-aware copy instead of surfacing raw English catalog blurbs on Chinese devices.

## Pause-Line Decision

The Android client has now reached the intended “remote control console” boundary for this phase:

- main session entry and return paths are stable
- voice and `$skill` input shortcuts are usable end-to-end
- common structured desktop output stays readable on mobile
- splash and home-top wording are light enough for quick scan
- settings-sheet runtime options now read consistently under both Chinese and English system languages

After this release, Android should default to maintenance, compatibility, and small UX repairs instead of adding more desktop-like capabilities.

## Versioning

- Android `versionName`: `0.4.17`
- Android `versionCode`: `24`

## Validation

- `cd apps/android && ./gradlew :app:testDebugUnitTest --tests "app.findeck.mobile.ui.sessions.RichTextBlocksTest"`
- `cd apps/android && ./gradlew :app:compileDebugKotlin`

## Related Docs

- [docs/android-mobile-next-phase-plan-2026-04-18.md](/Users/fainal/Documents/GitHub/CodexRemote/docs/android-mobile-next-phase-plan-2026-04-18.md)

## Delivery Artifact

- Dated APK output: `2026-04-19_findeck_v0.4.17.apk`
