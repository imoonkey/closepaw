## Python Playground for Core Agent Performance Iteration

### Goals
- Replicate the Kotlin agentic loop behavior in Python as closely as possible.
- Use ADB to capture a11y trees and execute actions, enabling fast iteration off-device.
- Keep prompt construction, tool schemas, and turn logic equivalent to Android app.
- Make the playground runnable via `uv` with Python >= 3.13.

### Non-goals
- Perfect feature parity with AccessibilityService internals (node actions, IME).
- Full UI rendering or emulator automation beyond ADB + uiautomator.
- Long-term production readiness; this is an iteration playground.

### Scope
1. Agent loop (ReAct turn cycle, history, tool call selection, completion logic).
2. A11y tree input and sanitization (element filters, dedup, truncation).
3. Prompt building (system + turn rules, user context with a11y JSON).
4. Tool definitions and parameter schemas (mobile_action, app_control, complete_task).
5. ADB-backed platform for actions (tap/type/swipe/system, app list/open).

### Architecture Overview
```
ADB (uiautomator, input, pm, am, screencap)
        |
    AdbPlatform
        |
  Perceptor (XML -> ScreenSnapshot)
        |
  AgentPromptBuilder + TurnInputBuilder
        |
        Turn (LLM call w/ tool schemas)
        |
  ToolRouter (validate -> execute)
        |
  AdbPlatform (actions + post-action observation)
```

### Data Model Parity (Kotlin -> Python)
- `ScreenSnapshot(timestamp, elements, image)`
- `PerceptionElement(index, text, resource_id, class_name, description, clickable, editable, scrollable, bounds, center)`
- `Bounds(left, top, right, bottom)` and `Point(x, y)`

### A11y Sanitization Parity
Match `Perceptor.kt` as closely as possible:
- `MAX_ELEMENTS = 80`, `MAX_STRING_LENGTH = 60`
- Two-pass traversal:
  1) Interactive only (clickable OR editable OR scrollable)
  2) All elements (interactive OR has text/desc)
- Dedup key: `resourceId|className|text|desc|C|E|S|left,top,right,bottom`
- Normalize whitespace:
  - collapse spaces/tabs
  - collapse multi-newlines
  - trim
- Output JSON for prompt:
  - fields: index, text, id, class, desc, clickable, editable, scrollable, center

Note: Uiautomator XML lacks AccessibilityNodeInfo actions. We approximate
`editable` with `editable="true"` OR class name ending with `EditText`/`TextInputEditText`.

### Tooling Parity
Mirror Kotlin tools and schemas:
- `mobile_action`
  - click (element_index)
  - long_press (element_index, duration_ms)
  - type (text, element_index optional)
  - swipe (start [x,y], end [x,y], duration_ms)
  - system_button (back/home/enter/recents)
  - wait (duration_ms)
- `app_control`
  - list_apps (filter)
  - open_app (package_name or app_name)
- `complete_task`
  - status, answer, reason

### ADB Platform Behavior
- `capture_screen()`:
  - `adb exec-out uiautomator dump /dev/tty` (fallback to `/sdcard/` + pull)
  - parse XML into `ScreenSnapshot`
  - optional screenshot capture for OpenAI image input
- `perform_action()`:
  - click: `adb shell input tap x y`
  - long_press: `adb shell input swipe x y x y duration`
  - type: `adb shell input text <escaped>`
  - swipe: `adb shell input swipe x1 y1 x2 y2 duration`
  - system_button: `adb shell input keyevent KEYCODE_BACK|HOME|ENTER|APP_SWITCH`
  - wait: `sleep`
- `list_apps`: `adb shell pm list packages -3` + label lookup (optional)
- `open_app`: `adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1`

### Prompt Separation on Kotlin Side (Optional)
If iteration speed benefits from separating prompts:
- Move `DEFAULT_SYSTEM_PROMPT` and `LOCAL_PROMPT_SUFFIX` to assets:
  - `app/src/main/assets/prompts/agent_system.txt`
  - `app/src/main/assets/prompts/agent_local_suffix.txt`
- Add a `PromptRepository` that loads/caches these at runtime with fallback to
  hardcoded defaults if missing.
- This keeps tool schemas in Kotlin, but allows prompt tuning without code edits.

### Python Project Layout
```
python/
  pyproject.toml
  README.md
  src/android_agent_playground/
    __init__.py
    cli.py
    config.py
    agent.py
    turn.py
    prompt.py
    history.py
    perceptor.py
    models.py
    tools/
      __init__.py
      registry.py
      mobile_action.py
      app_control.py
      complete_task.py
    platform/
      __init__.py
      adb.py
    llm/
      __init__.py
      base.py
      openai.py
```

### CLI Flow
1. `android-agent-playground run --goal "..."`
2. Capture a11y tree
3. Build system + user context
4. Call LLM, parse tool call(s)
5. Execute single tool, capture observation
6. Loop until completion or max turns

### Risks and Gaps
- A11y data mismatch vs AccessibilityService (editable/action support)
- ADB input is less reliable than Accessibility actions for complex UIs
- LLM tool calling differences between SDKs
- App label resolution without PackageManager on device

### Next Steps
1. Scaffold `python/` project with uv + core modules.
2. Implement a11y XML parsing + sanitization to match Kotlin.
3. Implement minimal OpenAI tool-calling client and tool registry.
4. Wire the ReAct loop with history + single-tool-per-turn enforcement.
