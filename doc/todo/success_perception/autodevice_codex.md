# AutoDevice (AndroidWorld AutoDev) - history/state + context window notes

## Sources (local)
- `doc/todo/success/autodevice_codex.md`
- `doc/todo/success/autodevice_claude.md`
- `doc/todo/success/improvement_recommendations_codex.md`
- `doc/todo/success/improvement_recommendations_claude.md`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/llm.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/scratchpad.py`
- `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/todo_list.py`

## 1) Context window contents (beyond chat history)
- Current screenshot is sent every planner/executor turn (multimodal input). (See `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/llm.py` in `AutoDevLLM.chat`.)
- A `<screen_transcription>` block is appended when `transcription` is provided, but in AutoDev this is used for system info (device date + navigation warnings), not OCR unless the planner explicitly calls `transcribe_screen()`. (See `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py` and `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/llm.py`.)
- A scratchpad system reminder is appended to each user message (with available PAD keys). This is the cross-step memory mechanism. (See `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/scratchpad.py` and `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/llm.py`.)
- A todo-list system reminder is appended when todo mode is enabled. (See `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/todo_list.py` and `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/llm.py`.)

## 2) Screenshot retention and history
- Images are **not kept in LLM history**. After each call, image blocks are removed from stored messages to save memory. (See `_remove_image_blocks_from_history()` in `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/llm.py`.)
- Only the *current* screenshot is sent to the model; past screenshots are represented implicitly via text (e.g., scratchpad notes, todo list, or warnings). This avoids the “keep every screenshot in history” performance hit.
- Navigation state stores **hashes** of screenshots to detect repeat screens (loop prevention) rather than storing the image content. (See `seen_screenshots` in `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py`.)

## 3) Other context injected
- Current device date is injected into `<system_info>` each step. (See `_get_current_device_date()` usage and system info build in `.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py`.)
- Scroll/loop warnings are injected into `<system_warnings>` when repeated screens or excessive scrolling are detected. (Same file as above.)
- Planner/executor scratchpad tools (`createItem`, `fetchItem`) and todo tool (`update_todos`) are always available in tool schema. (See `Scratchpad.get_*_tool` and `TodoList.get_tool` in their respective files.)

## 4) A11y tree sanitization
- **Not applicable** in AutoDev. It is screenshot-first with optional OCR via `transcribe_screen()`, not a11y-tree-driven.
