# Tool System - Consolidated Review Summary

> **Files**: `infra/tools/*.kt`, `infra/registry/ToolRegistry.kt`, `tools/base/BaseTool.kt`, `tools/impl/*.kt`
> **Reviewers**: Claude, Codex, Gemini

## High-Risk Issues (Must Fix)

### 1. Approval Timeout Can Block Forever
**Consensus**: Claude, Codex
**Location**: `ToolRouter.kt:130-131`

**Problem**: When awaiting user approval, code blocks indefinitely:
```kotlin
val decision = deferred.await()  // BLOCKS FOREVER if user never responds
```

If user closes approval dialog, navigates away, or app crashes, the agent loop stalls permanently.

**Impact**: Agent becomes unresponsive, coroutine leak, poor UX.

**Fix**: Add timeout (e.g., 60 seconds) with default deny policy:
```kotlin
val decision = withTimeout(APPROVAL_TIMEOUT_MS) { deferred.await() }
```

**Team Note**: Fix it. Add 60-second timeout that returns `ToolCallResult.Cancelled`. Catch `TimeoutCancellationException` and return `ToolCallResult.Cancelled` with reason "Approval timed out".

---

### 2. Observation Data Loss in ToolRouter
**Consensus**: All three reviewers
**Location**: `ToolRouter.kt:196-201`, `ToolCallResult.kt:16-22`

**Problem**: `ToolRouter.execute()` returns `ToolCallResult.Success` but drops the `observation` field from `ToolExecutionResult`. The screen capture in BaseTool is lost, forcing Agent to re-capture.

**Impact**: "Double Observation" performance issue - wastes time and tokens.

**Fix**: Add `observation` field to `ToolCallResult.Success` and propagate it.

**Team Note**: ✅ FIXED. `ToolCallResult.Success` now has `observation` field, and `ToolRouter.execute()` propagates `executionResult.observation`. Agent.kt uses the observation from tool result instead of re-capturing (lines 225-238).

---

### 3. AccessibilityNodeInfo Retained Without Recycling
**Consensus**: Codex, Gemini (also in Platform review)
**Location**: `Perceptor.kt`, `Models.kt`

**Problem**: `AccessibilityNodeInfo` objects stored in `ScreenSnapshot.rawMap` without recycling. These have strict lifecycle requirements and become invalid after UI changes.

**Impact**: Memory leaks, stale node crashes, long session degradation.

**Fix**: Don't store raw nodes long-term. Store stable selectors (resource id + bounds + class) and re-resolve at execution time.

**Team Note**: ✅ FIXED. `ScreenSnapshot` no longer stores `rootOriginal` or `rawMap`. Only `timestamp` and `elements` are retained. `Perceptor.traverse()` now properly recycles child nodes with `shouldRecycle` parameter. Actions use stored bounds/center coordinates for gestures and re-query tree for text input.

---

### 4. Stale Snapshot After Approval Delay
**Reviewer**: Claude
**Location**: `ToolRouter.kt:179-184`

**Problem**: Execution context uses snapshot from validation time. If approval wait takes time, snapshot becomes stale. Tools using element indices will reference incorrect elements.

**Impact**: Wrong element clicked/typed after approval delay.

**Fix**: Re-capture snapshot before execution if approval was required.

**Team Note**: Fix it. After approval is granted (line 153-156), re-capture snapshot via `context.platform.captureScreen()` before execution. This ensures element indices are valid after approval delay.

---

## Medium Issues (Should Fix)

### M1. activeToolCalls Never Cleaned on Abnormal Exit
**Reviewer**: Claude
**Location**: `ToolRouter.kt:247-250`

States are removed only on terminal results. If execution exits abnormally (exception before terminal state), call remains in activeToolCalls forever.

**Fix**: Ensure cleanup in finally block.

**Team Note**: Fix it. Wrap the terminal state handling in a `try-finally` so that the cleanup in the `finally` block calls `activeToolCalls.remove(resolvedCallId)` to ensure cleanup.

---

### M2. SCHEDULED State Is Meaningless
**Reviewer**: Claude
**Location**: `ToolRouter.kt:159-163`

When policy allows auto-execution, state transitions Scheduled → Executing instantly. "Scheduled" state emitted but never actually scheduled.

**Fix**: Either remove Scheduled state or add actual scheduling semantics.

**Team Note**: Skip for now. The Scheduled state provides a consistent state machine regardless of approval path. Keep it for future scheduling semantics (e.g., queueing multiple tools).

---

### M3. ToolRouter.resolveApproval() Silently Fails
**Reviewer**: Claude
**Location**: `ToolRouter.kt:262-272`

If approval resolution called for unknown callId, only logs warning. User thinks approval handled but it wasn't.

**Fix**: Return success/failure status.

**Team Note**: Fix it. Change return type to `Boolean` - return `true` if approval resolved, `false` if callId not found. Caller can handle appropriately (e.g., show error to user).

---

### M4. ToolRegistry.register() Allows Silent Overwrite
**Reviewer**: Claude
**Location**: `ToolRegistry.kt:28-42`

