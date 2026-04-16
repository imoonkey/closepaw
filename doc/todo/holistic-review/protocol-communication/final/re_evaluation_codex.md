# Protocol & Communication — Re-evaluation (Codex)

Date: 2026-04-16
Basis: `review.md` + `improvement_plan.md`, re-checked against the current workspace

## Scope

Reviewed:
- `doc/todo/holistic-review/protocol-communication/final/review.md`
- `doc/todo/holistic-review/protocol-communication/final/improvement_plan.md`
- all 27 files under `app/src/main/kotlin/com/moonkey/androidagent/protocol/`
- requested consumers:
  - `session/AgentSession.kt`
  - `agent/AgentEventDispatcher.kt`
  - `app/AgentServiceEventHandler.kt`
  - `ui/chat/ChatEventReducer.kt`
  - `session/SessionCheckpointCoordinator.kt`
  - `tool/ToolRouter.kt`
  - `ui/overlay/CapsuleStateHolder.kt`
- supporting files where needed to verify actual producers/side effects:
  - `agent/Agent.kt`
  - `agent/AgentRuntimeTypes.kt`
  - `agent/TurnExecutionPhaseRunner.kt`
  - `agent/TurnPlanningPhaseRunner.kt`
  - `session/SessionAgentRunner.kt`
  - `session/SessionServices.kt`
  - `history/SessionRecordingService.kt`
  - `history/model/SessionRuntimeSnapshot.kt`
  - `app/ServiceOverlayController.kt`
  - `onboarding/DefaultOnboardingDemoController.kt`

Validation:
- `./gradlew :app:compileDebugKotlin` passed during this review.
- The current workspace includes local uncommitted `ActionOutcome` changes in `protocol/ActionEvents.kt`, `agent/AgentEventDispatcher.kt`, and `agent/TurnExecutionPhaseRunner.kt`. This re-evaluation is against that compiled workspace state, not just `HEAD`.

Code refs below are repo-relative from `app/src/main/kotlin/com/moonkey/androidagent/` unless stated otherwise.

## Bottom Line

Worth fixing now:
1. Split task outcome from session end reason. The original design flaw still exists, and it now has a concrete downstream bug: session recording cannot faithfully preserve successful task outcome once the session later shuts down.
2. Fix checkpoint config persistence. Reload currently drops runtime-affecting fields and can change approval behavior, tracing, excluded tools, and action delay.
3. Validate approval resolution before mutating allow-lists.
4. Prune obviously dead event surface if protocol files are already being touched: `ApprovalResolved`, `TodosUpdated`, `ScratchpadUpdated`, `ApprovalRequired.actionId`, and the duplicated initial turn phase signal.

Probably not worth a dedicated refactor right now:
1. Removing marker interfaces.
2. Splitting `SessionConfig` into several config types.
3. Naming-only approval renames (`actionId` -> `callId`, `Approve` -> `ResolveApproval`).
4. Moving `sanitizeThought()` / `StatusUpdate` / `ThoughtUpdate` out of `protocol/`.

## What Changed Since 2026-04-08

1. `SessionError` is no longer dead. It is emitted for bootstrap failure in `session/AgentSession.kt:315-330` and consumed by chat/overlay in `ui/chat/ChatEventReducer.kt:144-150` and `app/AgentServiceEventHandler.kt:133-136`.
2. `TASK_IMPOSSIBLE` now has a real producer. `agent/Agent.kt:117-123` emits `AgentStopReason.TaskImpossible`, and `session/AgentSession.kt:449-455` maps it to `CompletionReason.TASK_IMPOSSIBLE`.
3. `ActionExecuted` has already been improved beyond the original review. It now carries `ActionOutcome` in `protocol/ActionEvents.kt:3-23`, and consumers handle `SUCCESS/FAILED/SKIPPED` in `app/AgentServiceEventHandler.kt:60-67`, `ui/chat/ChatEventReducer.kt:102-134`, and `agent/subagent/SubAgentRunner.kt:150-157`.

## Re-evaluation of `review.md`

