# Android Turn Timeline Execution Plan

## Status

Completed in code as of 2026-04-15.

Implemented outcomes:

- `CurrentTurnProjection` now owns current-turn structure.
- current turn renders message-first with settled assistant cards, a live tail, and a tappable `上 N 条消息` collapsed header.
- Android and server message models now carry `turnId`, `itemId`, and `orderIndex`, and Android projection/history grouping prefer those identities.
- settled cards no longer use the old section-splitting path.
- `splitOutputSections(...)` has been removed from Android code rather than retained as an active fallback.

Remaining work, if any, is optional follow-up polish or a future true live-item `isStreaming` model, not part of the core execution pass.

## Goal

Refactor the Android session-detail current-turn experience so it behaves like a real multi-message chat timeline:

- every distinct assistant reply in the current turn becomes its own card
- earlier replies do not flash, disappear, or get replaced by the next phase
- after completion, earlier replies remain visible behind a tappable collapsed header
- the final reply stays expanded
- live thinking/activity is shown as state, not as fake final prose
- streaming feels calm and continuous instead of rushing through a mutable full-text panel

This plan is explicitly about Android session detail. It does not expand the transport model, relay model, or pairing model.

## Why This Needs A Real Refactor

The recent incremental fixes improved symptoms:

- less empty gap between early and later reply phases
- more runtime-control visibility
- calmer degraded/recovery handling
- tighter composer layout

But the main experience problem remains:

- the current turn is still modeled as one changing output region, not as a message list

That is why the UI still exhibits these issues:

- the first reply card can flash and get swallowed
- multiple assistant replies often still land inside one visual region
- collapse behavior is unstable because it is inferred late
- labels like `阶段 1` and `阶段 2` feel internal and mechanical
- the typewriter effect gets interrupted by later turn state

This is not a styling bug. It is a data-model and projection problem.

## Current Android Pipeline

The current-turn UI is assembled from two partially independent sources:

1. `session.messages`
2. `liveRun.lastOutput`

The main files involved today are:

- [SessionDetailViewModel.kt](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailViewModel.kt)
- [SessionDetailHelpers.kt](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailHelpers.kt)
- [ConversationTimeline.kt](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/ConversationTimeline.kt)
- [MessageBubbles.kt](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/MessageBubbles.kt)
- [HistoryRoundItem.kt](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/HistoryRoundItem.kt)

The historical current-turn render path depended on a chain like:

- `latestCanonicalPrompt(...)`
- `latestCanonicalAssistantReply(...)`
- `currentTurnPromptOverride`
- `retainedLiveOutput`
- `cleanedOutput`
- `replyOutput`
- `splitOutputSections(...)`

That approach worked well enough when the current turn behaved like one assistant reply. It broke down as soon as one turn contained multiple assistant replies.

## Root Cause Analysis

### 1. Output-First Instead Of Message-First

The active turn still treats `liveRun.lastOutput` as the main live rendering source.

That means the UI has to guess:

- whether one text blob is actually multiple replies
- when a previous reply should stay visible
- when it should be replaced
- whether the current block is final prose or still an intermediate state

As long as the active turn is output-first, multi-card stability will stay fragile.

### 2. `session.messages` And `lastOutput` Update On Different Clocks

These two sources arrive on different cadences:

- `session.messages` come from `getSessionDetail(...)`
- `lastOutput` comes from live-run polling / SSE

Typical bad sequence:

1. `lastOutput` shows new content
2. UI renders it as live content
3. later `session.messages` catches up
4. current-turn heuristics recompute
5. previous content gets replaced or collapsed too early

That is the core reason for the flash-and-swallow behavior.

### 3. The Current Turn Has No Dedicated Projection Layer

Android already has a proper grouping model for history:

- `HistoryRound`
- `buildHistoryRounds(...)`
- `primaryMessages`
- `foldedMessages`

But the current turn has no equivalent `CurrentTurnProjection`.

So view code in `ConversationTimeline.kt` is doing too much:

- identifying settled assistant replies
- deciding collapse behavior
- deciding whether there is a live tail
- deciding what should stay visible after completion

That logic belongs in a helper/reducer layer, not inline in the composable.

### 4. `splitOutputSections(...)` Was Only A Fallback Heuristic

Splitting output by blank lines can sometimes mimic multi-card rendering, but it is not reliable enough to define message boundaries.

It fails when:

- multiple replies do not have predictable blank-line separation
- a single reply contains multiple paragraphs
- a thinking/activity update looks like content
- the final reply reuses part of earlier output

That helper was never reliable enough to define message boundaries, and it has now been removed from Android code instead of kept on the active path.

### 5. Collapse UI Is Backwards

Right now the code still leans on phase-centric naming and late structural inference.

What users actually understand is:

- `上 4 条消息`
- `已处理 4m 12s`

Those are product concepts, not implementation phases.

## What `remodex` Gets Right

The most relevant reference files were:

- `CodexMobile/CodexMobile/Views/Turn/TurnTimelineReducer.swift`
- `CodexMobile/CodexMobile/Views/Turn/TurnTimelineView.swift`
- `CodexMobile/CodexMobile/Views/Turn/TurnMessageComponents.swift`
- `CodexMobile/CodexMobileTests/TurnTimelineReducerTests.swift`

Important takeaways:

### 1. Reducer First, Views Second

`remodex` projects raw messages into render-ready items first.

That reducer owns:

- message dedupe
- turn ordering
- thinking collapse
- grouped system activity
- render grouping

