# BrowserDraw — Failure Analysis

## Scripted Success Criteria

From `browser.py` lines 98-109, `is_successful` checks two conditions:

```python
def is_successful(self, env: interface.AsyncEnv) -> float:
    state = env.get_state()
    package_name = adb_utils.extract_package_name(
        adb_utils.get_current_activity(env.controller)[0]
    )
    if package_name != 'com.android.chrome':
        return 0.0

    for element in state.ui_elements:
        if element.text == 'Success!':
            return 1.0
    return 0.0
```

1. Chrome must be the foreground app.
2. The accessibility tree must contain an element with text exactly `"Success!"`.

The `"Success!"` text is set by the in-page JavaScript `evaluateTask()` function, which:
- Reads every pixel from the canvas via `getImageData`
- Converts each non-transparent pixel to a hex color string
- Checks that ALL 3 randomly-chosen task colors appear in the drawn pixels (exact hex match)

```javascript
function evaluateTask() {
    const pixelData = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
    const usedColors = new Set();
    for (let i = 0; i < pixelData.length; i += 4) {
        const r = pixelData[i];
        const g = pixelData[i + 1];
        const b = pixelData[i + 2];
        const color = rgbToHex(r, g, b);
        usedColors.add(color);
    }
    const success = taskColors.every(color => usedColors.has(color));
    showResult(success);
}
```

## Task Setup

`initialize_task` (lines 56-85):
1. Clears device storage and Chrome app data.
2. Generates an HTML page (`task.html`) with a seeded RNG that picks 3 random colors from a palette of 24.
3. Pushes `task.html` to the device's Downloads folder.

The HTML page contains:
- 3 target color squares at the top (50x50px colored divs)
- A 300x300 canvas for drawing (with touch event listeners)
- A palette of 24 clickable color buttons that set `ctx.strokeStyle`
- Clear and Submit buttons

The goal string: "Open the file task.html in Downloads in the file manager; when prompted open it with Chrome. Then create a drawing using the three colors shown at the top and hit submit."

## Agent's Last Attempt (R24, run 20260309_194958)

29 turns total. Execution flow:

1. **Turns 1-17** (~18 turns): Struggled to open `task.html` from Downloads. Required multiple attempts — clicking the file row, long-pressing, using "Open with" menu, selecting Chrome, dismissing Chrome first-run dialog.
2. **Turn 18**: Scrolled the Chrome page to view the canvas and colors.
3. **Turns 19-28**: Attempted to draw with 3 colors:
   - Clicked a red palette button, swiped on canvas
   - Clicked a green palette button, swiped on canvas
   - Clicked a blue palette button, swiped on canvas
   - Clicked a magenta palette button, swiped on canvas
   - Clicked Submit (element_index 42)
4. **Turn 29**: Called `complete_task` with status "success".

**Screenshot evidence (turn 29)**: The final screenshot shows the canvas with only thin gray/light-colored lines. The target colors were teal (#008080), magenta (#ff00ff), and dark navy (#000080), but the visible strokes are NOT in those colors — they appear to be the default stroke color.

**Scoring context**: `ui_element_count: 0` (accessibility forwarder returned zero elements at scoring time), `score: 0.0`. Chrome was in foreground but no "Success!" text was found.

## Gap Analysis

There are **two distinct failures**:

### 1. Drawing strokes did not register the correct colors (primary failure)

The HTML palette buttons set `ctx.strokeStyle` via JavaScript click event listeners:
```javascript
button.addEventListener('click', () => {
    ctx.strokeStyle = color;
});
```

The agent clicked palette color buttons via accessibility actions or coordinate taps, then used `swipe` to draw on the canvas. However, the canvas shows only gray/default-colored lines, meaning:

- **The palette button clicks likely did not trigger the JavaScript `click` event.** Accessibility clicks or coordinate-based taps dispatched through the Android accessibility layer may not fire DOM `click` events on HTML `<button>` elements inside Chrome's WebView. The buttons are styled as 30x30px colored squares — the accessibility framework may have dispatched a focus or activation event that didn't propagate as a JavaScript `click`.
- **The swipe actions may not have triggered canvas touch events correctly.** The canvas registers `touchstart`/`touchmove`/`touchend`, and the swipes did produce visible lines — but in the wrong color, confirming the palette selection failed.

### 2. Agent did not verify success before calling complete_task

The agent clicked Submit and immediately called `complete_task(status="success")` without checking whether the page showed "Success!" text. The in-page evaluator sets `resultElement.textContent = 'Success!'` only on success, and leaves it empty on failure. The agent's final a11y tree had 43 elements but none with "Success!" — a clear signal that the drawing evaluation failed. The agent ignored this.

### 3. Color identification may have been wrong

The agent's thoughts reference "teal, magenta, and dark blue" as targets, which roughly matches the screenshot (teal, magenta, navy). However, it tried to use red, green, and blue palette buttons at various points, suggesting confusion about which palette colors matched the targets. Even if the clicks had worked, the wrong colors might have been selected.

## Verdict

**Structural limitation — very difficult to fix with prompt/skill changes alone.**

The core blocker is that interacting with HTML canvas + JavaScript color buttons through Android accessibility is unreliable:

1. **Palette color selection**: Accessibility clicks on HTML buttons inside Chrome WebView may not fire DOM `click` events, so `ctx.strokeStyle` never changes from the default. This is a platform-level limitation of how Chrome exposes web content to Android accessibility services.

2. **Canvas drawing**: While swipes do produce visible marks (touch events propagate), the color selection mechanism is broken, making it impossible to draw with the required colors.

3. **Exact pixel matching**: The evaluator does exact hex comparison — even slight color rendering differences (anti-aliasing, sub-pixel rendering) could cause mismatches, though this is secondary to the color selection failure.

**Possible mitigations** (all partial):
- Use `shell` tool to inject JavaScript via `adb shell "input keyevent"` or Chrome DevTools Protocol to set strokeStyle directly — but this requires knowledge of the page structure and may not be available.
- Use the Android `shell` tool with `input tap` at palette button coordinates (bypassing accessibility) — but this still goes through the Android input pipeline, not the DOM.
- Add a Chrome app skill that instructs the agent to use the address bar to execute `javascript:` URIs — but Chrome blocks `javascript:` from the address bar.

**Recommendation**: Park this task in `cannot_handle_group.txt`. The fundamental issue is that HTML canvas interaction + JavaScript button click propagation through Android accessibility is unreliable, and the task requires pixel-perfect color matching.
