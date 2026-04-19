# Android 下一阶段计划书

## Status

Proposed on 2026-04-18.

This document is the source plan for the next Android-only optimization phase after `v0.4.5`.

## Execution Snapshot

Updated on 2026-04-18 after the closing implementation batch.

### 已完成

- `v0.4.6` + `v0.4.7`：新建会话后的返回链路已修复，`DraftSessionDetail -> SessionDetail` 会保留真实父层，系统返回与左上角返回都能正常回退
- `v0.4.8` + `v0.4.9`：语音能力已从“依赖系统语音识别”切到“本地录音 -> 语音附件 -> Codex 本机处理”的闭环，绕开了设备 ROM 对标准 `RECOGNIZE_SPEECH` intent 不开放的问题
- `v0.4.10` + `v0.4.11`：`$skill` suggestion 已形成“选中 -> 发送 -> Codex 可识别”的闭环，并补了发送前规范化
- `v0.4.12` + `v0.4.13`：Android 富文本块已补上标准 markdown table 识别，并落成“表格占位卡片 -> 全屏预览查看器”的阅读闭环；当前预览由原生 `WebView` 承载，支持完整显示、双指缩放与中英文字形稳定展示
- `v0.4.14`：Splash 已加入最少 `2` 秒停留，冷启动不再“刚出现就跳走”，同时不会额外拖长可信重连成功/失败后的转场
- `v0.4.15` + `v0.4.16`：品牌与首页顶区已完成减噪，`精准控制台 / Precision Console` 已收敛成更轻的 `控制台 / Console`，主界面顶区最终收成仅保留 3 个统计标签
- `v0.4.17`：本轮收口判断已明确，Android 端达到“可暂停扩张、以维护和小修为主”的阶段线

### 本批次额外打磨

- 语音输入 UI 已改为更贴近对讲机的手机交互：语音按钮外置、进入按住说话模式、松开自动发送、少于 2 秒判无效
- 语音消息会以独立卡片显示，并支持回放
- 语音消息默认只显示“`X秒语音信息`”，不再暴露原始文件名
- 单条语音录音上限设为 `90` 秒，避免手机端出现过长低效输入
- 针对语音附件处理的英语过程话已在服务端消息解析层做过滤，避免前端把 commentary 当正式对话显示

### 当前版本判断

- Android `versionName` 已推进到 `0.4.17`
- 当前状态适合作为 Android-only 收口版本基线
- 这轮计划的剩余项已全部落地，后续默认只保留维护、小修和必要兼容性跟进

## 范围

- 只评估和规划 Android 手机端
- Web 端不在本轮范围内
- 当前基线版本：`v0.4.5`
- 版本规则：每完成一个验收通过的子任务，版本号 `+0.0.1`

## 先给结论

这轮不是继续“加更多功能”，而是把手机端收口成一个稳定、轻量、可暂停演进的远程 Codex cockpit。

优先级判断如下：

- `P0`：新建会话后的返回链路稳定、语音按钮真正可用、`$` 技能选择后可真正触发
- `P1`：桌面端表格输出在手机端保持可读结构，其次才是开屏停留 2 秒、首页与项目导航减字减噪
- `P0 决策项`：明确手机端暂停线，避免 APK 无限膨胀

## 现状判断

基于当前仓库代码，几个关键点已经比较明确：

- 语音能力不是从零开始。`AndroidManifest.xml` 已声明 `RECORD_AUDIO`，`SessionVoiceInputController.kt` 已接入 `SpeechRecognizer` 和外部识别器 fallback。当前问题更像“链路不稳定或反馈不明确”，不是“完全没做”。
- `$` 技能选择也不是纯占位。Android 端已经会调用 `/api/skills` 拉取技能建议，问题更像“选中后只插入文本，但没有形成稳定的可触发语义闭环”。
- 开屏当前没有“最少展示 2 秒”的节流逻辑。
- 会话列表顶部当前确实存在“精准控制台 / 项目导航 / 项目按更新时间分组”这类多层说明，信息密度偏高，不利于手机扫读。
- 当前 Android 富文本解析只支持 `Paragraph / CodeBlock / ListBlock / MemoryCitation`，没有 `TableBlock`，所以桌面端输出 markdown 表格时，手机端属于结构性降级，而不是单纯皮肤差异。

## 外部参考结论

本轮只借鉴产品边界和交互取向，不照搬架构。

### HAPI

- 定位是本地优先、远程控制、Web / PWA / Telegram Mini App
- 强调无缝切换、手机审批、终端远程控制、语音控制
- 说明手机端的核心价值是“不中断地继续控制”，不是把手机做成完整 IDE

### Remodex

- 定位是 local-first paired remote control
- 手机上的角色是已配对远程控制器，真正的 git 与会话持久化仍在桌面侧
- 这进一步说明手机端应优先保证控制闭环，而不是无限扩充本地复杂能力

### Paseo