| Item | Category | Re-evaluation |
| --- | --- | --- |
| H1 | `LOW_ROI` | Still true. `protocol/AgentEventDomains.kt:3-37` defines 12 marker interfaces, and repo-wide usage still shows they are only referenced by protocol event declarations. This is dead taxonomy, but deleting it is broad rename churn with no runtime payoff. |
| H2.a `AgentError` | `LOW_ROI` | The original wording is partially stale: `AgentError` is now instantiated in `session/AgentSession.kt:325-330`. But only `PlatformError` is used; the rest of the hierarchy, `isRecoverable`, and the factory remain unused (`protocol/AgentError.kt`, repo-wide search). This is over-designed, but not where the leverage is. |
| H2.b `SessionError` never emitted | `ALREADY_FIXED` | No longer true. Bootstrap failures now emit `SessionError` so the user input stays in chat and the overlay can show the failure (`session/AgentSession.kt:315-330`). Consumers in `ChatEventReducer` and `AgentServiceEventHandler` now have a real producer. |
| H2.c `TodosUpdated` / `ScratchpadUpdated` unconsumed | `STILL_VALID` | Still true, and now stronger. These events are now emitted from `agent/TurnExecutionPhaseRunner.kt:260-279` via `agent/AgentEventDispatcher.kt:98-112`, but repo-wide search still finds no consumer outside their definitions. |
| H2.d `ApprovalResolved` unconsumed | `STILL_VALID` | Still true. `session/AgentSession.kt:599-607` emits it, but repo-wide search finds no event consumer. The UI already transitions locally before the op is submitted (`app/ServiceOverlayController.kt:98-100`, `ui/chat/ChatScreen.kt:159-161`, `ui/overlay/CapsuleStateHolder.kt:146-156`). |
| H2.e `TurnStarted.phase` redundant | `STILL_VALID` | Still true. `protocol/TurnEvents.kt:4-10` still carries `phase`, `agent/AgentEventDispatcher.kt:45-52` still hardcodes `PERCEPTION`, and `agent/Agent.kt:93-94` still immediately emits `TurnPhaseChanged(PERCEPTION)`. `ChatEventReducer` uses `TurnStarted` only to clear the buffer (`ui/chat/ChatEventReducer.kt:59-62`). |
| H2.f `ApprovalRequired.actionId` duplicates `details.callId` | `STILL_VALID` | Still true. `protocol/ApprovalEvents.kt:4-10` and `protocol/ApprovalTypes.kt:31-52` both carry the same identifier, and the consumer still uses `details` rather than `actionId` (`app/AgentServiceEventHandler.kt:165-167`, `app/ServiceOverlayController.kt:329-345`). |
| H2.g `StatusUpdate.emoji` unused | `LOW_ROI` | Still true. `protocol/StatusEvents.kt:4-9` still exposes the field, `AgentServiceEventHandler` still branches on it (`app/AgentServiceEventHandler.kt:29-35`), but all current producers embed emoji directly in the status string and pass no non-null emoji (`agent/AgentEventDispatcher.kt:15-21`, `session/AgentSession.kt:269-279,286,347,360,432`). |
| H3.a `CompletionReason` conflates task outcome and session shutdown reason | `STILL_VALID` | Still the most important structural issue. `TaskCompleted` and `SessionCompleted` still both use `CompletionReason` (`protocol/TaskLifecycleEvents.kt:16-22`, `protocol/SessionLifecycleEvents.kt:11-16`), while `AgentSession` clearly emits task reasons at task end and shutdown reasons at session end (`session/AgentSession.kt:418-426`, `547-583`). The type still encodes impossible combinations. |
| H3.b consumers branch on impossible `SessionCompleted` states | `STILL_VALID` | Still true. `AgentServiceEventHandler` handles `GOAL_ACHIEVED`, `MAX_TURNS`, `TASK_IMPOSSIBLE`, and `ERROR` inside `SessionCompleted` (`app/AgentServiceEventHandler.kt:117-130`), but `AgentSession` only emits `USER_STOPPED` and `IDLE_TIMEOUT` there (`session/AgentSession.kt:573-583`). `CapsuleStateHolder.onSessionEnded()` has the same impossible branches (`ui/overlay/CapsuleStateHolder.kt:258-285`). |
| H3.c `TASK_IMPOSSIBLE` has no producer | `ALREADY_FIXED` | No longer true. It is now produced by `agent/Agent.kt:117-123` and mapped in `session/AgentSession.kt:449-455`. |
| H4.a `SessionConfig` mixes concerns | `LOW_ROI` | Still technically true. `protocol/SessionConfig.kt:12-60` remains a flat object spanning execution, routing, observability, perception, and eval knobs. But the object is still readable, call-sites are straightforward, and the concrete bug is persistence loss, not flatness itself. |
| H4.b checkpoint persistence is lossy | `STILL_VALID` | Still true and high leverage. `session/SessionCheckpointCoordinator.kt:84-128` persists only model/agent/perception/platform data. It silently drops `actionDelayMs`, `approvalMode`, `debugMode`, `traceEnabled`, `traceRunId`, and `excludedTools` from `protocol/SessionConfig.kt:12-60`, even though those fields still affect runtime in `session/SessionAgentRunner.kt:72-80`, `session/SessionServices.kt:107-108`, and `trace/TraceRecorderFactory.kt:12-16,35-44`. Reload can change security posture and tooling behavior. |
| H4.c `SessionLlmConfig` allows contradictory states | `LOW_ROI` | Still true in the type system (`protocol/SessionConfig.kt:57-60`), but current call-sites already construct it consistently (`app/MainActivity.kt:596-620`, `onboarding/DefaultOnboardingDemoController.kt:74-81`, `session/SessionCheckpointCoordinator.kt:113-126`). This is a design nicety, not a live bug. |
| M1 approval identifier naming inconsistent | `LOW_ROI` | Still true. `Op.Approve` uses `actionId` (`protocol/Op.kt:92-104`) while the actual approval flow uses `callId` in `ToolRouter`, `ApprovalDetails`, and UI state (`tool/ToolRouter.kt:328-337`, `protocol/ApprovalTypes.kt:31-52`, `ui/overlay/CapsuleStateHolder.kt:136-156`). But this is naming debt, not a runtime problem by itself. |
| M2 protocol package mixes domain contract with UI projection | `LOW_ROI` | Still true. `StatusUpdate` is display-ready (`protocol/StatusEvents.kt:4-9`), `ThoughtUpdate` carries already-sanitized display text from `agent/TurnPlanningPhaseRunner.kt:228-237`, and `sanitizeThought()` explicitly exists to support capsule display (`protocol/TextUtils.kt:3-13`). But these are small in-process session/UI boundary types, not a source of breakage. |
| L1 `ApprovalDetails.args: JSONObject` | `LOW_ROI` | Still true. `protocol/ApprovalTypes.kt:31-52` leaks `JSONObject`, and repo-wide search shows the field is not read by current consumers. If touched, removing the field is better than swapping in a new JSON type just for aesthetics. |
| L2 file granularity | `LOW_ROI` | Still true and still not worth touching. The single-event files are not harming readability at current scale. |
| L3 serialization inconsistency | `LOW_ROI` | Still true. `ScreenStatePhase` is still the only serialized protocol enum (`protocol/ScreenStatePhase.kt:3-8`). For this in-process event surface, that remains acceptable. |

