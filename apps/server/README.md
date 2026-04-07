# apps/server

中文：CodexRemote 的 Fastify 后端。  
English: Fastify backend for CodexRemote.

## Responsibilities / 主要职责

中文：
- 密码认证
- 会话和运行状态持久化
- SSE 实时更新
- 文件上传
- inbox intake 与 staging 存储
- 健康检查和审计可见性

English:
- password authentication
- session and run metadata persistence
- SSE updates for live runs
- upload handling
- inbox intake and staging storage
- health and audit visibility

## Development / 开发

在仓库根目录执行 / From the repository root:

```bash
npm install
npm run build --workspace @codexremote/shared
npm run dev --workspace @codexremote/server
```

Production-style local run / 本地生产式运行:

```bash
./scripts/codexremote-server.sh
```

中文：服务端默认读取 `.env.local`，并要求设置 `CODEXREMOTE_PASSWORD`。  
English: The server reads `.env.local` by default and requires `CODEXREMOTE_PASSWORD`.

## Storage / 存储

- SQLite database / SQLite 数据库: `data/codexremote.db`
- inbox staging directories / inbox staging 目录: `data/submissions/`
- session artifacts / 会话附件: backend-managed local storage

## Notes / 说明

中文：
- 审计日志是 best-effort，不应影响主请求
- Inbox 历史是后端持有的共享状态
- launchd 模板由 `scripts/install-launchd.sh` 在本机渲染生成

English:
- Audit logging is best-effort and should not break primary requests
- Inbox history is backend-owned shared state
- launchd templates are rendered locally by `scripts/install-launchd.sh`
