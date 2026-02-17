# Smart Capsule v2 — Test Tool Assessment (`/ux-visual-debug`)

Date: 2026-02-13
Author: Claude (code-level analysis)

## 1. Your Current Manual Workflow

```
1. Run:  ./scripts/setup.sh && ./scripts/debug-run.sh --basic "play a <singer> song on youtube"
   (or:  --basic --vd for virtual display mode)
2. During agent execution, manually tap:
   - Smart Capsule buttons (Takeover, Resume, Stop, Add note, Send, Done, Close)
   - Status Island (VD mode)
   - Navigation icons (⊖, 📱, 👁)
3. Visually judge whether the UI and state transitions are correct.
```

Goal: automate step 2 and partially automate step 3 via `/ux-visual-debug`.

## 2. Skill Capability Inventory

What `ux-visual-debug` currently offers (from `ux_runner_core.py` + `agent_link.py`):

| Capability | Implementation | Status |
|---|---|---|
| ADB tap by text match | `tap_text` → `uiautomator dump` + parse + `input tap x,y` | Working |
| ADB tap by partial text | `tap_contains_text` → substring match | Working |
| ADB tap by resource ID | `tap_resource_id` | Working |
| ADB tap by content-desc | `tap_desc` | Working |
| ADB tap by coordinates | `tap_xy` → `input tap x,y` | Working |
| Text input via ADB | `type` → `input text` / `input keyevent` | Working |
| Text assertion | `assert_text` / `assert_not_text` → check visible strings from UI dump | Working |
| Screenshot capture | `screencap -p` after each step | Working |
| UI tree dump | `uiautomator dump --compressed` after each step | Working |
| Visible text extraction | Parse XML → text/content-desc/resource-id | Working |
| Agent linked execution (parallel) | `--agent-goal` + `--agent-link-mode parallel` | Working |
| Agent linked execution (serial) | `--agent-link-mode serial` | Working |
| Setup integration | `--agent-setup` → `scripts/setup.sh` | Working |
| Debug-run args pass-through | `--agent-debug-arg=--basic` etc. | Working |
| Evidence per step | screenshot.png + ui.xml + visible.txt | Working |
| Report generation | `report.md` + `run_summary.json` | Working |
| Fixed wait | `wait` with `ms` parameter | Working |
| Wait-for-condition | **Not implemented** | Missing |
| Retry on tap failure | **Not implemented** | Missing |
| Conditional branching | **Not implemented** | Missing |

## 3. Flow-by-Flow Fit Analysis

How well can the current tool cover each flow from `user_flow_test_claude.md`:

### 3.1 Flows that CAN be automated today

| Flow | How | Confidence |
|---|---|---|
| **A1** (Send task from Main App) | `tap_text "Send →"` in Compose UI (main app is standard app window → uiautomator sees it) | High |
| **A2** (Task completes) | `assert_text "✓"` + `wait 4000` + `assert_not_text "✓"` to verify auto-hide | Medium (timing) |
| **A4** (User stops) | `tap_text "Stop"` or `tap_desc "Stop"` | Medium (see 4.1) |
| **D3** (Empty supplement) | Attempt submit with empty text, verify no crash | High |
| **E1** (Thought updates) | Series of `dump_ui` + `assert_text` checks over time | Medium |

### 3.2 Flows that MIGHT work with careful timing

| Flow | Challenge |
|---|---|
| **B1** (Takeover) | `tap_desc "Takeover"` — depends on overlay visibility in uiautomator dump (see 4.1) |
| **B2** (Resume) | `tap_desc "Resume"` — same overlay visibility issue |
| **D1** (Supplement) | Type in overlay EditText — requires overlay to be focusable + uiautomator to see it |
| **F2** (Island tap) | Island has NO contentDescription → can only use `tap_contains_text` with truncated thought text, or `tap_xy` with known coordinates |
| **F3** (Minimize) | `tap_desc "Minimize"` — overlay visibility |

### 3.3 Flows that CANNOT be automated with current tool

| Flow | Blocker |
|---|---|
| **C1, C2** (AskUser) | Cannot trigger on demand — agent decides when to ask. Would need either: (a) prompt engineering to force ask, (b) debug hook to inject AskUser events. |
| **F6** (AskUser auto-expand in VD) | Same as above. |
| **H1** (Rapid toggle) | No loop/repeat mechanism in scenario JSON. |
| **H8** (Nudge timer) | Need to wait 4+ minutes. No conditional wait primitive. |

## 4. Critical Blockers

