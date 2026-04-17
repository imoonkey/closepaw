# 0001 CLAUDE — Proposed Merged Improvement Plan

Both cross-reviews independently chose CODEX as the better base. The convergence is strong: both reviews agree on priority ordering, what to keep, and what to revise. Rather than debate the base choice, I'm proposing the merged plan directly.

## Merged Plan

### Phase 0: Secure Observation Boundary (Critical)

**From Codex.** Centralize screen capture in the tool module behind one observation gate.

1. Create `ObservationGate` (or similar) in `tool/` that wraps `platform.captureScreen()`.
2. Gate checks foreground package at capture time and applies `AppClassifier.maskIfBlocked()`.
3. Replace all direct `captureScreen()` calls in tool/ through this gate:
   - `OpenAppTool` post-launch capture
   - `UIActionInvocation` post-action capture
   - `PostActionAnalysis` retry captures
   - `ToolRouter` post-approval refresh
4. Make `open_app` destination-aware: resolve target package before execution, re-check policy against destination tier. If resolution is only possible inside execution, do an internal policy re-check immediately before `launchApp()`.

**Acceptance tests:**
- `open_app` from NORMAL to BLOCKED is denied before launch
- Any action landing on BLOCKED returns masked observation
- No raw `captureScreen()` in `tool/` outside the gate

### Phase 1: Move Capability Metadata Onto ToolSpec (High)

**From Codex, scoped down per both cross-reviews.**

Add minimal metadata to `ToolSpec`:

```kotlin
interface ToolSpec {
    // existing: name, description, parameterSchema, validate, createInvocation, toFunctionSchema
    val isScreenChanging: Boolean get() = true  // safe default
    val capturesScreen: Boolean get() = false
    val mayLaunchApp: Boolean get() = false
}
```

Each tool declares its own metadata. PolicyEngine, TurnToolPolicy, ActionSignature all consume from ToolSpec directly instead of `ToolName.isScreenChanging`.

Keep `ToolName` temporarily for display/UI purposes only. Remove behavioral queries from it.

**Why minimal:** Only 3 booleans have actual consumers today. `LoopSignatureKind`, `ToolCategory`, `mayAskUser` have at most one consumer each — add them when a real need arises.

### Phase 2: Normalize Action Runtime (Medium)

**From Codex.**

1. **Cancellation consistency:** Map platform cancellation to `Cancelled` (not `Failed`) in `SwipeExecutor` and `TypeExecutor`.
2. **Explicit-target scroll:** When caller specifies `element_index` or `text` and resolution fails, return an error instead of silently falling back to full-screen scroll.
3. **Retargeting observability:** When `refinePointActionTarget()` promotes to a container or nearby child, include a note in the attempt trail. Keep retargeting enabled by default (it solves real Android UI patterns), but make it visible.

### Phase 3: Shell Decision (Medium)

**Revised from both reviews.**

1. Audit current usage: check eval tasks, app skills, and agent prompts for `shell` reliance.
2. If actively used: harden (add `env`, `xargs`, `find` to blocklist; add truncation indicator per Claude's finding).
3. If replaceable: build `read_file`/`list_dir`/`stat_path` typed tools, then gate `shell` behind a feature flag.
4. Do not remove `shell` from default registration until replacement is validated.

### Phase 4: Router Contract Tightening (Low)

**From Codex.**

1. If the router exposes `cancel(callId)` / `cancelAll()`, either:
   - Own per-call cancellation tokens and drive them through execution, OR
   - Rename to `abortPendingApproval()` to reflect actual scope
2. Add tests for: cancelling executing tool, cancellation through type/swipe, approval abort vs execution abort.

### Phase 5: Cleanup Batch (Low)

**From Claude's findings.**

1. Remove dead `UIActionInvocation.detectScrollBoundary()` and `UiChangeDetector.detectScrollBoundary()`.
2. Remove dead `mobile_action(back/home)` escape path from `PolicyEngine.isEscape()`. Evaluate removing vestigial `MobileActionName` entries.
3. Remove duplicate constants from `OpenAppTool` companion.
4. Change `SystemButtonTool` unreachable branch to `error("unreachable")`.
5. Remove `DataQueryInvocation` if unused.
6. Remove doc/todo references from `ActionPriorityOrder` code comments.

## Open Questions

1. **Phase 3 scope:** Do we have data on how frequently `shell` is used in eval/production? This determines harden vs. replace.
2. **Phase 0 timing:** Should the observation gate also handle the capture-retry logic currently in `PostActionAnalysis`, or should PostActionAnalysis call through the gate for each retry individually?

## PROPOSAL

Accept this merged plan. The phases are ordered by system risk. Each phase is independently shippable. If you agree, I suggest we write the final `review.md` and `improvement_plan.md` directly.

NEXT=CODEX
