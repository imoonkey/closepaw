# Stage 2 Takeover & Supplement — Code Review

**Date:** 2025-02-12  
**Scope:** Smart Capsule V2 Stage 2 implementation  
**Files:** Op.kt, AgentEvent.kt, AgentSession.kt, AgentService.kt, ServiceOverlayController.kt, SmartCapsuleManager.kt, SmartCapsuleLayoutBuilder.kt

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 1 |
| High | 2 |
| Medium | 6 |
| Low | 4 |

**Recommendation:** **CHANGES_REQUESTED** — Fix the Critical and High issues before merge.

---

## Critical

### [CRITICAL] HistoryManager.addItem is not thread-safe; supplement injection can race with agent turn

**File:** `AgentSession.kt` (via `services.historyManager`)  
**Lines:** 321–326

**Problem:**  
`HistoryManager` uses `private val items = mutableListOf<ResponseItem>()` with no synchronization. `handleSupplement()` is invoked from `AgentSession.submit()`, which runs on `AgentService`'s `scope` (Dispatchers.Main). `AgentTurnRunner` also calls `historyManager.addItem()` from the agent coroutine; when the agent uses `withContext(Dispatchers.IO)` for LLM calls, it can run on another thread. `getAll()` / `forPrompt()` are read while `addItem()` mutates the list, so concurrent access can cause `ConcurrentModificationException` or inconsistent state.

**Fix:**  
Guarantee main-thread (or single-thread) access for HistoryManager, or add synchronization:

```kotlin
// Option A: Synchronize in HistoryManager
private val items = Collections.synchronizedList(mutableListOf<ResponseItem>())
// And synchronize getAll/forPrompt/processItem similarly, or return defensive copies

// Option B: Ensure supplement runs on the same dispatcher as agent (preferred)
// In AgentSession.handleSupplement:
withContext(Dispatchers.Main) {
    services.historyManager.addItem(...)
}
// And ensure AgentTurnRunner always uses Main for history access
```

**Recommendation:** Prefer Option B: run all `HistoryManager` access on the Main dispatcher so it stays single-threaded. Verify `AgentTurnRunner` and other callers do not switch to IO/Default when calling `historyManager`.

---

## High

### [HIGH] renderTakeoverPending omits consistent view state resets

**File:** `SmartCapsuleManager.kt`  
**Lines:** 184–206

**Problem:**  
`renderTakeoverPending` does not explicitly set several view states that other render methods set. This can leave stale state when transitioning from modes like `SupplementInput` or `Error`:

- `v.statusDot.visibility` — not set (relies on previous render)
- `v.supplementInputArea?.visibility` — not set to `View.GONE`
- `v.primaryButton.visibility` — not set
- `v.stopIcon.text` / `v.stopText.text` — not set (could show "✕" / "关闭" from Error)

**Fix:**  
Reset all relevant views in `renderTakeoverPending`:

```kotlin
private fun renderTakeoverPending(v: CapsuleViews, mode: CapsuleMode.TakeoverPending) {
    setDotColor(v, colorAmber, pulsing = false)
    v.statusDot.visibility = View.VISIBLE
    v.thoughtText.text = "正在交接..."
    v.thoughtText.alpha = 1f

    v.row2.visibility = View.VISIBLE
    v.divider.visibility = View.VISIBLE
    v.supplementInputArea?.visibility = View.GONE

    v.supplementButton.visibility = View.VISIBLE
    v.supplementButton.isEnabled = false
    v.supplementButton.alpha = 0.4f

    v.primaryIcon.text = "✋"
    v.primaryText.text = "交接中"
    v.primaryButton.visibility = View.VISIBLE
    v.primaryButton.isEnabled = false
    v.primaryButton.alpha = 0.4f

    v.stopIcon.text = "⏹"
    v.stopText.text = "停止"
    v.stopButton.visibility = View.VISIBLE
    v.stopButton.isEnabled = true
    v.stopButton.alpha = 1f
}
```

---

### [HIGH] onSupplementConfirmed delayed callback can touch detached view

**File:** `SmartCapsuleManager.kt`  
**Lines:** 391–405

**Problem:**  
A `handler.postDelayed` captures `v` (CapsuleViews) and later updates `v.thoughtText.text`. If `hide()` runs before the callback, `views` is set to null and the overlay is removed, but the callback still holds `v` and will run. The `if (views != null)` check prevents a crash, but the callback is never cancelled, so:

1. The handler keeps a reference to the view hierarchy for 1.5s.
2. The callback runs while the view may be detached.