### 4.1 `uiautomator dump` vs `TYPE_ACCESSIBILITY_OVERLAY` (P0 — Must investigate)

**The core question**: Can `uiautomator dump` see the Smart Capsule overlay views?

The Smart Capsule overlay is added via:
```kotlin
// SmartCapsuleLayoutBuilder.kt:297-309
WindowManager.LayoutParams(
    MATCH_PARENT, WRAP_CONTENT,
    TYPE_ACCESSIBILITY_OVERLAY,      // ← accessibility service overlay type
    FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN,
    TRANSLUCENT
)
```

`uiautomator dump` behavior with `TYPE_ACCESSIBILITY_OVERLAY`:
- **Android 10 and below**: Generally does NOT capture accessibility overlay windows in the dump. `uiautomator dump` captures the app window hierarchy and system UI, but accessibility overlay windows are in a separate layer.
- **Android 11+**: Slightly better, but still unreliable for overlay windows.
- **Key issue**: The overlay window has `FLAG_NOT_FOCUSABLE` by default. Even if `uiautomator dump` does capture it, the window may not be part of the "active window" hierarchy.

**Impact**: If `uiautomator dump` cannot see overlay elements:
- `tap_text "Takeover"`, `tap_desc "Stop"`, etc. will fail with "No node matched".
- `assert_text "Thinking..."` won't detect overlay-rendered thought text.
- Only `tap_xy` with hardcoded coordinates would work for overlay interactions.

**How to verify**: Run a simple test:
```bash
# Start a task, wait for capsule to appear as overlay
adb shell uiautomator dump /sdcard/test_dump.xml
adb pull /sdcard/test_dump.xml
grep -i "takeover\|stop\|thinking" test_dump.xml
```

If no matches → confirmed blocker. If matches found → overlay is visible to uiautomator.

**Status Island has the same issue**: `StatusIslandManager` uses `TYPE_ACCESSIBILITY_OVERLAY`.

### 4.2 Overlay EditText Input (P0)

The capsule overlay's EditText lives in a window with `FLAG_NOT_FOCUSABLE` by default. ADB `input text` sends keystrokes to the currently focused window.

Behavior:
- In **Running** state: overlay has `FLAG_NOT_FOCUSABLE`. ADB text input goes to the underlying app, not the capsule.
- In **WaitingForInput** state: `setOverlayFocusable(true)` is called (`SmartCapsuleManager.kt:301-306`), removing the flag. EditText gets focus. ADB `input text` *should* reach the overlay.
- When user taps EditText in **Running** state: `handleInputFocused()` calls `setOverlayFocusable(true)`.

Conclusion: Text input to overlay EditText is only reliable when the overlay has been made focusable (WaitingForInput or after user tap). For supplement (Running state), the ADB tap on EditText must succeed first to trigger focus change, then `input text` should work.

**Sequence for supplement via ADB**:
```json
{"action": "tap_desc", "desc": "..."},      // tap EditText area (needs bounds)
{"action": "wait", "ms": 300},               // wait for focus change
{"action": "type", "text": "my supplement"}, // now input should reach overlay
{"action": "tap_desc", "desc": "Add note"}   // submit
```

Problem: EditText has no `contentDescription` or stable `resource-id`. Need coordinates or a text-based selector on the hint text.

### 4.3 No Wait-for-Condition Primitive (P0)

Current runner only has `wait(ms)` — a fixed sleep. For async agent behavior:
- Agent may take 2s or 20s to start Running.
- Takeover confirmation timing depends on LLM response time.
- AskUser timing is unpredictable.
- Done → Hidden auto-hide is ~3s but not exact.

Fixed waits produce flaky results. Need:
```json
{"action": "wait_for_text", "text": "Takeover", "timeout_ms": 15000}
{"action": "wait_for_not_text", "text": "✓", "timeout_ms": 5000}
```

### 4.4 Stale Scenario References (P1)

Existing scenarios use Chinese labels:
```json
{"action": "tap_text", "text": "接管"}   // should be "Takeover"
{"action": "tap_text", "text": "补充"}   // should be "Add note"
```

Current UI is English. All reference scenarios (`scenario_main_and_capsule.json`, `scenario_agent_parallel_example.json`) will fail on current build.

### 4.5 No Stable Selector for Key Elements (P1)

