# Reference Agent Swipe Implementations Comparison

## Summary Table

| Agent | Input Mode | Duration | Coordinate System | Execution | Error Handling |
|-------|-----------|----------|-------------------|-----------|----------------|
| minitap-mobile-use | coords OR percentages | 400ms (1-10000) | absolute px / 0-100% | ADB shell | Error string or None |
| droidrun | coordinate pairs | 1.0s (configurable) | absolute (with convert) | adbutils async | ActionResult w/ flag |
| autodevice | direction OR coords | 500ms fixed | logical w/ 0.4 scale | ADB shell | No explicit handling |
| MobileAgent v3 | coordinate pairs | 500ms fixed | absolute px | subprocess ADB | subprocess capture |
| MAI-UI | direction + optional coord | implicit | absolute px | platform-dependent | LLM-facing only |
| **Ours** | direction+distance OR start/end | 400ms (configurable) | absolute px | AccessibilityService gesture | ActionResult w/ observation |

## Key Differences from Our Implementation

### 1. All reference agents use ADB `input swipe` — we use AccessibilityService

Every reference agent ultimately runs:
```bash
adb shell input swipe <startX> <startY> <endX> <endY> [durationMs]
```

We use `AccessibilityService.dispatchGesture()` with `GestureDescription.StrokeDescription`.

**Implications**:
- ADB `input swipe` injects at the InputDispatcher level — it works universally
- AccessibilityService gestures can be intercepted/blocked by app gesture handlers
- AccessibilityService gestures respect touch delegation and may be consumed differently
- ADB bypass means it always produces a physical swipe; AccessibilityService may not

### 2. No reference agent uses AccessibilityNodeInfo scroll actions

None of the reference agents fall back to `ACTION_SCROLL_FORWARD/BACKWARD`. They all rely
purely on coordinate-based swipe gestures. However, their ADB-level injection means they
don't need this fallback — it always works.

Since we use AccessibilityService gestures (higher-level than ADB), we are MORE likely to
need the scroll action fallback.

### 3. minitap percentage-based coordinates (unique)

minitap offers `swipe_percentages(start_x_percent, start_y_percent, end_x_percent, end_y_percent)`:
- Device-agnostic positioning
- No need for LLM to know screen resolution
- Automatically converted to pixels using device dimensions

This is notably absent from our implementation and would help with cross-device robustness.

### 4. autodevice direction-based endpoint calculation (closest to ours)

autodevice computes endpoints from screen dimensions for directional swipes:
```python
if direction == "up":
    start_x, start_y = mid_x, screen_height
    end_x, end_y = mid_x, 0
```

Key difference from our approach: autodevice uses **full screen** as the swipe range
(0 to screen_height), while we use `origin +/- delta` which creates shorter, centered swipes
that get clamped at edges.

### 5. droidrun auto-sleep after swipe

```python
await self.device.swipe(x1, y1, x2, y2, float(duration_ms / 1000))
await asyncio.sleep(duration_ms / 1000)  # Wait for swipe to complete
```

This ensures the UI has time to settle. We have a 300ms fixed `UI_SETTLE_DELAY_MS` which
may not be long enough for slow animations.

---

## Detailed Analysis by Agent

### minitap-mobile-use

**Tool Definition** (two separate tools):
```python
swipe_coordinates(agent_thought, start_x, start_y, end_x, end_y, duration=400)
swipe_percentages(agent_thought, start_x_percent, start_y_percent,
                  end_x_percent, end_y_percent, duration=400)
```

**Execution**:
```python
cmd = f"input touchscreen swipe {start.x} {start.y} {end.x} {end.y} {duration}"
self.device.shell(cmd)
```

**Strengths**: Percentage mode, Pydantic validation, async, clean separation
**Weaknesses**: No direction-based shortcut, no post-swipe verification

### droidrun

**Tool Definition**:
```python
"swipe": {
    "parameters": {
        "coordinate": {"type": "list", "required": True},   # [x, y]
        "coordinate2": {"type": "list", "required": True},  # [x, y]
        "duration": {"type": "number", "default": 1.0},     # seconds
    }
}
```

**Prompt Guidance**: "Try different swipe directions if content doesn't change"

**Execution**: `ctx.ui.convert_point()` for coordinate mapping, auto-sleep after duration

**Strengths**: Clean action layer, UI context integration, auto-sync
**Weaknesses**: No direction shortcut, no validation beyond list check

### autodevice_android_world

**Tool Definition** (dual mode):
```python
# Direction-based
def swipe(direction: str, x: Optional[int] = None, y: Optional[int] = None)

# Coordinate-based
def swipe_coords(start_x, start_y, end_x, end_y)
```

**Prompt Guidance**:
> "Consider exploring the screen by using the swipe action with different directions.
> If you cannot change the page content by swiping in the same direction continuously,
> the page may have been swiped to the bottom."

**Execution**: Screen-size aware endpoint calculation, 0.4 scale factor, 500ms duration

**Key Design**: Direction up means finger moves up → uses startY=screen_height, endY=0.
Full screen sweep. Our implementation uses smaller symmetric sweeps from center.

**Strengths**: Dual mode, full-screen directional sweeps, scale factor for resolution independence
**Weaknesses**: Fixed 500ms, no error handling at ADB level

### MobileAgent v3

**Tool Definition**:
```python
{
    "action": "swipe",
    "coordinate": [x, y],      # start
    "coordinate2": [x, y],     # end
}
```

**Execution**: `adb shell input swipe {x1} {y1} {x2} {y2} 500`

Simplest implementation. Fixed 500ms, no direction shortcut, no validation.

### MAI-UI

**Tool Definition**:
```json
{"action": "swipe", "direction": "up|down|left|right", "coordinate": [x, y]}
```

`coordinate` is optional — used to target a specific UI element as swipe origin.

**Key Design**: Direction-only with optional element targeting. No explicit coordinates.
The simplest LLM-facing interface.

---

## Design Patterns Worth Adopting

1. **Full-screen directional sweeps** (autodevice) — instead of symmetric origin +/- delta
2. **Percentage-based coordinates** (minitap) — device-agnostic positioning
3. **Direction-change prompt guidance** (autodevice, droidrun) — tell agent to try different directions
4. **Auto-sleep matching duration** (droidrun) — settle time proportional to swipe speed
5. **Separate drag tool** (MAI-UI) — distinguish scroll-swipe from precision-drag