**Fix:**  
Store the runnable and cancel it in `hide()` / `clearDelayedHide()`:

```kotlin
private var supplementConfirmedRunnable: Runnable? = null

fun onSupplementConfirmed() {
    val previousMode = (mode as? CapsuleMode.SupplementInput)?.previousMode
    if (previousMode != null) {
        updateMode(previousMode)
    }
    val v = views ?: return
    val originalText = v.thoughtText.text.toString()
    v.thoughtText.text = "✓ 已收到"
    supplementConfirmedRunnable?.let { handler.removeCallbacks(it) }
    val runnable = Runnable {
        views?.thoughtText?.text = originalText
        supplementConfirmedRunnable = null
    }
    supplementConfirmedRunnable = runnable
    handler.postDelayed(runnable, 1500)
}

// In hide():
supplementConfirmedRunnable?.let { handler.removeCallbacks(it) }
supplementConfirmedRunnable = null
```

---

---

## Medium

### [MEDIUM] TakeoverPending → Takeover transition race if TaskCompleted arrives first

**File:** `ServiceOverlayController.kt` → `SmartCapsuleManager.kt`

**Problem:**  
User taps Takeover → `TakeoverPending` → `onTakeover` → `SessionTakeover` event. If `TaskCompleted` is emitted before or during the takeover (e.g. agent finishes right as user takes over), `onTaskCompleted()` calls `updateMode(CapsuleMode.Done(...))` before `onSessionTakeover()`. The capsule would show Done instead of Takeover.

**Fix:**  
Ensure takeover has higher priority when both can apply. In `ServiceOverlayController.onTaskCompleted`, check if we are in takeover/paused state before switching to Done:

```kotlin
fun onTaskCompleted(reason: CompletionReason) {
    hasActiveTask = false
    currentTaskInput = null
    currentGlowState = GlowState.Success

    when (platformMode) {
        PlatformMode.ACCESSIBILITY -> {
            // If in takeover flow, SessionTakeover may arrive after TaskCompleted.
            // Defer Done until we're sure we're not in takeover.
            edgeGlowManager.updateState(GlowState.Success)
            capsuleManager.onTaskCompleted()
            // Consider: capsuleManager.onTaskCompleted(reason, inTakeoverFlow = currentGlowState == GlowState.Paused)
        }
        // ...
    }
}
```

`SmartCapsuleManager.onTaskCompleted()` could delay or skip Done when `mode is CapsuleMode.TakeoverPending`.

---

### [MEDIUM] Supplement sent during TakeoverPending has no explicit rejection

**File:** `AgentSession.kt`  
**Lines:** 314–332

**Problem:**  
`handleSupplement` allows supplement when state is `Running` or `Paused`. During TakeoverPending, the session is still `Running` until `handleTakeover` completes. So supplement during TakeoverPending is accepted. That is correct per the doc ("Valid in: Running or Paused"). The only concern is UI: if the user taps 补充 while in TakeoverPending, the supplement button is disabled, so this is mostly theoretical. If supplement is triggered by another path, it would still be accepted.

**Fix:**  
No change required. Document that supplement is valid during TakeoverPending because the session remains Running until takeover completes.

---

### [MEDIUM] onSessionResumed hardcodes Chinese status text

**File:** `ServiceOverlayController.kt`  
**Lines:** 284–288

**Problem:**  
`capsuleManager.updateMode(CapsuleMode.Running("思考中..."))` uses a hardcoded string. Other modes use various localized or configurable strings. This should eventually use resources.

**Fix:**  
Prefer string resources:

```kotlin
capsuleManager.updateMode(CapsuleMode.Running(context.getString(R.string.capsule_thinking)))
```

---

### [MEDIUM] setOverlayFocusable — add comment for FLAG_NOT_FOCUSABLE bit manipulation

**File:** `SmartCapsuleManager.kt`  
**Lines:** 351–363

**Problem:**  
The `params.flags and FLAG_NOT_FOCUSABLE.inv()` pattern is correct but may be unclear to readers. Mutating LayoutParams in place is standard for `updateViewLayout`.

**Fix:**  
Add a brief comment: `// Clear FLAG_NOT_FOCUSABLE to allow keyboard input` / `// Set FLAG_NOT_FOCUSABLE so overlay doesn't steal focus`.

---

### [MEDIUM] SmartCapsuleLayoutBuilder.sendButton click not wired in build()

**File:** `SmartCapsuleLayoutBuilder.kt`  
**Lines:** 208–226