Documentation says throws `IllegalArgumentException` on duplicate, but actually just warns and overwrites.

**Fix**: Match documentation (throw) or change docs.

**Team Note**: Fix documentation. Update KDoc to say "logs warning and overwrites" instead of "throws". Overwriting allows easy re-registration during development/testing.

---

### M5. BaseTool.createUIAction() Returns Nullable Without Explanation
**Reviewer**: Claude
**Location**: `BaseTool.kt:26`

No documentation on when/why it should return null. Tools return null on validation-like conditions that validate() should have caught.

**Fix**: Make non-nullable since validation ensures valid state, or document clearly.

**Team Note**: Fix with documentation. Add KDoc explaining: "Returns null if action cannot be created despite validation passing (e.g., defensive fallback). Invocation will return Failure if null." SwipeTool returns null for negative coords as defensive check.

---

### M6. Tool Observation Captured but Never Surfaced
**Consensus**: Codex (also Issue 2 above)
**Location**: `BaseTool.kt:199-211`, `ToolCallResult.kt:16-22`

BaseTool captures observation, but ToolCallResult drops it and Agent captures again.

**Fix**: Thread ToolObservation through ToolCallResult and use in Agent.formatToolResult().

**Team Note**: ✅ FIXED. Same as Issue 2. `ToolCallResult.Success` now has `observation` field. Agent.kt (lines 225-238) uses observation from tool result.

---

### M7. Call IDs Short and Inconsistent
**Reviewer**: Codex
**Location**: `ToolRouter.kt:62, 66, 280`

8-char IDs increase collision risk and make correlation with LLM tool call ids difficult.

**Fix**: Use full UUIDs or accept caller-provided IDs.

**Team Note**: ✅ FIXED. `ToolRouter.execute()` now accepts optional `callId` parameter (line 62). Agent passes LLM's `toolCall.id` to router (Agent.kt:200). Short 8-char ID is only fallback when caller doesn't provide one.

---

### M8. Optional Numeric Parameters Silently Coerce Invalid Types
**Reviewer**: Codex
**Location**: `WaitTool.kt:33`, `SwipeTool.kt:37`

`optLong/optInt` return defaults for non-numeric input, allowing invalid tool calls through validation.

**Fix**: Explicitly validate type when parameter present.

**Team Note**: Fix it. In validation, if `params.has("duration_ms")`, check that `params.get("duration_ms")` is a `Number`, else add error. This catches `"duration_ms": "hello"` which currently silently uses default.

---

### M9. Double Screen Capture in Tools
**Reviewer**: Gemini
**Location**: `BaseTool.kt:199-215`

Every tool waits for UI settle and captures screen. Correct for "Tools with Observation" but redundant when Agent also captures.

**Fix**: Once observation flow fixed, remove manual capture in Agent.

**Team Note**: ✅ FIXED. Agent.kt now uses observation from tool result (lines 225-238). Only falls back to manual capture if tool didn't provide observation (e.g., `complete_task` tool).

---

### M10. SwipeTool Allows No-Op Swipes
**Reviewer**: Claude
**Location**: `SwipeTool.kt:49-56`

Coordinates checked for `< 0` but (0,0) to (0,0) is valid. No-op swipe wastes gesture.

**Fix**: Validate start and end are different.

**Team Note**: Fix it. Add validation in `validate()`: if all coords present and startX==endX && startY==endY, add error "start and end coordinates must be different".

---

## Low-Risk Suggestions (Nice to Have)

| Issue | Reviewer | Location | Suggestion |
|-------|----------|----------|------------|
| Tool Call ID too short | Claude | `ToolRouter.kt:273` | Use 12+ characters |
| ValidationResult.Invalid constructor confusion | Claude | `ToolSpec.kt:65-68` | Use factory functions |
| WaitTool validation/coercion duplication | Claude | `WaitTool.kt` | Remove one |
| ScrollTool direction case-insensitivity | Claude | `ScrollTool.kt` | Document in error message |
| HomeTool in BackTool.kt | Claude | `BackTool.kt:34-57` | Split into separate files |
| BaseTool schema helpers type-safety | Claude | `BaseTool.kt:122-138` | Use sealed class for types |
| ToolSpec.toFunctionSchema() caching | Claude | `ToolSpec.kt:45-54` | Cache result for static tools |
| PolicyEngine SMART mode always allows medium risk | Codex | `PolicyEngine.kt` | Add config toggle |
| Scroll gesture hardcoded ratios | Codex | `AccessibilityPlatform.kt` | Use window insets |

---

## Open Questions

1. **Tool cancellation propagation**: When `ToolRouter.cancelAll()` is called during tool execution, does the tool actually stop? `isCancelled()` only checks before execution, not during.

2. **Concurrent tool calls**: Can multiple tool calls be in flight? ConcurrentHashMaps suggest yes, but Agent executes sequentially. Is parallel execution planned?

3. **Tool execution timeout**: No timeout on `platform.performAction()`. What if it hangs? Should there be a timeout?