- 定位是一套跨手机、桌面、CLI 的统一 agent 控制界面
- 强调 voice control、cross-device、多 agent 管理
- 但核心仍是“ship from your phone”，不是“在手机上重建桌面开发环境”

## 手机端边界评估

### 建议的产品边界

findeck Android 端应停在“远程控制台”而不是“移动 IDE”。

手机端完成以下 5 件事后，就可以进入“可暂停状态”：

1. 新建会话、继续会话、返回上一层这条主链路稳定
2. 至少一条高频输入加速链路真正可用
3. `语音` 和 `技能 mention` 两条链路至少做到能用、可反馈、可回退
4. 主界面信息密度收敛，手机端一眼能扫懂
5. 后续新增能力不再明显扩大包体、状态复杂度和维护成本

### 到什么程度可以暂停

满足下面条件后，建议暂停大规模 Android 功能扩张，只做维护和小修：

- 返回链路没有“退不回去 / 直接最小化 / 左上角无效”这类主路径故障
- 语音按钮可用，并且失败时有明确提示与 fallback
- `$skill` 选择后能稳定进入 Codex 可识别的触发语义
- 桌面端常见的表格输出在手机端能以“表格或等价结构化卡片”稳定读懂
- Splash、品牌文案、顶部导航区已经完成减噪
- 不再新增“桌面端同款但手机上低频”的重能力

### 明确不追求

以下内容不应作为这一阶段继续扩张的目标：

- 把 Android 做成完整桌面替代品
- 为了手机端去复制一整套 Web 端能力
- 引入重型本地文件系统、复杂项目管理器、全量 Git 客户端
- 为少量低频场景持续增加常驻按钮和说明文字
- 追求“功能看起来很多”，而牺牲 APK 体积、主路径稳定性和扫读效率

## 优先级拆分

## P0：必须先做

### 1. 新建会话后的返回链路修复

#### 目标

解决“新建会话进入运行态后，系统返回手势直接把应用最小化、左上角返回无反应”的问题。

#### 可能根因

- `DraftSessionDetail -> SessionDetail` 的 nav stack 替换链路存在不一致
- 创建成功后的回退栈没有保留 `SessionList`
- 返回事件与创建成功后的跳转时机发生竞争

#### 子任务

- `v0.4.6`：补一轮导航栈审计，确认 `新建项目 -> 草稿会话 -> 真正会话` 的 back stack 形态
- `v0.4.7`：修复返回链路并补回归验证，覆盖系统返回手势和左上角返回按钮

#### 文件范围

- `apps/android/app/src/main/java/app/findeck/mobile/navigation/AppNavHost.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionDetailScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/navigation/Screen.kt`

### 2. 语音按钮闭环修复

#### 目标

让语音按钮从“可能无反应”变成“可录、可转、失败可提示、不可用可回退”。

#### 产品判断

本阶段只做“语音转文字输入”MVP，不做全双工语音对话。

#### 参考结论

- HAPI 与 Paseo 都把语音放在 hands-free control / dictation 的位置
- 这更适合当前 findeck，成本低、价值高、不会把手机端做重

#### 子任务

- `v0.4.8`：排查当前无反应原因，补齐设备能力检测、权限提示、外部识别器 fallback 暴露
- `v0.4.9`：完成语音输入闭环，识别结果稳定写回 composer，并补回归验证

#### 文件范围

- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionVoiceInputController.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionDetailScreen.kt`
- `apps/android/app/src/main/AndroidManifest.xml`
- `apps/android/app/src/main/res/values/strings_session_detail.xml`
- `apps/android/app/src/main/res/values-en/strings_session_detail.xml`

### 3. `$` 技能 mention 真正触发

#### 目标

解决“输入 `$` 能看到技能，选中后却无法真正触发”的问题。

#### 现状判断

当前已经有技能枚举与建议面板，所以第一优先级不是“继续做技能列表”，而是把“选中 -> 发送 -> Codex识别”的闭环做通。

#### 参考结论

- HAPI / Paseo 的经验更偏向把手机端输入加速做成轻量、明确、低学习成本
- 因此这里不建议做复杂技能中心，而建议把 mention 触发语义做稳定

#### 子任务

- `v0.4.10`：修正技能 suggestion 选中后的插入语义与可见反馈，避免只插入一个“看起来像技能名”的字符串
- `v0.4.11`：补全发送前的 mention 规范化或 prompt 注入策略，并补回归验证

#### 文件范围

- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/ComposerSuggestions.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionDetailScreen.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionDetailViewModel.kt`
- `apps/server/src/routes/skills.ts`
- `packages/shared/src/api/skills.ts`

## P1：应做，但排在稳定性之后

### 4. 桌面端表格输出的手机端结构化渲染

#### 目标

解决“桌面端输出的是表格，但 Android 端只按普通段落显示，导致列结构丢失”的问题。

#### 产品判断

