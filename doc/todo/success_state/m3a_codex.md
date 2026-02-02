# M3A (AndroidWorld baseline) - history/state + context window notes

## Sources (local)
- `doc/todo/success/m3a_claude.md`
- `doc/todo/success/improvement_recommendations_codex.md`
- `doc/todo/success/improvement_recommendations_claude.md`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/m3a.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/m3a_utils.py`

## 1) Context window contents (beyond chat history)
- Action-selection prompt includes: current goal, **text history of step summaries**, current screenshot (raw + SOM-annotated), and the **UI elements list** with indices. (See `.reference/mobile_agent/autodevice_android_world/android_world/agents/m3a.py`.)
- Summarization prompt includes: before/after screenshots (both with labels), action + reason, and UI element lists for both before/after. The summary is then appended to history for future steps. (Same file.)

## 2) Screenshot retention and history
- Each step stores a `step_data` dict in `self.history`, which includes **raw + annotated screenshots** and UI element lists. There is no pruning or image stripping in history. (See `self.history.append(step_data)` in `.reference/mobile_agent/autodevice_android_world/android_world/agents/m3a.py`.)
- LLM context does **not** include all previous screenshots; it only receives the **current** step’s images. However, the in-memory history itself can grow large if a run is long (potential performance/memory impact outside the context window).

## 3) Other context injected
- The UI element list includes text, content descriptions, hints/tooltips, and state flags (clickable, editable, selected, etc.). (See `_generate_ui_element_description()` in `.reference/mobile_agent/autodevice_android_world/android_world/agents/m3a.py`.)
- Step summaries are explicitly intended as memory for cross-step info transfer. (See summary prompt in `.reference/mobile_agent/autodevice_android_world/android_world/agents/m3a.py`.)

## 4) A11y tree sanitization
- Elements are **filtered** before inclusion via `validate_ui_element()`:
  - Must be visible.
  - Bounding box must be valid and intersect the screen. (See `.reference/mobile_agent/autodevice_android_world/android_world/agents/m3a_utils.py`.)
- Only filtered elements are used for SOM annotation and for the UI element list in the prompt.