**Problem:**  
The send button is created but its click listener is set later in `SmartCapsuleManager.showSupplementInputArea()`. The layout builder does not receive an `onSend` callback. The flow works, but the responsibility is split: builder creates the view, manager wires the click. This is acceptable but could be clarified.

**Fix:**  
Optional refactor: add `onSendSupplement: (String) -> Unit` to `build()` and wire the send button there, so the builder owns all click wiring. Low priority.

---

### [MEDIUM] AgentService.submitOp launches without awaiting session availability

**File:** `AgentService.kt`  
**Lines:** 212–223

**Problem:**  
`submitOp` does `scope.launch { currentSession?.submit(op) }`. If `session` is null, we log and return. If `session` is being set (e.g. in `runAgent`'s launch), there is a brief window where `submitOp` could observe null. The `session = newSession` happens before `observeSession` and `submit(Op.UserInput)`, so it's usually safe, but a quick double-tap could theoretically race.

**Fix:**  
Document sequencing or add a short guard. Low risk; optional.

---

## Low

### [LOW] renderTakeoverPending uses setDotColor but does not call startPulse/stopPulse explicitly

**File:** `SmartCapsuleManager.kt`  
**Line:** 186

**Problem:**  
`setDotColor(v, colorAmber, pulsing = false)` calls `stopPulse()`, so pulse is stopped. Behavior is correct; the comment is just for clarity.

**Fix:**  
None required.

---

### [LOW] Op.Supplement and AgentEvent.SupplementReceived docstrings could mention TakeoverPending

**Files:** `Op.kt` (lines 72–81), `AgentEvent.kt` (lines 67–74)

**Problem:**  
Docs say "Valid in: Running or Paused state". During TakeoverPending the session is still Running, so it's valid. A brief note could clarify.

**Fix:**  
Add: "During TakeoverPending, session remains Running until takeover completes, so supplement is still valid."

---

### [LOW] Duplicate status "已收到" in onSupplementConfirmed and ServiceOverlayController

**Files:** `SmartCapsuleManager.kt`, `ServiceOverlayController.kt`  
**Lines:** SmartCapsuleManager ~403, ServiceOverlayController ~293

**Problem:**  
`SmartCapsuleManager.onSupplementConfirmed()` sets `"✓ 已收到"` and restores after 1.5s. `ServiceOverlayController.onSupplementReceived()` in VD mode sets `"已收到: ${text.take(16)}"`. Different behavior for VD vs A11y; could be unified via a shared string or resource.

**Fix:**  
Optional: extract to a constant or string resource.

---

---

## Protocol & Session

| File | Notes |
|------|-------|
| `Op.kt` | `Takeover`, `Supplement` well-defined. Docstrings clear. |
| `AgentEvent.kt` | `SessionTakeover`, `SupplementReceived` match protocol. |
| `AgentSession.kt` | `handleTakeover`, `handleResume`, `handleSupplement` follow protocol. Critical: HistoryManager thread safety. |
| `AgentService.kt` | Event routing for takeover/resume/supplement correct. |
| `ServiceOverlayController.kt` | Callbacks wired correctly. Mode branching consistent. |

---

## Checklist Summary

| Check | Status |
|-------|--------|
| Hardcoded secrets | ✅ None |
| Memory leaks | ⚠️ Delayed runnable holds view ref (High) |
| Main thread violations | ⚠️ HistoryManager concurrent access (Critical) |
| Null safety | ✅ No force unwrap |
| Error handling | ✅ Present |
| Lifecycle / scope | ✅ Scopes appropriate |
| Coroutine scope leaks | ✅ None obvious |
| Input validation | ✅ Supplement text not validated for length — acceptable |
| Thread safety | ❌ HistoryManager |
| Accessibility service | ✅ No violations |
| FLAG_NOT_FOCUSABLE | ✅ Correctly toggled |
| InputMethodManager | ✅ Used correctly |

---

## Recommended Actions (Priority Order)

1. **Critical:** Fix HistoryManager thread safety — ensure all access is on the same dispatcher or add synchronization.
2. **High:** Make `renderTakeoverPending` reset all view states consistently.
3. **High:** Cancel `onSupplementConfirmed` delayed callback in `hide()`.
4. **High:** Avoid mutating shared `LayoutParams` in `setOverlayFocusable` (or prove it is safe).
5. **Medium:** Handle TakeoverPending vs TaskCompleted ordering.
6. **Medium:** Move hardcoded strings to resources.
7. **Low:** Minor doc and style improvements.
