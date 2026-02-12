# Stage 4 Code Review — Smart Capsule

**Scope:** Smart Capsule Stage 4 changes (renderer extraction, expandedBody, nudge/supplement, VD ask_user, takeover timing)

**Files reviewed:**
- SmartCapsuleRenderer.kt (NEW)
- SmartCapsuleManager.kt (REWRITTEN)
- SmartCapsuleLayoutBuilder.kt (MODIFIED)
- ServiceOverlayController.kt (MODIFIED)
- Agent.kt (MODIFIED)
- SessionAgentRunner.kt (MODIFIED)
- AgentSession.kt (MODIFIED)

---

## CRITICAL

### [CRITICAL] Takeover/Resume deadlock

**Location:** `Agent.kt` (lines 163–165), `AgentSession.kt` (lines 283–292)

**Problem:** If the user calls `Op.Resume` while `handleTakeover` is still awaiting pause confirmation, the agent may never enter the pause block. `Agent.resume()` sets `pauseState.value = false` but does not complete `pauseConfirmed`. `handleTakeover` awaits `pauseConfirmed` forever → deadlock.

**Scenario:**
1. User clicks 接管 → `handleTakeover` runs, calls `pause()`, sets `pauseState = true`, returns `Deferred`, awaits.
2. Agent is still executing a long turn.
3. User quickly clicks 继续 → `handleResume` runs, sets `pauseState = false`.
4. Agent eventually reaches the pause check; `pauseState.value` is already `false`, so it skips the block.
5. `pauseConfirmed` is never completed → `handleTakeover` awaits forever.

**Fix:** In `Agent.resume()`, complete any pending `pauseConfirmed` before clearing pause state:

```kotlin
suspend fun resume() {
    lifecycleMutex.withLock {
        pauseConfirmed?.complete(Unit)
        pauseConfirmed = null
        pauseState.value = false
    }
    eventDispatcher.status("▶️ Resuming...")
}
```

---

## HIGH

### [HIGH] Nudge runnable captures CapsuleViews reference

**Location:** `SmartCapsuleManager.kt` (lines 324–330)

**Problem:** `startNudgeTimer(v)` captures `v` (CapsuleViews) in the runnable closure. If the overlay is hidden and re-shown before the 4-minute timer fires, `views` is replaced. The runnable would still reference the old `v` and modify `v.expandedBody.text` on a potentially detached view.

**Mitigation:** `cancelAllRunnables()` is called when leaving `WaitingForInput`/`WaitingForAction` and on `hide()`, so the nudge is normally cancelled. The risk is only if some code path leaves those modes without calling `cancelAllRunnables` (e.g. a new code path). Not a bug today but fragile.

**Recommendation:** In the nudge runnable, verify the view is still attached before modifying:

```kotlin
nudgeRunnable = Runnable {
    val body = v.expandedBody ?: return@Runnable
    if (body.windowToken == null) return@Runnable  // view detached
    val currentText = body.text?.toString() ?: ""
    body.text = "$currentText\n还在等待您的回复..."
}.also { handler.postDelayed(it, NUDGE_DELAY_MS) }
```

---

### [HIGH] Supplement confirmation runnable captures `originalText` after mode change

**Location:** `SmartCapsuleManager.kt` (lines 274–292)

**Problem:** In `onSupplementConfirmed()`, we call `updateMode(previousMode)` first, then read `originalText` from `v.thoughtText.text.toString()`. The renderer has already set `thoughtText` to the mode’s thought. If the mode is `Running`, the thought may be "思考中..." or similar. The restore runnable then sets `thoughtText.text = originalText` after the delay. If another `updateMode` happens during that delay (e.g. ThoughtUpdate), we would overwrite newer content with the old `originalText`.

**Severity:** Low likelihood—1.5–2s is short and supplement confirmation is a one-off. Mostly a correctness edge case.

**Recommendation:** Consider cancelling the supplement confirmation runnable when `updateMode` is called during the confirmation window. `cancelAllRunnables()` already cancels it, but `updateThought` calls `updateMode`, which would cancel it. So we’re covered. No change strictly required; document behavior if desired.

---

## MEDIUM

### [MEDIUM] SmartCapsuleRenderer.pulseAnimator lifecycle

**Location:** `SmartCapsuleRenderer.kt` (lines 28, 261–283)

**Problem:** `pulseAnimator` is held by the renderer. When modes switch from `Running` to non-pulsing, `setDotColor(..., pulsing = false)` calls `stopPulse()`. When the capsule is hidden, `SmartCapsuleManager.hide()` calls `renderer.stopPulse()`. Lifecycle looks correct.

**Note:** `CapsuleMode.Hidden` is handled by the manager; the renderer does nothing for it. The manager calls `hide()` which stops the pulse. No leak.

---

