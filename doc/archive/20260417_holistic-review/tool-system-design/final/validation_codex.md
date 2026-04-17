# Tool System Findings Validation (Codex)

Source findings reviewed from:
- `doc/todo/holistic-review/tool-system-design/final/review.md`
- `doc/todo/holistic-review/tool-system-design/final/improvement_plan.md`

ID: C1
Verdict: CONFIRMED
Evidence: `ToolRouter.kt:102-104` still does policy on the current foreground package before execution, while `OpenAppTool.kt:148-201` resolves the destination package only inside `OpenAppInvocation.execute()` and launches it without any second policy check. Separately, `PostActionAnalysis.kt:17-45` still captures post-action snapshots for `mobile_action` and builds observations without an `AppClassifier`, and `ObservationBuilder.kt:20-31` only masks when a classifier is supplied. The original review's `UIActionInvocation` / `OpenAppTool` masking evidence is partly stale because both now call `buildObservation(..., context.appClassifier)` at `UIActionInvocation.kt:79-80` and `OpenAppTool.kt:214-216`.
Rationale: The blocked-app invariant is still broken end-to-end even though a couple of the original leak paths were partially hardened.

ID: C2
Verdict: CONFIRMED
Evidence: `ToolName.kt:13-17,64-83` still treats unknown tools as `isScreenChanging = true`, and `ask_user` / `shell` are still missing from the canonical cases. That metadata is still consumed by `PolicyEngine.kt:48-49` and `TurnToolPolicy.kt:57-64`. Both tools are live: `ShellTool` is registered in `SessionToolingBootstrapper.kt:57-63`, and `AskUserTool` is registered on demand in `SessionAgentRunner.kt:149-157`.
Rationale: Tool metadata drift still changes runtime behavior; only the old `ActionSignature` consumer is gone.

ID: H1
Verdict: CONFIRMED
Evidence: `ShellTool.kt:43-49` only checks the first token against `BLOCKED_COMMANDS`, but `ShellTool.kt:79` still executes the whole string via `ProcessBuilder("sh", "-c", command)`. `StandaloneAgentDef.kt:8-20,38` still exposes `shell` to the standalone agent.
Rationale: Shell metacharacters and wrappers still bypass the blocklist, so the tool remains outside the otherwise declarative tool contract.

ID: H2
Verdict: CONFIRMED
Evidence: `ScrollExecutor.kt:111-127` still falls back to full-display bounds whenever target resolution fails or produces no bounds. `TargetResolver.kt:35-45,48-72` can return `NotFound`, and `TargetResolver.kt:28-31` resolves coordinate targets with `bounds = null`.
Rationale: An explicit scroll target can still silently degrade into a whole-screen scroll instead of failing.

ID: M1
Verdict: CONFIRMED
Evidence: `SwipeExecutor.kt:49-54` still maps `ActionResult.Cancelled` to `ActionOutcome.Failed`. `TypeExecutor.kt:73,87-117,138-142` still collapses cancelled action results into generic failure trails. `ToolRouter.kt:263-268` only exposes cancellation through `context.isCancelled()`, but `ToolRouter.kt:334-348` does not own or trip any per-call execution token when `cancel()` / `cancelAll()` is called.
Rationale: Cancellation still means different things depending on the executor path and the router API you use.

ID: M2
Verdict: NOT_WORTH_IT
Evidence: `ToolSpec.kt:16-55` still has no capability metadata, so `PolicyEngine.kt:48-49` and `TurnToolPolicy.kt:57-64` still ask `ToolName` for behavior. `ToolSpec.kt:133-137` and `ToolCallResult.kt:16-21` still expose `data: Any?`. Capture timing and retry constants are still scattered across `UIActionInvocation.kt:27-30`, `PointActionExecutorCore.kt:27`, `ScrollExecutor.kt:18-20`, `TypeExecutor.kt:25-26`, `PostActionAnalysis.kt:14-15`, and `OpenAppTool.kt:72-75,133-136`.
Rationale: The architectural critique is still true, but only the metadata drift is carrying real product impact right now, and that is already covered by C2.

