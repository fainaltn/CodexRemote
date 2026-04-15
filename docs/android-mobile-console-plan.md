# Android Mobile Console Plan

## Goal

Upgrade the Android app from a readable remote chat client into a stronger mobile control surface for Codex runs.

The reference for interaction quality is `remodex`, but not its transport architecture. CodexRemote will keep the current backend-centered model:

- Fastify API remains the source of truth
- Android stays a client of REST + SSE
- No QR pairing, relay, or secure bridge work is part of this roadmap

## Product Direction

We want the Android session screen to feel like a compact control console:

- clear run state at a glance
- strong composer controls while a run is active
- explicit recovery when live streaming degrades
- better handling for follow-up prompts and long sessions
- gradual exposure of repo- and run-level controls without turning the screen into an admin panel

## Non-Goals

- Replacing the current server-centric architecture
- Building a relay, trusted reconnect, or encrypted phone-to-host bridge
- Recreating iOS-only features from Remodex
- Shipping a large backend refactor before UX gains are visible

## Phase Breakdown

### Phase 1: Console Basics

Scope:

- Add a visible session control strip near the top of the detail screen
- Upgrade the composer so it can show run context, stream state, and queue state
- Allow entering the next prompt while the current run is still active
- Add a local queued-prompt flow so the next message can auto-send when the current run finishes

Android changes:

- `SessionDetailScreen`
- `SessionDetailViewModel`
- `ComposerBar`
- new shared run-status strip composable

Backend changes:

- none

Acceptance criteria:

- A running session clearly shows current run state, transport state, and queued count
- While a run is active, the user can write a follow-up prompt instead of waiting
- Queued follow-up prompts auto-send after the run reaches a terminal state
- Existing draft-session creation and attachment flows keep working

### Phase 2: Timeline Readability

Scope:

- Improve visual separation between live output, final answer, reasoning, and historical rounds
- Add more explicit cards for timeout, stop, failure, and degraded live-stream states
- Add concise round summaries and better affordances for long conversations

Android changes:

- `ConversationTimeline`
- `HistoryRoundItem`
- `MessageBubbles`
- related helpers

Backend changes:

- none required for the first pass

Acceptance criteria:

- Long sessions are easier to scan on mobile
- Users can distinguish “still running” from “final answer already landed”
- Error and degraded-stream states are visible without opening diagnostics

### Phase 3: Composer Controls

Scope:

- Add explicit runtime controls to the composer surface
- Expose model and reasoning controls as user-facing inputs instead of display-only metadata
- Add lightweight command affordances for common actions

Android changes:

- `ComposerBar`
- `SessionDetailViewModel`
- network request payload plumbing

Backend changes:

- likely none, because the server already accepts `model` and `reasoningEffort` for live runs

Acceptance criteria:

- The user can choose runtime knobs from Android before sending a turn
- The composer feels closer to an IDE-side control surface than a plain chat box

### Phase 4: Repo-Aware Controls

Scope:

- Add Git and workspace affordances that fit the current CodexRemote backend model
- Start with read-first controls: branch, repo state, diff summary
- Only then consider write actions like commit or push

Android changes:

- session-detail header or secondary control bar
- new repo status components

Backend changes:

- likely required for branch status and git actions APIs

Acceptance criteria:

- Users can understand repository context from the phone
- New git controls do not create hidden destructive paths

## Delivery Strategy

Ship this in small slices:

1. Land Phase 1 first
2. Validate the hand-feel on a real phone
3. Use that feedback to decide how much of Phases 2 and 3 should be merged or reordered

## Progress Status

Completed:

- Phase 1: Console Basics
- Phase 2: Timeline Readability
- Phase 3: Composer Controls
- Phase 4: Repo-Aware Controls

Phase 4 completed slices so far:

- read-only repo status route on the backend
- Android repo status surface with branch/root/dirty-state visibility
- richer read-only diff summary indicators for staged, unstaged, untracked, and ahead/behind counts
- repo write actions for branch creation, branch checkout, commit, and push
- Android session-detail controls for the new repo actions

Remaining planned work:

- none in the current plan