### [MEDIUM] VD mode: onThoughtUpdate hides capsule

**Location:** `ServiceOverlayController.kt` (lines 259–265)

**Problem:** In VD mode, `onThoughtUpdate` hides the capsule if it is showing. During `ask_user`, the agent is blocked and should not emit `ThoughtUpdate`. Hiding on thought update is defensive but might hide the `ask_user` UI if a thought event is incorrectly emitted.

**Assessment:** Reasonable defensive behavior. Document that thought updates should not occur during `ask_user`.

---

### [MEDIUM] handleTakeover sets state to Paused before await

**Location:** `AgentSession.kt` (lines 283–292)

**Problem:** `_state.value = SessionState.Paused` is set before `confirmed.await()`. The UI sees Paused immediately, but `SessionTakeover` is emitted only after the agent actually pauses. The overlay stays in `TakeoverPending` until then, which is correct.

**Assessment:** Intentional. No change needed.

---

## LOW

### [LOW] Magic number for nudge delay

**Location:** `SmartCapsuleManager.kt` (line 30)

```kotlin
private const val NUDGE_DELAY_MS = 4 * 60 * 1000L // 4 minutes
```

**Recommendation:** Consider a configurable constant or build-time parameter for different environments (e.g. debug).

---

### [LOW] Suppress SendButton onClick in build

**Location:** `SmartCapsuleLayoutBuilder.kt` (lines 226–244)

**Problem:** `sendButton` is a `TextView` added to `supplementInputRow` but has no `setOnClickListener` in `build()`. The click is set in `setupSupplementInput()`. That’s intentional—the layout builder is stateless and doesn’t bind behavior. Fine as is.

---

### [LOW] Renderer mode for SupplementInput does not set expandedBody

**Location:** `SmartCapsuleRenderer.kt` (lines 232–264)

**Problem:** `renderSupplementInput` does not explicitly set `expandedBody?.visibility = View.GONE`. Compact modes clear it at the start of `render()`, but `SupplementInput` is not in that list. When entering `SupplementInput` from `Running`, `expandedBody` was already GONE. Correct.

---

## Summary: Renderer / Manager Separation

**Renderer:** Pure visual logic. No callbacks, no state, no input handling. Renders per `CapsuleMode`.

**Manager:** Owns overlay lifecycle, mode, callbacks, input, timers. Calls `renderer.render()` and `renderer.stopPulse()`.

**Separation:** Clear. Renderer depends only on `CapsuleViews` and `CapsuleMode`.

---

## Summary: Takeover Timing (Deferred)

**Flow:**
1. User clicks 接管 → `requestTakeover()` → `updateMode(TakeoverPending)`, `onTakeover()`.
2. Session receives `Op.Takeover` → `handleTakeover()` → `pause()` (returns `Deferred`), `_state = Paused`, `await()`.
3. Agent loop reaches pause check → `pauseConfirmed?.complete(Unit)`.
4. `handleTakeover` resumes → `emit(SessionTakeover)`.
5. ServiceOverlayController → `onSessionTakeover()` → `onTakeoverConfirmed()` → `updateMode(Takeover)`.

**Correctness:** SessionTakeover is emitted only after the agent has actually paused. Deadlock risk remains if `Resume` is called before pause (see CRITICAL).

---

## Summary: VD Mode ask_user

**Flow:**
1. `onAskUser` is called for both modes.
2. `capsuleManager.cancelSupplementIfActive()`.
3. `capsuleMode = WaitingForInput` or `WaitingForAction`.
4. In VD mode: `capsuleManager.updateMode(capsuleMode)` so the capsule is shown for user input.
5. `statusIslandManager?.updateStatus(...)`.

**Correctness:** Capsule is shown in VD mode for `ask_user`. Previously it was not, causing timeouts. Fix is correct.

---

## Summary: Nudge Timer and Supplement Confirmation Cancellation

- **Nudge:** `cancelNudgeTimer()` is called from `cancelAllRunnables()`, which runs on `updateMode` (mode change) and `hide()`.
- **Supplement confirmation:** `supplementConfirmedRunnable` is cancelled in `cancelAllRunnables()`.
- **Keyboard:** `keyboardShowRunnable` cancelled in `cancelAllRunnables()`.
- **Auto-hide:** `delayedHideRunnable` cancelled in `cancelAllRunnables()`.

All timers are cancelled when leaving their modes or when hiding. No leaks found.

---

## Verdict

| Severity | Count |
|----------|-------|
| CRITICAL | 1     |
| HIGH     | 2     |
| MEDIUM   | 4     |
| LOW      | 3     |

**Recommendation: CHANGES_REQUESTED**

Fix the takeover/resume deadlock (CRITICAL) before merge. The HIGH items are defensive improvements; the MEDIUM and LOW items are optional.