这项不是纯视觉 polish，而是内容保真问题，优先级高于 splash 与文案减噪。

本阶段不追求完整 Markdown 引擎，而是做一个轻量、够用、适合手机的表格查看 MVP：

- 优先识别标准 markdown table
- 会话流中先显示轻量表格占位卡片
- 点开后进入完整表格预览窗口，默认完整显示，支持双指缩放
- 不做复杂合并单元格、嵌套表格、完整 HTML table 兼容

#### 子任务

- `v0.4.12`：补表格语法识别与数据模型，确认 Android 端对常见 markdown table 的解析规则
- `v0.4.13`：完成手机端表格渲染 MVP，并补回归验证

#### 文件范围

- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/RichTextBlocks.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/RichBlockList.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/MessageBubbles.kt`
- `apps/android/app/src/test/java/app/findeck/mobile/ui/sessions/*`

### 5. Splash 最少保留 2 秒

#### 目标

保留现在的开屏视觉，但增加最少 2 秒展示时间，避免刚出现就跳走。

#### 子任务

- `v0.4.14`：加入 splash 最少停留 2 秒的节流逻辑，并保证可信重连失败场景不会被错误延迟

#### 文件范围

- `apps/android/app/src/main/java/app/findeck/mobile/MainActivity.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/navigation/AppNavHost.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/splash/SplashScreen.kt`

### 6. 品牌与副标题精简

#### 目标

把“精准控制台”和“项目文件夹”等重说明文案收敛成更适合手机端的短词。

#### 产品判断

本轮只做减法，不做品牌重设计。

#### 子任务

- `v0.4.15`：统一品牌文案，默认只保留“控制台 / Console”，去掉“精准控制台”等过重命名

#### 文件范围

- `apps/android/app/src/main/res/values/strings_entry.xml`
- `apps/android/app/src/main/res/values-en/strings_entry.xml`
- `apps/android/app/src/main/res/values/strings_session_list.xml`
- `apps/android/app/src/main/res/values-en/strings_session_list.xml`

### 7. 主界面项目导航减噪

#### 目标

去掉上方项目导航方块里冗余的 3 行说明，只保留 3 个标签，让入口更干净。

#### 子任务

- `v0.4.16`：删除“精准控制台 / 项目导航 / 项目按更新时间分组”这类说明性文字，只保留 3 个统计标签层表达

#### 文件范围

- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionListScreen.kt`
- `apps/android/app/src/main/res/values/strings_session_list.xml`
- `apps/android/app/src/main/res/values-en/strings_session_list.xml`

## 决策收口任务

### 8. 手机端暂停线确认

#### 目标

在这轮收尾时，明确 Android 端已经到达“可以暂停”的程度，而不是继续无上限加功能。

#### 子任务

- `v0.4.17`：输出手机端暂停清单、非目标清单、后续只保留维护项的判断文档或 release note 段落

#### 输出物

- 本文档更新为执行结果版，或新增 release summary / acceptance checklist

## 推荐提交节奏

版本号每完成一个子任务都要 `+0.0.1`，但 GitHub 提交不建议跟子任务一一绑定。推荐按“闭环能力”提交：

1. `Commit A`
   内容：`v0.4.6` + `v0.4.7`
   原因：都属于同一条导航回退主链路

2. `Commit B`
   内容：`v0.4.8` + `v0.4.9`
   原因：都属于语音输入闭环，适合一起验收

3. `Commit C`
   内容：`v0.4.10` + `v0.4.11`
   原因：都属于技能 mention 触发闭环

4. `Commit D`
   内容：`v0.4.12` + `v0.4.13`
   原因：都属于表格渲染保真闭环，适合单独提交，便于回归桌面端输出兼容性

5. `Commit E`
   内容：`v0.4.14` + `v0.4.15` + `v0.4.16`
   原因：都属于 UI polish 与减噪，可合并成一次轻量视觉提交

6. `Commit F`
   内容：`v0.4.17`
   原因：作为阶段收口和暂停判断，单独提交更清晰

## 验收门槛

这轮计划完成后，Android 端应达到下面状态：

- 手机端主路径稳定，不再出现会话创建后“退不回去”
- 语音按钮至少在支持设备上稳定可用，在不支持设备上有清晰提示
- `$skill` 从建议面板到实际触发形成闭环
- 桌面端常见 markdown 表格在 Android 端不再丢失列结构，并可通过独立预览窗口完整查看
- Splash 与首页顶区的文案密度明显下降
- 团队对“手机端做到这里先暂停”有明确共识

## 这一轮之后建议暂停新增的大项

- 全量 Web 对齐
- 更复杂的本地项目管理
- 重型 Git 面板
- 完整桌面级调试体验
- 低频但高维护成本的装饰性功能

## 参考项目

- HAPI: <https://github.com/tiann/hapi>
- Remodex: <https://github.com/Emanuele-web04/remodex>
- Paseo: <https://github.com/getpaseo/paseo>
