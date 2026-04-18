# CodexRemote 对标 Paseo 改进计划书

## 文档目标

本计划书用于回答两个问题：

1. 相比 `getpaseo/paseo`，CodexRemote 当前最明显的差距是什么
2. 接下来应该先做哪些改进，才能最有效地提升“前端设计质感”和“切会话自然度”

本文不主张照搬 Paseo 的产品形态，也不把 relay、桌面端、语音全家桶、iOS 形态拉进本轮范围。

本轮目标只聚焦三件事：

- 让会话切换明显更快
- 让界面切换明显更自然
- 让 Web 与 Android 的产品感更完整

## 当前状态

截至 `2026-04-18`，这份计划书的执行状态可以概括为：

- `Milestone A`：已完成
- `Milestone B`：已完成
- `Milestone C`：已完成
- 整份计划书：已完成

已经明显落地的内容：

- 会话详情已拆成 `summary / messages tail / history pagination`
- Web 与 Android 都已改成更轻的首屏进入路径
- 活跃会话的高频同步已从多次 detail 刷新转成更轻的 tail / hydration 路径
- Web 与 Android 都支持向前加载更早历史
- Web 与 Android 的列表到详情切换都已加入预热缓存与更自然的过渡态
- 针对会话切换体验的性能埋点已经打通，关键链路已有实际耗时数据

当前已验证的结果：

- `Bootstrap load` 已降到几十毫秒量级
- `Live / Repo / Approvals` 补齐链路已降到百毫秒量级
- “进入会话先报错、再恢复”的问题已解决
- Android 端“进入详情页要等很多秒”的主瓶颈已被明显压低

后续仍可继续增强的部分：

- Web 长历史的更深层虚拟化与进一步性能治理
- Android 长历史的更深层渲染优化
- 更系统的状态组件抽象与视觉规范统一
- 发布后的持续体验打磨与回归记录整理

## 结论摘要

CodexRemote 当前的问题，不是“功能明显不够”，而是“重路径过多，壳层持续性不足，状态表达还不够产品化”。

和 Paseo 相比，体感差距主要来自下面 4 个方面：

### 1. 会话详情进入路径太重

当前服务端读取单个 session 详情时，会继续读取并解析该 session 的消息历史；而底层消息读取实现会先扫描 `~/.codex/sessions` 下所有 `jsonl` 文件，再筛出命中项并解析。

这意味着“点进一个会话”不是一次轻量 detail 读取，而更像一次偏重的全局文件扫描后再重建消息。

### 2. 会话页仍然偏“整页加载”

当前 Web 和 Android 都存在“进入详情页后等待 detail 完整可用，再展示主内容”的路径。

这会带来两个问题：

- 用户感知到明显等待
- 列表到详情的转场不连续，像换了一个新页面，而不是进入同一工作区

### 3. 活跃状态同步仍然偏“刷新式”

虽然当前已经有 SSE 与恢复态语义，但详情页仍然会在一些场景下重新拉完整 detail 或以轮询补齐。

结果是：

- 活跃会话切换时更容易卡顿
- 长对话和活跃运行会放大 detail 接口成本
- 页面容易给人“在不断重新装配”的感觉

### 4. 视觉语言已经有方向，但还没完全落到“切换体验”

项目已经有明确的 `Precision Console` 方向，但目前这套语言更多落在颜色、卡片、文案上，还没有完全覆盖：

- 列表到详情的连续感
- 当前会话与历史会话的差异感
- 流式运行、恢复、降级、完成之间的转场感
- 大历史场景下的轻重层级

## 现状依据

### CodexRemote 本地代码现状

- `apps/server/src/routes/sessions.ts`
  - `GET /api/hosts/:hostId/sessions/:sessionId` 会读取 detail 与 messages
- `apps/server/src/codex/cli.ts`
  - `findSessionFilesById()` 先递归扫描 session 目录下全部 `jsonl`
  - `readSessionMessages()` 在命中后再逐个解析文件
- `apps/web/src/app/sessions/[sessionId]/page.tsx`
  - 先 `getSessionDetail()`，再进入主内容
  - 页面在 `loading` 时走整页 loading screen
  - 仅在 session 校验成功后才开始订阅 live SSE
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailViewModel.kt`
  - 初次进入详情页时会并行拉 `detail + live + approvals + repo`
  - 活跃运行期间还会周期性补拉 `getSessionDetail()`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailScreen.kt`
  - 初始态仍然会走 full-screen loading card