ID: M3
Verdict: CONFIRMED
Evidence: `PointActionExecutorCore.kt:193-216` can still retarget from the resolved element to a clickable container or child, but it returns `warnings = resolved.warnings` unchanged and the surfaced `attemptTrail` at `PointActionExecutorCore.kt:82-136` only records channel outcomes. The only visibility is the debug log at `PointActionExecutorCore.kt:200-202`.
Rationale: Point-action semantics can still change invisibly to the caller.

ID: M4
Verdict: CONFIRMED
Evidence: After approval, `ToolRouter.kt:195-225` only aborts the unknown-package path when the newly observed app is `BLOCKED`; it does not re-ask if the app resolves to `CAUTIOUS`. `PolicyEngine.kt:71-76` still says `CAUTIOUS` requires approval in `SMART` mode.
Rationale: Approval context is still asymmetric when the pre-approval foreground app was unknown.

ID: L1
Verdict: NOT_WORTH_IT
Evidence: `UIActionInvocation.kt:53-58,87-110` still carries a local `detectScrollBoundary()` path guarded by `uiAction is UIAction.Swipe`, but `UIActionInvocation` is still only instantiated from `WaitTool.kt:62-69` and `SystemButtonTool.kt:73-78`. `UiChangeDetector.detectScrollBoundary()` is still defined at `UiChangeDetector.kt:31-49`, and repo search only finds the definition.
Rationale: The dead code is still there, but removing it is pure cleanup with no behavioral payoff.

ID: L2
Verdict: NOT_WORTH_IT
Evidence: `PolicyEngine.kt:133-136` still checks `mobile_action(action=back/home)`, while `MobileActionTool.kt:55-61` still only accepts `click`, `long_press`, `type`, `scroll`, and `swipe`. But `MobileActionName.Back/Home/Wait/SystemButton` are still used by the UI at `ToolUi.kt:29-58`.
Rationale: The dead part is the policy fallback, not the enum itself, so this is a tiny cleanup rather than a meaningful design defect.

ID: L3
Verdict: NOT_WORTH_IT
Evidence: `OpenAppTool.kt:72-75` and `OpenAppTool.kt:133-136` still both declare `UI_SETTLE_DELAY_MS` and `SUGGESTION_LIMIT`, and only the inner copies are referenced at `OpenAppTool.kt:205,247`.
Rationale: Real duplication, negligible payoff.

ID: L4
Verdict: NOT_WORTH_IT
Evidence: `SystemButtonTool.kt:53-61` still validates button values up front, yet `SystemButtonTool.kt:66-71` still has `else -> SystemButtonType.BACK`.
Rationale: The fallback is unreachable in the normal router flow, so changing it would be cleanup rather than a functional fix.

ID: L5
Verdict: NOT_WORTH_IT
Evidence: `DataQueryInvocation.kt:19-50` still exists, and repo-wide search only finds the class definition with no production callers.
Rationale: It is unused, but it is also tiny and harmless unless the team is already doing a cleanup pass.

ID: L6
Verdict: CONFIRMED
Evidence: `ShellTool.kt:59,91-99` still stops reading once `MAX_OUTPUT_CHARS = 4096` is hit, and `ShellTool.kt:112-114` still returns that truncated text with no explicit marker.
Rationale: The agent can still be misled by incomplete shell output.

ID: L7
Verdict: NOT_WORTH_IT
Evidence: `MobileActionTool.kt:69-94` still constructs `ClickExecutor()`, `LongPressExecutor()`, `TypeExecutor()`, `ScrollExecutor()`, and `SwipeExecutor()` inside `createInvocation()`. Those executors are still stateless wrappers (`ClickExecutor.kt:14-16`, `LongPressExecutor.kt:13-15`, `ScrollExecutor.kt:14-16`, `TypeExecutor.kt:21-23`, `SwipeExecutor.kt:17-21`).
Rationale: Technically true, but the allocation cost is noise compared with Android UI automation latency.

ID: L8
Verdict: NOT_WORTH_IT
Evidence: `ToolRouter.kt:233-235` still emits `ToolCallState.Scheduled`, but repo-wide search only finds that runtime constructor use plus state tests (`ToolCallStateTest.kt:47`). The main execute path in `TurnExecutionPhaseRunner.kt:95-107` still does not pass `onStateChange`.
Rationale: The state is effectively informational dead weight, but removing it buys almost nothing unless the state machine is being simplified anyway.
