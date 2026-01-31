# M3A (AndroidWorld baseline) tools deep dive (code)

Sources used
- .reference/mobile_agent/autodevice_android_world/android_world/agents/m3a.py

## Tool list (action_type JSON)
Completion + answer
- status(goal_status=complete|infeasible)
- answer(text)

Interaction
- click(index)
- long_press(index)
- input_text(text, index)
- keyboard_enter
- navigate_home
- navigate_back
- scroll(direction, index?)
- open_app(app_name)
- wait

## Targeting + parameters (nitty-gritty)
Index-based targeting
- UI elements are shown as bounding boxes with numeric indices on screenshots.
- The model must choose an index that is visible on the screenshot AND present in the UI element list.

Text input targeting
- input_text includes clicking the text field + typing + enter; no separate focus action required.

Scroll targeting
- scroll(direction, index?) can target a specific scrollable UI element by index or the whole screen.
- Direction semantics are explicitly explained (scroll down to see bottom content).

## Execution details
- Single-agent loop: action selection -> execute -> summarization; two LLM calls per step.
- No explicit tool-level retries; retry behavior is in prompt guidance.
- Uses both raw screenshot and annotated (SOM) screenshot for decision; action history uses step summaries.

## Prompt / policy details that matter
- Strong constraint: index must be visible and in the element list.
- Use open_app rather than app drawer.
- For questions, answer() must be used before status complete.

## What this suggests for our tools
- Index-only targeting can work if UI list and SOM overlays stay aligned.
- Summarization per step gives lightweight memory without a separate memory tool.
