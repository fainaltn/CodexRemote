# findeck

中文：findeck 是一个面向单机 Codex CLI 的轻量远程控制栈，可通过浏览器或 Android 手机查看、创建、运行和管理会话。  
English: findeck is a lightweight remote control stack for a single Codex CLI host, designed for browsing, creating, running, and managing sessions from a browser or an Android phone.

## Overview / 项目概览

中文：
- `apps/server`：Fastify 后端，负责认证、会话管理、实时运行、上传和 inbox staging
- `apps/web`：移动优先的 Next.js Web 控制台
- `apps/android`：原生 Android 客户端
- `packages/shared`：服务端与前端共享的 API 契约与 schema

English:
- `apps/server`: Fastify backend for auth, session management, live runs, uploads, and inbox staging
- `apps/web`: mobile-first Next.js control surface
- `apps/android`: native Android client
- `packages/shared`: shared API contracts and schemas used by the backend and UI

## Current Status / 当前状态

中文：
- Server 构建和测试通过
- Web 构建和测试通过
- Android Debug APK 可正常编译
- 本地运维已收敛到 `npm run findeck -- doctor|up|pair|status|logs|restart|web`
- 可通过 `npm run findeck -- pair` 在本机生成一次性配对码，供 Android 首次接入使用
- Android 已支持可信主机自动重连、归档会话恢复、轻量 smart composer、设置页运行默认值与更完整的前台反馈
- Web 与 Android 的首屏入口、项目导航和会话工作区已按 `Console` 方向统一收口
- Android 会话列表支持项目文件夹隐藏与自定义拖拽排序
- Web 与 Android 会话详情都已支持更明确的恢复态语义，弱网或后台恢复时会尽量补齐而不是直接报错
- Android 会话详情已升级为更完整的原生移动控制台，支持流式回复、历史折叠、附件队列、运行参数控制与仓库操作入口
- Web 会话页现在也支持显式的运行参数控制，可直接为下一次发送选择 `model` 与 `reasoning`
- Android 后台通知已区分运行完成、失败、需要注意和恢复同步，不再只有单一 completion 提醒
- Android 端支持在应用内修改服务密码，并在服务端重启后自动重新对齐连接
- 继续已有会话时，服务端会先恢复 thread 再启动 turn，避免旧会话落回只读权限
- Inbox 已改为 staging 模式，远端只落盘到 `data/submissions/`
- launchd 已改为通过 `~/Library/Application Support/findeck/launchd/` 下的 launcher 启动，避免直接依赖仓库目录权限

English:
- Server builds and tests are passing
- Web builds and tests are passing
- Android debug APK builds successfully
- Local operator flows are now unified under `npm run findeck -- doctor|up|pair|status|logs|restart|web`
- `npm run findeck -- pair` prints a one-time pairing code for Android first-time setup
- Android now supports trusted-host reconnect, archived-session restore, a lightweight smart composer, runtime defaults in settings, and clearer foreground feedback
- Web and Android first-use surfaces, project navigation, and session workspaces now follow the `Console` direction more consistently
- Android session lists now support hiding project folders and custom drag reordering
- Web and Android session detail now treat degraded transport as a recovery flow, aiming to catch up instead of failing loudly
- Android session detail is now a fuller native mobile console with streaming replies, folded history, attachment queueing, runtime controls, and repo action entry points
- The web session page now also exposes explicit runtime controls so the next send can choose `model` and `reasoning`
- Android background notifications now distinguish completed, failed, needs-attention, and recovered-sync events instead of a single completion alert
- Android can now change the service password in-app and automatically re-align the connection after the server restarts
- Resumed sessions now restore the thread before starting a turn so existing sessions do not fall back to read-only permissions
- Inbox now uses a staging model and stores submissions under `data/submissions/`
- launchd now starts from launcher scripts under `~/Library/Application Support/findeck/launchd/` instead of relying directly on the repo path

## Repository Layout / 仓库结构

```text
apps/
  server/   Fastify API + SQLite-backed local state
  web/      Next.js remote control UI
  android/  Native Android client
packages/
  shared/   Shared contracts and schemas
  sdk/      Reserved for future typed SDK work
scripts/
  findeck.sh     Unified local operator entrypoint
  install-launchd.sh  Install local macOS launch agents
  findeckctl.sh   Inspect and restart launchd services
  clear-inbox.sh      Remove inbox records and stored staging files
infra/
  launchd/  launchd templates generated at install time
docs/
  architecture.md
  operations.md
  release-checklist.md
```

## Local Development / 本地开发

```bash
npm install
cp .env.example .env.local
```

中文：在 `.env.local` 中设置强密码 `FINDECK_PASSWORD`，然后执行：  
English: Set a strong `FINDECK_PASSWORD` in `.env.local`, then run:

```bash
npm run findeck -- doctor
npm run findeck -- up
```

中文：本地开发时也可以继续直接运行 workspace dev 命令。
English: For active development you can still run the workspace dev servers directly.

```bash
npm run dev --workspace @findeck/server
npm run dev --workspace @findeck/web -- --port 31817
```

Android:

```bash
cd apps/android
./gradlew assembleDebug
```

## Runtime Notes / 运行说明

中文：
- Inbox 历史不是 APK 本地数据库，而是后端持有的数据
- 默认 staging 根目录为 `data/submissions/`
- 清理最近投递记录应清理后端，而不是只清手机缓存

English:
- Inbox history is backend-owned state, not an APK-local database
- The default staging root is `data/submissions/`
- Clearing recent inbox records is a backend cleanup task, not a phone-only cleanup

## Release Docs / 发布文档

- Architecture / 架构说明: [docs/architecture.md](./docs/architecture.md)
- Operations / 运维说明: [docs/operations.md](./docs/operations.md)
- Roadmap / 路线图: [docs/v0.3.0-roadmap.md](./docs/v0.3.0-roadmap.md)
- Next phase plan / 下一阶段计划书: [docs/v0.5.x-next-phase-plan-2026-04-19.md](./docs/v0.5.x-next-phase-plan-2026-04-19.md)
- Android v0.4.0 acceptance / Android v0.4.0 验收清单: [docs/android-v0.4.0-acceptance-checklist.md](./docs/android-v0.4.0-acceptance-checklist.md)
- Session transition acceptance / 会话切换验收清单: [docs/session-transition-acceptance-checklist.md](./docs/session-transition-acceptance-checklist.md)
- Release checklist / 发布检查单: [docs/release-checklist.md](./docs/release-checklist.md)
- Release notes / 发布说明: [docs/release-notes-v0.5.0.md](./docs/release-notes-v0.5.0.md)

## Inbox Cleanup / Inbox 清理

中文：清理当前 host 的 inbox 历史：  
English: Clear inbox history for the current host:

```bash
./scripts/clear-inbox.sh --host-id local
```

中文：清理所有 host：  
English: Clear all hosts:

```bash
./scripts/clear-inbox.sh --all
```
