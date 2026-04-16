# Error Resilience QA Report

**Task**: er-qa-real-device
**Date**: 2026-04-16
**Device**: EP0110MZ0BC101266W (physical)
**Build**: `./scripts/setup.sh` before every scenario (fresh APK + restored a11y / overlay permissions)
**LLM**: `gpt-5.4` via OpenAI
**Evidence root**: `/tmp/er-qa-evidence/`

## Summary

| # | Scenario | Result | Improvement-plan item |
|---|----------|--------|-----------------------|
| S1 | False-completion regression | PASS (code + indirect trace) | P0 #1 |
| S2 | Approval dispatch healthy-path | PASS (code + healthy trace) | P0 #2 |
| S3 | Delegate-task failure | PASS (code + unit test; live not triggered) | P0 #3 |
| S4 | Service restart mid-task | PASS | P0 #4 |
| S5 | Policy-denied blocked app | PARTIAL — bug note (see below) | P1 #7 |
| S6 | Task impossible → `TASK_IMPOSSIBLE` | PASS | P1 #5 |
| S7 | Normal multi-turn task → `GOAL_ACHIEVED` | PASS | sanity |

Targeted unit tests `AgentErrorRecoveryTest`, `TurnToolPolicyTest`, `DelegateTaskToolTest`, `ToolRouterTest` all pass.

---

## S1 — False-completion regression (P0 #1)

**Goal**: When a cognitive tool fails before `complete_task`, agent must not report success.

**Code verification** — `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntimeTypes.kt:72-104`:
`decideTurnOutcome()` checks `execution.terminatedEarly` first and returns `TurnOutcome.Error`/`Cancelled` based on `lastTerminalResult`. It also returns `Error("complete_task was planned but did not execute")` when the arbitration selected `complete_task` but execution never reached it.

**Live trace** — S6 run (`/tmp/er-qa-evidence/s1/agent.log`): `open_app` returned `outcome=FAILED` on the bogus app; the next turn correctly called `complete_task(status="failure")` rather than falsely completing earlier. `reason: TASK_IMPOSSIBLE` emitted (not `GOAL_ACHIEVED`).

Direct fault injection (simulating a cognitive tool failing *mid-turn before* `complete_task`) is not reachable from the debug-run harness on a real device. Code + unit test (`AgentErrorRecoveryTest`) + observed trace cover the fix.

**Result**: PASS.

---

## S2 — Approval dispatch healthy path (P0 #2)

**Goal**: Approval path should not hang 60 s when the dispatch itself breaks.

**Code verification** — `TurnExecutionPhaseRunner.kt:184-190`:
`emitApprovalRequired()` is now a plain forward to `eventDispatcher.approvalRequired(...)` — no try/catch suppression. An emitter exception would propagate to `ToolRouter` and convert to `ToolCallResult.Error("Approval request failed: ...")`.

**Live healthy-path trace** — first S7 run (`/tmp/er-qa-evidence/s2/agent.log`):
Agent requested `mobile_action` on `com.google.android.deskclock` (CAUTIOUS tier). Trace shows:
```
ToolRouter: Policy decision for mobile_action: AskUser(...)
ToolRouter: State: ... -> AwaitingApproval      (t0)
ToolRouter: Approval timeout for ...            (t0 + 60 s)
ToolRouter: State: ... -> Cancelled
ActionExecuted: mobile_action outcome=SKIPPED
```
This is the normal no-answer-from-user timeout (ApprovalRequired expected behavior), not a dispatch failure. Dispatch-failure simulation requires injecting an exception into the emitter which is not reachable from the debug harness.

**Result**: PASS (healthy-path observation). The 60 s window here is the legitimate approval-wait timeout, not the bug the fix targets.

---

## S3 — Delegate-task failure (P0 #3)

**Goal**: When a PRO-mode sub-agent fails, `delegate_task` must return structural failure (not success).

**Code verification** — `DelegateTaskTool.kt:176-180`:
```kotlin
return if (result.success) {
    textToolSuccess(output = output, data = data)
} else {
    ToolExecutionResult.Failure(error = output)   // "Sub-agent failed: ..."
}
```

**Unit test**: `DelegateTaskToolTest` passes.

**Live**: I ran a PRO-mode task against `BogusApp12345` (`--pro`, gpt-5.4 planner + executor). The planner resolved the task directly (`open_app` → FAILED → `complete_task(failure)`) without invoking `delegate_task`, so the sub-agent path was not exercised on this run. `/tmp/er-qa-evidence/s3/agent.log`. Task still completed correctly with `reason: TASK_IMPOSSIBLE`.

**Result**: PASS (code + unit test). Live delegate-failure path not triggered; the planner chose not to delegate for this task. Bug note: if a live delegate-failure repro is required, a task that reliably forces a delegate→executor→fail chain is needed (the current BogusApp task is too trivial for the planner to delegate).

---

## S4 — Service restart mid-task, no ANR (P0 #4)

**Steps**:
1. `./scripts/setup.sh`
2. Start Chrome task via `debug-run.sh`
3. Wait for turn 3 start
4. `adb shell am force-stop com.moonkey.androidagent`
5. Relaunch; inspect logcat for ANR; verify checkpoint on disk