### Paseo 对照观察

Paseo 的“自然切换感”主要来自下面几种策略：

- 使用持续存在的 workspace shell，而不是频繁销毁/重建页面
- 路由切换更偏复用已有工作区实例
- timeline 初始化支持 tail/catch-up，而不是每次都重新拉整块历史
- Web 端对长流内容有虚拟化窗口
- 状态更新更偏 store 驱动，而不是 detail 刷新驱动

## 本轮改进原则

### 1. 优先修重路径，不先做大视觉翻新

如果切换本身仍然慢，单纯美化 loading 卡片不会解决根问题。

### 2. 优先建立持续壳层，再优化细节动画

先让用户感觉“我还在同一个工作区里”，再优化卡片、阴影、动效。

### 3. 优先增量同步，不再放大全量 detail 的职责

`session detail` 应该回到“元信息 + 首屏可见内容”的职责，不再承担整个活动会话的持续同步重任。

### 4. Web 与 Android 共用一套状态模型，但保留平台表达差异

要统一的是状态语义与优先级，而不是强行做像素级一致。

## 分阶段执行

## Phase 1: 会话详情链路瘦身

### 目标

让“进入会话”从重扫描、重组装，变成首屏优先、按需补齐。

### 核心改动

1. 拆分 `session detail` 与 `message history`
2. 新增仅返回最近消息尾部的接口
3. 为 session message 读取增加缓存或索引
4. 避免每次读取单 session 时都重新扫描全量 `jsonl`

### 推荐实现

- 在 server 侧新增一层 session message index/cache
- 首屏 detail 只返回：
  - session 元信息
  - 最近一小段消息 tail
  - 当前 live run 摘要
- 历史消息改为 cursor/tail 分页读取

### 主要文件

- `apps/server/src/routes/sessions.ts`
- `apps/server/src/codex/local.ts`
- `apps/server/src/codex/cli.ts`
- `packages/shared/src/api/sessions.ts`
- `packages/shared/src/schemas/session-message.ts`

### 验收标准

- 进入已有会话时，不再依赖一次完整历史重建
- 同一会话二次进入的响应时间明显下降
- 长历史 session 与短历史 session 的进入速度差距明显缩小

## Phase 2: 会话页面改为持续壳层

### 目标

让列表进入详情时不再像“跳转并等待新页面完全加载”，而像“进入同一工作区的具体线程”。

### 核心改动

1. 会话详情支持保留上一次壳层结构
2. loading 改为局部骨架，不再整页白等
3. 先显示已知 session 标题、项目、上次状态，再补齐 timeline
4. SSE 与 tail 数据尽量更早接入

### Web 方向

- 减少整页 `loading-screen`
- 先渲染 detail shell 与 header
- timeline 区域独立 loading / catch-up
- 将当前 live 状态与历史区分加载

### Android 方向

- 进入详情时保留 top bar / control strip / composer 骨架
- 避免首屏只有 loading card
- 将 `SessionLoadingCard` 从主视图阻断器降级为 timeline 区域状态

### 主要文件

- `apps/web/src/app/sessions/[sessionId]/page.tsx`
- `apps/web/src/app/app-shell-client.tsx`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailScreen.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailViewModel.kt`

### 验收标准

- 列表进入详情时，页面骨架立即可见
- 标题、项目名、操作区先出现，timeline 后补
- 用户主观感知从“卡一下”变为“内容在补齐”

## Phase 3: 活跃会话同步改为 delta-first

### 目标

减少活跃 session 下反复 `getSessionDetail()` 的成本，让同步路径更像流式工作区，而不是定时刷新详情页。

### 核心改动

1. 将活跃运行同步改为 `live event + tail catch-up`
2. detail polling 只作为降级兜底
3. 为消息流建立 seq/cursor 机制
4. 终态时再做一次轻量 settle，而不是高频整页补齐

### 推荐实现

- 为 timeline 增加 event seq 或 message cursor
- 运行中只同步：
  - live run 状态
  - 新增消息
  - approval 变化
- full detail refresh 只在以下场景触发：
  - 首次进入
  - 流恢复失败后手动刷新
  - 运行结束后的 settle

### 主要文件

- `apps/server/src/routes/live-runs.ts`
- `apps/server/src/runs/manager.ts`
- `apps/web/src/lib/use-sse.ts`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailViewModel.kt`

