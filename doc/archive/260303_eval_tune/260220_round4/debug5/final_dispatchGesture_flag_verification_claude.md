# FLAG_NOT_TOUCHABLE Verification

Permanent `FLAG_NOT_TOUCHABLE` on CapsuleOverlayHost confirmed the root cause — 2/2 eval passed (`eval/results/20260220_145635`).

| Task | Result | Turns | Duration |
|------|--------|-------|----------|
| SystemBrightnessMax | **PASS** | 9 | 69s |
| SystemBrightnessMin | **PASS** | 7 | 53s |

Change: single line in `CapsuleOverlayHost.createLayoutParams()`:
```kotlin
WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or   // ← ADDED
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
```
