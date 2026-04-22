# Tool System Design Review

Date: 2026-04-08
Scope: `app/src/main/kotlin/com/moonkey/androidagent/tool/` (36 files)

---

## Perspective A: System Design (Minimal & Composable?)

### Overall Architecture Assessment

The pipeline is: **ToolSpec -> ToolRegistry -> ToolRouter -> PolicyEngine -> ToolInvocation -> Executor**

This is clean. Five stages, single responsibility each, no unnecessary layers. The design
successfully avoids the common trap of over-abstracting tool execution.

**Grade: Strong.** The pipeline is minimal for what it does. No stage can be eliminated
without losing necessary functionality.

### A.1 ToolSpec Interface -- Correct Abstraction Level

The `ToolSpec` interface is lean: `name`, `description`, `parameterSchema`, `validate()`,
`createInvocation()`, plus a default `toFunctionSchema()`. This is the right level -- it
captures the declarative nature of a tool without imposing execution concerns.

The separation of `ToolSpec` (what) from `ToolInvocation` (how) is a strong pattern. It
enables the approval flow: you can inspect what will happen before committing to execution.

**No issues.**

### A.2 ToolRegistry -- Appropriate Simplicity

A `MutableMap<String, ToolSpec>` with registration, lookup, and schema generation.
`createFilteredCopy` is used for sub-agent tool subsetting. `generateResponsesApiTools`
bridges to the OpenAI SDK types.

The registry is intentionally dumb -- it does not own policy, ordering, or lifecycle.

**No issues.** This is KISS done right.

### A.3 ToolRouter -- State Machine Correctness

The state machine has 7 states: `Validating -> Scheduled -> Executing -> Success/Error/Cancelled`,
with `AwaitingApproval` as an alternative path from `Validating`.

**Finding A.3.1 (Low): `Scheduled` state is ephemeral and arguably unnecessary.**
In `execute()`, when `PolicyDecision.Allow` is returned, the router transitions to
`Scheduled`, then immediately to `Executing` with no intervening logic. The state exists
only as a notification to the `onStateChange` callback. This is defensible for UI
purposes but adds a state that nothing ever queries.

**Finding A.3.2 (Medium): TOCTOU re-check after approval is good but asymmetric.**
After `APPROVED`, the router re-checks the foreground package. However:
- If `packageName` was non-null and changed: cancelled. Good.
- If `packageName` was null and the current app is BLOCKED: cancelled. Good.
- If `packageName` was null and the current app is CAUTIOUS: allowed without re-checking.
  This means a user who approves on App A could have execution land on App B (CAUTIOUS)
  without notification. This is a minor gap because the original policy check already
  passed for a null package, but it breaks the principle that approval is bound to context.

**Finding A.3.3 (Low): Snapshot re-capture after approval leaks platform detail.**
Line 252-258 of `ToolRouter.kt`: The router directly calls `platform.captureScreen()` and
`policyEngine.appClassifier.maskIfBlocked()`. This is the only place the router knows about
screen capture mechanics. It could be pushed into the execution context instead.

### A.4 PolicyEngine -- Logic Correctness

Decision tree:
1. Non-screen-changing -> Allow
2. Escape actions (back/home) -> Allow
3. BLOCKED -> Deny (hard floor)
4. User allow-list -> Allow (except ALWAYS_ASK)
5. Mode-based: ALWAYS_ASK -> AskUser, AUTO_APPROVE -> Allow, SMART -> tier-based

**Finding A.4.1 (Medium): `isScreenChanging` is incomplete in `ToolName`.**
`AskUser` and `Shell` are not in the `ToolName` sealed class. They fall through to
`Unknown`, which returns `isScreenChanging = true`. This means:
- `ask_user` goes through policy check as screen-changing, but it does not change
  the screen. It will unnecessarily trigger approval in SMART/CAUTIOUS scenarios.
- `shell` is read-only inspection, but will trigger approval on CAUTIOUS apps.

Both tools are misclassified because they are not enumerated in `ToolName`. The
`Unknown` fallback defaulting to `isScreenChanging = true` is a safe default but
causes unnecessary friction.