| Element | Selector options | Issue |
|---|---|---|
| Status Island pill | None — no contentDescription, no resource-id | Can only use `tap_xy` or `tap_contains_text` on truncated thought |
| Overlay EditText | hint text via `tap_contains_text "Got ideas"` | But hint may not show in uiautomator if EditText is in overlay window |
| Compose InputDock | Compose semantics → uiautomator may see placeholder text | More reliable than overlay |
| Primary button | `contentDescription` matches label | Good: `tap_desc "Takeover"` / `tap_desc "Resume"` |
| Stop button | `contentDescription` matches label | Good: `tap_desc "Stop"` / `tap_desc "Close"` |
| Nav icons | `contentDescription`: "Minimize", "Open app", "View screen" | Good if overlay visible |

### 4.6 Cannot Trigger AskUser On Demand (P1)

WaitingForInput and WaitingForAction are agent-initiated events. The UX runner cannot force the agent to ask a question. Options:
1. Use prompts known to trigger ask_user (unreliable).
2. Add a `--force-ask-user` debug flag to `debug-run.sh` that injects an AskUser event after N turns.
3. Add an `inject_event` action to the runner that sends a broadcast/intent to trigger state changes.
4. Accept that AskUser flows require manual testing or a dedicated mock harness.

### 4.7 Parallel ADB Contention (P2)

In parallel linked mode, both `debug-run.sh` (agent + its screenshot capture) and the UX runner issue ADB commands simultaneously. Potential issues:
- `uiautomator dump` can fail or return stale data.
- `screencap` may capture mid-transition frames.
- `input tap` timing may collide with agent's actions.

Mitigation: Extra `wait` steps, `continue_on_fail: true`, and acceptance of some flakiness in parallel mode.

## 5. Improvement Recommendations

### 5.1 P0 — Must Do First

**1. Empirically test `uiautomator dump` overlay visibility**

Run:
```bash
# With capsule overlay showing:
adb shell uiautomator dump /sdcard/dump.xml && adb pull /sdcard/dump.xml
grep -i "takeover\|resume\|stop\|thinking\|Add note" dump.xml
```

If overlay nodes are NOT visible:
- **Option A (recommended)**: For running/takeover state interactions, switch to `tap_xy` with coordinate ranges calculated from `SmartCapsuleLayoutBuilder`'s dp layout. This is brittle but functional.
- **Option B**: Add `resource-id` to key overlay views for more stable selectors.
- **Option C**: Use `adb shell dumpsys window windows` to find overlay window bounds, then compute button coordinates.

If overlay nodes ARE visible → great, proceed with `tap_text`/`tap_desc` selectors.

**2. Add `wait_for_text` / `wait_for_not_text` to runner**

Add to `ux_runner_core.py`:
```python
elif action == "wait_for_text":
    target = str(step.get("text") or "")
    timeout_ms = int(step.get("timeout_ms", 10000))
    interval_ms = int(step.get("interval_ms", 500))
    deadline = time.time() + timeout_ms / 1000.0
    while time.time() < deadline:
        if self._has_text(target):
            break
        time.sleep(interval_ms / 1000.0)
    else:
        raise RuntimeError(f"Timed out waiting for text: {target}")
```

Similarly for `wait_for_not_text` and `wait_for_desc`.

**3. Update reference scenarios to English labels**

Replace all Chinese selectors with current English UI text:
- `"接管"` → `"Takeover"` (or use `tap_desc "Takeover"`)
- `"补充"` → `"Add note"` (or use `tap_desc "Add note"`)

**4. Add `contentDescription` to Status Island**

In `StatusIslandManager.kt`, `buildPillLayout()`:
```kotlin
val pill = LinearLayout(service).apply {
    // ... existing code ...
    contentDescription = "Agent status island"  // ADD THIS
}
```

### 5.2 P1 — Strongly Recommended

**5. Add retry wrapper for tap actions**

```json
{
  "action": "tap_desc",
  "desc": "Takeover",
  "retries": 3,
  "retry_interval_ms": 1000,
  "continue_on_fail": true
}
```

Implementation: wrap `_lookup_and_tap` in a retry loop with fresh UI dump on each attempt.

**6. Add Main App vs Overlay scenario variants**

Many flows work differently in Main App (Compose) vs A11y Overlay (View):
- Main App: uiautomator can likely see Compose UI elements.
- Overlay: may need coordinate-based taps.

Write two variants of each scenario:
- `scenario_a11y_core.json` — tests with overlay interactions (uses `tap_xy` fallback if needed)
- `scenario_main_app_core.json` — tests within the main app (more reliable selectors)
- `scenario_vd_navigation.json` — VD-specific island/capsule/viewer flows

**7. Add `assert_desc` action**

