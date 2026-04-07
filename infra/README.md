# infra

中文：CodexRemote 的基础设施模板目录。  
English: Infrastructure template directory for CodexRemote.

中文：`infra/launchd/` 中保存的是带占位符的 launchd 模板，安装时由 [install-launchd.sh](../scripts/install-launchd.sh) 渲染成真实 plist。  
English: `infra/launchd/` stores placeholder-based launchd templates, which are rendered into real plist files by [install-launchd.sh](../scripts/install-launchd.sh).

中文：渲染时会注入本机仓库路径、Node 可执行文件路径，以及 `.env.local` 中的运行时变量。  
English: Rendering injects your local repository path, Node binary path, and runtime values from `.env.local`.
