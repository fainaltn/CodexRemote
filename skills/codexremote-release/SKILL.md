---
name: codexremote-release
description: Validate and publish the findeck GitHub repository release flow. Use when the user wants to 一键发布仓库, 发布版本到 GitHub, push main, create/update GitHub release, or run the findeck repo release flow. Do not use this skill for APK SMB sync; that belongs to apk-smb-sync.
---

# findeck Release

Use this skill when the user wants to run the project-specific release flow for findeck.

## What This Skill Does

This skill turns the current findeck workspace into a repeatable GitHub release path:

1. Read and verify the project version
2. Run release validation for web and Android
3. Commit release changes on `main`
4. Push `main` to GitHub
5. Build the Android debug APK release artifact
6. Create the matching GitHub release from the local release notes file and attach the APK

## Default Version Sources

The release version should already be updated before running the final release step.

Primary version locations:

- root package: `package.json`
- workspace packages: `apps/web/package.json`, `apps/server/package.json`, `packages/shared/package.json`, `packages/sdk/package.json`
- Android app: `apps/android/app/build.gradle.kts`

Release notes file:

- `docs/release-notes-v<version>.md`

## Default Release Validation

Run these checks before publishing:

```bash
npm run typecheck --workspace @codexremote/web
npm run build --workspace @codexremote/web
cd apps/android && ./gradlew :app:compileDebugKotlin
```

## Preferred Command

Run:

```bash
./skills/codexremote-release/scripts/release_codexremote.sh
```

This command:

- validates versions are aligned
- runs release checks
- builds the Android debug APK asset
- stages release changes
- commits `Release v<version>`
- pushes `main`
- pushes the matching git tag
- creates GitHub release `v<version>`
- uploads the APK asset to that GitHub release

Useful overrides:

```bash
./skills/codexremote-release/scripts/release_codexremote.sh --dry-run
./skills/codexremote-release/scripts/release_codexremote.sh --skip-release
./skills/codexremote-release/scripts/release_codexremote.sh --skip-apk
./skills/codexremote-release/scripts/release_codexremote.sh --notes-file docs/release-notes-v0.1.4.md
```

## Guardrails

- This flow is for findeck only; the release commit title must be `Release v<version>`
- The Git branch must be `main` unless the user explicitly asks for something else
- The release notes file must exist before release creation
- The script must fail fast on version mismatches between root, workspace packages, and Android
- The script should support `--dry-run` for safe preview
- GitHub release creation should attach the prepared APK asset by default
- SMB publication stays in `apk-smb-sync`; GitHub release attachment belongs here

## Notes

- This skill is intentionally project-specific rather than generic
- The script uses `gh` for release creation, so GitHub auth must already be active
- The APK asset should use the same prepared filename shape as SMB delivery: `YYYY-MM-DD_findeck_v<version>.apk`
- If the release tag already exists, stop and ask whether to skip release creation or edit the existing release manually
