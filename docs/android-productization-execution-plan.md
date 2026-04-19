# Android 产品化执行计划

## Status

Proposed on 2026-04-15.

This document is the execution plan for the next Android-focused milestone after the current `v0.3.0` work. It extends the earlier roadmap documents and should be treated as the source document for the next round of implementation.

## 目标

把 `findeck` Android 客户端从“已经可用的远程控制台”推进成“更完整的手机端 Codex 工作台”，重点补齐以下七类能力：

1. QR / 配对码接入 + 可信主机自动重连
2. 会话搜索 + 归档列表 + 更显眼的导航入口
3. 紧凑型 composer 增强：`/` 命令面板 + 文件 / 技能 mention + access / speed 控制
4. 统一连接状态机 UI，并与现有状态灯语言联动
5. Android 端触感、滑动操作、完成 banner、即时反馈等微交互
6. 扩展设置页：外观、通知、运行默认值、连接偏好、归档入口
7. 后端一键下载安装部署

## 硬约束

后续所有代码实现都应遵守以下边界：

- 保持当前后端中心架构：`apps/server` 继续作为状态源，Android 继续基于 REST + SSE。
- 会话主窗仍然是核心工作区，不把主界面改造成菜单堆叠或工具面板页。
- Composer 保持紧凑，不做成臃肿控制台；新增能力优先进入现有按钮带、bottom sheet、popover、轻量建议面板。
- 维持当前按钮菜单风格，不直接照搬 `remodex` 的 iOS 视觉结构。
- 状态呈现尽量简洁，优先使用状态灯、单行状态条、短 banner，不上大段说明文字。
- 保持 `Console` 方向，避免切换到另一套品牌风格。
- 保留当前多服务器 / 后端管理模型，不为了 QR 配对把产品退化成“只能连一个主机”的心智。

## 术语澄清

为了避免后续实现时混淆，以下三类能力必须区分：

- 附件上传：把本地文件或图片作为 artifact 上传到当前会话。Android 端这一能力已经存在。
- 文件 mention：像桌面端那样引用工作区路径，例如 `@apps/server/src/app.ts`，让 Codex 读取已有工程文件，而不是上传一份副本。
- 技能 mention：像桌面端那样引用 skill，例如 `$sourceweave` 或等价 UI 表达，让手机端也能显式调用可用技能。

结论：

- 当前 Android 已经有“附件”，但还没有真正的“文件 mention”。
- 当前仓库里也还没有面向 Android 的“技能枚举 / 技能补全 / 技能 mention”完整链路。

## 产品方向

下一阶段不追求把 Android 做成桌面端的镜像，而是做成一个更稳、更快、更像手机产品的远程 Codex cockpit：

- 首次接入更低摩擦
- 断线与恢复更可理解
- 长会话更容易找到、归档和继续
- 手机上发复杂指令更高效
- 高级控制可用，但不把输入区做胖
- 后端部署和连接故事更闭环

## 非目标

以下内容不在本计划的第一优先级中：

- 复制 `remodex` 的 relay / E2E transport 架构
- 重写 Android 会话主窗为侧边栏主导布局
- 一次性补齐所有桌面端高级能力
- 引入大量只在 UI 层“看起来支持”但服务端没有真实语义的装饰性按钮

## 七条执行计划

### 1. 配对与可信重连

#### 为什么做

当前 Android 首次接入仍是“手填地址 + 登录密码”模式，路径偏运维化。对真实手机使用来说，这会放大首次接入成本，也会让“换机器 / 重装 / 恢复连接”显得笨重。

#### 目标结果

把首次接入升级为“引导式配对”，但仍兼容现有多服务器模型：

- 新增 QR 配对入口
- 新增配对码手动输入入口
- 手机端保存可信主机记录
- App 冷启动或回前台时优先尝试可信重连
- 保留原有手动添加服务器流作为 fallback / advanced path

#### UX 形态

- `Splash` / `ServerList` 增加明显的“扫码配对”主入口
- `AddServer` 保留，但降级为“手动配置”
- 配对成功后直接创建或更新一个 server record
- Settings 中新增“当前可信主机 / 重新配对 / 忘记主机”
- 当可信重连失败时，给出简洁状态而不是直接抛红错

#### 后端 / 脚本工作

- 新增“生成配对 payload / 配对码”的服务端或脚本能力
- 新增“可信主机登记 / 查询 / 失效”接口
- 新增面向 Android 的“恢复连接候选信息”接口
- 让现有 `npm run findeck -- up` 能在启动后输出或暴露可扫描的配对信息

