# Round4 Smart Capsule Code Review (KISS + Occam)

## Scope
- Diff reviewed: `339448dd127c6de7a6612f918be8a7d9351ff7b1..HEAD` (only `app/`)
- Focus: state machine correctness, edge transitions, cross-component rendering contract
- Local verification run:
  - `./gradlew :app:testDebugUnitTest --tests "com.moonkey.androidagent.ui.overlay.CapsuleStateHolderTest" --tests "com.moonkey.androidagent.ui.overlay.model.CapsuleModeTest" --tests "com.moonkey.androidagent.session.UserResponseChannelTest"`

## Critical Findings
1. **UI state transitions are optimistic, not acknowledged by backend; state can desync and get stuck.**
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:57`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:63`
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:146`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:221`
- Problem:
  - Click callbacks mutate `CapsuleStateHolder` first, then async submit `Op`.
  - If session is null, op rejected, or `callId` mismatch, UI has already transitioned.
  - `onUserResponseSent(callId)` does not validate `callId` against pending request.
- User-facing impact:
  - “按钮点了没反应” and waiting states becoming inconsistent with real agent state.
- KISS fix:
  - Move `onTakeoverRequested/onUserResponseSent` transition trigger to **ack events** from session (`UserResponseAccepted`, `TakeoverAccepted`).
  - Keep click path intent-only.

2. **Navigation contract is broken in MAIN_APP for VD mode (buttons visible but no behavior).**
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleRenderSpec.kt:159`
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:131`
- Problem:
  - `NavSpec.from()` can show `⊖` and `👁` in MAIN_APP (VD mode).
  - `ChatScreen` wires `onNavigate` as no-op.
- User-facing impact:
  - “VD 模式点 eye 没反应”, “一些按钮点了没反应”.
- KISS fix:
  - Either hide nav actions in MAIN_APP, or wire real handlers (open viewer/minimize) end-to-end.

## High Findings
1. **VD context initialization is inconsistent; context defaults to MAIN_APP and is not corrected on task start.**
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:86`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:195`
- Problem:
  - Context starts as `MAIN_APP`; VD task start path does not set to `BACKGROUND`.
  - Nav visibility and window policy can use wrong context until viewer/island events happen.

2. **State propagation model is mixed: capsule is reactive, island is push-only.**
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:386`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:68`
- Problem:
  - Internal state transitions (auto-hide, dismiss error) are not uniformly propagated to island unless controller happens to call `renderIsland()`.
- User-facing impact:
  - “status island 一点没了” or stale island text/state after edge transitions.
- KISS fix:
  - Let `StatusIslandManager` observe `stateHolder.mode + derivedGlowState` directly (same model as `SmartCapsuleManager`).

3. **Session completion ownership is split between UI and service; history finalization path is not single-source.**
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:117`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:313`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:353`
- Problem:
  - MainActivity forces `completeSession + Op.Shutdown` on `TaskCompleted`.
  - Service handles session lifecycle independently and does not centralize final recording completion in `SessionCompleted` path.
- User-facing impact:
  - “complete_task 没显示在 history” type inconsistency risk across entrypoints.
- KISS fix:
  - One owner: finalize recording/session only in service lifecycle terminal event.

## Medium Findings
1. **`hasActiveTask` includes terminal states (`Done`, `Error`), causing ambiguous visibility decisions.**
- Where:
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:68`
- Problem:
  - Overlay/window logic treats terminal states as active tasks.
- KISS fix:
  - Define explicit active set: `Running/TakeoverPending/Takeover/WaitingForInput/WaitingForAction`.

2. **Test coverage misses controller-level transition correctness and nav action contract.**
- Existing tests are mostly state-holder/mode:
  - `app/src/test/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolderTest.kt`
  - `app/src/test/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleModeTest.kt`
- Missing:
  - `ServiceOverlayController` transition tests (VD + A11y), nav visibility-action consistency tests, ack/failure paths.

## Root-Cause Summary (for reported symptoms)
1. `eye` not working in VD:
- MAIN_APP nav shown but no-op binding.
2. buttons unresponsive:
- optimistic transition before backend ack + possible no-session submit.
3. island disappears/stales:
- island push model not fully reactive to state-holder internal transitions.
4. complete_task/history anomalies:
- split ownership of recording finalization and shutdown timing.
5. complete后主app状态异常:
- terminal/active semantics and mixed lifecycle paths cause residual `Done`/visibility mismatch.

## Refactor Blueprint (Systemic, not patch-by-patch)
1. **Command/ack split for state machine**
- Introduce explicit UI intents (`TakeoverClicked`, `UserResponseSubmitted`) and backend-ack events (`TakeoverConfirmed`, `UserResponseAccepted/Rejected`).
- `CapsuleStateHolder` transitions only on events, not on optimistic clicks.

2. **Single reactive pipeline for all renderers**
- `SmartCapsuleManager`, `SmartCapsuleCompose`, `StatusIslandManager` all subscribe to same state stream.
- Controller only manages window attach/detach.

3. **Pure visibility policy function**
- Derive `showCapsule/showIsland/showGlow` from `(platformMode, context, mode, appForeground, viewerVisible)` in one place.
- Remove ad-hoc branching spread across event handlers.

4. **Single owner for session finalization/history**
- Service finalizes recording on session terminal event; UI stops calling direct shutdown on task completion.

## Recommendation
**CHANGES_REQUESTED** before considering round4 architecture stable.