**Evidence** — `/tmp/er-qa-evidence/s4/`:
- `logcat.log` — full buffer covering the kill window
- `context.json` — on-disk checkpoint: `schemaVersion=v1`, `checkpointState=RUNNING_DIRTY`, `historyItems=8`, `lastCheckpointAt=1776379110024`
- `after_relaunch.png` — app relaunches cleanly

**ANR check**: `grep -iE "ANR in com.moonkey|am_anr.*moonkey" logcat.log` → **0 hits**. Only unrelated system `AnrMonitor: Unknown process` noise for other apps (uber/whatsapp/maps).

**Shutdown trace**: `vendor.qti.hardware.servicetrackeraidl-service: destroyService is called for service: com.moonkey.androidagent/.app.AgentService` — clean teardown. `SessionCoordinator: Session shutdown completed` present in an earlier run.

**Checkpoint**: `context-2026-04-16T18-38-06-...json` persisted with 8 history items under `NonCancellable`, confirming item #4's design (partial shutdown tolerable).

**Result**: PASS.

---

## S5 — Policy-denied blocked app (P1 #7, partial)

**Steps**: `Open the Robinhood app and check my balance` (Robinhood is BLOCKED in `security/app_tiers.json`).

**Evidence** — `/tmp/er-qa-evidence/s5/`:
- `agent.log`:
```
ToolRouter: Policy decision for open_app: Deny(reason=Blocked: financial/auth app ...)
ActionExecuted: open_app outcome=FAILED
Task completed: task-..., reason: TASK_IMPOSSIBLE
```
- `turn_002_n2.png`, `session.json`

**UI requirement (no ✓ executed)**: MET. `outcome=FAILED` does not render the success checkmark.

**Bug note — outcome type vs. plan spec**:
The final plan item #7 specifies:
> `ToolCallResult.Cancelled (user denied, approval timeout, blocked app) → SKIPPED`

But `ToolRouter.kt:116-121` maps `PolicyDecision.Deny` to `ToolCallResult.Error`, which becomes `outcome=FAILED`, not `SKIPPED`. All three intended "skipped" sources (user-denied, approval-timeout, blocked-app) should produce `SKIPPED`; currently only user-denied and approval-timeout do. Blocked-app falls through as FAILED.

The end-user-visible guarantee ("no ✓ checkmark for non-success") is still met, so the scenario is marked PARTIAL — no behavioral regression, but the outcome classification deviates from the final plan specification. Recommended follow-up: change `PolicyDecision.Deny` to return `ToolCallResult.Cancelled("Policy denied: …")` so semantics match item #7.

**Result**: PARTIAL / bug note filed (not fixing per QA instructions).

---

## S6 — Task impossible (P1 #5)

**Steps**: `Open an app named BogusApp12345 that doesn't exist`

**Evidence** — `/tmp/er-qa-evidence/s6/agent.log`:
```
ActionExecuted: open_app outcome=FAILED
FunctionCallOutput: "Error: App not found: 'BogusApp12345'..." success=false
TurnExecutionPhase: Executing tool: complete_task with args: {"status":"failure","answer":"..."}
ActionExecuted: complete_task outcome=SUCCESS
Task completed: task-..., reason: TASK_IMPOSSIBLE
```

Session JSON shows the full `historyItems` chain terminating in a `complete_task(status=failure)` call and `checkpointState=CLOSED`.

**Result**: PASS. `CompletionReason.TASK_IMPOSSIBLE` emitted (item #5 split is live).

---

## S7 — Normal multi-turn task (sanity)

**Steps**: `Open the Chrome app and search for 'android development tutorial'` (12 turns).

**Evidence** — `/tmp/er-qa-evidence/s7/`:
- `agent.log`: `Task completed: task-..., reason: GOAL_ACHIEVED`
- `final.png`: last turn screenshot (Chrome search result)
- `session.json`: 2 messages, 23 screenStates, turn 1–12 all captured

**Result**: PASS. Normal GOAL_ACHIEVED path is intact after the resilience changes.

---

## Summary of live observations for item #7 (outcome enum)

Across all runs, `AgentEventDispatcher: ActionExecuted: <tool> outcome=<X>` was emitted with all three values of the enum:
- `SUCCESS` — open_app, complete_task
- `FAILED` — open_app on missing app / blocked app, mobile_action failing
- `SKIPPED` — mobile_action after approval timeout

So the `ActionOutcome { SUCCESS, FAILED, SKIPPED }` enum is live end-to-end in the dispatcher, with the S5 caveat above (blocked app currently FAILED, not SKIPPED).

## Environment / reproducibility

- All scenarios re-run `./scripts/setup.sh` immediately before their `debug-run.sh` invocation (per project convention — setup restores a11y after any force-stop or reinstall).
- Evidence files preserved under `/tmp/er-qa-evidence/s{1..7}/`.
- Session files pulled via `adb shell run-as com.moonkey.androidagent cat files/sessions/<file>`.
