# @codexremote/web

中文：CodexRemote 的移动优先 Next.js Web 客户端。  
English: Mobile-first Next.js web client for CodexRemote.

## Features / 功能

中文：
- 通过 Fastify 后端进行密码登录
- 会话列表、新建会话、归档、会话详情
- Inbox 链接投递、文件上传、submission bundle 导入
- 主题偏好持久化

English:
- Password login against the Fastify backend
- Session list, new-session flow, archive flow, and session detail
- Inbox link submit, file upload, and submission bundle import
- Persistent theme preference

## Development / 开发

在仓库根目录执行 / From the repository root:

```bash
npm install
npm run build --workspace @codexremote/shared
npm run dev --workspace @codexremote/server
npm run dev --workspace @codexremote/web -- --port 31817
```

中文：Web 默认通过 `CODEXREMOTE_API_URL` 连接后端，默认值为 `http://localhost:31807`。  
English: The web app connects to the backend through `CODEXREMOTE_API_URL`, which defaults to `http://localhost:31807`.

## Scripts / 脚本

| Command | Description |
| --- | --- |
| `npm run dev` | Start Next.js locally |
| `npm run build` | Production build |
| `npm run start` | Start production build |
| `npm run typecheck` | Run `tsc --noEmit` |
| `npm run test` | Run Vitest once |

## Notes / 说明

中文：
- 使用 App Router
- Inbox 历史不是浏览器本地状态，而是从后端 staging 读取
- 会话详情是完整 Web 页面，也被 Android WebView 复用

English:
- Uses the App Router
- Inbox history is not browser-local state; it is loaded from backend staging
- Session detail is implemented as a full web screen and reused by the Android WebView

更多运行说明 / More runtime details: [docs/operations.md](../../docs/operations.md)
