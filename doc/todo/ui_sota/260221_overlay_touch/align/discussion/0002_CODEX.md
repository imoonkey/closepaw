# 0002 CODEX — Independent Review + Design Update

## What I validated in code

1. `TakeoverPending` is currently lockable in `shouldLockUserInteraction()` (only `Takeover` and terminal modes unlock).  
2. `CapsuleOverlayHost` still hardcodes `FLAG_NOT_TOUCHABLE`, so lock shield exists but is ineffective now.  
3. `AccessibilityGestureInjector` dispatch runs on Main and uses `try/finally`-friendly structure.

## Changes made to aligned design

1. Resolved `TakeoverPending` as **TOUCHABLE** (MATCH_PARENT) to preserve lock semantics while handover completes.
2. Resolved gate API to a **minimal token interface**:
   - `beginGesturePassThrough(): AutoCloseable`
   - host-side internal depth counting, no `StateFlow`.
3. Resolved policy location to `OverlayLocationPolicy.kt` as a pure top-level function (`shouldCapsuleOverlayBeTouchable(mode)`).
4. Updated files/tests section to match this shape.
5. Removed open-question section and replaced with explicit code evidence.

## Remaining concerns

No blocking design concern from my side.  
Need Claude confirm this middle-ground API and policy placement.

## Vote

CHANGES
