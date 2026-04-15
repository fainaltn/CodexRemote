# Frontend Experience Plan

## Goal

Upgrade CodexRemote from a capable engineering control surface into a more polished product experience across web and Android.

The reference for interaction quality is `remodex` as inspected on April 15, 2026, but not its transport architecture or iOS-specific product model.

CodexRemote will keep the current product foundation:

- `apps/server` remains the source of truth
- `apps/web` remains a Next.js browser control surface
- `apps/android` remains a native Android client backed by REST + SSE
- no relay, QR pairing, end-to-end encrypted bridge, or iOS clone work is part of this roadmap

## Why This Plan Exists

CodexRemote already covers important workflows:

- session browsing and creation
- live run streaming
- inbox intake
- attachment upload
- Android-native session control

The biggest gap versus `remodex` is not feature count. The gap is product feel:

- weaker first impression
- less deliberate visual hierarchy
- run states feel more functional than alive
- navigation feels data-driven rather than product-designed
- empty, loading, and error states are serviceable but not memorable

This plan turns those observations into a strict execution sequence.

## Product Direction

We want CodexRemote to feel like a calm, high-signal remote Codex cockpit:

- the first screen should establish trust and capability
- session navigation should feel curated, not raw
- active runs should feel alive, legible, and controllable
- the composer should feel like the command center
- visuals should be cohesive across web and Android without forcing exact parity

## Brand Direction

CodexRemote will follow a brand direction called `Precision Console`.

This direction should guide future frontend work unless we explicitly revise the plan.

### Precision Console

Desired feel:

- precise
- calm
- technical
- premium
- trustworthy

Visual characteristics:

- graphite and mist surfaces instead of flat neutral gray
- electric blue as the primary signal color
- restrained cyan lift for atmosphere, not for primary actions
- stronger headline weight and tighter title spacing
- clearer panel elevation with softer but deeper shadows
- borders that guide structure without outlining every surface aggressively
- terminal-adjacent status cues that still feel product-designed

What this is not:

- neon cyberpunk
- generic SaaS blue-on-white
- over-decorated glassmorphism
- dark-mode-only branding

Implementation implications:

- strengthen type hierarchy before adding ornament
- use atmospheric backgrounds sparingly and keep core content crisp
- make active states feel energetic while keeping idle states quiet
- reserve motion for meaningful state change, not constant decoration

## Core Principles

### 1. Product Before Decoration

Visual changes must improve clarity, state awareness, confidence, or task flow.

Do not add styling that makes the interface prettier but less scannable.

### 2. States Are First-Class UI

Every important state must feel intentionally designed:

- empty
- loading
- connecting
- streaming
- queued
- success
- degraded
- failed

### 3. Hierarchy Beats Density

The app can stay information-dense, but users must immediately distinguish:

- project navigation
- current session
- historical context
- active run output
- system/process/tool state
- composer controls

### 4. Shared Language, Native Delivery

Web and Android should share the same UX direction:

- same product story
- same importance hierarchy
- same status semantics

But implementation should respect platform strengths instead of forcing pixel-identical UI.

### 5. No Architecture Drift

This roadmap is for frontend experience improvement.

Do not pull backend or transport redesign into scope unless a UX task is truly blocked by a missing API.

## Non-Goals

- cloning `remodex` screen-for-screen
- rebuilding transport architecture around phone pairing or relay concepts
- rebranding the project around an iPhone-first narrative
- shipping large backend refactors before visible UX gains land
- adding motion-heavy or decorative effects that harm responsiveness

## Experience Gaps To Close

### Gap 1: Weak First Impression

Current login and landing flows explain access, but they do not sell the product as a serious remote Codex workspace.

What we want instead:

- a stronger product hero
- clearer setup narrative
- clearer host/workspace framing
- polished loading and auth transitions

### Gap 2: Session Navigation Feels Operational, Not Designed

Project grouping works, but the UI still reads like a useful tree rather than a premium navigation surface.

What we want instead:

- stronger active-state treatment
- clearer group hierarchy
- richer session status cues
- better visual handling for drafts, recency, and unread/live signals

### Gap 3: Live Runs Lack Presence

The app exposes run data, but active execution does not yet feel distinct enough from static history.

What we want instead:

- stronger active-run identity
- better streaming feedback
- clearer terminal outcomes
- better transition from running to final result

### Gap 4: Timeline Hierarchy Can Be Sharper

Messages, reasoning, tool output, and system states are all present, but the visual grammar is still too flat.

What we want instead:

- dedicated treatment for user vs assistant vs process vs system
- stronger fold/unfold affordances
- compact summaries for long histories
- purpose-built cards for failures, retries, and run recap

### Gap 5: Composer Is Not Yet the Center of Gravity

The current composer works, but it still behaves more like a utility bar than a command surface.

What we want instead:

- stronger visual prominence
- better attachment handling
- clearer runtime controls
- more confidence while queueing or editing follow-up prompts

### Gap 6: Visual System Is Stable but Too Conservative

Current colors and surfaces are solid but safe. The UI needs more memorable contrast, rhythm, and polish.

What we want instead:

- more deliberate surface layering
- tighter radius/shadow/border rules
- more distinctive title and label hierarchy
- better empty, loading, and feedback components

## Phase Breakdown

### Phase 1: Foundations And Design Language

Scope:

- define the visual direction for both web and Android
- tighten tokens for color, surface, border, radius, shadow, spacing, and status semantics
- define shared treatment rules for empty, loading, success, warning, and error states

Primary files:

