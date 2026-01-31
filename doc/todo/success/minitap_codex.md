# Minitap (mobile-use) - leaderboard winner methodology summary

## Sources (local)
- `.reference/minitap-mobile-use/README.md`
- `.reference/minitap-mobile-use/minitap/mobile_use/agents/orchestrator/orchestrator.md`
- `.reference/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md`
- `.reference/minitap-mobile-use/minitap/mobile_use/agents/cortex/cortex.md`
- `.reference/minitap-mobile-use/minitap/mobile_use/agents/executor/executor.md`
- `.reference/minitap-mobile-use/minitap/mobile_use/agents/contextor/contextor.md`
- `.reference/minitap-mobile-use/minitap/mobile_use/agents/hopper/hopper.md`
- `.reference/minitap-mobile-use/minitap/mobile_use/agents/outputter/human.md`

## Architecture and agent roles
- Multi-agent split with clear separation of concerns:
  - Planner: decomposes goals into sequential, purpose-driven subgoals.
  - Cortex: reads UI state, decides structured actions, and marks subgoals complete only on observed evidence.
  - Executor: executes tool calls in order, no strategy, always includes `agent_thought` for tracing.
  - Orchestrator: tracks subgoal completion, triggers replanning when repeated failures occur.
  - Contextor: enforces app lock, only allows deviations for explicit OAuth/permission flows.
  - Hopper: extracts info from large batch inputs (e.g., package lookup) without reformatting.
  - Outputter: formats final structured output if a schema is requested.

## Tooling and action model
- Emphasis on tool usage and robust element targeting (resource_id + bounds + text + indexes) for fallback.
- Uses both UI tree and screenshot; explicitly calls out their complementary strengths.
- Strong constraints around actions that change state unpredictably (`back`, `launch_app`, `open_link`): one such action per turn.
- Text tooling prefers `focus_and_input_text` and `focus_and_clear_text`; includes fallbacks (long-press, select-all, erase).

## Screen perception
- Two-channel perception: **UI hierarchy (a11y)** for resource-id/text/bounds targeting, and **screenshot** for visual cues/verification.
- Cortex requires combining both signals because each channel has limits (UI tree lacks visual context; screenshot lacks precise element metadata).

## Prompt policies that boost success
- Planner subgoals are milestone-based (not button-by-button), sequential, no loops.
- Always include a verification subgoal when formatting constraints exist.
- App lock is a first-class constraint; avoid reopening app already in foreground.
- Cortex requires reading agent thoughts before acting; avoids repeating failed actions.
- Data fidelity rules: exact transcription and no premature completion.
- Explicit video recording pattern for transcription tasks (start-record -> play -> stop-record as separate subgoals).

## Why this likely performs well
- Tight multi-agent separation keeps planning, execution, and safety policies focused.
- Error recovery is baked into both Planner (replan) and Cortex (avoid repeated failed actions).
- High precision targeting + fallback helps with noisy accessibility trees.
- Strong task constraints (data fidelity, app lock, and verification) reduce silent errors.

## Where our Android agent can improve (actionable)
- Add a strict element-target schema with fallback order (resource_id -> bounds -> text) plus indices.
- Enforce a "single unpredictable action per step" guardrail to reduce cascading UI changes.
- Introduce an explicit app-lock / contextor layer for OAuth/permission flow handling.
- Require plan-level verification subgoals for formatting-sensitive outputs.
- Add a dedicated subgoal pattern for video tasks (start recording before playback, stop after).
- Adopt a Cortex-style rule: never mark completion without observed evidence in the current UI state.
