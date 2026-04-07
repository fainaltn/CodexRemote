# Contributing to CodexRemote

中文：欢迎为 CodexRemote 做出贡献。这个项目目前仍然偏小型，但非常欢迎 issue、文档改进、bug 修复和功能建议。  
English: Contributions to CodexRemote are welcome. The project is still intentionally small, but issues, documentation improvements, bug fixes, and feature suggestions are all appreciated.

## Before You Start / 开始之前

中文：
- 请先阅读 [README.md](/Users/fainal/Documents/GitHub/CodexRemote/README.md) 和 [docs/operations.md](/Users/fainal/Documents/GitHub/CodexRemote/docs/operations.md)
- 优先提交小而清晰的改动
- 如果改动会影响 API、数据结构或运行方式，请先开 issue 说明

English:
- Please read [README.md](/Users/fainal/Documents/GitHub/CodexRemote/README.md) and [docs/operations.md](/Users/fainal/Documents/GitHub/CodexRemote/docs/operations.md) first
- Prefer small and focused changes
- If your change affects APIs, storage, or runtime behavior, please open an issue first

## Development / 开发

From the repository root / 在仓库根目录执行:

```bash
npm install
npm run build --workspace @codexremote/shared
```

Common checks / 常用检查:

```bash
npm test --workspace @codexremote/server
npm test --workspace @codexremote/web
npm run build --workspace @codexremote/server
npm run build --workspace @codexremote/web
cd apps/android && ./gradlew assembleDebug
```

## Contribution Guidelines / 贡献建议

中文：
- 保持改动范围聚焦，不要顺手混入无关重构
- 不要提交 `.env.local`、`data/`、`.run/`、构建产物或本机配置
- 如果涉及 inbox，请记住历史记录属于后端 staging 状态
- 文档和 README 改动同样有价值

English:
- Keep the scope focused and avoid unrelated refactors
- Do not commit `.env.local`, `data/`, `.run/`, build outputs, or machine-local config
- If you touch inbox behavior, remember that history is backend-owned staging state
- Documentation and README improvements are valuable contributions too

## Pull Requests / Pull Request 建议

中文：
- 说明你改了什么
- 说明为什么需要这个改动
- 如果适用，附上测试结果或截图

English:
- Explain what changed
- Explain why the change is needed
- Include test results or screenshots when helpful

## Communication / 沟通方式

中文：请保持尊重、建设性和可协作。  
English: Please keep communication respectful, constructive, and collaborative.
