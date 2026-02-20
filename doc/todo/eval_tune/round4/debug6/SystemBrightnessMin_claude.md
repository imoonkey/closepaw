# SystemBrightnessMin — Click Analysis

**Task**: Turn brightness to the min value.
**Result**: FAILED (GoalAchieved but scripted_success=false, 3 turns, 0 tool failures)

## Turn-by-Turn Action Analysis

| Turn | Action | Target | Result | UI Changed? |
|------|--------|--------|--------|-------------|
| 1a | write_todos | 3-item plan | Success: Plan updated | — |
| 1b | open_app "Settings" | — | Success: Launched Settings | ✓ Settings opened |
| 2 | scroll down idx=5 | Settings list | Scrolled via gesture_swipe | ✓ Display option visible |
| 3 | **(TEXT OUTPUT, NOT TOOL CALL)** | — | No tool executed | **✗ Session ended** |

## The Critical Turn 3 Failure

### LLM Raw Output (Turn 3)

```
write_todos({"todos":[{"description":"Open Settings app to access display/brightness controls","status":"completed"},{"description":"Navigate to Display settings","status":"in_progress"},{"description":"Adjust brightness slider to minimum value","status":"pending"}],"agent_thought":"Found Display option with brightness mentioned. Clicking on it to access brightness controls."})
mobile_action({"action":"click","element_index":20,"agent_thought":"Clicking on Display settings to access brightness controls."})
```

The LLM (qwen3.5) emitted **function call syntax as plain text** rather than structured tool calls. The output was parsed as:
- `has_text: true` (text was present)
- `tool_calls: 0` (no structured tool calls detected)
- `is_complete: true` (text-only response = terminal)

This caused the session to end after only 3 turns with no brightness adjustment made.

### Pre-failure A11y Tree (Turn 2 post-state)

```json
{
  "index": 20,
  "text": "Display\nDark theme, font size, brightness",
  "class": "LinearLayout",
  "clickable": true,
  "bounds": [0, 1582, 1080, 1813],
  "center": [540, 1697]
}
```

The agent was looking at the correct target — Display settings with brightness mentioned in the description. Element 20 was clickable at center (540, 1697). If the tool call had been structured, it would have tapped Display and continued to the brightness slider.

## Key Observations

### Only 3 Turns — Premature Termination

This is the shortest failed task. Compare:
- BrowserMultiply: 30 turns (stuck on DocumentsUI)
- FilesMoveFile: 30 turns (stuck on DocumentsUI)
- SystemBrightnessMax: 17 turns (achieved 98%)
- ClockTimerEntry: 7 turns (correctly completed, eval gap)
- **SystemBrightnessMin: 3 turns** (LLM format error)

The task never even reached the brightness control. The agent was on the right path (Settings → Display → Brightness) but the LLM's output format broke the tool call pipeline.

### Agent Reasoning Was Correct

The LLM's reasoning was sound:
1. Open Settings ✓
2. Scroll to find Display option ✓
3. Click Display to access brightness controls ← correct target, wrong format

The `agent_thought` in the text output confirms correct identification: "Found Display option with brightness mentioned. Clicking on it to access brightness controls."

### qwen3.5 Model-Specific Issue

This failure mode is specific to the `qwen3.5-plus-02-15` model used in this eval run. The model sometimes emits function calls as text strings (`function_name({json})`) instead of structured tool_use blocks. This is a known limitation — the model's function calling is not 100% reliable, especially in multi-turn scenarios where it switches between text and tool outputs.

## Root Cause: LLM Tool Call Format Error

**Category**: Reasoning (Model)

The qwen3.5 model emitted function calls as plain text instead of structured tool_use responses. The agent framework interpreted this as a text-only (terminal) response and ended the session. No execution issue — the actions were never dispatched.

## Proposed Fixes

1. **Primary**: Add a **text-as-tool-call recovery parser** in the agent framework. When a response contains `has_text: true, tool_calls: 0`, scan the text for patterns like `function_name({json})` and attempt to parse them as tool calls. This recovers from the LLM's formatting errors without model changes.

2. **Secondary**: Add a **retry mechanism** — when a text-only response is detected but the task is not complete, send a follow-up prompt asking the model to re-emit its intended actions as proper tool calls.

3. **Alternative**: Switch to a model with more reliable function calling (e.g., Claude, GPT-4) for tasks where tool call reliability is critical.

4. **Defensive**: Don't treat text-only responses as terminal when the task hasn't been explicitly completed. Only end the session when `complete_task` is called or max turns are reached.
