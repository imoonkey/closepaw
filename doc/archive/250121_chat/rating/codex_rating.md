# Chat Design Ratings (Codex)

Scope: evaluate the three independent designs in `doc/todo/chat_design/` for a multi-round chat MVP with streaming UI text. Ratings are 1-10.

## Evaluation Rubric
- **MVP scope fit**: minimal changes, quick to ship
- **Reuse of existing architecture**: leverages `AgentSession`, `HistoryManager`, existing events
- **Streaming + UI practicality**: clear contract, throttling/UX concerns handled
- **Tool integration + ReAct compatibility**: keeps tool loop usable in chat
- **Clarity + implementability**: actionable steps, concrete changes
- **Risk + complexity**: likelihood of regressions, refactor surface area

## claude.md
- **MVP scope fit**: 6/10 — comprehensive but introduces multiple new layers (`ChatSession`, `StreamingTurn`, new event types) that feel heavier than MVP.
- **Reuse of existing architecture**: 6/10 — wraps `Turn` but forks a parallel session stack instead of reusing `AgentSession`.
- **Streaming + UI practicality**: 8/10 — strong event model, clear UI handling, streaming-first focus.
- **Tool integration + ReAct compatibility**: 8/10 — includes tool call streaming and ReAct cycle continuation.
- **Clarity + implementability**: 8/10 — detailed component list, migration phases, and sample APIs.
- **Risk + complexity**: 5/10 — more moving parts, simulated streaming could diverge from future real streaming.
- **Overall**: **7/10**
  - Best for a full-featured chat foundation, but heavier than needed for MVP.

## codex.md
- **MVP scope fit**: 9/10 — intentionally minimal, uses `Op.UserInput` and existing session flow.
- **Reuse of existing architecture**: 9/10 — stays inside `AgentSession` + `HistoryManager` and extends `AgentEvent`.
- **Streaming + UI practicality**: 8/10 — clear delta/done/error contract, throttling, newline-gated rendering.
- **Tool integration + ReAct compatibility**: 5/10 — explicitly defers tool-calling chat changes (gap for tool-first agent).
- **Clarity + implementability**: 8/10 — straightforward steps, small set of new types.
- **Risk + complexity**: 8/10 — low refactor surface area, incremental steps.
- **Overall**: **8/10**
  - Best MVP path if we can accept tool use as a follow-up.

## gemini.md
- **MVP scope fit**: 7/10 — moderate scope; proposes agent-loop state machine.
- **Reuse of existing architecture**: 6/10 — refactors `Agent.run()` into a new state model.
- **Streaming + UI practicality**: 7/10 — defines deltas + streaming path, but less concrete than claude/codex.
- **Tool integration + ReAct compatibility**: 8/10 — keeps tools interleaved with streaming turns.
- **Clarity + implementability**: 6/10 — broader strokes, fewer actionable details.
- **Risk + complexity**: 6/10 — agent loop refactor introduces regression risk.
- **Overall**: **7/10**
  - Balanced vision with tool support, but heavier and less concrete for MVP.

## Quick Recommendation
- **MVP-first**: codex.md as the baseline, add minimal tool streaming later.
- **Tool-heavy MVP**: mix codex.md’s minimal event model with gemini.md’s tool loop ideas.
- **Full foundation**: claude.md if we accept extra architecture now.
