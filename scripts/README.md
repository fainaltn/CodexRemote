# scripts

中文：本地开发与部署使用的运维脚本。  
English: Operational helper scripts for local development and deployment.

## Included Scripts / 包含脚本

- `install-launchd.sh`: generate and install launch agents from templates
- `codexremotectl.sh`: inspect, restart, start, stop, and tail logs
- `codexremote-server.sh`: start the built Fastify server using `.env.local`
- `codexremote-web.sh`: start the built web app
- `clear-inbox.sh`: remove inbox records from SQLite and delete matching staging directories

## Environment / 环境变量

中文：复制 `.env.example` 为 `.env.local`，至少设置以下变量：  
English: Copy `.env.example` to `.env.local` and set at least:

- `CODEXREMOTE_PASSWORD`
- optionally `HOST`
- optionally `PORT`
