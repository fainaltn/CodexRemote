# Android Turn Timeline Refactor Plan

## Status

Completed in code as of 2026-04-15.

The refactor has been implemented with these outcomes:

- current-turn rendering is projection-first and message-first
- collapsed current-turn assistant replies use product-facing `上 N 条消息` headers
- server/shared/Android message schemas now expose `turnId`, `itemId`, and `orderIndex`
- Android current-turn and history grouping both prefer stable identity over text-shape heuristics
- `splitOutputSections(...)` has been removed from Android code instead of remaining on the fallback path

Any remaining work is optional polish or future live-item modeling, not unfinished core refactor work.

## Goal

Refactor the Android session-detail timeline so the current turn behaves like a real multi-message chat timeline instead of a single `lastOutput` panel with frontend heuristics.

The target user-visible behavior is:

- each assistant reply within the current turn renders as its own message card
- intermediate replies remain visible after the turn completes, but collapse into tappable summary cards
- the final reply stays expanded
- streaming feels calm and intentional instead of racing through a full-text typewriter effect
- thinking / activity state is shown as a lightweight live state, not as another block of pseudo-final prose
- the UI no longer flashes, swallows, or replaces intermediate output between phases

This plan is intentionally Android-first. It does not expand the transport model, pairing model, or relay architecture.

## Why This Plan Exists

Recent iterations improved several things:

- recoverable disconnects no longer hard-fail as often
- first-use surfaces feel more productized
- runtime controls are more visible
- notification tiers are clearer
- the current turn no longer drops into a large empty gap between the first and second visible reply phases

But the current turn timeline still has structural issues:

- the first visible assistant card can briefly appear and then disappear
- multiple assistant replies in one turn do not reliably become multiple cards
- the active turn still behaves like one mutable output region rather than a message list
- intermediate steps collapse too aggressively after completion
- collapsed cards are passive summaries rather than interactive message groups
- labels like `阶段 1` / `阶段 2` feel implementation-driven instead of product-driven
- the typewriter animation is too eager and gets interrupted by later state changes

## Current Problems

### 1. Output-First Rendering

The current Android detail screen still treats `liveRun.lastOutput` as the primary live rendering source.

That means:

- the active turn is inferred from one evolving text blob
- frontend code tries to recover multiple cards by splitting text after the fact
- once output changes shape, the rendered cards can be replaced instead of appended

This is the biggest reason the UI still feels brittle.

### 2. Message List And Live Output Are Out Of Sync

The app has two separate sources of truth for the current turn:

- `session.messages`
- `liveRun.lastOutput`

These two sources are merged late in the render path with heuristics like:

- `currentTurnStoredReply`
- `retainedLiveOutput`
- `splitOutputSections(...)`

This creates race conditions:

- one source updates before the other
- a previous phase is briefly visible, then removed
- a later phase arrives and the UI replaces the whole assistant area

### 3. No Dedicated Turn Projection Layer

The current Android timeline has helpers like `buildHistoryRounds(...)`, but there is no Android equivalent of a dedicated render reducer for:

- current-turn live message projection
- collapse groups
- thinking rows
- tool/activity rows
- stable collapsed header state

Without that layer, view code is forced to derive structure directly from mixed raw data.

### 4. Collapse UX Is Too Literal

The current naming and collapse logic uses internal-phase language:

- `阶段 1`
- `阶段 2`

That makes the interface feel mechanical.

What users actually understand is:

- `上 4 条消息`
- `已处理 4m 12s`
- tap to expand hidden messages

### 5. Typewriter Effect Is Over-Applied

The current typewriter treatment still tries to reveal too much text as if the whole visible output were one active stream.

The result:

- it feels rushed
- it is interrupted when later content arrives
- it competes with message-phase transitions

## Root Cause Analysis

### Local Android Architecture

The current Android pipeline is roughly:

1. fetch `session.messages`
2. fetch `liveRun`
3. derive:
   - `currentTurnPromptOverride`
   - `retainedLiveOutput`
   - `latestCanonicalAssistantReply(...)`
4. render current turn in `ConversationTimeline`
5. attempt to split output into sections in `MessageBubbles`

