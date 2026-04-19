# Android Current-Turn Refactor Plan

## Status

Completed in code as of 2026-04-15.

Implemented outcomes:

- current turn now projects settled assistant messages, collapsed assistant messages, and a live tail separately
- history grouping and current-turn grouping both prefer `turnId` and `orderIndex` when available
- earlier assistant replies remain accessible behind a tappable summary header
- settled assistant cards no longer depend on section-splitting heuristics
- `splitOutputSections(...)` has been removed from Android code

The remaining future-looking item is a true live-item `isStreaming` model if we ever need more than the current live-tail approach.

## Goal

Refactor the Android session-detail experience so the current turn behaves like a real message timeline:

- every distinct assistant reply in the current turn renders as its own card
- intermediate replies remain visible after the turn completes
- intermediate replies collapse behind a tappable summary header instead of disappearing
- the final reply stays expanded
- streaming feels calm and intentional instead of racing through one mutable output region
- the UI no longer relies on brittle `lastOutput` section splitting to infer message boundaries

This plan is focused on Android session detail only.

## Why This Plan Exists

Recent work improved several parts of the Android detail screen:

- recoverable disconnects feel calmer
- first-use/product framing is stronger
- runtime controls are more visible
- the empty gap between the first and second visible reply phases was reduced

But the current turn still has structural UX problems:

- the first reply card can flash and then disappear
- multiple assistant replies still often collapse into one mutable reply region
- intermediate replies get hidden too aggressively after completion
- collapsed intermediate replies are not modeled as a proper interactive group
- labels like `阶段 1` / `阶段 2` feel implementation-driven rather than product-driven
- the typewriter effect feels rushed and is often interrupted by later turn state

These are not isolated styling bugs. They are consequences of the current turn data model.

## Current Android Architecture

The current Android detail screen effectively merges two different sources of truth:

1. `session.messages`
2. `liveRun.lastOutput`

The current turn is then inferred through a chain like:

- `latestCanonicalPrompt(...)`
- `latestCanonicalAssistantReply(...)`
- `currentTurnPromptOverride`
- `retainedLiveOutput`
- `cleanLiveOutput(...)`
- `splitOutputSections(...)`

This works well enough for:

- one active assistant region
- one settled final reply
- simple active vs terminal transitions

That approach broke down for:

- multiple assistant replies in one turn
- stable collapsed intermediate-reply groups
- smooth message-first current-turn rendering

## Root Cause Analysis

### 1. The Current Turn Is Still Output-First

The current turn rendering path is still primarily driven by one evolving text payload:

- `liveRun.lastOutput`
- `retainedLiveOutput`
- `replyOutput`

That means frontend code tries to guess:

- how many assistant messages exist
- whether one visible text block is actually multiple replies
- when a previous phase should remain visible
- when a previous phase should disappear

This is the core reason the current turn still behaves like one mutable card.

### 2. `session.messages` And `liveRun.lastOutput` Arrive On Different Clocks

The current turn mixes:

- canonical session history from `getSessionDetail`
- live output from `getLiveRun` / SSE

These update on different cadences.

So the UI often sees one of these states:

- `lastOutput` updated, but `session.messages` not updated yet
- `session.messages` updated, but the frontend still clears or replaces the retained live region
- final settled assistant reply landed, but the current-turn projection still treats it as one mutable assistant area

This creates:

- flash-and-swallow behavior
- previous phase being replaced instead of preserved
- inconsistent collapse behavior after completion

### 3. The Current Turn Does Not Have Its Own Projection Model

Android already has a projection model for historical rounds:

- `buildHistoryRounds(...)`
- `HistoryRound`
- `HistoryRoundItem`

But the current turn does not have an equivalent dedicated projection layer.

Instead, it is pieced together inline in `ConversationTimeline` from mixed state:

- current user text
- latest settled reply
- retained live output
- cleaned live output
- activity state

Without a first-class current-turn projection, the view layer is forced to guess structure from partial data.

### 4. `buildHistoryRounds(...)` Already Showed The Right Direction

