# Reference Click Implementations (from .reference/mobile_agent/)

## Overview

Examined 4 implementations. Key difference from our agent: they all use **ADB-based tap** (shell input tap x y) rather than AccessibilityService actions. This gives them a fundamentally different execution model.

---

## 1. AutoDevice Android World (Google Research)

**Files**: `autodevice_android_world/android_world/env/actuation.py`, `adb_utils.py`, `executor_tools.py`
**Layers**: 4

```
Tool function: click(x, y)
    |  (scales coords by 0.4x factor)
    v
JSONAction(action_type="click", x=X, y=Y)
    |
    v
execute_adb_action():
    if index: element = screen_elements[index]; x,y = element.bbox_pixels.center
    if x,y: tap_screen(x, y)
    |
    v
adb_utils.tap_screen(x, y, env):
    AdbRequest(tap=Tap(x=x, y=y))  -> env.execute_adb_call()
```

**Click execution**: Single ADB tap. No retry. No UI change detection.

**Alternative**: `find_and_click_element()` - polls for 10 seconds with Levenshtein fuzzy text matching (allows 1 character difference). This is for text-based element finding, not click retry.

**Long press**: `adb shell input swipe x y x y 1000` (swipe to same point, 1000ms).

---

## 2. Minitap (MIT-IBM)

**Files**: `minitap-mobile-use/minitap/mobile_use/tools/mobile/tap.py`, `controllers/android_controller.py`
**Layers**: 4

```
Tool: get_tap_tool() with Target(bounds, resource_id, text)
    |
    v
Fallback chain (try each selector once):
    Level 1: controller.tap_at(center.x, center.y)     <- coordinate-based
    Level 2: controller.tap_element(resource_id=...)    <- UIAutomator selector
    Level 3: controller.tap_element(text=...)           <- text selector
    |
    v
Controller.tap(): "input tap {x} {y}" via ADB shell
```

**Key insight**: Fallback happens at the **selector level**, not at the coordinate level. Instead of jittering coordinates, it tries a completely different way to find the element (resource_id, then text). Each attempt is logged in an `attempts` list for debugging.

**Long press**: `input swipe x y x y {duration}ms`.

---

## 3. Droidrun (Bytedance)

**Files**: `droidrun/droidrun/agent/utils/actions.py`, `tools/driver/android.py`, `tools/helpers/element_search.py`
**Layers**: 4-5

```
Action: click(index, *, ctx)
    |
    v
ctx.ui.get_element_coords(index)  <- bounds lookup
ctx.ui.convert_point(x, y)        <- coordinate scaling
    |
    v
ctx.driver.tap(x, y)
    |
    v
AndroidDriver.tap(): device.click(x, y)  <- async_adbutils
```

**Element search**: Composable filter system (636 lines) with `text_matches()`, `id_matches()`, `clickable()`, `below()`, `above()`, `left_of()`, `right_of()`, `deepest_matching()`. This complexity is in FINDING the right element, not in CLICKING it.

**No retry on click itself. No UI change detection.**

---

## 4. MobileAgent V3 (Alibaba)

**Files**: `MobileAgent/Mobile-Agent-v3/mobile_v3/utils/android_controller.py`
**Layers**: 2

```
tap(x, y): subprocess.run(f"adb shell input tap {x} {y}")
```

Simplest possible. No retry, no verification.

---

## Comparative Analysis

| Aspect | AutoDevice | Minitap | Droidrun | **Our Agent** |
|--------|-----------|---------|----------|---------------|
| **Dispatch** | ADB tap | ADB tap | ADB tap | a11y ACTION_CLICK + dispatchGesture |
| **Layers** | 4 | 4 | 4 | **7** |
| **Code lines (click)** | ~100 | ~200 | ~100 | **~2000** |
| **Retry strategy** | None | Selector fallback | None | 12-attempt jitter+re-resolve |
| **UI change detection** | No | No | No | FNV hash + perceptual hash |
| **Occlusion handling** | No | No | No | 6-candidate avoidance |
| **Long press** | swipe(x,y,x,y,1000) | swipe(x,y,x,y,dur) | swipe(x,y,x,y,1000) | ACTION_LONG_CLICK + gesture |
| **Failure signal** | None (fire & forget) | Attempt list | ActionResult | Attempt trail + error message |

---

## Key Takeaways

### T1: ADB vs AccessibilityService Dispatch
All reference implementations use `adb shell input tap x y`. This is a fundamentally different dispatch path than our agent's:
- ADB input: injects a MotionEvent at the OS level (ALWAYS reaches the target view)
- ACTION_CLICK: asks the a11y framework to perform a click (framework may refuse)
- dispatchGesture: injects a gesture via AccessibilityService (similar to ADB, but more constrained)

ADB input is more reliable because it bypasses the a11y framework's clickable/focusable checks. But it requires ADB connection (not available from on-device service).

**Our constraint**: We run as an on-device AccessibilityService, so we CANNOT use ADB. We must use `node.performAction(ACTION_CLICK)` or `service.dispatchGesture()`. This is fundamentally harder.

### T2: Nobody Else Does Retry-on-Click
None of the 4 reference implementations retry a click operation. If the tap doesn't work, the AGENT (LLM) decides what to do next. The retry intelligence is at the planning level, not the execution level.

Our 12-attempt retry chain with jitter and re-resolution is unique. It adds complexity and latency without fundamentally improving success rates (as shown by the eval failures).

### T3: Nobody Else Does UI Change Detection Per-Click
Post-click UI change detection (hash comparison) is our unique addition. Other agents simply execute the click and return. The LLM sees the new screen state and decides if the click worked.

This means our "click failed" signal is often misleading: the click may have dispatched correctly but the UI hash check returned "unchanged" (e.g., focus-only changes).

### T4: Selector-Level Fallback > Coordinate-Level Jitter
Minitap's approach is superior to our jitter: when coordinates fail, try resource_id; when that fails, try text. This addresses different failure causes (wrong coordinates vs wrong element vs stale selectors). Our jitter tries the same coordinates ±12px, which rarely helps.

### T5: Complexity Budget
Our click path is ~20x more code than any reference implementation. The added complexity was meant to improve reliability but introduced new bugs (occlusion false rejection, edge failures, UI change false positives) that negate the benefits.
