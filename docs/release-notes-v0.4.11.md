# Release Notes v0.4.11

## Summary

`v0.4.11` is the first Android-only stabilization batch after `v0.4.5`.

This release closes the current `P0` batch from the Android next-phase plan:
- session creation return-path recovery
- `$skill` mention execution closure
- mobile voice-note command flow that no longer depends on device-provided system speech recognition

## Highlights

- Fixed the new-session return chain so Android no longer drops out of the app after creating a session
- Closed the `$skill` mention loop from suggestion to recognizable Codex prompt semantics
- Reworked voice input into `hold to talk -> release to send` with recorded audio attached to the session
- Filtered voice-processing commentary so users see the executed result instead of English progress chatter
- Added tappable voice-message playback in the current round

## User-Facing Improvements

- New sessions now return through a stable back stack instead of leaving the user stranded after the first send.
- Skill suggestions insert normalized mention syntax and survive mobile punctuation and spacing edge cases.
- Voice input is now shaped for phone usage:
  - voice button sits beside send
  - tap voice once to enter voice mode
  - press and hold to record
  - release to stop and auto-send
  - recordings shorter than `2` seconds are rejected with a lightweight warning
  - recordings cap at `90` seconds
- Sent voice messages render as a dedicated bubble and can be replayed from the conversation.
- Voice messages display as `X秒语音信息` / `Xs voice note` instead of raw filenames.

## Technical Notes

- The Android voice path no longer assumes the phone can resolve `android.speech.action.RECOGNIZE_SPEECH`.
- Voice commands are sent as audio attachments and processed through Codex’s existing local-tool workflow (`whisper`, `ffmpeg`) on the server host.
- Audio uploads are now explicitly allowed by the backend upload MIME allowlist.
- Voice-note execution prompts are hidden from normal chat rendering, and known commentary-style intermediate assistant messages are filtered out from visible history.

## Versioning

- Workspace packages: `0.4.11`
- Android `versionName`: `0.4.11`
- Android `versionCode`: `18`

## Validation

- `npm --workspace packages/shared run build`
- `npm --workspace apps/server run build`
- `cd apps/android && ./gradlew :app:testDebugUnitTest --tests "app.findeck.mobile.ui.sessions.SessionVoiceInputControllerTest" --tests "app.findeck.mobile.ui.sessions.SessionDetailHelpersTest"`
- `cd apps/android && ./gradlew :app:compileDebugKotlin`
- `cd apps/android && ./gradlew :app:assembleDebug`

## Related Docs

- [docs/android-mobile-next-phase-plan-2026-04-18.md](/Users/fainal/Documents/GitHub/findeck/docs/android-mobile-next-phase-plan-2026-04-18.md)

## Delivery Artifact

- Dated APK output: `2026-04-18_findeck_v0.4.11.apk`