**Finding A.4.2 (Low): Escape detection duplicates knowledge.**
`isEscape()` checks both `system_button(button=back/home)` and
`mobile_action(action=back/home)`. The mobile_action path is a "fallback path" comment
suggests legacy compatibility. If mobile_action no longer supports back/home directly
(MobileActionTool validates action as click/long_press/scroll/swipe/type only), this
second branch is dead code.

**Finding A.4.3 (Low): `addUserOverride` tightening check is inverted.**
`AppClassifier.addUserOverride()`: `if (tier.ordinal >= current.ordinal) return false`.
The enum order is `BLOCKED(0), CAUTIOUS(1), NORMAL(2)`. So `tier.ordinal >= current.ordinal`
means "new tier is same or more permissive" -> rejected. This is correct for tightening-only
but the logic reads counter-intuitively because BLOCKED has ordinal 0 (most restrictive)
while NORMAL has ordinal 2 (least restrictive). A comment explaining the ordinal semantics
would help.

### A.5 ToolCallState vs ToolCallResult -- Redundancy Assessment

`ToolCallState` is the full lifecycle (7 states, used for callbacks/UI).
`ToolCallResult` is the terminal-only result (3 variants, returned to caller).

These serve different audiences. `ToolCallState` is observable state for the UI layer.
`ToolCallResult` is the final answer for the agent loop. Having both is justified.

**Finding A.5.1 (Low): `ToolCallState.Success` wraps `ToolExecutionResult.Success`, while
`ToolCallResult.Success` duplicates its fields.** There are now three success types:
`ToolExecutionResult.Success`, `ToolCallState.Success`, `ToolCallResult.Success`. The
mapping chain is: ExecutionResult -> State.Success -> CallResult.Success. The intermediate
unpacking/repacking in ToolRouter lines 278-286 is boilerplate but tolerable.

### A.6 ToolName / MobileActionName -- Dual Type Hierarchy

**Finding A.6.1 (Medium): Two parallel sealed hierarchies with overlapping semantics.**
`ToolName` has `Back`, `Home`, `Wait`, `SystemButton` only inside `MobileActionName`.
`MobileActionName` has `Back`, `Home`, `Wait`, `SystemButton` -- concepts that also exist
as tools (`SystemButtonTool`, `WaitTool`). This overlap exists because `mobile_action`
historically supported back/home/wait/system_button as sub-actions, while the current
design has them as separate tools.

`MobileActionName` is only used in two places: `PolicyEngine.isEscape()` and nowhere
else in production code (some test fixtures). The entire `MobileActionName` hierarchy
may be vestigial.

### A.7 Invocation Pattern Fragmentation

There are four invocation patterns in use:

1. **Dedicated inner class**: `CompleteTaskInvocation`, `WriteTodosInvocation`, `ScratchpadInvocation`, `ShellTool.ShellInvocation` -- tool-specific logic.
2. **Lambda-based**: `MobileActionInvocation` -- receives an `executeAction` lambda.
3. **Generic handler**: `UIActionInvocation` -- wraps a `UIAction` for SystemButton/Wait.
4. **Generic handler**: `DataQueryInvocation` -- wraps a query function.