- `apps/web/src/app/globals.css`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/theme/Color.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/theme/Theme.kt`
- related shared UI components that represent app-wide states

Acceptance criteria:

- both web and Android expose a clearer surface hierarchy
- status colors and semantic UI states are consistent across surfaces
- new components do not introduce style one-offs that fight the token system

Notes:

- do this before major page rewrites so later screens inherit the new language

### Phase 2: First-Run And Login Experience

Scope:

- redesign the web login experience
- redesign the Android login experience
- add a clearer “what this app does” framing
- improve loading transitions around auth and app entry

Primary files:

- `apps/web/src/app/login/page.tsx`
- `apps/web/src/app/page.tsx`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/login/LoginScreen.kt`
- splash or entry-state components if needed

Acceptance criteria:

- the first screen communicates capability, not just password entry
- login feels intentional on desktop and mobile
- loading and auth transitions no longer feel like placeholder utility screens

Notes:

- keep the flow short
- avoid building a long onboarding wizard unless it clearly improves setup speed

### Phase 3: Session Navigation Upgrade

Scope:

- improve project/session navigation on web
- improve session list presentation on Android
- strengthen active, running, draft, and archived states
- reduce the feeling of a raw grouped list

Primary files:

- `apps/web/src/app/app-shell-client.tsx`
- `apps/web/src/app/sessions/page.tsx`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionListScreen.kt`

Acceptance criteria:

- current project and current session are obvious at a glance
- running or recently updated sessions are visually legible without opening them
- grouped navigation feels curated rather than purely data-rendered

Notes:

- project structure should stay scannable under large datasets
- no destructive actions should become easier to mis-tap

### Phase 4: Session Detail And Run-State Redesign

Scope:

- upgrade the session detail reading experience on web
- continue refining Android’s session detail into a more polished console
- make streaming, thinking, terminal states, and errors visually distinct
- improve the relationship between history and the current run

Primary files:

- `apps/web/src/app/sessions/[sessionId]/page.tsx`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/SessionDetailScreen.kt`
- `apps/android/app/src/main/java/dev/codexremote/android/ui/sessions/MessageBubbles.kt`
- related timeline and helper components

Acceptance criteria:

- users can instantly tell whether Codex is idle, running, queued, degraded, or done
- historical rounds and current output feel related but clearly separated
- terminal outcomes such as stopped, failed, or empty-output states have dedicated UI treatment

Notes:

- do not overload the screen with too many always-visible controls
- reveal advanced controls progressively

### Phase 5: Composer As Command Center

Scope:

- redesign the composer on web and Android
- improve attachment affordances
- improve queued prompt visibility
- expose runtime controls with stronger UX affordances
- support more confident follow-up prompting while a run is active

Primary files:

- `apps/web/src/app/sessions/[sessionId]/page.tsx`
- Android composer-related files under `ui/sessions/`

Acceptance criteria:

- the composer is visually prominent and easy to trust
- attachments, queued prompts, and runtime knobs are understandable without digging
- active-run follow-up behavior feels deliberate rather than bolted on

Notes:

- the composer must remain fast and usable on narrow screens

### Phase 6: Empty States, Feedback, And Polish Pass

Scope:

- unify empty states across web and Android
- unify success/error/progress feedback components
- polish micro-interactions, motion, and transitions
- remove any remaining placeholder-feeling text or raw utility affordances

Primary files:

- inbox screens
- session empty states
- upload feedback components
- loading and error components across both clients

Acceptance criteria:

- common feedback patterns feel consistent across the app
- no key screen falls back to a bare spinner plus one line of text without intent
- the app feels shipped, not merely assembled

## Delivery Order

We will execute this roadmap in the following order:

1. Phase 1: Foundations And Design Language
2. Phase 2: First-Run And Login Experience
3. Phase 3: Session Navigation Upgrade
4. Phase 4: Session Detail And Run-State Redesign
5. Phase 5: Composer As Command Center
6. Phase 6: Empty States, Feedback, And Polish Pass

This order is strict unless a later phase exposes a blocker that must be solved earlier.

## Execution Rules

These rules are part of the plan and should be followed in future implementation work.

### Rule 1: Finish One Experience Layer Before Jumping Ahead

Do not partially touch all screens in parallel.

For each phase:

- land the token and component changes it needs
- implement the primary screens in scope
- verify behavior
- only then move to the next phase

### Rule 2: Prefer Reusable Primitives Over Screen-Specific Hacks

If a pattern appears more than once, extract a reusable component or style primitive.

Examples:

- status chips
- empty-state shells
- section headers
- timeline notices
- composer chips

### Rule 3: Preserve Existing Capability

Beauty work must not regress:

- auth
- session creation
- run streaming
- attachment upload
- inbox workflows
- archive and rename flows

### Rule 4: Measure UX Work By State Clarity

A change is successful only if the user can more quickly answer:

- where am I?
- what is active?
- what just happened?
- what can I do next?

### Rule 5: Avoid “AI Slop” Styling

Do not use generic gradient-heavy redesigns that erase the product’s control-surface identity.

The target is:

- crisp
- calm
- technical
- polished

Not:

- flashy
- toy-like
- over-animated
- marketing-page-driven

## Validation Checklist

Each phase should be checked against this list before being considered complete:

- visual hierarchy is stronger than before
- the relevant active states are easier to parse
- the changed surfaces still work on narrow screens
- the changed surfaces still work on wide screens
- loading, empty, and error paths were reviewed, not just the happy path
- no core workflow regressed

## Progress Status

Completed:

- none yet in this plan

Current phase:

- Phase 1: Foundations And Design Language

Next up after the current phase:

- Phase 2: First-Run And Login Experience

## Working Reference

This plan was informed by a direct comparison against the public `remodex` repository as inspected on April 15, 2026, especially these areas:

- onboarding and first-run framing
- sidebar/thread navigation
- streaming and thinking-state presentation
- timeline componentization
- active-run visual feedback

CodexRemote should borrow the level of polish and state design discipline, while keeping its own backend model and product identity.
