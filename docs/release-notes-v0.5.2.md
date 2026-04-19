# Release Notes v0.5.2

## Summary

`v0.5.2` closes the current cold-launch recovery phase after the `v0.5.1` trusted reconnect audit.

This release is centered on one practical goal: when Android returns after time away, reinstall, token loss, or trusted-secret drift, the app should make a calmer and more truthful recovery decision instead of dropping into a vague broken state.

本次 `v0.5.2` 对应的是 `v0.5.1` 可信重连审计之后的冷启动恢复收口阶段。

这一版聚焦一个很实际的目标：Android 在隔一段时间重新进入、令牌失效、可信凭据漂移，甚至重装之后，不再落入含糊的“坏掉了”状态，而是给出更准确、更平静的恢复路径。

## Highlights

- Cold launch now distinguishes `trusted reconnect`, `saved password restore`, and `no eligible restore host`
- When trusted reconnect fails because the trusted credentials are stale, Android can fall back to saved-password restore if that path still exists
- Splash copy now tells the user whether the app is restoring a trusted session or a saved sign-in
- Server list now shows restore readiness for each host instead of hiding that logic inside startup only
- Settings now show whether a host is recoverable on cold launch and by which path

- 冷启动现在可以明确区分 `可信重连`、`保存密码恢复登录` 和 `当前没有可恢复主机`
- 如果可信重连因为可信凭据失效而失败，只要本地还保留了密码恢复路径，Android 就会自动回退
- 启动页现在会明确告诉用户当前是在恢复可信连接，还是在恢复保存的登录
- 服务器列表现在直接展示每台主机的恢复准备度，而不是把这层逻辑只藏在冷启动内部
- 设置页现在会明确展示当前主机在冷启动时是否可恢复，以及会走哪条恢复路径

## User-Facing Improvements

- Android no longer treats all reconnect failures as the same class of problem.
- A host that is reachable but not auto-recoverable is now described differently from a first-time pairing state.
- A saved-password path is now treated as a first-class recovery route rather than as a manual fallback the user must rediscover alone.
- Recovery-oriented UI language across splash, server list, and settings is now more internally consistent.

- Android 不再把所有重连失败都视为同一类问题。
- “主机可达但当前不可自动恢复” 现在会和 “首次配对” 明确区分。
- “保存密码恢复登录” 现在成为正式恢复路径，而不是让用户自己重新摸索的手动补救。
- 启动页、服务器列表和设置页之间的恢复语言现在更加一致。

## Recovery Behavior

- `trusted reconnect` succeeds: restore the session directly and refresh trusted reconnect metadata
- `trusted reconnect` fails because the trusted credentials are invalid: fall back to `saved password restore` if available
- `trusted reconnect` fails because the host is unreachable: do not fake a fallback; send the user to the correct recovery surface
- no saved restore path exists: show an explicit no-restore state instead of a vague failure

- `可信重连` 成功：直接恢复，并刷新可信自动重连元数据
- `可信重连` 因可信凭据失效失败：如果本地还保留密码恢复路径，则自动回退到 `保存密码恢复登录`
- `可信重连` 因主机不可达失败：不会伪造回退，而是进入正确的恢复入口
- 本地没有任何可恢复路径：直接展示明确的“当前没有可恢复主机”状态

## Validation

- `npm --prefix packages/shared run build`
- `npm --prefix apps/server test -- pairing.test.ts pairing-store.test.ts auth.test.ts`
- `npm --prefix apps/server run typecheck`
- `cd apps/android && ./gradlew :app:testDebugUnitTest --tests app.findeck.mobile.StartupRecoveryTest --tests app.findeck.mobile.navigation.AppNavHostTest --tests app.findeck.mobile.data.model.ColdLaunchRestoreSelectionTest --tests app.findeck.mobile.data.model.ColdLaunchRestoreDecisionTest`
- local stack check after restart:
  - `http://macmini.lan:31807/api/health` returned `status: "ok"`
  - `http://127.0.0.1:31817/login` returned `HTTP 200`

## Versioning

- Workspace packages: `0.5.2`
- Android `versionName`: `0.5.2`
- Android `versionCode`: `26`
- Android package: `app.findeck.mobile`

## Delivery Artifact

- GitHub release APK: `2026-04-19_findeck_v0.5.2.apk`

## Related Docs

- [docs/v0.5.x-next-phase-plan-2026-04-19.md](/Users/fainal/Documents/GitHub/findeck/docs/v0.5.x-next-phase-plan-2026-04-19.md)
- [docs/release-checklist.md](/Users/fainal/Documents/GitHub/findeck/docs/release-checklist.md)