#### Android 文件范围

- `apps/android/app/src/main/java/app/findeck/mobile/ui/splash/SplashScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/servers/ServerListScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/servers/AddServerScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/login/LoginScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/settings/ServerSettingsScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/navigation/AppNavHost.kt`
- 新增 `pairing` 相关 viewmodel / screen / scanner

#### 服务端文件范围

- `apps/server/src/routes/*` 新增配对相关 route
- `packages/shared/src/api/*` 新增共享 schema
- `scripts/findeck.sh`
- `docs/operations.md`
- `README.md`

#### 验收标准

- 新用户可以通过扫码或输入配对码完成首次接入。
- 同一台手机再次打开时，会优先尝试可信主机重连。
- 可信重连失败时，UI 进入“等待主机 / 需要重新配对 / 重试中”之一，而不是笼统报错。
- 原有手动服务器添加路径仍可用。

### 2. 会话搜索、归档列表与导航显性化

#### 为什么做

当前 Android 会话列表已经有项目分组、隐藏和排序，但“快速找到某个会话”“进入归档”“快速进 Inbox / Settings”仍不够顺手，入口层级偏深。

#### 目标结果

- 会话列表支持搜索
- 有独立的归档列表入口与 unarchive 流程
- Inbox / Settings / Archive / Search 入口比现在更显眼
- 项目文件夹主视图继续保留，不改掉当前信息架构主轴

#### UX 形态

- 会话列表顶部加入搜索胶囊或展开式搜索条
- 顶部 action 区把 `Inbox` / `Archive` / `Settings` 从隐藏菜单里前置至少一部分
- 会话行支持 swipe actions
- 归档列表单独成页，可 unarchive / delete

#### 后端工作

- 新增 unarchive API
- 搜索第一期可直接基于已拉取 session 列表本地过滤，不强依赖服务端搜索
- 如果后续列表规模变大，再补服务端 session search

#### Android 文件范围

- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionListScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/navigation/AppNavHost.kt`
- 新增 `ArchivedSessionsScreen.kt`
- 可能拆分新的 `SessionListToolbar` / `SessionSearchField`

#### 服务端文件范围

- `apps/server/src/routes/sessions.ts`
- `packages/shared/src/api/sessions.ts`
- `apps/android/app/src/main/java/app/findeck/mobile/data/network/ApiClient.kt`

#### 验收标准

- 用户可以在 session list 中直接搜索会话标题、路径名或预览文本。
- 用户可以查看已归档会话列表，并支持 unarchive。
- `Inbox` / `Archive` / `Settings` 至少有一项不再藏在 overflow 里。
- 项目文件夹分组、隐藏和拖拽排序能力保持不退化。

### 3. 紧凑型 Composer 增强

#### 为什么做

手机端最值得补的是“复杂指令发得更快”，而不是简单继续堆按钮。当前 Android composer 已有附件、相册、拍照、语音、model、reasoning，但还没有桌面端那种“带语义的输入器”。

#### 设计原则

- 不把 composer 做厚
- 不新增一排排长期常驻的控制块
- 优先复用现有按钮菜单风格
- 高级能力通过 `/` 面板、mention 建议面板、bottom sheet 进入

#### 目标结果

第一阶段：

- `/` 命令面板
- 文件 mention
- 技能 mention
- speed / access 控制

第二阶段：

- 更接近桌面端的 slash command 语义
- mention 插入后的 chip 呈现
- 最近技能 / 最近路径的记忆

#### UX 形态

- 输入框检测到 `/` 时拉起紧凑命令面板
- 输入框检测到 `@` 时拉起文件建议
- 输入框检测到 `$` 或约定前缀时拉起技能建议
- `model / reasoning / speed / access` 不全部常驻在主栏上
- `model / reasoning` 保留现有入口
- `speed / access` 进入同一个 runtime sheet 或二级 sheet

#### 和附件的关系

- 附件上传继续保留，适合图片、临时文件、外部资料
- 文件 mention 则面向工作区内已有文件
- 两者是互补关系，不互相替代

#### 服务端工作

- 新增文件搜索 / 路径建议接口，尽量复用已有 `projects/browse` 思路，但要支持更细粒度文件层级
- 新增技能列表 / 技能元数据接口
- 扩展 `StartLiveRunRequest` 与 `StartRunOptions`
- 在 API 层显式加入 `accessMode`
- 评估是否加入 `serviceTier` 或 speed 对应字段

#### Android 文件范围

- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/ComposerBar.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionDetailScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionDetailViewModel.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/data/network/ApiClient.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/data/model/*`
- 新增 `ComposerCommandPanel` / `ComposerMentionPanel` / `RuntimeSheet`