The existing history-round logic already contains a useful pattern:

- it groups multiple assistant replies
- it separates `primaryMessages` from `foldedMessages`

But today that logic is only used for historical rounds, not for the active/current turn.

So the app already has part of the solution, just not in the right place.

### 5. The Typewriter Effect Is Doing The Wrong Job

The current typewriter logic is being used to make a large changing output feel live.

But when the UI model itself is unstable, typewriter animation becomes a liability:

- it reveals too much text before the turn structure settles
- it gets interrupted by later state updates
- it amplifies the feeling that text is being rewritten rather than new replies arriving

## What `remodex` Does Better

The most relevant files examined were:

- `CodexMobile/CodexMobile/Views/Turn/TurnTimelineReducer.swift`
- `CodexMobile/CodexMobile/Views/Turn/TurnTimelineView.swift`
- `CodexMobile/CodexMobile/Views/Turn/TurnMessageComponents.swift`
- `CodexMobile/CodexMobileTests/TurnTimelineReducerTests.swift`

The key ideas worth copying are architectural, not cosmetic.

### 1. Reducer First, Views Second

`remodex` does not let the view layer guess message structure directly from raw streaming text.

It first projects raw service messages into a render-ready message list through a timeline reducer.

That reducer handles:

- hidden-system markers
- intra-turn ordering
- collapsing repeated thinking rows
- deduping assistant replies
- grouping repeated system activity into burst items

This is the biggest missing piece in our Android flow.

### 2. Thinking Is Treated As Its Own UI State

`remodex` explicitly separates:

- assistant message text
- thinking state
- tool activity
- grouped burst state

It even normalizes placeholder streaming text like `Thinking...` before rendering assistant content.

That means “still thinking” is represented as a stateful UI row, not as final assistant prose.

### 3. Collapse Is A Render Projection Concern

In `remodex`, collapse headers are not produced by string splitting.

Instead, the render projection produces grouped items that own:

- hidden-count labels
- expansion state
- pinned vs overflow rows

This is why “+N tool calls” style headers stay stable.

### 4. Stable Identity Matters

`remodex` relies on message identity like:

- `turnId`
- `itemId`
- `orderIndex`

That makes it much easier to decide:

- when two assistant replies are distinct
- when a placeholder should be replaced
- when two rows are duplicates

Our Android message model does not currently expose enough of that identity.

## Current Conclusion

We stopped optimizing the current Android turn purely through:

- `splitOutputSections(...)`
- `retainedLiveOutput`
- more conditional swapping inside `ConversationTimeline`

That path can keep improving symptoms, but it will not reliably produce desktop-like multi-message current-turn behavior.

The refactor path was to move to a message-first current-turn projection model, and that path is now implemented.

## Target UX Model

### While The Turn Is Running

If the assistant has already emitted multiple settled replies:

- each settled reply renders as its own card
- the currently-streaming assistant tail renders as its own active card
- thinking state is represented as a lightweight live indicator attached to the active tail

If the assistant has not emitted settled reply text yet:

- show a lightweight thinking/activity state
- do not pretend there is already a full assistant prose card

### After The Turn Completes

If there were multiple assistant replies:

- the final reply stays expanded
- earlier replies collapse behind a tappable summary header
- tapping reveals all earlier replies inline

If there was only one assistant reply:

- just show the final reply card

### Collapse Language

Replace implementation language like:

- `阶段 1`
- `阶段 2`

With product-facing language like:

- `上 4 条消息`
- `已处理 4m 12s`

The primary collapse header should be message-count based first.

## Proposed Refactor

## Phase 1: Current-Turn Projection Model

Create a dedicated projection for the current turn.

Suggested new model:

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

Suggested home:

- `SessionDetailHelpers.kt`

Projection should be derived from:

- `session.messages`
- `liveRun`
- `currentTurnPromptOverride`
- `retainedLiveOutput`

But it should decide one thing clearly:

- which pieces are real settled assistant messages
- which piece is still the live tail

Acceptance criteria:

