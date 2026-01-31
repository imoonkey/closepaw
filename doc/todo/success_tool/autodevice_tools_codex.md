# AutoDevice (AutoDev) tools deep dive (code)

Sources used
- .reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/executor_tools.py
- .reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/prompts.py

## Tool list
Coordinate-based interaction
- click(x,y), tap(x,y)
- double_tap(x,y)
- long_press(x,y)
- scroll(direction, x?, y?)
- swipe(direction, x?, y?)
- swipe_coords(start_x, start_y, end_x, end_y)

Text + system
- input_text(text, x?, y?, clear_text=False)
- type_text(text, clear_first=False)
- keyboard_enter()
- navigate_back(), navigate_home()
- wait()

App control
- open_app(app_name)

Screen text
- transcribe_screen() (full-screen transcription tool)

Reporting / callbacks
- extracted_data(data)
- report(notes)

## Targeting + parameters (nitty-gritty)
Coordinate-only targeting
- All tap/press actions take explicit pixel coordinates (x,y).
- scroll/swipe can use optional origin coordinates; otherwise center.
- No resource_id, bounds, or index selectors.

Deterministic coordinate scaling
- All coordinates are scaled by SCALE=0.4 (x and y divided by SCALE) before creating the JSONAction.
- This forces a fixed logical coordinate system for the model and makes device-resolution handling deterministic.

Text input targeting
- input_text can optionally click at x,y before typing; clear_text allows replace-mode.
- type_text uses focused field only; no coordinates.

## Execution details
- Tools are pure constructors returning JSONAction objects; execution happens in AndroidWorld env.
- No built-in retry or fallback logic in the tool itself.
- transcribe_screen exists as a tool but is a stub in executor_tools.py; actual wiring is elsewhere.

## Prompt / policy details that matter (from prompts.py)
- Planner is instructed to use transcribe_screen only when text extraction is needed.
- Explicit rules for task types (counts, filters-first, duplicates/merge exactness) live in prompt, not in tools.

## What this suggests for our tools
- A fixed coordinate normalization scheme reduces resolution drift.
- input_text supports clear/replace as first-class.
- The tool layer is thin; reliability is mostly in prompt policies.
