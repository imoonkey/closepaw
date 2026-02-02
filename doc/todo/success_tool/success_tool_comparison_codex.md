# Success tools comparison + plan (focus: our tool layer)

This file compares tool design/targeting/execution across DroidRun, Minitap, AutoDevice, M3A, and our current agent. It then proposes a concrete tool-focused improvement plan.

## Current status (as of 2026-02-02)

Legend: DONE = implemented + shipped, PARTIAL = usable but missing key pieces, TODO = not started.

**Phase 1 (P0: targeting + input reliability)**
- 1) Target object for `mobile_action`: PARTIAL (selectors exist, but no explicit `Target` object type)
- 2) Deterministic fallback order + attempt logging: DONE (click/long_press/type all use consistent fallback order + attempt logs on failure)
- 3) Perceptor output for targeting: PARTIAL (bounds+center included; no explicit selector indices in prompt JSON)
- 4) `focus_and_input_text` / `focus_and_clear_text`: PARTIAL (type supports multi-selector focus + clear; still no dedicated action(s) or post-type verification)

**Phase 2 (P1: coordinate + scroll robustness)**
- 5) Coordinate/area tap: DONE (merged into click fallback; no separate actions)
- 6) Scroll helpers: TODO
- 7) Overlap-aware tap: TODO

**Phase 3 (P2: execution resilience)**
- 8) Tool-level retries: TODO
- 9) Memory + transcription tools: TODO
- 10) Rich tool outputs for recovery: PARTIAL (attempt logs + element-not-found details are better; still not structured “selector used” on success)

**What’s still left (short list)**
- Scroll helper actions (direction-based / percentage swipe).
- Out-of-bounds bounds handling (explicit errors / clamping policy).
- Dedicated `focus_and_input_text` / `focus_and_clear_text` actions + post-type verification.
- Safe retry policy for captureScreen/click/type.
- Overlap-aware tap point selection (occlusion-aware).

## 0) Our current tools (code snapshot)
Tool list
- mobile_action (actions: click, long_press, type, swipe, system_button, wait)
- app_control (actions: list_apps, open_app)
- complete_task

Targeting + parameters
- click supports multi-selector targeting: bounds (x1..y2), coordinates (x,y), resource_id (+ resource_id_index), text (+ text_index), element_index.
- long_press supports multi-selector targeting: bounds (x1..y2), coordinates (x,y), resource_id (+ resource_id_index), text (+ text_index), element_index.
- type supports multi-selector targeting for field focus: resource_id (+ resource_id_index), target_text (+ target_text_index), bounds (x1..y2), coordinates (x,y), element_index, or no target (type into focused field).
  - Compatibility: if `target_text_index` is omitted, `text_index` is accepted as an alias (avoid using this in new prompts; it’s for recovery).
- swipe uses absolute pixel coordinates (start/end arrays) and is clamped to screen bounds at execution.
- system_button uses back/home/enter/recents.
- type supports clear/replace via clear=true (ACTION_SET_TEXT with empty string then input).
- app_control open_app can accept package_name or app_name (fuzzy match + alias mapping) and supports agent_thought.
- agent_thought is accepted on mobile_action and app_control (included in action descriptions).

Execution details
- Click: ClickTargetInvocation performs fallback order (bounds → x/y → resource_id → text → element_index). Element-based click still uses ACTION_CLICK on node, then gesture.
- Type: TypeTargetInvocation performs fallback order (bounds → x/y → resource_id → target_text → element_index). If no targeting selector is provided, it types into the focused field. Coordinate/bounds targeting taps to focus first, then types into the focused node. Element targeting uses elementIndex -> tap -> ACTION_SET_TEXT.
- Long press: LongPressTargetInvocation performs fallback order (bounds → x/y → resource_id → text → element_index). Adds long_press_at via UIAction.LongClickAt for coordinate/bounds-based fallback.
- Wait: simple delay with max 30s.
- UIActionInvocation captures a post-action snapshot after 300ms; open_app waits ~800ms before snapshot.
- No explicit retry on click/long_press/type or on captureScreen failures.
- Snapshot UI list is limited to 80 elements, interactive-first, with center + bounds in prompt JSON.
- Failure output now includes attempted selectors and available indices when element not found (click/long_press/type).
- Unit tests cover selector ordering + key mismatch behavior (see `app/src/test/.../tool/handlers`).

Prompt constraints (Turn.kt)
- "One action per turn" and prefer resource_id/text/bounds for click with element_index as fallback.
- Only click clickable and type editable.
- Prefer resource_id/target_text/bounds targeting for long_press/type where possible; keep element_index as fallback.

## 1) Cross-system action comparison (targeting + execution)

