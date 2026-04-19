# Release Notes v0.5.0

## Summary

`v0.5.0` is the first Android release cut under the `findeck` product line.

`v0.5.0` 是 `findeck` 产品线下的首个 Android 版本。

This release turns the previous Android-only stabilization work into a cleaner branded baseline by combining:

- the `app.findeck.mobile` package migration
- the `findeck` app identity
- the calmer `控制台 / Console` in-app language
- the full-screen table preview workflow for structured desktop output

本次版本把前一阶段的 Android-only 稳定化工作收束成一条更清晰的品牌基线，核心包括：

- Android 包名迁移到 `app.findeck.mobile`
- 应用品牌统一为 `findeck`
- 应用内功能位保持为 `控制台 / Console`
- 桌面端结构化表格输出在手机端通过全屏预览工作流查看

## Highlights

- Android package renamed to `app.findeck.mobile`
- App display name unified to `findeck`
- In-app function language stays focused on `控制台 / Console`
- Table output now opens in a dedicated preview window with full-table visibility and zoom
- Splash, session list top area, settings runtime sheets, and icon resources are aligned with the new brand direction

- Android 包名已迁移到 `app.findeck.mobile`
- 应用显示名称统一为 `findeck`
- 应用内功能位仍聚焦在 `控制台 / Console`
- 表格输出现在通过独立预览窗口查看，支持完整显示与缩放
- 启动页、首页顶区、设置运行时面板和图标资源已对齐新的品牌方向

## User-Facing Improvements

- The Android app now reads as `findeck` in system-level surfaces while the in-app experience still uses concise control-surface language.
- Common markdown tables from desktop sessions no longer degrade into unreadable text walls on mobile.
- The home-top summary is reduced to three small status tags for quicker scan.
- Settings-sheet model and reasoning descriptions now respect the active locale instead of exposing raw English catalog blurbs on Chinese devices.
- Launcher and splash visuals now use the current brand icon set.

- Android 应用在系统层面的显示名称现在统一为 `findeck`，而应用内体验继续使用更清晰的 `控制台 / Console` 语言。
- 桌面端常见的 markdown 表格不再在手机端退化成难以阅读的文本墙。
- 首页顶区已经收成 3 个小标签，扫读更快。
- 设置页中的模型和思考强度说明现在会跟随系统语言，而不是在中文设备上直接露出英文 catalog 文案。
- 桌面图标和开屏视觉已切到当前品牌图标。

## Migration Notes

- Android installs under a new package id: `app.findeck.mobile`
- Existing installs under the old Android package line do not upgrade in place; treat this as a new-install path
- Pairing payload parsing remains compatible with both `findeck://...` and older `codex...` style payloads during transition

- Android 安装包现在使用新的包名：`app.findeck.mobile`
- 旧 Android 包名线路不会原地覆盖升级，应按新安装路径处理
- 过渡期内，配对载荷解析同时兼容 `findeck://...` 和旧的 `codex...` 风格

## Versioning

- Workspace packages: `0.5.0`
- Android `versionName`: `0.5.0`
- Android `versionCode`: `25`
- Android package: `app.findeck.mobile`

- Workspace packages：`0.5.0`
- Android `versionName`：`0.5.0`
- Android `versionCode`：`25`
- Android package：`app.findeck.mobile`

## Validation

- `cd apps/android && ./gradlew :app:compileDebugKotlin`
- `cd apps/android && ./gradlew :app:testDebugUnitTest --tests "app.findeck.mobile.ui.sessions.RichTextBlocksTest"`

- `cd apps/android && ./gradlew :app:compileDebugKotlin`
- `cd apps/android && ./gradlew :app:testDebugUnitTest --tests "app.findeck.mobile.ui.sessions.RichTextBlocksTest"`

## Related Docs

- [docs/android-mobile-next-phase-plan-2026-04-18.md](/Users/fainal/Documents/GitHub/CodexRemote/docs/android-mobile-next-phase-plan-2026-04-18.md)
- [docs/release-checklist.md](/Users/fainal/Documents/GitHub/CodexRemote/docs/release-checklist.md)

## Delivery Artifact

- Dated APK output: `2026-04-19_findeck_v0.5.0.apk`

- 发布 APK：`2026-04-19_findeck_v0.5.0.apk`
