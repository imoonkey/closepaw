# Tool System Improvement Plan

Prioritized action items from the design review. Ordered by impact/effort ratio.

---

## P0: Fix Before Next Release

### 1. Add AskUser and Shell to ToolName sealed class

**Problem:** `ask_user` and `shell` are not enumerated in `ToolName`. They resolve to
`Unknown`, which defaults `isScreenChanging = true`. This causes unnecessary approval
prompts in SMART mode on CAUTIOUS apps.

**Fix:** Add two new `data object` entries to the `ToolName` sealed class.

```
File: tool/ToolName.kt

+ data object AskUser : ToolName(
+     raw = "ask_user", canonical = "ask_user", displayName = "Ask user"
+ )
+ data object Shell : ToolName(
+     raw = "shell", canonical = "shell", displayName = "Shell"
+ )

// Update isScreenChanging:
  val isScreenChanging: Boolean get() = when (this) {
      MobileAction, OpenApp, Wait, SystemButton, DelegateTask -> true
-     CompleteTask, WriteTodos, Scratchpad, RememberExperience -> false
+     CompleteTask, WriteTodos, Scratchpad, RememberExperience, AskUser, Shell -> false
      is Unknown -> true
  }

// Update from() lookup
```

**Effort:** 15 minutes. **Impact:** Eliminates false approval prompts for two tools.

---

## P1: Clean Up Dead Code

### 2. Remove dead scroll boundary detection from UIActionInvocation

**Problem:** `UIActionInvocation.detectScrollBoundary()` (lines 87-110) checks
`if (uiAction is UIAction.Swipe)`, but UIActionInvocation is only used by
SystemButtonTool and WaitTool -- neither produces a Swipe action. The method is dead code.

`UiChangeDetector.detectScrollBoundary()` is also unused (the ScrollExecutor uses its
own change detection via `capturePostActionAnalysis`).

**Fix:**
- Delete `detectScrollBoundary()` from `UIActionInvocation`.
- Delete the `scrollBoundaryWarning` variable and its usage (lines 53-65).
- Delete `UiChangeDetector.detectScrollBoundary()` if no callers remain.
- Delete the `preSnapshot` capture on line 46 (no longer needed).

**Effort:** 10 minutes. **Impact:** Reduces confusion and ~25 dead lines.

### 3. Remove dead MobileActionName entries or consolidate escape check

**Problem:** `MobileActionName` has entries for `Back`, `Home`, `Wait`, `SystemButton`
that correspond to separate tools in the current design. The only production usage of
`MobileActionName` outside of `MobileActionTool.validate()` is in `PolicyEngine.isEscape()`,
which checks for `mobile_action(action=back/home)` -- but MobileActionTool only accepts
`click, long_press, scroll, swipe, type`. So the mobile_action branch in `isEscape()` can
never match a valid tool call.

**Fix:**
- In `PolicyEngine.isEscape()`, remove the `mobile_action` fallback branch (lines 134-137).
- Evaluate whether `MobileActionName.Back`, `.Home`, `.Wait`, `.SystemButton` can be removed
  from the sealed class. Keep them only if tests or non-production code still references them.

**Effort:** 15 minutes. **Impact:** Removes confusing dead path in security-critical code.

### 4. Remove duplicate constants in OpenAppTool

**Problem:** `OpenAppTool` companion declares `UI_SETTLE_DELAY_MS` and `SUGGESTION_LIMIT`
but only the `OpenAppInvocation` companion values are used.

**Fix:** Delete the unused constants from `OpenAppTool.Companion`.

**Effort:** 2 minutes. **Impact:** Removes dead declarations.

---

## P2: Low-Effort Quality Improvements

### 5. Make action executors reusable (singleton or val)

**Problem:** `ClickExecutor()`, `LongPressExecutor()`, `TypeExecutor()`, `ScrollExecutor()`,
`SwipeExecutor()` are instantiated per `createInvocation()` call. They are stateless.

**Fix:** Either:
- (a) Make each executor an `object` (they have no mutable state).
- (b) Hold them as `private val` fields on `MobileActionTool`.

Option (b) is simpler since the executors take a `TargetResolver` constructor param.

```kotlin
class MobileActionTool : ToolSpec {
    private val clickExecutor = ClickExecutor()
    private val longPressExecutor = LongPressExecutor()
    private val typeExecutor = TypeExecutor()
    private val scrollExecutor = ScrollExecutor()
    private val swipeExecutor = SwipeExecutor()
    ...
}
```

**Effort:** 5 minutes. **Impact:** Avoids per-call allocation, minor GC improvement.

