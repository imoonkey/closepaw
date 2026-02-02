# DroidRun - history/state + context window notes

## Sources (local)
- `doc/todo/success/droidrun_codex.md`
- `doc/todo/success/droidrun_claude.md`
- `doc/todo/success/improvement_recommendations_codex.md`
- `doc/todo/success/improvement_recommendations_claude.md`
- `.reference/mobile_agent/droidrun/droidrun/agent/manager/manager_agent.py`
- `.reference/mobile_agent/droidrun/droidrun/agent/executor/executor_agent.py`
- `.reference/mobile_agent/droidrun/droidrun/agent/droid/state.py`
- `.reference/mobile_agent/droidrun/droidrun/tools/android/adb.py`
- `.reference/mobile_agent/droidrun/droidrun/tools/filters/concise_filter.py`
- `.reference/mobile_agent/droidrun/droidrun/tools/filters/detailed_filter.py`
- `.reference/mobile_agent/droidrun/droidrun/tools/formatters/indexed_formatter.py`

## 1) Context window contents (beyond chat history)
- **Manager** injects into the last user message: memory, current device state, screenshot (if vision), and script results; it also injects **previous device state** into the prior user message for before/after reasoning. (See `_build_messages_with_context()` in `.reference/mobile_agent/droidrun/droidrun/agent/manager/manager_agent.py`.)
- **Manager system prompt** variables include device date, app card, error history, custom variables, available secrets, output schema, and scripter config. (See `_build_system_prompt()` in the same file and prompt loader in `.reference/mobile_agent/droidrun/droidrun/config/prompts/*`.)
- **Executor** prompt includes current device state, plan/subgoal, progress summary, action history (last 5), atomic action schema, and available secrets; adds screenshot if vision enabled. (See `prepare_context()` in `.reference/mobile_agent/droidrun/droidrun/agent/executor/executor_agent.py`.)
- Shared state also tracks action history, summaries, error flags, memory, and message history, which are fed back into Manager/Executor prompts. (See `.reference/mobile_agent/droidrun/droidrun/agent/droid/state.py`.)

## 2) Screenshot retention and history
- Screenshots are **captured per step** and stored as `shared_state.screenshot`, then attached to the *current* LLM call (Manager/Executor). They are **not persisted** inside `shared_state.message_history`. (See `prepare_context()` and `_build_messages_with_context()` in Manager; Executor’s `prepare_context()`.)
- For logging/telemetry, screenshots can be recorded via events or tracing, but those are outside LLM context. (See `record_langfuse_screenshot()` usage in Manager.)
- This design avoids carrying all screenshots in the context window; only the latest image is used.

## 3) Other context injected
- App cards (app-specific guidance) are loaded and injected into Manager system prompt when enabled. (See `_initialize_app_card_provider()` and `_build_system_prompt()` in Manager.)
- Error history is injected when `error_flag_plan` is set, with the last N action outcomes. (See `_build_system_prompt()`.)
- Memory is appended into the last user message via `<memory>` tags; progress summary and last action are also used. (See `_build_user_message_content()` and `_build_messages_with_context()`.)

## 4) A11y tree sanitization
- **Filtering** happens before formatting:
  - Vision enabled => `ConciseFilter` (removes nodes outside screen bounds or below min size). (See `.reference/mobile_agent/droidrun/droidrun/tools/filters/concise_filter.py` and selection in `.reference/mobile_agent/droidrun/droidrun/tools/android/adb.py`.)
  - Vision disabled => `DetailedFilter` (filters keyboard elements, removes low-visibility nodes, optional bounds clipping). (See `.reference/mobile_agent/droidrun/droidrun/tools/filters/detailed_filter.py`.)
- **Formatting** then flattens and trims to a small schema: index, short class name, resourceId, text (fallbacks), and bounds. (See `.reference/mobile_agent/droidrun/droidrun/tools/formatters/indexed_formatter.py`.)
- The formatted device state (not the raw tree) is what gets injected into the LLM context. (See `get_state()` in `.reference/mobile_agent/droidrun/droidrun/tools/android/adb.py`.)
