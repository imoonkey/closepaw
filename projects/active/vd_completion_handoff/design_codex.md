# Virtual Display Completion Handoff

> **Update (post-implementation)**: the `View virtual screen` CTA described
> below was implemented, then removed before merge — post-completion the
> viewer had no working takeover, no edge glow, and no exit affordance, so
> the button landed users on a dead surface (see
> `view_vd_tap_bug.md`, `codex_general_review.md` HIGH #1, and commit
> `2dfc19bd`). Only the `Open <App>` CTA ships. The `virtualDisplayAvailable`
> field on `CompletionHandoff` is still captured so the viewer CTA can be
> re-introduced once the broader `vd-runtime-boundary` work keeps the VD
> session alive past completion and restores the in-viewer controls.

## Goal

When a Virtual Display task completes, keep the user in ClosePaw chat by default and expose an explicit handoff action:

```text
[Open <App>]
```

The current behavior where `New Session` can open the last VD app is a bug. New session must only start a new ClosePaw conversation.

## Approach

Use runtime facts, not intent classification.

At `TaskCompleted`, if the effective platform is `VIRTUAL_DISPLAY`, capture:

```kotlin
data class CompletionHandoff(
    val appPackage: String?,
    val appLabel: String?,
    val virtualDisplayAvailable: Boolean, // captured but not currently rendered
)
```

Chat renders the button only when it is valid:

- `Open <App>`: show only when `appPackage` exists, is not ClosePaw/SystemUI/blocked, and `PackageManager.getLaunchIntentForPackage(appPackage)` returns a launcher intent.

No automatic app launch. No screenshot fallback. No promise to restore the exact VD page; opening the real app means launching the same package on the real display.

## Components

- `TaskCompleted`: carries optional `CompletionHandoff` for the completed task.
- `AgentSession`: builds handoff metadata when emitting `TaskCompleted`.
  - `appPackage`: `services.platform.getCurrentPackageName()` in VD mode (filtered through `AppClassifier` to drop BLOCKED-tier packages and self/SystemUI).
  - `appLabel`: resolved from `PackageManager` with `NameNotFoundException` → null.
  - `virtualDisplayAvailable`: true only while the current VD platform can still open the viewer. Captured for future use; not currently rendered.
- `ChatMessage.Agent`: stores optional completion handoff metadata.
- `ChatEventReducer`: copies `TaskCompleted.handoff` into the completed agent row.
- `AgentRow` / final region: renders the `Open <App>` CTA under the final answer when `RowState.Complete` and `handoff != null`.
- `MainActivity` / `ChatScreen`: handles the CTA callback by launching the package's real-display launcher intent.

## Interaction

State machine:

```text
VD Running
  -- TaskCompleted --> CompletedInChat(handoff?)

CompletedInChat
  -- Open <App> CTA, valid launcher intent --> RealAppOpened
  -- New Session --> NewChat
```

Guards:

- `New Session` never reads or executes `CompletionHandoff`.
- Invalid app packages produce no app CTA.

Side effects:

- `Open <App>` starts the real-display launcher intent.
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
   - Acceptance: valid completed VD rows show `[Open <App>]`; tap invokes the explicit action.
   - Dependencies: `vd-handoff-capture`.

4. `new-session-no-handoff-regression`
   - Scope: chat/header tests and any affected QA helpers.
   - Acceptance: tapping `New Session` never opens the last app.
   - Dependencies: `vd-handoff-render`.

## Trade-offs

- No intent classification: simpler and more honest. The user chooses whether to open the app or stay in chat.
- No final screenshot fallback: avoids asset lifecycle work until the basic handoff proves useful.
- Runtime-only handoff: restored old chat history does not need to recreate stale app buttons.
- Explicit CTA beats automatic handoff: prevents VD workspace state from leaking to the real screen as a lifecycle side effect.
- Viewer CTA deferred: keeping a useful post-completion viewer requires the VD session to outlive task completion plus a real exit/takeover surface on the viewer; that work is `vd-runtime-boundary`.

## Self-Review

This design solves the product question with the smallest state surface: completion stays in chat, the app handoff is explicit, and `New Session` has a clean invariant. The only new state is per-completion metadata. No classifier, page migration, screenshot storage, or VD service refactor is required.
