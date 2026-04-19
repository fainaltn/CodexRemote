---
name: apk-smb-sync
description: Build a simple post-update Android APK delivery flow that reads the app version and copies a dated findeck APK into the LAN SMB share. Use when the user mentions APK update sync, Android release handoff, SMB share upload, 局域网共享, or asks to archive the latest APK by version date.
---

# APK SMB Sync

Use this skill when the user wants to push an updated Android APK into the LAN SMB share as a dated delivery artifact.

## Default flow

1. Confirm the APK to publish. In this repo the default source is `apps/android/app/build/outputs/apk/debug/app-debug.apk`.
2. Read `versionName` from `apps/android/app/build.gradle.kts` unless the user gives a different version.
3. Use the APK file modification date as the version date unless the user gives a different date.
4. Rename the APK into a dated artifact named like `YYYY-MM-DD_findeck_v0.1.4.apk`.
5. Copy the APK into the default LAN target `smb://192.168.2.1/share/`.

## Project-specific guardrails

- In this repository, the default delivery artifact is the raw APK, not a zip wrapper.
- The default label must be `findeck`; never reuse names from unrelated projects or SMB share history.
- The default SMB target should point to the share or subdirectory, not embed a legacy filename unless the user explicitly asks for one.
- The build wrapper should work both with and without extra publish arguments; do not assume there is always a trailing `-- ...` section.

## Preferred command

Run:

```bash
./skills/apk-smb-sync/scripts/build_and_publish_apk.sh
```

This command builds the Android debug APK first, then copies the dated APK to the SMB share.

If you already have a fresh APK and only want to publish it:

```bash
./skills/apk-smb-sync/scripts/publish_apk_to_smb.sh
```

Useful overrides:

```bash
./skills/apk-smb-sync/scripts/build_and_publish_apk.sh \
  --target-url smb://192.168.2.1/share/ \
  --version-date 2026-04-14 \
  --dry-run
```

## Notes

- The default artifact name is `YYYY-MM-DD_findeck_v<version>.apk`.
- The build wrapper uses `apps/android/gradlew assembleDebug` and then publishes `apps/android/app/build/outputs/apk/debug/app-debug.apk`.
- If a generated filename or target path contains a project name unrelated to `findeck`, treat that as a skill bug and correct the skill/scripts before publishing.
- If `apps/android/local.properties` is missing, the build wrapper tries `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `~/Library/Android/sdk`, and `/opt/homebrew/share/android-commandlinetools`, then exports the detected SDK path for Gradle.
- If the SMB share is already mounted under a non-default path such as `~/smb/share`, the publish script should reuse that existing mount automatically.
- If the SMB share is not mounted yet, the script tries to open `smb://192.168.2.1/share` in Finder and waits for the mount point to appear.
- If the publish step still complains about the mount point, inspect the real path with `mount | grep smb` or `smbutil statshares -a`, then rerun with `--mount-point /actual/path`.
- If the source APK is missing, stop and either build the APK first or ask the user which APK file should be published.
