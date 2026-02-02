# Minitap (mobile-use) - history/state + context window notes

## Sources (local)
- `doc/todo/success/minitap_codex.md`
- `doc/todo/success/minitap_claude.md`
- `doc/todo/success/improvement_recommendations_codex.md`
- `doc/todo/success/improvement_recommendations_claude.md`
- `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/contextor/contextor.py`
- `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/cortex/cortex.py`
- `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.py`
- `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/executor.py`
- `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/graph/state.py`
- `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/clients/ui_automator_client.py`
- `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/scratchpad.py`

## 1) Context window contents (beyond chat history)
- **Contextor -> Cortex pipeline**:
  - Contextor gathers `latest_ui_hierarchy`, `latest_screenshot` (base64), focused app info, and device date. (See `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/contextor/contextor.py`.)
  - Cortex builds a fresh message set each step: system prompt (goal, subgoals, tools, app lock), device info + device date + focused app info, **agent thoughts history**, then UI hierarchy JSON and screenshot (compressed). (See `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/cortex/cortex.py`.)
- **Planner** context includes initial goal, previous plan, and agent thoughts; also platform, tool list, and app-lock state. (See `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.py`.)
- **Executor** context includes Cortex decisions + last thought + accumulated executor messages; tools are bound at call time. (See `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/executor/executor.py`.)
- **Scratchpad** is persistent key/value memory via tools (`save_note`, `read_note`, `list_notes`), but its contents are not auto-injected into prompts unless tools are invoked. (See `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/scratchpad.py`.)

## 2) Screenshot retention and history
- Only the **latest screenshot** is stored in state (`latest_screenshot`) and sent to Cortex; it is **cleared immediately after** Cortex runs. (See `state.asanitize_update` in `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/cortex/cortex.py`.)
- Screenshots are **compressed** before sending to the LLM (`get_compressed_b64_screenshot`). (See `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/cortex/cortex.py` and controller implementations.)
- This avoids keeping a full screenshot history in the context window; only the current image is used per step.

## 3) Other context injected
- Agent thoughts accumulate in state and are re-fed to Cortex as AI messages, providing a lightweight textual history. (See `agents_thoughts` in `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/graph/state.py` and usage in Cortex.)
- App lock state and current foreground app are injected into planner/contextor prompts to enforce app constraints. (See Contextor and Planner code.)

## 4) A11y tree sanitization
- UI hierarchy comes from UIAutomator2 `dump_hierarchy(compressed=True)` and is parsed into a **flat list of element dicts** with selected attributes (resource-id, text, content-desc/accessibilityText, bounds, class, package, and common flags). (See `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/clients/ui_automator_client.py`.)
- There is **no explicit visibility/size filtering** before inserting the hierarchy into the Cortex prompt; sanitization is mainly the XML cleanup + attribute selection.
