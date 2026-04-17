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

### Validated Scope

**[Post-validation]**: Only `PostActionAnalysis` is unprotected. `UIActionInvocation` and `OpenAppTool` already pass `appClassifier` to `buildObservation`. Phase 0 is narrower than originally scoped.

### Changes

1. Thread `appClassifier` through `capturePostActionAnalysis`:
   - Add `appClassifier: AppClassifier? = null` parameter to `capturePostActionAnalysis()`
   - Pass it to `buildObservation(it, platform, appClassifier)` at `PostActionAnalysis.kt:45`
   - Thread from each caller: `PointActionExecutorCore`, `SwipeExecutor`, `TypeExecutor`, `ScrollExecutor`
   - Callers get `appClassifier` from `ToolExecutionContext` (already available)

2. ~~Replace all direct `captureScreen()` calls in `tool/` through a gate~~ **[Dropped]**: UIActionInvocation and OpenAppTool already use `buildObservation` with `appClassifier`. No gate needed — just thread the parameter.

3. Make `open_app` destination-aware:
   - Resolve target package before execution
   - Re-check policy against destination tier
   - If resolution only possible inside execution, do internal policy re-check before `launchApp()`

### Acceptance Tests

- `open_app` from NORMAL to BLOCKED is denied before launch
- Any mobile_action landing on BLOCKED returns masked observation
- No unmasked `buildObservation()` call in `PostActionAnalysis`

### Files Changed

`PostActionAnalysis.kt`, `PointActionExecutorCore.kt`, `SwipeExecutor.kt`, `TypeExecutor.kt`, `ScrollExecutor.kt`, `OpenAppTool.kt`

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

### Phase 1b: Metadata Migration (Optional — not recommended now)

**[Post-validation]**: Only 2 consumers exist (`PolicyEngine`, `TurnToolPolicy`). Phase 1a stopgap is sufficient. Full migration is overkill until more consumers emerge.

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
- ~~`ActionSignature` (replaces `ToolName` lookup)~~ **[Validated: does not exist]**

Keep `ToolName` temporarily for display/UI only. Remove behavioral queries from it.

**Why minimal:** Only 3 booleans have actual consumers. Add more fields when real callers need them.

### Files Changed

`ToolName.kt`, `ToolSpec.kt`, `PolicyEngine.kt`, `TurnToolPolicy.kt`, ~~`ActionSignature.kt`~~, all `tool/impl/*.kt`, new `ToolCapabilitiesResolver.kt`

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
2. Remove dead `mobile_action(back/home)` escape path from `PolicyEngine.isEscape()`. **[Validated]**: `MobileActionName.Back/Home/Wait/SystemButton` members are used by `ToolUi.kt` — only remove the dead policy branch, NOT the enum members
3. Remove duplicate constants from `OpenAppTool` companion (`UI_SETTLE_DELAY_MS`, `SUGGESTION_LIMIT`)
4. Change `SystemButtonTool` unreachable branch to `error("Unreachable: validated in validate()")`
5. Remove `DataQueryInvocation` (zero production callers)
6. Remove stale doc reference from `ActionPriorityOrder` code comment (points to non-existent path)

### Files Changed

`UIActionInvocation.kt`, `UiChangeDetector.kt`, `PolicyEngine.kt`, `ToolName.kt`, `OpenAppTool.kt`, `SystemButtonTool.kt`, `DataQueryInvocation.kt`, `ActionPriorityOrder.kt`

---

## Summary

| Phase | Priority | Scope | Key Metric |
|-------|----------|-------|------------|
| 0 | Critical | Security boundary | Zero unmasked BLOCKED captures in PostActionAnalysis |
| 1a | High | Stopgap | ask_user/shell classified correctly |
| 1b | Optional | Metadata | Deferred — only 2 consumers, stopgap is sufficient |
| 2 | Medium | Action runtime | Consistent cancellation + explicit scroll failure |
| 3 | Medium | Shell | Hardened blocklist + truncation indicator |
| 4 | Low | Router | Cancel APIs match actual scope |
| 5 | Low | Cleanup | ~60 lines dead code removed |

**Each phase is independently shippable.** Phase 0 is narrower than originally planned (no new `ScreenCaptureGate` needed). Phase 1b is deferred.