## Re-evaluation of `improvement_plan.md`

| Item | Category | Re-evaluation |
| --- | --- | --- |
| 1A Delete `AgentError.kt` | `STALE` | The premise changed. `AgentError` is no longer fully dead because bootstrap failures emit `AgentError.PlatformError` via `SessionError` (`session/AgentSession.kt:325-330`). If this area is touched, shrink the hierarchy instead of deleting the entire concept outright. |
| 1B Delete `SessionError` | `STALE` | No longer a correct plan. `SessionError` now has a concrete job: surfacing bootstrap failures to chat/overlay while preserving the user input in history (`session/AgentSession.kt:315-330`). |
| 1C Collapse marker-interface taxonomy | `LOW_ROI` | Still valid as cleanup, but not worth a dedicated pass. It is broad mechanical churn with little behavioral value. |
| 1D.a Remove `StatusUpdate.emoji` | `LOW_ROI` | True but tiny. Clean it up when already editing status events; not worth a standalone change. |
| 1D.b Stop emitting `TodosUpdated` / `ScratchpadUpdated` | `STILL_VALID` | Worth doing if protocol cleanup is in scope. These events are emitted and entirely unconsumed. |
| 1D.c Stop emitting `ApprovalResolved` | `STILL_VALID` | Worth doing. The event is redundant with the local UI transition and has no consumer. |
| 1D.d Remove `ApprovalRequired.actionId` | `STILL_VALID` | Worth doing if the approval event is touched. It is duplicate payload with no consumer need. |
| 1D.e Remove `TurnStarted.phase` or the duplicate initial `TurnPhaseChanged(PERCEPTION)` | `STILL_VALID` | Worth doing. The current pair is redundant and `TurnStarted.phase` is not used. |
| 1E Move `sanitizeThought()` out of `protocol/` | `LOW_ROI` | Still more cosmetic than useful. The function is tiny and shared by the planning layer and capsule UI. |
| 2A Split `CompletionReason` | `STILL_VALID` | Still the best structural fix. It removes impossible states from consumers and fixes the current semantic leak between task outcome and session shutdown. |
| 2B Decide on a hot-idle transition event | `ALREADY_FIXED` | The current runtime already chose the reasonable path. Task completion uses `TaskCompleted`, hot idle stays internal, and `SessionCompleted` is only emitted on shutdown (`session/AgentSession.kt:418-444`, `547-583`). No extra idle event is currently needed. |
| 3A Canonical approval ID noun | `LOW_ROI` | Still a readability cleanup, not a pressing runtime fix. |
| 3B Rename `Op.Approve` to `ResolveApproval` | `LOW_ROI` | Naming-only churn across service/UI/session paths. Not worth prioritizing by itself. |
| 3C Immutable approval payload | `LOW_ROI` | The current `JSONObject` payload is not ideal, but the higher-value simplification is probably deleting unused `args`, not introducing another generic JSON carrier. |
| 4A Split `SessionConfig` by ownership | `LOW_ROI` | Still technically defensible, but the current class is manageable. The concrete bug is bad checkpoint round-tripping, not lack of sub-config classes. |
| 4B Fix persistence | `STILL_VALID` | High-priority and worth doing now. This is the most concrete runtime bug in the plan besides completion semantics. |
| 4C Make invalid LLM states unrepresentable | `LOW_ROI` | Nice-to-have only. Current constructors already avoid invalid combinations. |
| 5 Move UI projection out of `protocol/` | `LOW_ROI` | Still not where the leverage is. `StatusUpdate`, `ThoughtUpdate`, and `sanitizeThought()` are mildly impure, but not a real source of bugs. |

