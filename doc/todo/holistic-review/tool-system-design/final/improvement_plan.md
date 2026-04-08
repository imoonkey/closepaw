# Tool System Improvement Plan — Final

**Date:** 2026-04-08
**Agreed by:** Claude + Codex (double-design alignment)
**Base:** Codex's architectural direction, merged with Claude's implementation-level findings

---

## Guiding Principles

- KISS over framework growth
- Capabilities come from tool definitions, not parallel enums
- Every screen capture goes through one gate
- Explicit targets fail explicitly
- Cancellation means the same thing across all tools

---

## Phase 0: Secure The Observation Boundary (Critical)

### Objective

Make "blocked apps are masked and denied" true at every tool-layer capture point.

### Changes

1. Create `ScreenCaptureGate` (or `SnapshotGate`) in `tool/`:
   - Wraps `platform.captureScreen()`
   - Reads current foreground package at capture time
   - Applies `AppClassifier.maskIfBlocked()`
   - Returns sanitized snapshot
   - Does NOT own retry logic or observation building

2. Replace all direct `captureScreen()` calls in `tool/` through this gate:
   - `OpenAppTool` post-launch capture
   - `UIActionInvocation` post-action capture
   - `PostActionAnalysis` retry captures (PostActionAnalysis calls gate per retry)
   - `ToolRouter` post-approval refresh

3. Make `open_app` destination-aware:
   - Resolve target package before execution
   - Re-check policy against destination tier
   - If resolution only possible inside execution, do internal policy re-check before `launchApp()`

### Acceptance Tests

- `open_app` from NORMAL to BLOCKED is denied before launch
- Any action landing on BLOCKED returns masked observation
- No raw `captureScreen()` in `tool/` outside the gate

### Files Changed

`ToolRouter.kt`, `OpenAppTool.kt`, `UIActionInvocation.kt`, `PostActionAnalysis.kt`, new `ScreenCaptureGate.kt`

---

## Phase 1: Move Capability Metadata Onto ToolSpec (High)

### Phase 1a: Immediate Stopgap

Patch `ToolName` for `ask_user` and `shell`:

```kotlin
// ToolName.kt
data object AskUser : ToolName(raw = "ask_user", canonical = "ask_user", displayName = "Ask user")
data object Shell : ToolName(raw = "shell", canonical = "shell", displayName = "Shell")

// isScreenChanging:
CompleteTask, WriteTodos, Scratchpad, RememberExperience, AskUser, Shell -> false
```

**Effort:** 15 minutes. Eliminates false approval prompts immediately.

### Phase 1b: Metadata Migration

Add minimal metadata to `ToolSpec`:

```kotlin
interface ToolSpec {
    // existing members...
    val isScreenChanging: Boolean get() = true   // safe default
    val capturesScreen: Boolean get() = false
    val mayLaunchApp: Boolean get() = false
}
```

Each tool declares its own metadata.

Build `ToolCapabilitiesResolver` from registered tools at session bootstrap. Inject it into:
- `PolicyEngine` (replaces `ToolName.isScreenChanging`)
- `TurnToolPolicy` (replaces `ToolName` lookup)
- `ActionSignature` (replaces `ToolName` lookup)

Keep `ToolName` temporarily for display/UI only. Remove behavioral queries from it.

**Why minimal:** Only 3 booleans have actual consumers. Add more fields when real callers need them.

### Files Changed

`ToolName.kt`, `ToolSpec.kt`, `PolicyEngine.kt`, `TurnToolPolicy.kt`, `ActionSignature.kt`, all `tool/impl/*.kt`, new `ToolCapabilitiesResolver.kt`

---

## Phase 2: Normalize Action Runtime (Medium)

### Changes

1. **Cancellation consistency:**
   - `SwipeExecutor`: map platform cancellation to `Cancelled`, not `Failed`
   - `TypeExecutor`: preserve `Cancelled` through direct-set, tap-to-focus, and focused-set paths

2. **Explicit-target scroll:**
   - When caller specifies `element_index` or `text` and resolution fails, return error
   - Only targetless scroll may use full-screen bounds

3. **Retargeting observability:**
   - When `refinePointActionTarget()` promotes to container or nearby child, include a note in the attempt trail
   - Keep retargeting enabled by default (solves real Android UI patterns)

### Files Changed

`SwipeExecutor.kt`, `TypeExecutor.kt`, `ScrollExecutor.kt`, `PointActionExecutorCore.kt`

---

## Phase 3: Shell Hardening (Medium)

Shell is confirmed live (`StandaloneAgentDef.allowedTools`, standalone prompt rule 9).

### Changes

1. **Harden blocklist:** Add `env`, `xargs`, `find` (for `-exec`) to blocked commands
2. **Truncation indicator:** When output exceeds `MAX_OUTPUT_CHARS`, append `\n[output truncated at N chars]`
3. **Measure usage:** Track shell invocations and commands in eval/debug runs

### Future (Not This Plan)

- Build typed replacements (`read_file`, `list_dir`, `stat_path`)
- Feature-gate `shell` after replacements validated
- Only then consider removal

### Files Changed

`ShellTool.kt`

---

## Phase 4: Router Contract Tightening (Low)

### Changes

Option A (preferred): Own per-call cancellation tokens, drive them through execution.
Option B (simpler): Rename `cancel()`/`cancelAll()` to `abortPendingApproval()`/`abortAllPendingApprovals()`.

Add tests:
- Cancelling an already-executing tool
- Cancellation propagation through type/swipe
- Approval abort vs execution abort

### Files Changed

`ToolRouter.kt`, `SimpleToolRouterContext.kt`, test files

---

## Phase 5: Cleanup Batch (Low)

1. Remove dead `UIActionInvocation.detectScrollBoundary()` and `UiChangeDetector.detectScrollBoundary()`
2. Remove dead `mobile_action(back/home)` escape path from `PolicyEngine.isEscape()`. Evaluate removing vestigial `MobileActionName` entries (Back, Home, Wait, SystemButton)
3. Remove duplicate constants from `OpenAppTool` companion (`UI_SETTLE_DELAY_MS`, `SUGGESTION_LIMIT`)
4. Change `SystemButtonTool` unreachable branch to `error("Unreachable: validated in validate()")`
5. Remove `DataQueryInvocation` if no callers
6. Remove `doc/todo/...` references from `ActionPriorityOrder` code comments

### Files Changed

`UIActionInvocation.kt`, `UiChangeDetector.kt`, `PolicyEngine.kt`, `ToolName.kt`, `OpenAppTool.kt`, `SystemButtonTool.kt`, `DataQueryInvocation.kt`, `ActionPriorityOrder.kt`

---

## Summary

| Phase | Priority | Scope | Key Metric |
|-------|----------|-------|------------|
| 0 | Critical | Security boundary | Zero unmasked BLOCKED captures |
| 1a | High | Stopgap | ask_user/shell classified correctly |
| 1b | High | Metadata | Zero ToolName behavioral queries |
| 2 | Medium | Action runtime | Consistent cancellation + explicit scroll failure |
| 3 | Medium | Shell | Hardened blocklist + truncation indicator |
| 4 | Low | Router | Cancel APIs match actual scope |
| 5 | Low | Cleanup | ~60 lines dead code removed |

**Each phase is independently shippable.** Phase 0 is the priority — it addresses the only critical design flaw.