This is actually well-stratified. The lambda pattern (#2) for MobileActionTool is particularly
good -- it keeps all action dispatch in the ToolSpec while the Invocation is pure glue.

**No issues.** Four patterns for different execution shapes is fine.

### A.8 Action Layer -- Fallback Chain Design

The action layer uses a dual-channel fallback pattern:
- Click: node_click -> gesture_tap
- LongPress: node_long_click -> gesture_long_press
- Scroll: a11y_scroll -> gesture_swipe (configurable via `ActionPriorityOrder`)
- Type: SetTextOnNodeAt -> TapToFocus+SetTextOnFocused
- Swipe: gesture only (no fallback)

**Finding A.8.1 (Medium): Scroll channel order in `ActionPriorityOrder` does not match `ScrollExecutor` iteration.**
`ActionPriorityOrder.scroll` lists `[A11Y_SCROLL, GESTURE_SWIPE]`.
But `ScrollExecutor.execute()` iterates `ActionPriorityOrder.scroll` with an explicit
`when` block that checks `GESTURE_SWIPE` first and `A11Y_SCROLL` second in its
match arms. Since `when` on enum values matches by value not by position, and the
iteration follows the list order, the actual execution order IS A11Y_SCROLL first.
However, it is confusing that the `when` arms are ordered differently from the list.

Wait -- re-reading: `for (channel in ActionPriorityOrder.scroll)` iterates in list order.
The first element is `A11Y_SCROLL`. But the `when` block has `GESTURE_SWIPE` as the first
arm. This is a style issue only; behavior is correct because `when` dispatches by value.
But the code physically places the GESTURE_SWIPE arm first, which is misleading.

**Correction**: Actually looking more carefully at `ActionPriorityOrder.scroll`:
```kotlin
val scroll = listOf(ScrollChannel.A11Y_SCROLL, ScrollChannel.GESTURE_SWIPE)
```
But the `ScrollExecutor` `when` block processes `GESTURE_SWIPE` first in the source
code listing. Since `for` iterates the list in order, A11Y_SCROLL runs first regardless
of `when` arm ordering. Behavior is correct; code ordering is cosmetically misleading.

**Finding A.8.2 (Low): `ClickExecutor` and `LongPressExecutor` allocate new instances per call.**
`MobileActionTool.createInvocation()` calls `ClickExecutor().execute(...)` -- creating
a fresh executor each time. The executors are stateless, so this works but is wasteful.
They could be `object` singletons or held as vals.

### A.9 Post-Action Analysis -- Retry Budget

`PostActionAnalysis.capturePostActionAnalysis()` has a 3-attempt retry chain:
1. Initial: settleDelayMs (typically 300ms)
2. Retry: +500ms
3. Slow: +1000ms

Total worst-case: 1800ms. This is reasonable for slow transitions. The escalating delay
pattern is sound -- fast actions resolve in 300ms, slow ones get up to 1.8s.

However, the third capture happens even when the second shows `Changed` (the third
`if` only checks for `Unchanged` from the *first* capture's result variable `latestResult`,
which is reassigned after the second capture). Wait -- re-reading:

```kotlin
if (latestResult == UiChangeDetector.ChangeResult.Unchanged) {
    captures += captureAttempt(platform, RETRY_SETTLE_DELAY_MS)
    latestResult = captures.last().snapshot?.let { ... } ?: ...
}
if (latestResult == UiChangeDetector.ChangeResult.Unchanged) {
    captures += captureAttempt(platform, SLOW_TRANSITION_DELAY_MS)
}
```

`latestResult` is updated after the second capture. If second shows `Changed`, third is
skipped. Correct.

**No issues with retry logic.**

### A.10 UiChangeDetector -- Fingerprinting Quality

FNV-1a hash over sorted elements with fallback to 8x8 perceptual image hash. This is
thoughtful. The exclusion of `isFocused` to avoid false positives is a learned fix.

**Finding A.10.1 (Low): `detectScrollBoundary` in `UiChangeDetector` duplicates logic in `UIActionInvocation`.**
Both `UiChangeDetector.detectScrollBoundary()` and `UIActionInvocation.detectScrollBoundary()`
implement identical scroll-boundary detection logic (comparing sorted text+description+bounds
strings). The `UiChangeDetector` version appears unused -- only the `UIActionInvocation`
version is called (for swipe actions only). The duplication should be resolved.

---

## Perspective B: Implementation Quality (Clean & Correct?)

### B.1 MobileActionTool -- Strongest Implementation

326 lines. Schema, validation, invocation creation, and target parsing all in one file.
Validation is thorough with specific error messages per action type.

**Finding B.1.1 (Low): `agent_thought` in schema but never in `required`.**
Every tool declares `agent_thought` as optional. This is consistent and correct. But
some tools (MobileActionTool, SystemButtonTool, WaitTool) only require their core params.
The LLM may skip `agent_thought` frequently. This is a design choice, not a bug.

**Finding B.1.2 (Low): Bounds selector rejection is permanent technical debt marker.**
Lines 48-53 reject legacy `x1/y1/x2/y2`. If this is truly dead code (no LLM prompt
references bounds), the check could be removed. If kept for safety, it's fine.

### B.2 OpenAppTool -- Good Resolution Strategy

Clean 5-strategy resolution chain (exact label, contains, alias, package-name-like, fuzzy).
Foreground check avoids unnecessary re-launches.

**Finding B.2.1 (Low): `UI_SETTLE_DELAY_MS` declared in both `OpenAppTool` companion and
`OpenAppInvocation` companion.** The `OpenAppTool` constant is unused. Only the
`OpenAppInvocation` one is used. Dead constant.

**Finding B.2.2 (Low): `SUGGESTION_LIMIT` also duplicated.** Same pattern -- declared in
both companions, only used in `OpenAppInvocation`.

### B.3 SystemButtonTool + WaitTool -- Lean and Correct

SystemButtonTool: 80 lines. WaitTool: 71 lines. Both delegate to `UIActionInvocation`.
No issues.

**Finding B.3.1 (Nit): SystemButtonTool's `else -> SystemButtonType.BACK` in the when.**
This is a dead branch because `validate()` already rejects unknown buttons. But it
provides a safe fallback. Could be `else -> error("unreachable")` for clarity.

### B.4 CompleteTaskTool -- Correct but Output is Redundant

The execute method builds an output string with "Task completed successfully.\n\nAnswer: ..."
and also puts `answer` in the data map. The output duplicates the answer with a prefix.

**Finding B.4.1 (Low): The `\n\nAnswer:` prefix in the output is noise for the LLM.**
The agent loop likely extracts `data["answer"]` directly. The formatted output string
goes to the LLM context as confirmation, but the "Task completed successfully.\n\nAnswer:"
wrapper adds no information.

### B.5 WriteTodosTool + ScratchpadTool -- State-Backed Tools

Both accept injected state objects (`TodoState`, `ScratchpadState`). Validation is
thorough (at-most-one in_progress constraint, JSON parsing for scratchpad).

**Finding B.5.1 (Low): WriteTodosTool parses todos twice.**
`validate()` calls `parseTodos()` to check constraints, then `createInvocation()` calls
`parseTodos()` again. This double parse is wasteful but harmless given small list sizes.

**Finding B.5.2 (Low): ScratchpadTool validates entry count against live state.**
In `validate()`, the tool reads `state.list()` to compute whether new keys would exceed
the max. This creates a TOCTOU window: if another tool call modifies the scratchpad between
validate and execute, the count check is stale. In practice, tool calls are serialized by
the agent loop, so this is not exploitable.

### B.6 DelegateTaskTool -- Clean Sub-Agent Integration

Validates agent exists in registry, builds `SubAgentRequest`, emits lifecycle events.
The `runnerFactory` injection enables testing.

**Finding B.6.1 (Low): Always returns `textToolSuccess` even on sub-agent failure.**
Line 179: `textToolSuccess(output = output, ...)` wraps the failure message in a
Success result. This is intentional -- the outer agent needs to see the failure message
and decide what to do. It's not a tool execution failure; it's a sub-agent result.
But it means `data["success"] == false` with `ToolExecutionResult.Success`. Callers
must check the data map, not the result type.

### B.7 RememberExperienceTool -- Memory Gate is Sound

The Layer 4 Memory Gate blocks writes when the foreground app is BLOCKED. This prevents
the agent from recording sensitive data even if it somehow reaches a BLOCKED app screen.

**No issues.** This is defensive-in-depth done well.

### B.8 AskUserTool -- Correct Suspend-Based Flow

Uses `UserResponseChannel.awaitResponse()` with a 5-minute timeout. Timeout returns
Success (not Failure) with guidance text. This is correct -- a timeout is not an error,
it's information.

**Finding B.8.1 (Low): `validate()` checks `responseChannel.hasPending` at validation time.**
This is another TOCTOU pattern -- the pending state could change between validation and
execution. But since tool calls are serialized, this is safe in practice.

### B.9 ShellTool -- Strong Sandboxing

Blocked command list covers destructive and escalation vectors. Concurrent output reading
prevents pipe deadlock. 10s timeout with forced process destruction.

**Finding B.9.1 (Medium): Command blocklist is bypassable.**
Only the first token is checked. `sh -c "rm -rf /"` is blocked (first token = `sh`).
But `cat /etc/passwd | rm -rf /` is NOT blocked (first token = `cat`, pipe target = `rm`).
Also, `env rm -rf /` or `/bin/rm -rf /` path variants: the `substringAfterLast('/')`
handles full paths, but `env` is not in the blocklist.

However, the tool runs within the Android app's sandbox (no root), so the blast radius
is limited to the app's own writable directories. The blocklist is defense-in-depth, not
a security boundary. The LLM prompt also restricts usage to "cat, ls, stat".

**Finding B.9.2 (Low): Output truncation is silent.**
If output exceeds 4096 chars, it is silently truncated without notification. The LLM
receives a cut-off output with no indicator that data was lost.

### B.10 UIActionInvocation -- Scroll Boundary Detection

`detectScrollBoundary` compares sorted text+description+bounds of pre/post snapshots.
This is a duplicate of `UiChangeDetector.detectScrollBoundary()`.

**Finding B.10.1 (Low): Only triggered for `UIAction.Swipe`, which is only used by
WaitTool... no, Wait uses `UIAction.Wait`.** Actually, checking the code: `UIActionInvocation`
is used by SystemButtonTool and WaitTool. SystemButton produces `UIAction.SystemButton(...)`,
Wait produces `UIAction.Wait(...)`. Neither produces `UIAction.Swipe`. So the scroll
boundary detection in `UIActionInvocation` is dead code -- it can never trigger because
`uiAction is UIAction.Swipe` is always false for its callers.

### B.11 DataQueryInvocation -- Currently Unused?

This generic invocation handler exists but no tool in `tool/impl/` uses it. It may be
used by tools registered elsewhere, or it may be vestigial from a removed `list_apps` tool.

### B.12 TargetResolver -- Pure and Correct

Stateless object with clear resolution logic. Text matching normalizes for comparison
and falls back to `description` and `hintText`. The `mergedText` helper from the
perception layer provides consistent text representation.

**No issues.**

### B.13 PointActionExecutorCore -- Sophisticated but Justified

The `refinePointActionTarget` logic that promotes from non-clickable elements to their
nearest clickable container (with ambiguity detection) is the most complex code in the
module. It handles real Android UI patterns where the LLM targets a text label inside a
clickable row.

The ambiguity guard (`AMBIGUITY_DISTANCE_RATIO`) prevents misrouting to overflow/toggle
controls when multiple clickable children are similarly close. This is empirically tuned.

**Finding B.13.1 (Low): `PerceptionElement` properties `center`, `isClickable`,
`isLongClickable` are assumed to exist but defined elsewhere.** The action layer depends
on the model layer's contract. This is fine but means the action layer is coupled to
`PerceptionElement`'s field names.

### B.14 ActionPriorityOrder -- Clean Configuration

Single source of truth for channel ordering. Easy to swap priorities. Scroll order was
recently changed (gesture-first based on autotune results). The enum-per-action pattern
prevents mixing channel types across actions.

**No issues.**

---

## Synthesis

### Strengths
1. **Pipeline is genuinely minimal.** No unnecessary abstractions, adapters, or strategy patterns.
2. **Security model is layered correctly.** AppClassifier -> PolicyEngine -> MemoryGate -> screen masking.
3. **Action fallback chains are well-designed.** Dual-channel with configurable priority, attempt trail for debugging.
4. **State machine is correct.** No impossible states representable. Terminal states are properly handled.
5. **Tools are consistent.** Every ToolSpec follows the same validate-then-create pattern with similar error messages.

### Weaknesses
1. **ToolName sealed class is incomplete** (AskUser, Shell missing). This causes policy misclassification.
2. **MobileActionName may be vestigial.** Its only production usage is in `PolicyEngine.isEscape()`.
3. **Duplicated logic:** scroll boundary detection in both `UiChangeDetector` and `UIActionInvocation`, with the latter being dead code.
4. **ShellTool blocklist is bypassable** via pipes and `env`. Low severity due to Android sandbox.
5. **Minor allocation waste:** Executor classes instantiated per call instead of reused.

### Risk Assessment
- **No critical bugs found.**
- **No security bypasses in the policy engine** (BLOCKED is a hard floor, escape actions are correctly whitelisted).
- **The Unknown fallback in ToolName defaulting to isScreenChanging=true is safe** but causes unnecessary approval prompts.
