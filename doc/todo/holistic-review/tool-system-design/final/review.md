# Tool System Design Review — Final

**Date:** 2026-04-08
**Reviewers:** Claude, Codex (independent reviews + cross-review + alignment)
**Scope:** `app/src/main/kotlin/com/moonkey/androidagent/tool/` (36 files)
**Verdict:** CHANGES_REQUESTED — good skeleton, critical security gap and metadata drift

---

## Architecture

Pipeline: **ToolSpec -> ToolRegistry -> ToolRouter -> PolicyEngine -> ToolInvocation -> Executor**

Five stages, single responsibility each. The design avoids over-abstracting tool execution. `ToolSpec` (declarative what) is correctly separated from `ToolInvocation` (executable how), enabling the approval flow.

**Grade: Good skeleton, incomplete enforcement.**

---

## Critical Findings

### C1. Blocked-App Boundary Not Enforced End-to-End

The security model states that BLOCKED apps are masked and denied. This invariant holds only at turn-start and one approval-refresh path, not at every observation boundary.

**Evidence:**
- `PolicyEngine.check()` evaluates the current foreground package before execution
- `open_app` resolves destination package inside invocation execution, after policy has allowed the call
- Raw `platform.captureScreen()` is called directly in multiple tool-layer paths:
  - `UIActionInvocation` (post-action)
  - `PostActionAnalysis` (retry captures)
  - `OpenAppTool` (post-launch)
- `maskIfBlocked()` is applied only in `ToolRouter` post-approval refresh

**Consequence:** From a NORMAL app, the agent can navigate into a BLOCKED app and receive unmasked content in tool observations.

### C2. ToolName Is Not Canonical — Omissions Change Runtime Behavior

`ask_user` and `shell` are not in `ToolName`. They resolve to `Unknown`, which defaults `isScreenChanging = true`. This metadata is consumed by:
- `PolicyEngine` (policy gating)
- `TurnToolPolicy` (turn arbitration)
- `ActionSignature` (loop detection)

**Consequence:** `ask_user` triggers unnecessary approval prompts on CAUTIOUS apps. Both tools distort turn arbitration and loop detection.

**Root cause:** Capability metadata lives in a parallel enum (`ToolName`) that drifts from registered tools. Metadata should live on `ToolSpec`.

---

## High Findings

### H1. Shell Bypasses Declarative Tool Model

`shell` runs `ProcessBuilder("sh", "-c", command)`. Validation checks only the first token against a blocklist. Shell metacharacters, pipes, and wrappers bypass the check.

The tool is actively used (confirmed in `StandaloneAgentDef.allowedTools` and standalone prompt). The Android sandbox limits blast radius, but the tool breaks the otherwise declarative, typed, policy-checkable tool system.

### H2. Explicit-Target Scroll Silently Degrades

`ScrollExecutor.resolveScrollArea()` falls back to full-display bounds when target resolution fails. A `scroll` call with explicit `element_index` or `text` that can't be resolved silently changes semantics to whole-screen scroll instead of failing.

---

## Medium Findings

### M1. Cancellation Semantics Inconsistent Across Executors

- `PointActionExecutorCore` and `ScrollExecutor` propagate `Cancelled` correctly
- `SwipeExecutor` converts platform cancellation to `Failed`
- `TypeExecutor` collapses cancellation into generic failure trails
- Router's `cancel()`/`cancelAll()` doesn't own per-call cancellation tokens for executing invocations

### M2. ToolSpec Standardizes Inputs But Not Capabilities or Outputs

- Capability decisions delegated to `ToolName.isScreenChanging` instead of tool spec
- Tool success payloads are `data: Any?`
- Post-action capture timing/constants scattered across `UIActionInvocation`, `OpenAppTool`, `PostActionAnalysis`

### M3. Point-Action Retargeting Is Hidden Policy

`refinePointActionTarget()` can promote from a non-clickable element to its nearest clickable container or nearby child. This handles real Android UI patterns but changes target semantics invisibly. The behavior should be observable in the attempt trail.

### M4. TOCTOU After Approval Is Asymmetric

After approval, the router re-checks foreground package. If `packageName` was null and current app is CAUTIOUS, execution proceeds without re-check. Approval context is not fully bound.

---

## Low Findings

### L1. Dead Scroll-Boundary Code

`UIActionInvocation.detectScrollBoundary()` checks `uiAction is UIAction.Swipe`, but UIActionInvocation is only used by SystemButton and Wait tools — neither produces Swipe. Dead code. `UiChangeDetector.detectScrollBoundary()` is also unused.

### L2. MobileActionName Vestigial Members

`PolicyEngine.isEscape()` checks `mobile_action(action=back/home)` but `MobileActionTool` only accepts click/long_press/scroll/swipe/type. The branch can never match.

### L3. Duplicate Constants in OpenAppTool

`UI_SETTLE_DELAY_MS` and `SUGGESTION_LIMIT` declared in both `OpenAppTool` and `OpenAppInvocation` companions; only invocation ones used.

### L4. SystemButtonTool Unreachable Fallback

`else -> SystemButtonType.BACK` can never execute because `validate()` rejects unknown buttons. Should be `error("unreachable")`.

### L5. DataQueryInvocation Appears Unused

Generic invocation handler with no production callers.

### L6. Shell Output Truncation Is Silent

Output exceeding 4096 chars is truncated without indicator. LLM receives incomplete data.

### L7. Executor Per-Call Allocation

`ClickExecutor`, `LongPressExecutor`, etc. are stateless but instantiated per `createInvocation()` call.

### L8. Scheduled State Is Ephemeral

`Scheduled` in the router state machine exists only as UI notification. Nothing queries it.

---

## Strengths

1. **Pipeline is genuinely minimal.** No unnecessary abstractions or strategy patterns.
2. **Security model is layered.** AppClassifier -> PolicyEngine -> MemoryGate -> screen masking (enforcement gap aside).
3. **Action fallback chains are well-designed.** Dual-channel with configurable priority, attempt trails for debugging.
4. **State machine is correct.** No impossible states representable.
5. **Tools are consistent at input level.** Every ToolSpec follows validate-then-create with similar error messages.
6. **TargetResolver is pure and correct.** Stateless, clear resolution logic.
7. **RememberExperienceTool Layer 4 gate** is defensive-in-depth done right.