Tap / Click
- DroidRun: index-based. tap_by_index -> bounds -> center; requires get_state cache; explicit errors + available indices; has overlap-aware tap_on_index.
- Minitap: target object with resource_id/text/bounds + indices; actual tap uses bounds -> resource_id -> text (prompt claims different order). Validates bounds and logs each attempt.
- AutoDevice: coordinate-only (x,y) with deterministic scaling (SCALE=0.4). No a11y.
- M3A: index-based with SOM bounding boxes and UI element list alignment.
- Ours: multi-selector click with fallback order bounds → x/y → resource_id → text → element_index; click uses ACTION_CLICK then gesture.

Long press
- DroidRun: long press via swipe/gesture or long_press action by index.
- Minitap: long_press_on(target) via same Target selectors.
- AutoDevice: long_press(x,y).
- M3A: long_press(index).
- Ours: long_press supports bounds/x-y/resource_id/text/element_index fallback; long_press_at supported via coordinate/bounds fallback (gesture-based).

Type / Input
- DroidRun: input_text(text, index=-1, clear=False) optionally focuses with tap_by_index; PortalClient handles clear.
- Minitap: focus_and_input_text(text,target) + focus_and_clear_text(target); verifies focus and can return full content of field by resource_id.
- AutoDevice: input_text(text, x?, y?, clear_text) and type_text(text, clear_first).
- M3A: input_text(text, index) (includes click+type+enter in one action).
- Ours: type(text, …) supports multi-selector targeting (resource_id/target_text/bounds/x-y/element_index) + clear=true; includes a defensive resource_id/target_text mismatch check; still no post-type verification.

Swipe / Scroll
- DroidRun: swipe(start/end coords, duration) (long press is extended duration); no explicit scroll tool.
- Minitap: swipe coordinates or percentage-based (converted using device size). Prompt guidance on swipe physics.
- AutoDevice: swipe(direction, x?, y?) and scroll(direction, x?, y?), plus swipe_coords.
- M3A: scroll(direction, index?) with explicit direction guidance in prompt.
- Ours: swipe(start/end coords) only; no direction-based scroll helper or percentage swipes; swipe coords are clamped to screen bounds.

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
- Ours: no tool-level retries; click/long_press/type attempt logs recorded in failure; element-not-found includes available indices.

## 2) Concrete plan to improve our tools (tool-first)

### Phase 1 (P0: targeting + input reliability)
1) Introduce a Target object for mobile_action
   - Add fields: resource_id, resource_id_index, (target_)text, (target_)text_index, bounds {x,y,width,height}.
   - Keep element_index for backward compatibility.
   - Update schema + validation to accept multiple selectors.
   - Status: PARTIAL (selectors added, but no explicit Target object type).

2) Implement deterministic fallback order for targeting
   - Pick and document ONE order.
   - Align prompt instructions to the exact order used in code.
   - Return structured attempt logs in tool output (selector + error) like Minitap.
   - Status: DONE (click/long_press/type: bounds → x/y → resource_id → text/target_text → element_index + attempt logs on failure).

3) Expand Perceptor output for better targeting
   - Include bounds (left/top/right/bottom) in prompt JSON (not just center).
   - Include resource_id and text indices or at least provide resource_id/text in prompt so model can populate Target.
   - Status: PARTIAL (bounds added; no explicit indices).

4) Add focus_and_input_text + focus_and_clear_text actions
   - Focus first using Target; if mismatch between resource_id/text, drop id like Minitap.
   - Support clear/replace via ACTION_SET_TEXT with empty string or keyboard delete fallback.
   - Optionally return the full field content after input when resource_id is available.
   - Status: PARTIAL (type now supports multi-selector focus + clear, and has a resource_id/target_text mismatch defense; still no dedicated focus_and_input_text tool or post-type verification).

### Phase 2 (P1: coordinate + scroll robustness)
5) Add coordinate tap and area tap actions
   - click_at(x,y) and click_area(x1,y1,x2,y2) (center-based).
   - This provides explicit fallback when a11y is stale.
   - Status: DONE (merged into click fallback; no separate actions).

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
    - Status: PARTIAL (attempt logs + element-not-found details for click/long_press/type; still missing structured “selector used” on success).

## 3) Quick wins (minimal code, high impact)
- DONE: Add clear flag to type.
- DONE: Add bounds to Perceptor JSON.
- DONE: Add click fallback when element_index fails (multi-selector click).
- DONE: Add attempt logs to tool error output (click/long_press/type).
- DONE: Add tests for selector ordering and key mismatch defense.

## 4) Validation checklist (post-change)
- DONE: Tap by resource_id when element_index is stale.
- TODO: Tap when bounds are partially off-screen (explicit out-of-bounds errors).
- DONE: Type into focused field with and without clear.
- DONE: Type into a field via resource_id/target_text/bounds/x-y targeting (multi-selector).
- DONE: Long press via resource_id/text/bounds/x-y targeting (multi-selector).
- TODO: Scroll with direction helper; verify direction semantics.
- TODO: get_state retry paths for transient accessibility failures.