#### 服务端文件范围

- `packages/shared/src/api/live-runs.ts`
- `packages/shared/src/limits.ts`
- `apps/server/src/routes/live-runs.ts`
- `apps/server/src/runs/manager.ts`
- `apps/server/src/codex/types.ts`
- 可能新增 `apps/server/src/routes/files.ts`
- 可能新增 `apps/server/src/routes/skills.ts`

#### 验收标准

- 用户可以在 Android 输入框里通过 `/` 选择常用命令。
- 用户可以 mention 工作区文件，而不是只能上传附件。
- 用户可以 mention 技能，并且语义上尽量贴近桌面端。
- `speed / access` 可控，但 composer 视觉体积不明显增加。

### 4. 统一连接状态机 UI

#### 为什么做

当前 Android 已经有很多恢复逻辑和状态文案，但它们还分散在 `liveStreamStatus`、error banner、recovery notice、control strip 等不同位置。下一步不是简单继续加 copy，而是把它们统一成一个小而稳定的状态机。

#### 目标结果

定义一套 Android 连接状态机，并把它映射到：

- 状态灯
- `SessionControlStrip`
- `MicroStatusRow`
- 简短 banner
- 通知 tier

#### 推荐状态集合

- `connecting`
- `syncing`
- `live`
- `degraded`
- `waiting_host`
- `reconnect_retrying`
- `re_pair_required`
- `failed`

#### UI 规则

- 状态灯始终是最小可见状态
- 控制条只显示短语，不显示长解释
- banner 只在需要用户注意或刚完成关键状态迁移时出现
- `re_pair_required` 和真正 `failed` 才允许升级为明显错误态

#### Android 文件范围

- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionDetailViewModel.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionDetailScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionControlStrip.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/MicroStatusRow.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/TimelineStatusCards.kt`
- `apps/android/app/src/main/res/values/strings_session_detail.xml`
- `apps/android/app/src/main/res/values-en/strings_session_detail.xml`

#### 验收标准

- “连接中 / 同步中 / 等待主机 / 需要重新配对”能通过统一状态机解释。
- 状态灯、控制条、banner 文案一致，不互相打架。
- UI 风格延续当前安卓设计语言，不变成大段说明块。

### 5. Android 微交互与即时反馈

#### 为什么做

当前 Android 是“稳的”，但还不够“顺”。微交互补齐后，手机使用手感会明显提升，而且这部分对整体视觉入侵最小。

#### 目标结果

- 关键轻操作加入 haptic
- session row / archive row / queued item / composer action 支持更自然的滑动与反馈
- 运行完成、需要注意、恢复成功时有轻量 banner
- 提交成功、恢复成功、切换成功等动作有即时反馈

#### 范围

- haptic feedback service
- swipe actions
- transient completion banner
- success snackbar / toast 规范

#### Android 文件范围

- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionListScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionDetailScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/inbox/InboxScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/settings/ServerSettingsScreen.kt`
- 可能新增 `ui/feedback/*`

#### 验收标准

- 关键触发点有统一轻触反馈。
- 列表行支持至少一类高频 swipe action。
- 运行完成后会出现简洁 completion banner。
- 成功类操作不再只依赖底层日志或隐式状态变化。

### 6. 设置页扩展

#### 为什么做

当前 Android 设置页主要集中在改密码，承载面过窄，导致很多“设备偏好”和“连接偏好”没有落点。

#### 目标结果

把 Settings 扩成真正的移动端偏好页，至少包含：

- 外观
- 通知
- 运行默认值
- 连接偏好
- 可信主机 / 重新配对
- 归档入口
- 高级诊断入口
- 保留密码修改

#### UX 形态

- 分 section card
- 轻量 summary 文案
- 高级项留到二级页或 sheet

#### Android 文件范围

- `apps/android/app/src/main/java/app/findeck/mobile/ui/settings/ServerSettingsScreen.kt`
- 新增 `SettingsScreen` 或将当前 server settings 升级为更完整的设置页
- `apps/android/app/src/main/java/app/findeck/mobile/navigation/AppNavHost.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/theme/*`

