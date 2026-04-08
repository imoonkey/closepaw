# Agent Core Simplicity Improvement Plan (Codex)

## Goal

Reduce the agent core to a cleaner ReAct loop with fewer parallel abstractions:

- observe
- decide
- execute at most one screen-changing step
- observe again

The plan below is ordered by how much conceptual weight it removes, not by how easy each patch is.

## 1. Enforce one screen-changing action per turn

Priority: P0

Why first:

- This is the main simplification lever.
- It removes both accidental complexity and one real correctness problem: next-turn action state can currently describe an action that never executed.

Change:

- Update `cognition/policy/TurnToolPolicy.kt` so selection is:
  - any pure cognitive/memory tools that can safely run in the same turn
  - plus at most one screen-changing tool
  - never `complete_task` in the same turn as a screen-changing tool
- Update `TurnExecutionPhaseRunner.kt` so it returns the signature of the action that actually executed, not the action that was merely planned.
- Keep snapshot refresh simple: only capture a post-action screen once per turn, after the single screen-changing action.

Expected deletions/simplifications:

- less arbitration logic
- no multi-screen action chaining in one turn
- simpler completion rules
- simpler next-turn state tracking

Acceptance check:

- no code path executes two screen-changing tools in one turn
- loop detection only sees actions that actually ran
- prompts and runtime rules match

## 2. Split turn-budget warning from delegated-executor failure summary

Priority: P1

Why next:

- `ExecutorStepPolicy` currently mixes unrelated jobs and includes a dead `WarnApproaching` branch.
- This is a good reduction after the action invariant is clean.

Change:

- Replace `ExecutorStepPolicy` with two simpler pieces:
  - a tiny helper for "is this the final allowed turn?"
  - a standalone formatter for delegated-executor max-turn summaries
- Remove `ExecutorStepDecision.WarnApproaching` unless the warning will actually be surfaced.
- Rename anything still called "Executor" if it applies to all agents.

Primary files:

- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/ExecutorStepPolicy.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt`

Acceptance check:

- no computed-but-unused decision states remain
- final-turn warning logic is obvious from one call site
- delegated max-turn summary generation does not require instantiating a policy object

## 3. Unify agent role definitions into one model

Priority: P1

Why here:

- The current split between `AgentDef` and `AgentDefinition` duplicates the same ownership data.
- This simplification is structural and should happen before smaller cleanup work, otherwise the cleanup gets applied twice.

Change:

- Replace the two definition types with one role/profile model.
- That model should own:
  - role name
  - prompt
  - allowed tools
  - execution role/model bucket
  - whether it can be invoked as a sub-agent
- Derive both top-level session startup and `delegate_task` registry entries from that same source.

Primary files:

- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDefRegistry.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskTool.kt`

Expected deletions:

- `ExecutorAgent` bridge object
- duplicate registry concepts
- unused `id` if it still has no consumer

Acceptance check:

- executor prompt/tool ownership exists in exactly one place
- top-level and delegated agent startup read from the same definition source

## 4. Shrink `NavigationState` to the behavior that still exists

Priority: P2

Why now:

- Once action execution semantics are fixed, the remaining navigation memory can be reduced with confidence.

Change:

- Remove `consecutiveScrollActions` and `recentActions` if stable-screen detection remains the only loop heuristic.
- Remove severity levels if warnings are always a single factual class.
- Keep only:
  - recent screen signatures
  - bounded history size
  - similarity calculation

Primary files:

- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/context/NavigationState.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/LoopDetectionPolicy.kt`

Acceptance check:

- every field in `NavigationState` is read by production code
- loop detection behavior is still fully test-covered

## 5. Flatten the turn orchestration data flow

Priority: P2

Why here:

- After the major invariants are cleaner, the orchestration code can be reduced without fighting moving behavior.

Change:

- Inline or collapse one-use DTOs where they are only handoff envelopes:
  - `PreTurnContext`
  - `PreparedTurn`
  - `PlanningPhaseOutput`
  - `TurnExecutionResult`
  - `CompletionDecision` if it does not survive beyond one branch
- Remove dead fields like `appTier`.
- Replace `by lazy` members in `AgentTurnRunner` with direct properties unless construction is genuinely conditional.

Primary files:

- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntimeTypes.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt`

Acceptance check:

- the single-turn control path can be followed without bouncing through many miniature wrapper types
- no dead fields remain in turn handoff objects

## 6. Create one canonical current-observation representation

Priority: P3

Why later:

- Valuable, but lower leverage than fixing invariants and duplicate definitions first.

Change:

- Stop rendering the current screen separately for prompt text and history text.
- Introduce one canonical observation payload per turn, then render from that payload where needed.
- Remove the subtle "build prompt first, then record history" dependency if possible.

Primary files:

- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptBuilder.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt`

Acceptance check:

- prompt and history cannot drift in how they describe the same screen
- no correctness comment depends on call ordering between prompt building and history recording

## 7. Consolidate event emission and tool-call decoding

Priority: P3

Why last:

- These are worthwhile cleanups, but they matter less than the control-flow reductions above.

Change:

- Pick one event-creation boundary:
  - either all agent-originated events go through `AgentEventDispatcher`
  - or reduce the dispatcher to a few shared helpers and emit directly everywhere
- Consolidate `mobile_action` argument decoding so formatting and action-signature generation do not each parse raw JSON independently.

Primary files:

- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentEventDispatcher.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/ActionDescriptionFormatter.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/ActionSignature.kt`

Acceptance check:

- a new event type has one obvious place to be added
- a `mobile_action` schema change has one obvious decoding path to update

## 8. Finish with a dead-code sweep

Priority: P4

Change:

- delete unused fields/methods confirmed after the structural changes
- examples likely include:
  - `AgentDef.id`
  - `AgentRegistry.getAll()`
  - unused warning severity variants
  - duplicate pause/resume status emission if still redundant

Acceptance check:

- repo search shows no unused public/internal members left in `agent/**` except explicit extension points

## Sequencing Note

Do not start by extracting more classes. The current problem is not insufficient abstraction; it is too many weak abstractions around a loop that should be governed by a few hard invariants.

The best path is:

1. reduce runtime freedom
2. delete state/policies made unnecessary by that reduction
3. collapse duplicate role-definition structures