That pipeline was good enough for a single reply region, but not for real multi-message current-turn rendering.

### What Is Missing

Android still lacks a stable current-turn projection with these explicit buckets:

- `historicalRounds`
- `currentTurnUser`
- `currentTurnSettledAssistantMessages`
- `currentTurnStreamingAssistantTail`
- `currentTurnThinkingState`
- `currentTurnCollapsedAssistantMessages`

Until those are first-class state, the UI will keep fighting between `messages` and `lastOutput`.

## What Remodex Does Better

The most relevant files inspected from `remodex` are:

- `CodexMobile/CodexMobile/Views/Turn/TurnTimelineReducer.swift`
- `CodexMobile/CodexMobile/Views/Turn/TurnTimelineView.swift`
- `CodexMobile/CodexMobile/Views/Turn/TurnMessageComponents.swift`
- `CodexMobile/CodexMobileTests/TurnTimelineReducerTests.swift`

Important takeaways:

### 1. Reducer First, Views Second

`remodex` projects raw messages into render-ready timeline items before the view layer renders them.

That reducer handles:

- duplicate removal
- collapse rules
- thinking-row reuse
- intra-turn ordering
- grouped bursts for repeated tool/system activity

This is the main architectural difference we should copy.

### 2. Streaming State Is Not Treated As Final Text

`remodex` normalizes placeholder streaming text like `Thinking...` before rendering assistant message content.

That means:

- live state can be displayed as a status block
- assistant prose is not polluted by transitional placeholder text
- the UI does not confuse activity with a final reply

### 3. Collapse Is A Projection Concern

In `remodex`, collapse headers are derived from grouped render items, not from string slicing.

This makes headers stable and interactive:

- one grouped item owns the hidden-count state
- expansion is driven by render state, not by re-parsing the same string differently

### 4. Message Identity Matters

The `remodex` reducer logic heavily benefits from stable identifiers like:

- `turnId`
- `itemId`
- `orderIndex`
- message kind

Those identities make it possible to know whether multiple assistant rows are:

- true distinct replies
- placeholder updates
- duplicate event echoes
- separate activity cards

Our current Android `SessionMessage` model does not expose enough of that.

## Proposed Direction

### Principle 1: Message-First Current Turn

The current turn should be rendered from structured messages first.

Use `liveRun.lastOutput` only for:

- the still-streaming tail that has not landed in `session.messages` yet
- short-lived continuity between polling intervals

Do not use `lastOutput` as the primary source for deciding how many assistant cards exist.

### Principle 2: Separate State Kinds

Current turn UI should explicitly separate:

- user message
- assistant settled messages
- assistant streaming tail
- thinking state
- degraded/recovering transport notices

These should not all be flattened into one assistant block.

### Principle 3: Collapse By Message Count, Not By Phase Number

Replace `阶段 1 / 阶段 2 / ...` with product-facing collapse headers:

- `上 3 条消息`
- `上 12 条消息`

Optional secondary summary:

- `已处理 4m 12s`

These should be tappable and expand inline.

### Principle 4: Streaming Should Feel Calm

Do not typewriter the whole active assistant region continuously.

Instead:

- render settled assistant messages immediately as full cards
- render only the newest streaming tail as active
- use a restrained typewriter or short progressive reveal for the tail only
- use a subtle live status chip / indicator when the model is still thinking

## Target UX Model

### While A Turn Is Running

If the assistant has already produced multiple visible replies:

- reply 1: full card
- reply 2: full card
- current thinking/output tail: active card with live indicator

If the assistant has not yet emitted visible prose:

- show a small live status block
- do not pretend there is a final prose card yet

### After The Turn Completes

If there were multiple assistant replies:

- keep the final reply expanded
- collapse earlier replies behind a header:
  - `上 4 条消息`
- tapping expands them inline

If there was only one assistant reply:

- just show the final reply card

### During Recovery / Polling Gaps

- keep the current visible assistant cards mounted
- never blank the current-turn assistant area just because the next polling cycle has not yet landed
- degraded / recovering transport should appear as auxiliary status, not as a destructive assistant-content replacement

## Required Refactor

### Phase 1: Current-Turn Projection Model