- `ConversationTimeline` no longer manually infers current-turn structure inline
- the projection layer decides whether intermediate replies are visible, collapsed, or still streaming

## Phase 2: Message-First Current Turn Rendering

Replace the current assistant rendering branch in `ConversationTimeline` with render paths driven by `CurrentTurnProjection`.

Suggested components:

- `CurrentTurnSettledAssistantCard`
- `CurrentTurnStreamingTailCard`
- `CurrentTurnCollapsedHeader`
- `CurrentTurnExpandedIntermediateReplies`
- `CurrentTurnThinkingState`

Key rule:

- `SessionMessage` cards should be primary
- `liveRun.lastOutput` should only backfill the current streaming tail if no settled assistant message exists for that part yet

Acceptance criteria:

- multiple assistant replies in one turn render as multiple cards when they exist in `session.messages`
- the final assistant reply is not forced into the same visual card as previous replies

## Phase 3: Collapsed Header Interaction

Introduce a dedicated collapsed current-turn header, separate from historical rounds.

Suggested first version:

- label: `上 %d 条消息`
- secondary metadata: optional duration like `已处理 4m 12s`
- chevron affordance
- tap to expand/collapse

Unlike historical rounds, this collapse header should appear above the final reply within the current turn area.

Acceptance criteria:

- intermediate replies remain visible as collapsed summaries after terminal settle
- collapsed content is interactive, not passive preview text
- the wording matches desktop mental model better than `阶段 N`

## Phase 4: Streaming And Thinking Behavior

Tune the streaming experience after the projection layer is in place.

Recommended behavior:

- settled assistant cards should never use typewriter animation
- only the current live tail should animate
- the live tail should use a shorter, calmer progressive reveal
- once enough visible text exists, the UI should lean on a thinking/activity indicator instead of racing to animate every remaining character

This will reduce the “too fast and then cut off” feeling.

Acceptance criteria:

- streaming feels calmer
- later message arrival does not visually erase a previous settled card
- the active tail transitions into a settled assistant card without flash

## Phase 5: Backend Identity Upgrade If Still Needed

If frontend-only projection still cannot reliably distinguish multiple assistant replies from duplicate snapshots, add richer message identity to the mobile-visible schema.

Potential additions:

- `turnId`
- `itemId`
- `orderIndex`
- `isStreaming`

Likely files:

- `packages/shared`
- `apps/server/src/codex/cli.ts`
- Android `SessionMessage` data model

This phase should happen only if Phase 1-4 still leave ambiguity.

## Recommended File Scope

### Primary Android Files

- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionDetailViewModel.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/SessionDetailHelpers.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/ConversationTimeline.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/MessageBubbles.kt`
- `apps/android/app/src/main/java/app/findeck/mobile/ui/sessions/HistoryRoundItem.kt`
- `apps/android/app/src/main/res/values/strings_session_timeline.xml`
- `apps/android/app/src/main/res/values-en/strings_session_timeline.xml`

### Possible Backend Follow-Up

- `apps/server/src/codex/cli.ts`
- `packages/shared`

## Execution Order

Recommended sequence for the next code pass:

1. introduce `CurrentTurnProjection`
2. refactor `ConversationTimeline` to consume the projection
3. build collapsible current-turn header with `上 N 条消息`
4. render current-turn settled assistant messages as separate cards
5. isolate live tail into a separate active card
6. tune typewriter/thinking behavior only after the structure is stable
7. only then decide if backend identity work is required

## Success Criteria

We can call the refactor successful when all of these are true:

- multiple assistant replies in one current turn produce multiple cards
- the first reply no longer flashes and disappears
- the UI no longer depends on phase labels like `阶段 1`
- intermediate replies remain available after terminal settle via a collapsed header
- the final reply stays expanded
- the active tail does not erase previously-settled reply cards
- streaming feels deliberate instead of rushed

## Next Coding Pass Scope

The next implementation pass should cover only:

- Phase 1
- Phase 2
- Phase 3

Do not expand into backend schema work in the same pass unless frontend projection is demonstrably blocked.