## New Findings

| Item | Severity | Why it matters |
| --- | --- | --- |
| NF1 Approval side effects happen before pending approval validation | High | `session/AgentSession.kt:589-607` persists allow-list changes for `SESSION` / `ALWAYS` approvals before checking whether `toolRouter.resolveApproval()` actually matched a pending approval. `ToolRouter.resolveApproval()` explicitly returns `false` when no pending approval exists (`tool/ToolRouter.kt:328-337`), but that result is ignored. A stale or duplicate `Op.Approve` can therefore mutate policy even when there is no matching pending approval. This is approval-path correctness and should be fixed before any rename cleanup. |
| NF2 Session recording loses task outcome at session shutdown | High | `AgentSession` emits `TaskCompleted` with task outcome (`session/AgentSession.kt:418-426`) and later emits `SessionCompleted` only with shutdown reasons (`session/AgentSession.kt:573-583`). `SessionRecordingService.completeSession()` derives `completedNormally` from the session-level `CompletionReason` (`history/SessionRecordingService.kt:211-244`). That means a session whose last task succeeded but was later closed by idle timeout or explicit stop is persisted as not completed normally. This is the concrete bug behind H3, not just a theoretical type smell. |
| NF3 `SessionCompleted.result` is dead payload | Low | `protocol/SessionLifecycleEvents.kt:11-16` still carries `result`, but `AgentSession` always emits `result = null` (`session/AgentSession.kt:578-583`) and current consumers ignore it. If H3 is fixed, this field should probably disappear with the old enum. |

## Practical Recommendation

If I were cutting follow-up work now, I would do it in this order:

1. Split completion semantics and update the handful of consumers that currently branch on impossible `SessionCompleted` states.
2. Fix checkpoint persistence so reload preserves runtime-affecting config.
3. Fix `handleApproval()` so allow-list mutation only happens after `resolveApproval()` succeeds.
4. In the same protocol cleanup pass, remove the small dead pieces that are clearly not earning their keep:
   `ApprovalResolved`, `TodosUpdated`, `ScratchpadUpdated`, `ApprovalRequired.actionId`, and either `TurnStarted.phase` or the duplicate initial `TurnPhaseChanged(PERCEPTION)`.

Everything else from the original plan is real but mostly theoretical cleanup. I would not spend a dedicated PR on it unless those files are already open for one of the higher-value fixes above.