Currently `assert_text` only checks `text`, `content-desc`, and `resource-id` as visible strings. Add an explicit `assert_desc` that specifically checks content descriptions:
```json
{"action": "assert_desc", "desc": "Takeover"}
```

This is more precise than `assert_text` for overlay buttons.

**8. Write concrete round5 scenario files**

Based on the user flow test plan, create scenario JSON files for each flow group:

```
.ai-dev/skills/ux-visual-debug/references/
├── scenario_a11y_lifecycle.json      # A1-A5, B1-B4
├── scenario_a11y_supplement.json     # D1-D3
├── scenario_vd_navigation.json       # F1-F9
├── scenario_main_app_core.json       # A1, D1, E1, E5 (all in-app)
```

### 5.3 P2 — Nice to Have

**9. Singer rotation / parametric scenarios**

Add a template variable system:
```json
{"action": "type", "text": "play a {{singer}} song on youtube"}
```

With a runner flag: `--var singer=Adele` or `--matrix singers.txt` to run across all singers.

**10. Conditional branching**

For flows that depend on agent behavior (AskUser may or may not happen):
```json
{
  "action": "if_text_visible",
  "text": "Awaiting response",
  "then": [
    {"action": "type", "text": "yes"},
    {"action": "tap_text", "text": "Send →"}
  ],
  "else": [
    {"action": "note", "text": "AskUser did not trigger"}
  ]
}
```

**11. Debug event injection**

Add an ADB broadcast receiver in the app for testing:
```kotlin
// TestEventReceiver registered in debug builds only
"com.moonkey.androidagent.TEST_INJECT_ASK_USER" → triggers WaitingForInput
"com.moonkey.androidagent.TEST_INJECT_ERROR" → triggers Error state
```

Then add a runner action:
```json
{"action": "broadcast", "intent": "com.moonkey.androidagent.TEST_INJECT_ASK_USER", "extra_string": "What color do you prefer?"}
```

This would unlock reliable testing of all AskUser and Error flows.

## 6. Verdict

| Aspect | Can it do it today? | With improvements? |
|---|---|---|
| Start task from Main App | Yes | Yes |
| Tap capsule buttons (overlay) | **Unknown — must test** | Yes (with `tap_xy` fallback) |
| Tap Status Island (VD) | No (no selector) | Yes (with contentDescription) |
| Verify state transitions by text | Partially (fixed waits) | Yes (with `wait_for_text`) |
| Text input to overlay | **Unknown — focus issue** | Likely (with tap-focus-type sequence) |
| Text input in Main App | Yes | Yes |
| AskUser flow testing | No (can't trigger) | Yes (with broadcast injection) |
| Supplement flow testing | Partially | Yes |
| VD island/capsule navigation | No | Yes (with contentDescription + `wait_for_text`) |
| Parallel agent + UX | Yes (basic) | Yes (with retries + waits) |

**Bottom line**: The `/ux-visual-debug` skill is a solid foundation. The runner, scenario format, evidence capture, and agent linkage all work. But there are 3 blockers before it can replace your manual workflow:

1. **Verify overlay visibility in uiautomator dump** (quick test, determines strategy for all overlay interactions).
2. **Add `wait_for_text`** (required for any non-trivial async flow).
3. **Fix stale scenarios** (trivial — just update text strings).

After these 3 fixes, you can automate ~60% of the user flows. Adding contentDescription to the island, retry logic, and broadcast injection would bring it to ~90%.

## 7. Suggested Immediate Next Steps

1. **Run the overlay visibility test** (5 minutes):
   ```bash
   ./scripts/setup.sh && ./scripts/debug-run.sh --basic "play a Adele song on youtube"
   # Wait for capsule to appear, then:
   adb shell uiautomator dump /sdcard/dump.xml && adb pull /sdcard/dump.xml
   grep -ci "takeover\|stop\|thinking\|Add note" dump.xml
   ```

2. **Implement `wait_for_text`** in `ux_runner_core.py` (30-50 LoC).

3. **Update scenario JSON files** to use English labels.

4. **Write one end-to-end scenario** for `A1 + D1 + B1 + B2 + A2` (basic lifecycle) and run it.

5. **Based on results**: decide whether to invest in overlay `tap_xy` mapping, contentDescription additions, and broadcast injection.

## 8. Readiness Criteria

The tool can replace most manual testing when:
- 10 consecutive runs of the lifecycle scenario pass with ≤ 1 flaky failure.
- Timing-induced false fails < 10%.
- Every failure produces a report artifact (screenshot + XML) sufficient to diagnose without re-running manually.
- VD island/capsule flows can be exercised end-to-end.