Create a dedicated projection layer for the current turn, separate from `buildHistoryRounds(...)`.

Suggested new model:

```kotlin
internal data class CurrentTurnProjection(
    val userPrompt: String?,
    val settledAssistantMessages: List<SessionMessage>,
    val collapsedAssistantMessages: List<SessionMessage>,
    val streamingTailText: String?,
    val showThinkingState: Boolean,
    val hasTerminalReply: Boolean,
)
```

Suggested file location:

- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailHelpers.kt`

Acceptance criteria:

- no UI component needs to infer collapse or message count from raw string sections
- `ConversationTimeline` renders projection items, not mixed heuristics

### Phase 2: Current-Turn Render Components

Split assistant rendering into distinct components:

- `SettledAssistantMessageCard`
- `CollapsedAssistantMessageHeader`
- `StreamingAssistantTailCard`
- `ThinkingStatusCard`

Suggested file location:

- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/MessageBubbles.kt`

Acceptance criteria:

- one settled assistant reply always maps to one card
- the collapsed header is interactive
- the final reply remains expanded after completion

### Phase 3: Collapse Header UX

Introduce two collapse header styles:

1. message-count header
   - `上 4 条消息`
2. processed-duration header
   - `已处理 4m 12s`

Use message-count as the primary collapse mechanism first.

Use processed-duration only if it adds clarity without creating noise.

Suggested file location:

- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/ConversationTimeline.kt`
- string resources in `strings_session_timeline.xml`

Acceptance criteria:

- headers are product-language, not phase-language
- collapsed cards are tappable and expand inline

### Phase 4: Streaming Behavior Tuning

Adjust the typewriter and live-state treatment.

Recommended changes:

- do not typewriter already-settled assistant cards
- typewriter only the live tail
- reduce total typewriter coverage
- use live state chip / shimmer / dot indicator to show continued thinking

Implemented in:

- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/MessageBubbles.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailHelpers.kt`

Result:

- active tail is limited to the live card instead of settled cards
- later reply phases no longer overwrite earlier settled assistant cards through the old section-splitting model

### Phase 5: Session Message Identity Upgrade

This is likely necessary for the best version of the experience.

Extend the Android-visible session message schema with more timeline identity when available:

- `turnId`
- `itemId`
- `orderIndex`
- `isStreaming` or equivalent transient hint

Likely files:

- `packages/shared`
- `apps/server/src/codex/cli.ts`
- `apps/server/src/routes/sessions.ts`
- Android data models

Acceptance criteria:

- frontend can distinguish true new assistant messages from placeholder or duplicate updates
- current turn ordering no longer depends on fragile text comparison

## Concrete File Scope

Primary Android files:

- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailViewModel.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailHelpers.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/ConversationTimeline.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/MessageBubbles.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/HistoryRoundItem.kt`
- `apps/android/app/src/main/res/values/strings_session_timeline.xml`
- `apps/android/app/src/main/res/values-en/strings_session_timeline.xml`

Likely backend follow-up files:

- `apps/server/src/codex/cli.ts`
- `apps/server/src/routes/sessions.ts`
- shared session-message schema files under `packages/shared`

## Execution Order

Recommended implementation order:

1. stop doing further phase-number UI tweaks
2. add current-turn projection model
3. change current-turn render path to use projection
4. replace `阶段 N` with `上 N 条消息`
5. make collapsed header expandable
6. tune live tail typewriter / thinking indicator
7. only then consider backend message identity extensions

## Success Criteria

We can call this refactor successful when all of these are true:

- during a single active turn, multiple assistant replies render as multiple cards
- earlier replies do not disappear when later replies arrive
- after completion, earlier replies remain behind a tappable collapsed header
- the final reply stays expanded
- there is no visible blank gap between reply phases
- thinking state is shown as live status, not as a fake final text block
- the UI uses product language like `上 4 条消息`, not `阶段 1`

## Next Step

The next coding pass should implement only:

- Phase 1: current-turn projection model
- Phase 2: current-turn render components
- Phase 3: collapse header interaction

Do not expand into backend schema work in the same pass unless the frontend is fully blocked.
