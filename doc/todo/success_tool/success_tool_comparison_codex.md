# Success tools comparison + plan (focus: our tool layer)

This file compares tool design/targeting/execution across DroidRun, Minitap, AutoDevice, M3A, and our current agent. It then proposes a concrete tool-focused improvement plan.

## 0) Our current tools (code snapshot)
Tool list
- mobile_action (actions: click, long_press, type, swipe, system_button, wait)
- app_control (actions: list_apps, open_app)
- complete_task

Targeting + parameters
- click/long_press/type use element_index only (index-based).
- swipe uses absolute pixel coordinates (start/end arrays).
- system_button uses back/home/enter/recents.
- type has no clear/replace mode (it always sets text via ACTION_SET_TEXT).
- app_control open_app can accept package_name or app_name (fuzzy match + alias mapping).

Execution details
- Click: tries ACTION_CLICK on accessibility node at element center; falls back to gesture tap if node click fails.
- Type: if element_index provided, tap to focus then re-query node at location; uses ACTION_SET_TEXT. No keyboard-based fallback.
- Long press: gesture-based long press on element center.
- Wait: simple delay with max 30s.
- UIActionInvocation captures a post-action snapshot after 300ms; open_app waits ~800ms before snapshot.
- No explicit retry on click/type or on captureScreen failures.
- Snapshot UI list is limited to 80 elements, interactive-first, with center coordinates only (no bounds in prompt JSON).

Prompt constraints (Turn.kt)
- "One action per turn" and "index-only" selection rules.
- Only click clickable and type editable.

## 1) Cross-system action comparison (targeting + execution)

Tap / Click
- DroidRun: index-based. tap_by_index -> bounds -> center; requires get_state cache; explicit errors + available indices; has overlap-aware tap_on_index.
- Minitap: target object with resource_id/text/bounds + indices; actual tap uses bounds -> resource_id -> text (prompt claims different order). Validates bounds and logs each attempt.
- AutoDevice: coordinate-only (x,y) with deterministic scaling (SCALE=0.4). No a11y.
- M3A: index-based with SOM bounding boxes and UI element list alignment.
- Ours: index-based only; no resource_id/bounds/text selectors exposed; click uses ACTION_CLICK then gesture.

Long press
- DroidRun: long press via swipe/gesture or long_press action by index.
- Minitap: long_press_on(target) via same Target selectors.
- AutoDevice: long_press(x,y).
- M3A: long_press(index).
- Ours: long_press(index) with gesture; no long_press_at.

Type / Input
- DroidRun: input_text(text, index=-1, clear=False) optionally focuses with tap_by_index; PortalClient handles clear.
- Minitap: focus_and_input_text(text,target) + focus_and_clear_text(target); verifies focus and can return full content of field by resource_id.
- AutoDevice: input_text(text, x?, y?, clear_text) and type_text(text, clear_first).
- M3A: input_text(text, index) (includes click+type+enter in one action).
- Ours: type(text, element_index?) uses ACTION_SET_TEXT; no clear option; no focus validation beyond tap + re-query; no post-type verification.

Swipe / Scroll
- DroidRun: swipe(start/end coords, duration) (long press is extended duration); no explicit scroll tool.
- Minitap: swipe coordinates or percentage-based (converted using device size). Prompt guidance on swipe physics.
- AutoDevice: swipe(direction, x?, y?) and scroll(direction, x?, y?), plus swipe_coords.
- M3A: scroll(direction, index?) with explicit direction guidance in prompt.
- Ours: swipe(start/end coords) only; no direction-based scroll helper or percentage swipes.

System buttons
- DroidRun: press_key(keycode), back().
- Minitap: back(), press_key(key).
- AutoDevice: navigate_back/home, keyboard_enter.
- M3A: navigate_back/home, keyboard_enter.
- Ours: system_button back/home/enter/recents.

App control
- DroidRun: start_app(package, activity) + list_packages + install_app.
- Minitap: launch_app(package_or_bundle_id), stop_app.
- AutoDevice: open_app(app_name).
- M3A: open_app(app_name).
- Ours: open_app(package_name or app_name) + list_apps with alias expansion.

Memory
- DroidRun: remember(info) + get_memory().
- Minitap: save_note/read_note/list_notes (scratchpad).
- M3A: memory is action summaries only.
- Ours: no memory tool.

Retries / error handling
- DroidRun: get_state has 3 retries; tap_by_index has explicit errors; tap_on_index avoids occlusion.
- Minitap: per-tool attempt logging with structured failure reasons; executor aborts remaining tool calls on first error; Cortex forbids repeating failed actions.
- AutoDevice: no tool-level retries; relies on prompt policies.
- M3A: no tool-level retries; retry guidance only in prompt.
- Ours: no tool-level retries; no attempt logs; failure surfaced as tool error only.

## 2) Concrete plan to improve our tools (tool-first)

### Phase 1 (P0: targeting + input reliability)
1) Introduce a Target object for mobile_action
   - Add fields: resource_id, resource_id_index, text, text_index, bounds {x,y,width,height}.
   - Keep element_index for backward compatibility.
   - Update schema + validation to accept multiple selectors.

2) Implement deterministic fallback order for targeting
   - Pick and document ONE order (recommend: resource_id -> bounds -> text -> element_index -> coordinates).
   - Align prompt instructions to the exact order used in code.
   - Return structured attempt logs in tool output (selector + error) like Minitap.

3) Expand Perceptor output for better targeting
   - Include bounds (left/top/right/bottom) in prompt JSON (not just center).
   - Include resource_id and text indices or at least provide resource_id/text in prompt so model can populate Target.

4) Add focus_and_input_text + focus_and_clear_text actions
   - Focus first using Target; if mismatch between resource_id/text, drop id like Minitap.
   - Support clear/replace via ACTION_SET_TEXT with empty string or keyboard delete fallback.
   - Optionally return the full field content after input when resource_id is available.

### Phase 2 (P1: coordinate + scroll robustness)
5) Add coordinate tap and area tap actions
   - click_at(x,y) and click_area(x1,y1,x2,y2) (center-based).
   - This provides explicit fallback when a11y is stale.

6) Add scroll helpers
   - scroll(direction, element_index?) as a semantic wrapper around swipe with default distances.
   - percentage-based swipe helper: swipe_percentages(start_x_pct, start_y_pct, end_x_pct, end_y_pct).

7) Add overlap-aware tap
   - Compute occlusion with bounds and select a clear point inside target bounds, like DroidRun tap_on_index.

### Phase 3 (P2: execution resilience)
8) Add tool-level retry where safe
   - get_state/captureScreen retry with exponential backoff (2-3 attempts).
   - click/type retry only if the target selector is still valid after re-capture.

9) Add memory + transcription tools
   - memory_tool: save/read/list with step context rules.
   - transcribe_screen tool (OCR) for text-heavy tasks without flooding a11y list.

10) Instrument tool outputs for recovery
    - Include selector used, element metadata (text/id/bounds), and clear errors.
    - Feed these into prompt context to avoid repeat failures.

## 3) Quick wins (minimal code, high impact)
- Add clear flag to type.
- Add bounds to Perceptor JSON.
- Add click_at fallback when element_index fails.
- Add attempt logs to tool error output.

## 4) Validation checklist (post-change)
- Tap by resource_id when element_index is stale.
- Tap when bounds are partially off-screen (should return explicit out-of-bounds errors).
- Type into focused field with and without clear.
- Scroll with direction helper; verify direction semantics.
- get_state retry paths for transient accessibility failures.
