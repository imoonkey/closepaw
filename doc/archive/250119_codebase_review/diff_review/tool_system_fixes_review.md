# Diff Review: Tool System Fixes

> **Reviewer**: Code review following `sop/diff_review.md`
> **Files Reviewed**: `ToolRouter.kt`, `ToolRegistry.kt`, `BaseTool.kt`, `WaitTool.kt`, `SwipeTool.kt`, `tool_system_summary.md`
> **Source**: Fixes for `doc/review/summary/tool_system_summary.md`

---

## 1) Summary

These changes implement fixes for issues from `doc/review/summary/tool_system_summary.md`:

1. **Approval timeout with proper message** - Adds 60-second timeout to approval wait, returns "Approval timed out" (Issue 1)
2. **Stale snapshot refresh after approval** - Re-captures snapshot if approval was required (Issue 4)
3. **activeToolCalls cleanup in finally** - Ensures cleanup on abnormal exit (M1)
4. **resolveApproval returns Boolean** - Caller can detect if approval was found (M3)
5. **ToolRegistry documentation fix** - Docs now match behavior (warn and overwrite) (M4)
6. **BaseTool.createUIAction() documentation** - Explains nullable return (M5)
7. **Parameter type validation** - Validates duration_ms is numeric in WaitTool/SwipeTool (M8)
8. **No-op swipe validation** - SwipeTool rejects start==end coordinates (M10)

---

## 2) High-Risk Issues - All Fixed

### Issue 1. Approval Timeout Can Block Forever ✅

**Files Changed**: `ToolRouter.kt`

**Fix**: Added `APPROVAL_TIMEOUT_MS` constant (60 seconds). Wraps `deferred.await()` with `withTimeout()`. On timeout, returns `ToolCallResult.Cancelled` with reason "Approval timed out" directly (not via DENIED path).

```kotlin
val decision = try {
    withTimeout(APPROVAL_TIMEOUT_MS) {
        deferred.await()
    }
} catch (e: TimeoutCancellationException) {
    // Returns directly with proper message
    return ToolCallResult.Cancelled(resolvedCallId, "Approval timed out")
}
```

---

### Issue 4. Stale Snapshot After Approval Delay ✅

**Files Changed**: `ToolRouter.kt`

**Fix**: Added `approvalWasRequired` flag. After approval granted, sets flag to true. Before execution, if flag is set, re-captures snapshot via `context.platform.captureScreen()`.

```kotlin
val executionSnapshot = if (approvalWasRequired) {
    Log.d(TAG, "Re-capturing snapshot after approval wait")
    context.platform.captureScreen()
} else {
    context.currentSnapshot
}
```

---

## 3) Medium Issues Fixed

### M1. activeToolCalls Never Cleaned on Abnormal Exit ✅

**File**: `ToolRouter.kt`

**Fix**: Wrapped terminal state handling in `try-finally` that calls `activeToolCalls.remove(resolvedCallId)`.

```kotlin
return try {
    when (executionResult) { ... }
} finally {
    activeToolCalls.remove(resolvedCallId)
}
```

---

### M3. ToolRouter.resolveApproval() Silently Fails ✅

**File**: `ToolRouter.kt`

**Fix**: Changed return type from `Unit` to `Boolean`. Returns `true` if approval resolved, `false` if callId not found.

---

### M4. ToolRegistry.register() Allows Silent Overwrite ✅

**File**: `ToolRegistry.kt`

**Fix**: Updated KDoc to document actual behavior: "logs a warning and overwrites it".

---

### M5. BaseTool.createUIAction() Returns Nullable Without Explanation ✅

**File**: `BaseTool.kt`

**Fix**: Added comprehensive KDoc explaining when/why null may be returned and what happens.

---

### M8. Optional Numeric Parameters Silently Coerce Invalid Types ✅

**Files**: `WaitTool.kt`, `SwipeTool.kt`

**Fix**: Added explicit type validation checking `!is Int && !is Long && !is Double` when parameter is present.

---

### M10. SwipeTool Allows No-Op Swipes ✅