### 验收标准

- 活跃 session 下 detail 接口调用次数明显下降
- 会话切换回来后不再频繁看到“重新拼装”
- 弱网恢复仍然可用，但正常路径不依赖轮询

## Phase 4: 长历史渲染优化

### 目标

让长会话在 Web 和 Android 上都保持可滚动、可切换、可恢复。

### 核心改动

1. Web 端引入历史虚拟化窗口
2. Android 端优化 timeline 组装与折叠策略
3. 当前轮与历史轮的渲染权重分离
4. 大块 reasoning/tool output 默认折叠

### 主要文件

- `apps/web/src/app/sessions/[sessionId]/page.tsx`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/ConversationTimeline.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/RichBlockList.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/MessageBubbles.kt`

### 验收标准

- 长会话进入详情时不卡主线程
- 切换会话后滚动恢复更稳定
- 当前轮内容优先可见，历史默认更轻

## Phase 5: 导航和状态设计收口

### 目标

把 `Precision Console` 从“颜色系统”推进到“导航与转场系统”。

### 核心改动

1. 强化列表中的当前项目、当前会话、活跃会话、历史会话差异
2. 增强列表到详情的连续感
3. 强化运行中、恢复中、降级中、已完成四类状态的视觉表达
4. 统一 Web 与 Android 的状态文案与层级

### 推荐方向

- 当前会话使用更明确的持续高亮，而不是仅颜色变化
- 活跃会话增加轻量“活着”的状态提示
- 将 recovery/degraded 设计为“过程状态”，不是“错误替代品”
- composer 区域在运行中和空闲态要有更强区分

### 主要文件

- `docs/frontend-experience-plan.md`
- `apps/web/src/app/globals.css`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/theme/Color.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/theme/Theme.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionListScreen.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailScreen.kt`

### 验收标准

- 切换状态不再只靠 spinner 和提示文案表达
- 列表和详情之间的“我是在哪个会话里”更一眼可辨
- 状态组件形成统一的视觉家族

## 优先级排序

### P0

- Phase 1: 会话详情链路瘦身
- Phase 2: 会话页面改为持续壳层

这是最直接影响“切会话慢”和“切换不自然”的部分。

### P1

- Phase 3: 活跃会话同步改为 delta-first
- Phase 4: 长历史渲染优化

这是把体验从“明显改善”推到“长期稳定顺滑”的关键阶段。

### P2

- Phase 5: 导航和状态设计收口

这是把“工程感界面”再推到“产品感界面”的收口阶段。

## 建议里程碑

### Milestone A

目标：
- 先把进入会话的等待时间压下去

范围：
- Phase 1 的服务端拆分
- Web/Android 首屏骨架改造的一部分

### Milestone B

目标：
- 让切换会话的主观体验自然起来

范围：
- Phase 2 完整落地
- Phase 3 基础 delta 链路

### Milestone C

目标：
- 让长会话和活跃会话也保持稳定顺滑

范围：
- Phase 4
- Phase 5

## 风险与约束

### 1. 不要把本轮改成“大架构迁移”

当前项目仍然是：

- 单机 Codex CLI host
- Fastify server
- Next.js web
- Native Android

本轮改进不需要复制 Paseo 的完整 daemon/desktop/relay 体系。

### 2. 不要让 detail 接口继续承担所有职责

如果后续继续往 `getSessionDetail()` 叠更多字段和场景，切换性能问题会再次回来。

### 3. 不要只做视觉层优化

如果底层仍然是重查询、重刷新，再好的动效也只会让等待“更精致”，不会让体验真正变自然。

## 建议下一步

建议按下面顺序开始执行：

1. 先设计 `session detail / tail messages / history cursor` 三类接口
2. 再改 Web 会话页，去掉整页 loading
3. 再改 Android 会话页，把 loading card 降级成局部状态
4. 最后补 delta sync 和长历史优化

## 成功标准

如果这轮计划执行完成，用户对 CodexRemote 的主观感受应当从：

- “功能都在，但进会话要等”
- “像工程控制台”
- “切换时会重新加载”

变成：

- “进入会话很快”
- “当前线程一直在线”
- “切换像进入同一工作区，而不是打开新页面”
- “运行、恢复、历史都更有层次”
