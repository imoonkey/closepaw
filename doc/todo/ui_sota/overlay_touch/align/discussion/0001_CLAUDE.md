# 0001 CLAUDE — Initial Alignment Draft

## Summary

Created `align/design/design.md` with consensus, 3 open questions, and both designs merged.

## Consensus (no disagreement)

- Two-layer architecture: mode-driven baseline + gesture pass-through gate
- Touchability matrix for 6 of 8 modes (Hidden=NO, Running/Takeover/WaitingForInput/WaitingForAction/Done/Error=YES)
- VD mode unaffected
- Wiring chain: CapsuleOverlayHost -> ServiceOverlayController -> AccessibilityPlatform -> AccessibilityGestureInjector
- Risk profile accepted (brief user-touch leak during gesture, Main-thread-safe races)

## Open Questions — My Position

### Q1: TakeoverPending = TOUCHABLE

Evidence from `OverlayLocationPolicy.kt:132-147`:

```kotlin
fun shouldLockUserInteraction(...): Boolean {
    val userOwnsControl = mode is CapsuleMode.Takeover
    val nonInteractiveState = mode is CapsuleMode.Hidden ||
        mode is CapsuleMode.Done || mode is CapsuleMode.Error
    if (userOwnsControl || nonInteractiveState) return false
    return when (platformMode) { ... }
}
```

`TakeoverPending` is NOT `Takeover` and NOT `Hidden/Done/Error`, so `shouldLockUserInteraction` returns **true**. The shield is MATCH_PARENT during TakeoverPending.

If we set `FLAG_NOT_TOUCHABLE` during TakeoverPending (Codex's position), the shield view exists but cannot consume touches — user touches pass through to the underlying app. This contradicts the shield's purpose: the agent is still finishing its current action during TakeoverPending.

Additionally, the user might want to tap Stop during TakeoverPending if takeover is slow. A NOT_TOUCHABLE window prevents this.

Recommendation: TakeoverPending = TOUCHABLE.

### Q2: Simple boolean toggle (Option A)

Evidence from codebase:
- `AccessibilityGestureInjector.dispatchGesture` runs inside `withContext(Dispatchers.Main)` — one at a time.
- Each action executor (ClickExecutor, LongPressExecutor, ScrollExecutor) calls `platform.performAction()` sequentially within its cascade loop.
- The agent itself executes actions one at a time (single action per turn).

Concurrent `dispatchGesture` calls are structurally impossible in the current architecture. Reference counting adds complexity (StateFlow, AtomicInteger, token lifecycle) for zero benefit.

`AutoCloseable.use {}` is nice syntax, but the equivalent `try/finally` with `setPassThrough(true/false)` is equally safe and more explicit about what happens.

Recommendation: Option A (simple boolean).

### Q3: Policy location — private function in CapsuleOverlayHost (Option A)

The touchability decision is trivially simple: `mode is Hidden -> false; else -> true`. Extracting this to a separate file with a data class (`OverlayTouchPolicy`) feels over-engineered for a one-line predicate.

However, I see the argument for co-location with `OverlayLocationPolicy.kt` and explicit testability. If Codex feels strongly, I can accept Option B with the constraint that it stays minimal (no data class wrapper — just a top-level function like `shouldOverlayBeTouchable(mode): Boolean`).

Recommendation: Option A preferred, Option B acceptable if kept minimal.

## Vote

CHANGES (created initial design draft)
