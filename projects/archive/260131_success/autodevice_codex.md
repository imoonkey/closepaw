# AutoDevice (AutoDev agent in AndroidWorld) - leaderboard methodology summary

## Sources (local)
- `.reference/autodevice_android_world/README.md`
- `.reference/autodevice_android_world/android_world/agents/README.md`
- `.reference/autodevice_android_world/android_world/agents/autodev_agent.py`
- `.reference/autodevice_android_world/android_world/agents/autodev/prompts.py`
- `.reference/autodevice_android_world/android_world/agents/autodev/executor_tools.py`

## Architecture and agent roles
- Planner-executor split with a dedicated AutoDev planner LLM (system prompt in `prompts.py`).
- Shared scratchpad for cross-step memory; planner must `createItem` then `fetchItem` before reuse.
- Executor uses tool registry for actions, with deterministic scaling of coordinates.
- Adaptive model routing: planner model chosen by task difficulty (from `task_metadata.json`).
- Navigation state tracking inside the agent to reduce loops (seen items, scroll history, visited screens).

## Tooling and action model
- Action space is explicit and low-level: `click`, `double_tap`, `long_press`, `scroll`, `swipe`, `swipe_coords`, `input_text`, `keyboard_enter`.
- Tools use a global `SCALE` to normalize coordinates from model resolution to device.
- Planner uses `transcribe_screen()` only when text extraction is required.

## Screen perception
- **Screenshot-first**: the planner is instructed to analyze screenshots directly; it calls `transcribe_screen()` only when it needs text/UI labels.
- `transcribe_screen()` is defined as a full-screen transcription tool and is backed by an LLM-based screenshot OCR helper (`transcription.py`), though the helper is noted as not wired into the main workflow yet.
- Interaction primitives are **coordinate-based**, so perception focuses on visual understanding + text transcription rather than a11y element indices.

## Prompt policies that boost success
- Planner analyzes screenshot directly; calls `transcribe_screen()` only if needed.
- Strong todo-based planning: `update_todos()` required for multi-step tasks.
- Strict date-range logic with explicit rules for "next week" and handling overdue items.
- Detailed playbooks for complex task types:
  - count/search tasks must filter first, then answer immediately after findings.
  - duplicate deletion requires exhaustive item-by-item comparisons.
  - merge operations require exact newline semantics (`\n` vs `\n\n`).
  - multi-app tasks require extracting all data before switching apps.
- Failure handling: executor summarizes failed attempts; planner must switch strategies (no repeated approach).

## Why this likely performs well
- Rich system prompt encodes many task-specific edge cases that are common failure modes.
- Explicit "answer now" rule reduces over-scrolling and step budget waste.
- Deterministic action primitives + coordinate scaling stabilize execution.
- Task difficulty-based model routing balances cost vs accuracy.

## Where our Android agent can improve (actionable)
- Add a planner scratchpad with strict create/fetch semantics for cross-app workflows.
- Encode date-range and relative-date rules in system prompt to avoid calendar errors.
- Add type-specific playbooks (count/search, duplicates, merge/rename, filters-first).
- Use explicit todo tracking for multi-step tasks and require verification before completion.
- Add deterministic coordinate scaling and a consistent low-level action schema.
- Route to stronger models for hard tasks using task metadata or heuristics.