### 6. Add truncation indicator to ShellTool output

**Problem:** When shell output exceeds 4096 chars, it is silently truncated. The LLM
receives incomplete data with no indication.

**Fix:** After reading, if `length >= MAX_OUTPUT_CHARS`, append `\n[output truncated at $MAX_OUTPUT_CHARS chars]`.

**Effort:** 5 minutes. **Impact:** Prevents LLM reasoning on incomplete data.

### 7. Clarify SystemButtonTool unreachable branch

**Problem:** `else -> SystemButtonType.BACK` in the `when` block can never execute
because `validate()` already rejects unknown buttons.

**Fix:** Change to `else -> error("Unreachable: validated in validate()")` to fail
loudly if the invariant is ever broken.

**Effort:** 1 minute. **Impact:** Correctness signal for maintainers.

### 8. Avoid double-parse in WriteTodosTool

**Problem:** `validate()` and `createInvocation()` both call `parseTodos()`.

**Fix:** Cache the parsed todos in a temporary field, or restructure to parse once.
Since `validate()` and `createInvocation()` are called sequentially on the same params,
the simplest fix is to accept the double-parse as intentional (ToolSpec is stateless by
contract). Alternatively, validate could return the parsed data.

**Decision:** Accept as-is. The stateless ToolSpec contract is more valuable than
saving a trivial parse. Document the intentional double-parse with a comment.

**Effort:** 1 minute (comment only). **Impact:** Prevents future "optimization" attempts
that would break the stateless contract.

---

## P3: Deferred / Monitor

### 9. ShellTool blocklist hardening

**Problem:** The first-token check can be bypassed with pipes (`cat x | rm y`) or
wrappers (`env rm`).

**Options:**
- (a) Parse the full command for all tokens (complex, fragile with shell syntax).
- (b) Add `env`, `xargs`, `find -exec` to the blocklist.
- (c) Accept current risk level -- Android sandbox limits blast radius.

**Recommendation:** Option (b) for incremental hardening. Do not pursue full shell
parsing (option a) as it is unbounded complexity.

**Effort:** 10 minutes for (b). **Impact:** Closes obvious bypass vectors.

### 10. Consolidate ToolExecutionResult / ToolCallState / ToolCallResult success types

**Problem:** Three layers of success types with field duplication.

**Assessment:** This is the natural cost of the state-machine + result-return pattern.
`ToolExecutionResult` is the executor's contract, `ToolCallState` is the observable
lifecycle, `ToolCallResult` is the caller's contract. Merging any two would conflate
their audiences.

**Decision:** No action. The triplication is justified by separation of concerns.

### 11. Consider making ToolRouter snapshot re-capture a context responsibility

**Problem:** ToolRouter directly calls `platform.captureScreen()` after approval wait
(A.3.3). This is the only platform-aware logic in the router.

**Assessment:** Moving this to `ToolExecutionContext` or an `onPreExecute` hook would
keep the router pure. But it adds a hook point for one caller. Not worth the abstraction.

**Decision:** No action. Document the design choice.

### 12. AppClassifier.addUserOverride ordinal semantics

**Problem:** The tightening check `tier.ordinal >= current.ordinal` is correct but
reads counter-intuitively because BLOCKED(0) < NORMAL(2) in ordinal but BLOCKED is
more restrictive.

**Fix:** Add a brief inline comment explaining the ordinal mapping.

**Effort:** 1 minute. **Impact:** Prevents misreading by future maintainers.

---

## Summary

| Priority | Item | Effort | Files Changed |
|----------|------|--------|---------------|
| P0 | Add AskUser/Shell to ToolName | 15 min | ToolName.kt |
| P1 | Remove dead scroll boundary code | 10 min | UIActionInvocation.kt, UiChangeDetector.kt |
| P1 | Clean up MobileActionName escape path | 15 min | PolicyEngine.kt, ToolName.kt |
| P1 | Remove OpenAppTool duplicate constants | 2 min | OpenAppTool.kt |
| P2 | Reuse executor instances | 5 min | MobileActionTool.kt |
| P2 | Shell output truncation indicator | 5 min | ShellTool.kt |
| P2 | SystemButton unreachable branch | 1 min | SystemButtonTool.kt |
| P2 | Document double-parse in WriteTodosTool | 1 min | WriteTodosTool.kt |
| P3 | Shell blocklist hardening | 10 min | ShellTool.kt |
| P3 | AppClassifier ordinal comment | 1 min | AppClassifier.kt |

**Total estimated effort for P0+P1+P2:** ~55 minutes.
**Reduction opportunities:** ~50 lines of dead code removed. No new files or abstractions needed.
