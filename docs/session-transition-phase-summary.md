# 会话切换体验阶段总结

## 时间范围

本总结对应 `2026-04-17` 到 `2026-04-18` 这一轮围绕会话切换体验展开的集中优化。

关联文档：

- [docs/paseo-gap-improvement-plan.md](/Users/fainal/Documents/GitHub/CodexRemote/docs/paseo-gap-improvement-plan.md)
- [docs/session-transition-release-summary.md](/Users/fainal/Documents/GitHub/CodexRemote/docs/session-transition-release-summary.md)
- [docs/session-transition-acceptance-checklist.md](/Users/fainal/Documents/GitHub/CodexRemote/docs/session-transition-acceptance-checklist.md)

## 收口口径

这份总结只收口两段内容：

1. `Phase 4：长历史渲染优化`
2. `Phase 5：导航和状态设计收口`

其中 `Phase 1` 到 `Phase 3` 已经提供了 `summary / messages tail / history pagination`、轻量 bootstrap、以及活跃会话的 tail / hydration 同步基础；本文件只记录在这个基础上，Phase 4 和 Phase 5 已经落地到什么程度。

为避免夸大，下面的“已完成”只写当前代码和计划书都能对齐的结果；更深层的长历史性能治理、状态组件抽象和更完整的视觉系统统一，仍然保留在剩余风险里。

## Phase 4：长历史渲染优化

### 已落地结果

目前可以明确确认的 Phase 4 落地点有 4 类：

1. 长历史进入不再要求一次性把整段历史全部拉完。
   Web 和 Android 都已经以最近消息 tail 作为首屏历史基础，再通过 `beforeOrderIndex` 继续向前分页。

2. 历史区已经具备“逐步展开”的用户可见反馈。
   两端都已经把历史状态明确成 `部分历史 / 完整历史`，并提供“加载更早消息 / 加载更早回合”的继续展开入口。

3. 当前轮与历史轮已经开始分层渲染。
   Android 的 `ConversationTimeline` 已经把历史轮与当前轮分开，Web 也已经把历史分组与当前轮内容分开显示，降低长历史进入时首屏的认知和渲染负担。

4. 大块历史内容已经不再默认全部平铺。
   Web 历史组默认支持 folded card；Android 当前轮里也已经对折叠内容提供展开入口。这说明“首屏先轻一些”的方向已经真正落地，而不只是接口拆分。

### 当前可宣称的结论

对于 Phase 4，当前更准确的说法是：

- “长历史会话已经从一次性整块加载，推进到尾部优先、向前分页、逐步展开的路径”
- “长历史的首屏负担已经明显下降”

但还不能把它写成：

- “长历史性能已经彻底解决”
- “更深层虚拟化已经完成”
- “极长会话在所有设备上都已经充分验证”

### 仍未完成的部分

Phase 4 还没有完全收口的点主要是：

- Web 长历史的更深层虚拟化与进一步性能治理
- Android 长历史的更深层渲染优化
- 超长会话、弱设备、极端 reasoning / tool output 场景下的专项压力验收

## Phase 5：导航和状态设计收口

### 已落地结果

目前可以明确确认的 Phase 5 落地点有 5 类：

1. 列表到详情已经具备预热缓存与过渡态。
   Web 的 session bootstrap cache / transition cache、Android 的 `SessionDetailBootstrapStore` 和列表侧 prewarm 已经接上，切换时不再完全依赖冷启动详情页。

2. 详情页已经具备“壳层先到，剩余状态继续补齐”的连续感。
   两端都会优先显示 header、标题、路径或基础历史，再补齐 live、repo、approvals 等延迟状态。

3. “切换中”已经从隐性等待改成显性过渡。
   Web 列表项和详情页都有 transition 表达；Android 详情页也已经有独立的 transition notice，而不是只剩 spinner 或错误提示。

4. 恢复 / 降级 / 已同步的状态语义已经开始固定下来。
   Web 的 transport state 与 Android 的 `recoveryNotice` 已经把 `恢复同步`、`降级同步`、`已同步` 变成连续过程状态，而不是把所有异常都挤进错误态。

5. 列表里的层级识别已经更明确。
   `当前项目`、`当前会话`、`最近活跃` 这些词和对应强调方式已经进入 Web / Android 的导航界面，说明 Phase 5 不只是“美化”，而是开始把“我现在在哪个会话里”做成稳定反馈。

### 当前可宣称的结论

对于 Phase 5，当前更准确的说法是：

- “导航连续感和状态语言已经完成基础收口”
- “切换状态不再只靠整页 loading、spinner 或偶发报错来表达”

但还不能把它写成：

- “导航与状态设计系统已经完全定型”
- “Web 与 Android 的所有状态组件已经彻底统一”
- “整份 Precision Console 视觉语言已经全部收口完毕”

### 仍未完成的部分

Phase 5 还没有完全收口的点主要是：

- 更系统的状态组件抽象
- 更完整的视觉规范统一
- 更正式的跨端人工验收记录

## 阶段结论

把 Phase 4 和 Phase 5 合在一起看，当前最准确的阶段结论是：

- 会话切换体验主线已经完成一轮可交付的系统化收口
- `Milestone A` 已完成
- `Milestone B` 核心已完成
- `Milestone C` 相关能力已经落地了关键基础，但还不宜写成“全部完成”

换句话说，本轮已经足够支撑“会话切换体验明显更快、更自然”的阶段性结论；但如果最终说明要保持审慎，就仍然需要保留“长历史深层性能治理与状态组件系统化仍在后续范围内”这条风险说明。

## 建议同步到最终说明的风险

最终说明里建议保留下面 3 条风险，不要省略：

1. Phase 4 当前确认的是“尾部优先 + 向前分页 + 折叠减负”的落地，不等于更深层长历史虚拟化已经完成。
2. Phase 5 当前确认的是“导航连续感与状态语言基线”已经落地，不等于跨端状态组件体系已经完全统一。
3. 当前文档能支撑“已落地结果”和“验收口径”收口，但仍然需要按验收清单补齐一轮 Web / Android / 弱网 / 长历史的人工验收记录。