This is the right mental model for us too.

### 2. Thinking Is A State Row, Not Assistant Prose

In `TurnMessageComponents.swift`, `Thinking...` is normalized and handled as a dedicated UI state block.

That prevents:

- placeholder text being rendered as final assistant content
- thinking updates mutating the assistant prose region

### 3. Collapse Belongs To The Render Projection

`remodex` creates grouped render items and then renders headers like `+N tool calls`.

The important point is not the exact visual treatment. The important point is:

- collapse headers come from grouped render items
- expansion state is attached to that group
- the UI does not derive collapse from ad hoc string slicing

### 4. Stable Identity Helps A Lot

`remodex` benefits from richer identifiers:

- `turnId`
- `itemId`
- `orderIndex`

These make it much easier to know whether two assistant rows are:

- distinct replies
- duplicate payloads
- placeholder transitions
- reordered turn content

We do not have enough of that on Android yet.

## What We Did

## Phase 1: Introduce `CurrentTurnProjection`

Create a dedicated projection model in [SessionDetailHelpers.kt](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailHelpers.kt).

Suggested model:

```kotlin
internal data class CurrentTurnProjection(
    val userPrompt: String?,
    val settledAssistantMessages: List<SessionMessage>,
    val collapsedAssistantMessages: List<SessionMessage>,
    val finalAssistantMessage: SessionMessage?,
    val streamingTailText: String?,
    val showThinkingState: Boolean,
    val isWaitingForFirstVisibleReply: Boolean,
)
```

This projection should be derived from:

- `session.messages`
- `liveRun`
- `currentTurnPromptOverride`
- `retainedLiveOutput`

But it should make a strict distinction between:

- settled assistant messages
- still-streaming live tail

Acceptance criteria:

- `ConversationTimeline` no longer decides current-turn structure on its own
- current-turn structure is computed in one place

## Phase 2: Make The Current Turn Message-First

Refactor `ConversationTimeline.kt` so the current turn renders from `CurrentTurnProjection`, not directly from `replyOutput`.

Target render states:

- user prompt card
- zero or more settled assistant cards
- optional current live tail card
- optional thinking-state card
- optional collapsed header above the final reply

Acceptance criteria:

- multiple assistant replies in the current turn become multiple cards when present in `session.messages`
- `liveRun.lastOutput` is only used for the live tail, not for defining all current-turn cards

## Phase 3: Collapsed Header UX

Introduce an explicit collapsed header component, probably in `MessageBubbles.kt` or `ConversationTimeline.kt`.

First version:

- title: `上 %d 条消息`
- optional subtitle: `已处理 4m 12s`
- chevron affordance
- tap to expand/collapse inline

Important behavior:

- collapsed messages stay associated with the final reply
- the final reply remains visible and expanded
- expansion state is user-controlled and stable

Acceptance criteria:

- earlier replies do not vanish after turn completion
- earlier replies are accessible via a clear collapsed header
- phase-centric labels disappear from the UI

## Phase 4: Streaming Experience Cleanup

Only after the current-turn projection is stable:

- reduce typewriter scope to the current live tail only
- do not typewriter settled cards
- use a lightweight thinking/activity indicator instead of huge evolving text
- keep the current visible cards mounted during polling gaps

Acceptance criteria:

- streaming feels calmer
- the first reply no longer flashes and disappears
- later updates do not erase earlier settled cards

## Phase 5: Backend Identity Upgrade If Needed

If the frontend still cannot reliably distinguish distinct assistant replies from duplicate snapshots, add more message identity in the shared message schema:

- `turnId`
- `itemId`
- `orderIndex`
- optional `isStreaming`

Likely files:

- `packages/shared`
- `apps/server/src/codex/cli.ts`
- Android `SessionMessage`

This should happen only if frontend-only projection still leaves ambiguity.

## Recommended File Scope

### Android First Pass

- [SessionDetailHelpers.kt](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailHelpers.kt)
- [ConversationTimeline.kt](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/ConversationTimeline.kt)
- [MessageBubbles.kt](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/MessageBubbles.kt)
- [SessionDetailViewModel.kt](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailViewModel.kt)
- [strings_session_timeline.xml](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/res/values/strings_session_timeline.xml)
- [strings_session_timeline.xml](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/res/values-en/strings_session_timeline.xml)

### Secondary Follow-Up

- [HistoryRoundItem.kt](/Users/fainal/Documents/GitHub/CodexRemote/apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/HistoryRoundItem.kt)

### Possible Backend Follow-Up

- `apps/server/src/codex/cli.ts`
- shared message schemas in `packages/shared`

## Execution Order

Recommended sequence:

1. define `CurrentTurnProjection`
2. change `ConversationTimeline` to use it
3. add `上 N 条消息` collapsed header interaction
4. reduce `splitOutputSections(...)` to a fallback role only
5. calm down typewriter / thinking behavior
6. only then evaluate backend identity work

## Success Criteria

We can call this refactor successful when all of these are true:

- multiple assistant replies in one current turn render as multiple cards
- the first reply no longer flashes and disappears
- earlier replies remain available after completion
- earlier replies collapse behind `上 N 条消息`
- the final reply stays expanded
- the UI no longer depends on phase labels like `阶段 1`
- the active tail no longer visually overwrites previously-settled assistant cards

## What The Next Coding Pass Should Contain

The core execution pass is complete.

The remaining optional follow-up is:

- future message-level `isStreaming` modeling if we ever need richer live-item semantics than the current live-tail approach