#### 后端联动

- 如果设置页需要读取 runtime default / trusted pairing / notification registration 状态，则补对应 API

#### 验收标准

- 用户能在设置页完成主要设备偏好设置，而不是只改密码。
- 归档入口、连接偏好、运行默认值都能在 Settings 找到。
- 设置页结构与当前美术风格一致。

### 7. 后端一键下载安装部署

#### 为什么做

手机端的“接入闭环”最终还是受制于后端部署成本。如果后端还是开发者手工搭起来，前面的 QR、重连、首登体验就很难真的闭环。

#### 目标结果

把当前已经存在的 `doctor / up / status / logs / restart` 能力，进一步收敛成真正的“一键部署故事”：

- fresh machine 可以按统一文档快速完成安装
- 启动后自动构建必要产物
- 安装 launchd / 启动服务 / 输出访问入口
- 如果启用了 QR / pairing，也同时输出配对入口

#### 推荐分两段实施

第一段：

- 强化 repo 内 `npm run findeck -- up`
- 补齐 `.env.local` 引导
- 补齐缺失依赖检查
- 启动后输出 Web URL、API URL、pairing 信息

第二段：

- 提供更轻量的安装脚本或 release installer
- 目标是“下载后更少手工步骤”

#### 文件范围

- `scripts/findeck.sh`
- `scripts/install-launchd.sh`
- `scripts/README.md`
- `docs/operations.md`
- `README.md`
- 可能新增 `scripts/bootstrap-findeck.sh`

#### 验收标准

- 新机器从 clone 到服务可访问，不需要读多份文档和手工执行多段命令。
- 启动后能直接看到连接入口与后续手机接入信息。
- 部署路径和手机端的首次接入路径讲的是同一套故事。

## 分期顺序

建议按下面顺序推进：

1. 基础协议与状态建模
2. 首次接入与可信重连
3. 会话导航与归档
4. Composer 增强
5. 微交互与即时反馈
6. 设置页扩展
7. 一键部署闭环

原因：

- 没有状态机和配对基础，后面的首登体验会很散
- 没有 search / archive，手机端日常使用效率上不去
- Composer 增强要依赖新 API，适合在连接与基础路由稳定后推进
- Settings 和一键部署要在主能力定型后做整合，否则容易返工

## 推荐里程碑

### Milestone A：连接闭环

- 统一连接状态机
- QR / 配对码接入
- 可信主机自动重连
- Settings 中出现可信主机与重连设置

### Milestone B：会话工作流

- 搜索
- 归档列表
- unarchive
- 更显眼的导航入口
- swipe actions

### Milestone C：紧凑型智能 Composer

- `/` 命令面板
- 文件 mention
- 技能 mention
- `speed / access` 控制

### Milestone D：产品化收口

- completion banner
- haptic
- 即时反馈
- 扩展设置页
- 一键部署脚本和文档

## 依赖关系

- 文件 mention 依赖新的文件搜索或文件浏览细化 API。
- 技能 mention 依赖新的技能枚举 / 元数据 API。
- `access / speed` 控制依赖 live-run request schema 扩展。
- 归档列表中的 unarchive 依赖服务端新增 unarchive route。
- 可信重连依赖新的 pairing / trusted host 持久化与恢复接口。
- 一键部署与 QR 配对最好联动设计，否则用户会经历两套接入故事。

## 风险点

- 如果直接把 `speed / access` 常驻在 composer 主栏，输入区会迅速变胖，违背当前设计约束。
- 如果文件 mention 偷懒走“把工作区文件当附件上传”，语义会和桌面端不一致，也会让会话 artifact 变脏。
- 如果状态机只做在 view 层，不做 ViewModel 统一枚举，后续文案和颜色会继续分裂。
- 如果 QR 配对实现时默认只服务单主机，会和现有 server list 心智冲突。
- 如果一键部署只停留在脚本命令层，不同步更新 app 首次接入故事，产品感还是会断裂。

## 最终验收

当本计划完成后，Android 应该达到以下体验结果：

- 新用户第一次连接不再主要依赖手填地址。
- 老用户回到前台时，连接恢复路径简洁且稳定。
- 用户能在手机上快速找到、归档、恢复、继续一个会话。
- 用户能在不让 composer 变胖的前提下发出更复杂的桌面式指令。
- 状态灯、控制条、banner、通知说的是同一套状态语言。
- 设置页可以承载主要偏好配置。
- 后端部署和手机接入形成一条完整闭环。
