status: draft

# Refactor 02: Agent Turn Pipeline Decomposition

Date: 2026-02-16
Goal: split `AgentTurnRunner` into focused units with stable interfaces while preserving behavior.

## Problem
`AgentTurnRunner` (788 LOC) handles pre-turn capture, prompt/build, streaming collection, arbitration, tool execution, observation capture, thought/status emission, and error classification in one file.

## Scope
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`
- New package candidates under `agent/turn/`.

## Design
1. Extract pure policy helpers.
- Completion decision and warning builders become stateless utilities.

2. Extract planning stage.
- `TurnPlanningStage`: model resolution, prompt construction, LLM streaming, arbitration output.

3. Extract execution stage.
- `TurnExecutionStage`: tool-call execution loop, observation handling, post-action capture.

4. Keep runner as orchestrator only.
- Runner coordinates stage calls and state transitions.

## Phases
### Phase 1
- No behavior changes.
- Move code into new stage classes and wire back through current public entrypoints.

### Phase 2
- Add narrow tests for each stage: planning output, execution flow, completion decisions.

### Phase 3
- Reduce cross-stage shared mutable state (favor immutable stage inputs/outputs).

## Risks
- Regression in event ordering.
- Mitigation: preserve existing event emission order and verify with trace tests.

## Verification
- Existing agent tests pass.
- Event sequence snapshots unchanged for representative scenarios.
