# Minitap (mobile-use) tools deep dive (code + prompts)

Sources used
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/index.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/mobile/tap.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/mobile/focus_and_input_text.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/mobile/swipe.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/types.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/utils.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/controllers/unified_controller.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/controllers/android_controller.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/cortex/cortex.md
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/executor.md
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/tool_node.py

## Tool list (executor-facing)
Mobile actions
- tap(target)
- long_press_on(target)
- swipe (composite tools: swipe_coordinates, swipe_percentages)
- focus_and_input_text(text, target)
- focus_and_clear_text(target)
- erase_one_char(nb_chars)
- press_key(key)
- back()
- launch_app(package_or_bundle_id)
- stop_app(package_or_bundle_id)
- open_link(url)
- wait_for_delay(ms)

Memory + video tools
- save_note(name, content), read_note(name), list_notes()
- start_video_recording(), stop_video_recording()

## Targeting + parameters (nitty-gritty)
Target schema (pydantic Target)
- resource_id + resource_id_index
- text + text_index
- bounds (x, y, width, height)
- default indices: if resource_id or text present and index omitted, defaults to 0.

Tap fallback order (actual implementation)
1) bounds -> coordinate tap at bounds center
2) resource_id -> find element by resource-id, index
3) text -> find element by text, index
Notes:
- Tap validates bounds against screen width/height and returns explicit "out of bounds" errors.
- Attempts are tracked and returned in tool output for debugging.
- This order is IMPORTANT: it is coordinates-first (not resource_id-first).

Prompt vs code mismatch
- Cortex prompt claims fallback is resource_id -> bounds -> text.
- Actual tap tool uses bounds -> resource_id -> text.
- This mismatch is a likely source of model/tool drift when only text is reliable.

Element lookup
- android_controller.find_element() matches exact resource-id OR exact text/accessibilityText.
- If multiple matches, index selects which; if index out of range, returns explicit error.
- bounds are parsed from "[x1,y1][x2,y2]" string in UI hierarchy.

Focus + input logic
- focus_and_input_text() calls focus_element_if_needed():
  - if resource_id + text both provided, it checks that the text for that id matches; if mismatch, resource_id is ignored to avoid wrong-field focus.
  - then tries resource_id, else bounds, else text.
- After focus, move_cursor_to_end_if_bounds() taps near bottom-right of bounds to place cursor at end.
- If resource_id is provided, it re-reads UI hierarchy and returns full content of that input in the tool output.

Swipe
- Supports coordinates or percentages; percentages converted to coordinates using device width/height.

## Execution details (ordering, failure handling)
- ExecutorToolNode executes tool calls strictly sequentially.
- If a tool call fails, subsequent tool calls are aborted with a synthetic error message.
- Telemetry records per-tool success/failure.

## Prompt / policy details that matter
- Cortex requires "one unpredictable action per turn" for back/launch_app/stop_app/open_link and navigation taps.
- Cortex requires completion only on observed evidence.
- Executor requires agent_thought for each tool call and follows order strictly.

## What this suggests for our tools
- Rich Target object with multiple selectors and explicit indices.
- Explicit fallback order in both prompt and code (keep them aligned).
- Bounds validation + detailed attempt logging helps for recovery.
- Focus step validates id/text consistency to avoid wrong-field typing.
- Sequential tool execution with abort-on-failure reduces cascading errors.
