# Virtual Display Completion Handoff

## Goal

When a Virtual Display task completes, keep the user in ClosePaw chat by default and expose explicit handoff actions:

```text
[Open <App>] [View virtual screen]
```

The current behavior where `New Session` can open the last VD app is a bug. New session must only start a new ClosePaw conversation.

## Approach

Use runtime facts, not intent classification.

At `TaskCompleted`, if the effective platform is `VIRTUAL_DISPLAY`, capture:

```kotlin
data class CompletionHandoff(
    val appPackage: String?,
    val appLabel: String?,
    val virtualDisplayAvailable: Boolean,
)
```

Chat renders buttons only when they are valid:

- `Open <App>`: show only when `appPackage` exists, is not ClosePaw/SystemUI/blocked, and `PackageManager.getLaunchIntentForPackage(appPackage)` returns a launcher intent.
- `View virtual screen`: show only when the VD is still alive.

No automatic app launch. No screenshot fallback. No promise to restore the exact VD page; opening the real app means launching the same package on the real display.

## Components

- `TaskCompleted`: carry optional `CompletionHandoff` for the completed task.
- `AgentSession`: builds handoff metadata when emitting `TaskCompleted`.
  - `appPackage`: `services.platform.getCurrentPackageName()` in VD mode.
  - `appLabel`: resolved from `PackageManager`.
  - `virtualDisplayAvailable`: true only while the current VD platform can still open the viewer.
- `ChatMessage.Agent`: stores optional completion handoff metadata.
- `ChatEventReducer`: copies `TaskCompleted.handoff` into the completed agent row.
- `AgentRow` / final region: renders the two CTA buttons under the final answer.
- `MainActivity` / `ChatScreen`: handle CTA callbacks:
  - open real app with launcher intent on display 0;
  - open existing `VirtualDisplayViewerActivity`.

## Interaction

State machine:

```text
VD Running
  -- TaskCompleted --> CompletedInChat(handoff?)

CompletedInChat
  -- Open <App> CTA, valid launcher intent --> RealAppOpened
  -- View virtual screen CTA, VD alive --> VDViewerOpened
  -- New Session --> NewChat
```

Guards:

- `New Session` never reads or executes `CompletionHandoff`.
- Invalid app packages produce no app CTA.
- Dead VD produces no viewer CTA.

Side effects:

- `Open <App>` starts the real-display launcher intent.
- `View virtual screen` uses the existing viewer path.
- `TaskCompleted` itself does not launch anything.

## Tasks

1. `vd-handoff-event-model`
   - Scope: `app/src/main/kotlin/ai/closepaw/protocol/*`, `app/src/main/kotlin/ai/closepaw/ui/chat/model/*`
   - Acceptance: completed agent rows can hold optional handoff metadata.
   - Dependencies: none.

2. `vd-handoff-capture`
   - Scope: `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt`, `app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/*`
   - Acceptance: VD task completion emits package/label/viewer availability; accessibility mode emits no handoff.
   - Dependencies: `vd-handoff-event-model`.

3. `vd-handoff-render`
   - Scope: `app/src/main/kotlin/ai/closepaw/ui/chat/*`, `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt`
   - Acceptance: valid completed VD rows show `[Open <App>]` and/or `[View virtual screen]`; taps invoke the explicit action.
   - Dependencies: `vd-handoff-capture`.

4. `new-session-no-handoff-regression`
   - Scope: chat/header tests and any affected QA helpers.
   - Acceptance: tapping `New Session` never opens the last app or VD viewer.
   - Dependencies: `vd-handoff-render`.

## Trade-offs

- No intent classification: simpler and more honest. The user chooses whether to inspect the app or stay in chat.
- No final screenshot fallback: avoids asset lifecycle work until the basic handoff proves useful.
- Runtime-only handoff: restored old chat history does not need to recreate stale app/viewer buttons.
- Explicit CTA beats automatic handoff: prevents VD workspace state from leaking to the real screen as a lifecycle side effect.

## Self-Review

This design solves the product question with the smallest state surface: completion stays in chat, app/viewer handoff is explicit, and `New Session` has a clean invariant. The only new state is per-completion metadata. No classifier, page migration, screenshot storage, or VD service refactor is required.
