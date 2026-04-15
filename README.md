# CodexRemote

中文：CodexRemote 是一个面向单机 Codex CLI 的轻量远程控制栈，可通过浏览器或 Android 手机查看、创建、运行和管理会话。  
English: CodexRemote is a lightweight remote control stack for a single Codex CLI host, designed for browsing, creating, running, and managing sessions from a browser or an Android phone.

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
- Android 会话列表支持项目文件夹隐藏与自定义拖拽排序
- Android 会话详情已升级为更完整的原生移动控制台，支持流式回复、历史折叠、附件队列、运行参数控制与仓库操作入口
- Android 端支持在应用内修改服务密码，并在服务端重启后自动重新对齐连接
- 继续已有会话时，服务端会先恢复 thread 再启动 turn，避免旧会话落回只读权限
- Inbox 已改为 staging 模式，远端只落盘到 `data/submissions/`
- launchd 已改为通过 `~/Library/Application Support/CodexRemote/launchd/` 下的 launcher 启动，避免直接依赖仓库目录权限

English:
- Server builds and tests are passing
- Web builds and tests are passing
- Android debug APK builds successfully
- Android session lists now support hiding project folders and custom drag reordering
- Android session detail is now a fuller native mobile console with streaming replies, folded history, attachment queueing, runtime controls, and repo action entry points
- Android can now change the service password in-app and automatically re-align the connection after the server restarts
- Resumed sessions now restore the thread before starting a turn so existing sessions do not fall back to read-only permissions
- Inbox now uses a staging model and stores submissions under `data/submissions/`
- launchd now starts from launcher scripts under `~/Library/Application Support/CodexRemote/launchd/` instead of relying directly on the repo path

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
  install-launchd.sh  Install local macOS launch agents
  codexremotectl.sh   Inspect and restart launchd services
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

中文：在 `.env.local` 中设置强密码 `CODEXREMOTE_PASSWORD`，然后执行：  
English: Set a strong `CODEXREMOTE_PASSWORD` in `.env.local`, then run:

```bash
npm run build --workspace @codexremote/shared
npm run dev --workspace @codexremote/server
npm run dev --workspace @codexremote/web -- --port 31817
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
- Release checklist / 发布检查单: [docs/release-checklist.md](./docs/release-checklist.md)

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