**File**: `SwipeTool.kt`

**Fix**: Added validation that start and end coordinates must be different. Added explanatory comment about null check.

---

## 4) PR Review Feedback Applied

### PR-P1. Type Check `!is Number` Incorrect (Copilot)

**Issue**: `value !is Number` doesn't correctly validate JSON numeric types because JSONObject returns `Int`, `Long`, or `Double`.

**Fix**: Changed to `value !is Int && value !is Long && value !is Double` in both WaitTool and SwipeTool.

---

### PR-P2. Timeout Returns "User denied" Instead of "Approval timed out" (Copilot)

**Issue**: The timeout case set `decision = ApprovalDecision.DENIED`, which then returned "User denied" message.

**Fix**: Timeout now returns directly from the catch block with proper "Approval timed out" message.

---

### PR-P3. Missing Comment for Null Check in SwipeTool (Copilot)

**Issue**: No explanation for why null check was needed before no-op validation.

**Fix**: Added comment explaining that nulls indicate missing/invalid params already reported by validateRequiredInt.

---

### PR-P4. APPROVAL_TIMEOUT_MS Comment Says "Denied" (Copilot)

**Issue**: Comment said "action is denied" but implementation returns Cancelled.

**Fix**: Changed comment to "action is cancelled".

---

### PR-P5. Team Note Says "Default DENIED" (Copilot)

**Issue**: Team Note in Issue 1 said "default DENIED" but implementation returns Cancelled directly.

**Fix**: Updated to "returns `ToolCallResult.Cancelled`".

---

### PR-P6. Line Number References Incorrect (Copilot)

**Issue**: Several line number references in documentation were incorrect after code changes.

**Fix**: Updated M1, M3, M4 location references to match actual code lines.

---

## 5) Issues Already Fixed (From Previous Work)

| Issue | Status | Verification |
|-------|--------|--------------|
| Issue 2. Observation Data Loss | ✅ Already Fixed | `ToolCallResult.Success` has `observation` field |
| Issue 3. AccessibilityNodeInfo Recycling | ✅ Already Fixed | `ScreenSnapshot` no longer stores raw nodes |
| M6. Tool Observation Never Surfaced | ✅ Already Fixed | Same as Issue 2 |
| M7. Call IDs Short and Inconsistent | ✅ Already Fixed | `callId` parameter accepts caller-provided ID |
| M9. Double Screen Capture | ✅ Already Fixed | Agent uses observation from tool result |

---

## 6) Verification Checklist

### Original Issues - Status

| Issue | Status | Verification |
|-------|--------|--------------|
| Issue 1. Approval Timeout | ✅ Fixed | 60s timeout with proper "timed out" message |
| Issue 4. Stale Snapshot | ✅ Fixed | Re-captures if `approvalWasRequired` |
| M1. activeToolCalls Cleanup | ✅ Fixed | `finally` block ensures removal |
| M2. SCHEDULED State | ⏭️ Skipped | Kept for future scheduling semantics |
| M3. resolveApproval Return | ✅ Fixed | Returns `Boolean` |
| M4. ToolRegistry Docs | ✅ Fixed | Docs match behavior |
| M5. createUIAction Docs | ✅ Fixed | KDoc added |
| M8. Parameter Type Validation | ✅ Fixed | Explicit type check |
| M10. No-Op Swipe | ✅ Fixed | Validation added |

### Code Quality

- [x] No linter errors
- [x] Thread safety: timeout uses proper coroutine cancellation
- [x] Error handling: timeout returns proper cancellation message
- [x] Documentation: KDoc updated where needed

---

## 7) Conclusion

All high-priority and medium-priority issues from the tool_system_summary have been addressed:
- Approval timeout prevents infinite blocking with proper "Approval timed out" message
- Stale snapshots are refreshed after approval delay
- Tool call cleanup is guaranteed via finally block
- Parameter validation catches type errors

PR feedback identified the `!is Number` type check issue and timeout message mismatch, both now corrected.

**Verdict**: Tool System fixes complete. Ready for merge.
