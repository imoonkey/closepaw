# Agent Core Simplicity Review (Codex)

## Scope

- Reviewed `app/src/main/kotlin/com/moonkey/androidagent/agent/**` end to end.
- Read only directly related runtime/test files when needed to confirm usage and dead code:
  `session/SessionAgentRunner.kt`, `tool/impl/DelegateTaskTool.kt`, and targeted tests under `app/src/test/kotlin/...`.
- Did not read any other `doc/todo/` design documents.

## Summary

The module is only ~3.2k LOC, but the conceptual surface is larger than it needs to be. The main source of complexity is that the runtime does not enforce a simple ReAct invariant. Prompts describe "observe -> choose one screen-changing step -> observe again", but the runtime still permits multiple screen-changing tool calls in one turn. That choice ripples outward into tool arbitration, completion deferral, post-action snapshot chaining, action-signature tracking, and sub-agent result handling.

The second large source of complexity is duplicated role-definition machinery: the project has one definition system for the main agent and another for sub-agents, with the executor role copied between them. After that, there is a long tail of vestigial state and policy objects that survived earlier heuristics and now mostly add cognitive load.

## High

### 1. The runtime does not enforce the one-screen-action invariant, and that leaks into incorrect state

The prompts consistently teach a one-screen-action mental model:

- `definition/PlannerAgentDef.kt:28-37` says to prefer at most one screen-affecting execution tool per turn.
- `definition/ExecutorAgentDef.kt:29-40` says to prefer at most one screen-affecting action per turn and complete only after observing the result.
- `definition/StandaloneAgentDef.kt:31-41` says navigation actions that change the screen must be the only screen action in that turn.

The runtime does not enforce that model:

- `cognition/policy/TurnToolPolicy.kt:33-84` keeps all screen-changing tools, not one.
- `TurnExecutionPhaseRunner.kt:44-64` then executes every selected tool sequentially in one turn.
- `TurnExecutionPhaseRunner.kt:108-127` refreshes the snapshot after each tool so later tools in the same turn can act on a new screen.

That policy creates extra machinery and one concrete correctness risk:

- `TurnExecutionPhaseRunner.kt:45` computes `actionForNextTurn` before executing anything.
- If an earlier tool fails, the loop breaks (`TurnExecutionPhaseRunner.kt:59-62`) but still returns the precomputed signature (`TurnExecutionPhaseRunner.kt:64`).
- `AgentTurnRunner.kt:113-116` stores that signature into next-turn state, and `cognition/context/NavigationState.kt:22-40` records it as if it had actually happened.

Example: if `scratchpad` fails before a planned `mobile_action`, the next turn still believes the `mobile_action` was the previous action. That contaminates loop detection and any logic that depends on "what actually ran last turn."

KISS read: the simplest useful invariant is "per turn, execute any pure memory/tooling calls plus at most one screen-changing tool." Once that is true, the core loop gets simpler immediately:

- `TurnToolPolicy` stops needing multi-screen arbitration.
- `TurnExecutionPhaseRunner` stops chaining post-action snapshots within one turn.
- `complete_task` deferral becomes a hard invariant instead of a soft policy.
- next-turn action signatures can be derived from the action that actually executed.

### 2. The codebase has two parallel agent-definition systems for the same concept

Main-agent roles are defined in one model:

- `definition/AgentDef.kt:10-15`
- `definition/AgentDefRegistry.kt:5-13`
- `definition/PlannerAgentDef.kt`
- `definition/ExecutorAgentDef.kt`
- `definition/StandaloneAgentDef.kt`

Sub-agents are defined again in another model:

- `subagent/SubAgentRunner.kt:29-39` defines `AgentDefinition`
- `subagent/SubAgentRunner.kt:79-99` defines `AgentRegistry`
- `subagent/SubAgentRunner.kt:61-74` copies executor data from `ExecutorAgentDef` into a new `AgentDefinition`

The duplication is not conceptual; it is literal data-copying. `ExecutorAgent` exists only to translate one definition object into another. Outside the module, the split continues:

- `session/SessionAgentRunner.kt:51-77` uses `AgentDef` to choose prompt/tools/model.
- `session/SessionAgentRunner.kt:129-147` separately builds an `AgentRegistry` for `delegate_task`.
- `tool/impl/DelegateTaskTool.kt:18-35` then consumes the second registry.

This means prompt ownership, tool ownership, and role ownership are spread across two registries and two definition types. A future executor change has to be reasoned about in both places.

KISS read: there should be one role-definition type. The same object should answer:

- what tools this role can use
- what prompt it runs with
- whether it is top-level only, delegatable, or both
- what execution role/model bucket it belongs to

### 3. `ExecutorStepPolicy` mixes unrelated jobs and carries dead behavior

`ExecutorStepPolicy` currently tries to do three things:

- warn near turn limit (`cognition/policy/ExecutorStepPolicy.kt:8-19`, `33-47`)
- produce a final-turn warning for the active run (`AgentTurnRunner.kt:220-226`, `234-243`)
- generate a narrative step-limit summary after a sub-agent stops (`subagent/SubAgentRunner.kt:177-193`)

The abstraction does not line up cleanly with actual usage:

- It is named "Executor" but is instantiated by `AgentTurnRunner` for every agent run, including main agents (`AgentTurnRunner.kt:46-49`).
- `WarnApproaching` is effectively dead. `AgentTurnRunner.buildWarnings` handles only `ForceStop` (`AgentTurnRunner.kt:234-243`), so the "approaching limit" state is computed but never shown.
- The sub-agent runner instantiates a second `ExecutorStepPolicy` after the child has already stopped, just to reuse the summary formatter (`subagent/SubAgentRunner.kt:177-193`).

KISS read: these are two separate concerns and should not share a state machine:

- "Is this the final allowed turn?" -> a tiny turn-budget helper.
- "Build a summary when a delegated executor times out/maxes out." -> a standalone formatter.

## Medium

### 4. `NavigationState` still carries removed heuristics

`NavigationState` tracks three things:

- recent screen signatures
- consecutive scroll count
- recent action history

See `cognition/context/NavigationState.kt:17-40`.

Only one of those is currently used:

- `LoopDetectionPolicy.detect` reads `recentSignatures` only (`cognition/policy/LoopDetectionPolicy.kt:38-49`).

The tests confirm the old heuristics are gone:

- `agent/cognition/policy/LoopDetectionPolicyTest.kt:105-132` explicitly says scroll-spam and repeated-action checks were removed.

But the runtime state and tests still preserve those fields:

- `agent/cognition/context/NavigationStateTest.kt:13-39`

The same thing is true for warning severity:

- `cognition/context/NavigationState.kt:55-60` defines `LoopWarningSeverity`.
- `cognition/policy/LoopDetectionPolicy.kt:41-45` only ever emits `WARNING`.
- No production code branches on severity.

KISS read: if loop detection is now "stable recent screens only", then `NavigationState` should shrink to that exact need.

### 5. Screen observation formatting is duplicated, and the ordering contract is fragile

The current screen is rendered twice in two different shapes:

- `cognition/prompt/PromptBuilder.kt:111-178` renders the current observation for the LLM prompt.
- `TurnPlanningPhaseRunner.kt:173-205` renders the current observation again for history.

`TurnPlanningPhaseRunner.kt:84-86` and `173-180` explicitly document a timing dependency: build the prompt first, then record the screen to history, otherwise the current screen would be duplicated in the same prompt turn.

That is a classic temporal-coupling smell. It means:

- two serializers must stay semantically aligned
- the call order is part of correctness
- the reader has to remember why prompt-building and history-writing happen in that specific order

KISS read: one canonical observation representation should exist per turn, then prompt/history can project from it. Right now the code encodes the same idea twice.

### 6. The core turn flow is fragmented by many one-use DTOs

The main turn path is linear, but the data flow is spread across several tiny wrappers:

- `AgentRuntimeTypes.kt:28-41` -> `TurnRunnerState`, `TurnExecutionResult`
- `AgentTurnRunner.kt:40-45` -> `PreTurnContext`
- `AgentTurnRunner.kt:134-137` -> `PreparedTurn`
- `TurnPlanningPhaseRunner.kt:20-23` -> `PlanningPhaseOutput`
- `cognition/policy/TurnToolPolicy.kt:23-27` -> `CompletionDecision`

Most of these are not long-lived domain types; they are handoff envelopes for a single caller. One field is already dead:

- `AgentTurnRunner.kt:43` / `171-176` stores `appTier`, but nothing uses it after capture.

There is also unnecessary lazy wiring in the same class:

- `AgentTurnRunner.kt:46-67` uses `by lazy` for policies/runners that are effectively always used once execution begins.

KISS read: the core loop is easier to reason about when most of this becomes direct local state. The code is not too large for that.

### 7. Event emission ownership is split between dispatcher and raw emitter

`AgentEventDispatcher` centralizes some event emission:

- status, deltas, turn lifecycle, screen capture, todos, scratchpad, thought, ask-user
- see `AgentEventDispatcher.kt:15-145`

But several agent-sourced events bypass it:

- `TurnExecutionPhaseRunner.kt:148-179` emits `ActionExecuted` and `ApprovalRequired` directly via raw `eventEmitter`
- `subagent/SubAgentRunner.kt:207-226` emits bridged sub-agent activity directly

The result is that "where agent events come from" is not actually centralized. A reader has to inspect both the dispatcher and raw emitter call sites.

KISS read: either the dispatcher owns all agent-originated event creation, or the dispatcher should be reduced/removed. The current halfway split is more surface area without full value.

### 8. Tool argument interpretation is duplicated across utilities

Two helpers independently interpret raw `ToolCallRequest.arguments`:

- `ActionDescriptionFormatter.kt:20-124`
- `ActionSignature.kt:20-94`

Both know how to decode variants of:

- text target
- bounds target
- coordinate target
- element-index target
- mobile action subtypes

This is not catastrophic, but it is duplicated tool-schema knowledge in the agent core. Any change to `mobile_action` now has at least two JSON parsers to keep in sync.

KISS read: one normalized action-target decoder would reduce drift and make both formatting and signature generation easier to trust.

## Low

### 9. There is a useful dead-code cleanup pass available now

A few items look vestigial:

- `definition/AgentDef.kt:11` exposes `id`, but repo-wide search under `app/src/main/kotlin` found no production usage.
- `subagent/SubAgentRunner.kt:88` exposes `AgentRegistry.getAll()`, but repo search found no production usage.
- `cognition/context/NavigationState.kt:55-60` defines `LoopWarningSeverity.CRITICAL`, but no production code emits or uses it.
- `AgentTurnRunner.kt:43` / `171-176` carries `appTier` without using it.
- `Agent.kt:69-75` and `173-190` emit pause/resume status from multiple places, which works but makes the pause state machine noisier than necessary.

These are not the main problem, but they are low-risk reductions that would immediately reduce mental overhead.

## Recommendation

Focus simplification on runtime invariants first, not on prompt wording or test reshuffling.

Order of impact:

1. Re-establish "at most one screen-changing action per turn" in code.
2. Collapse duplicate role-definition systems.
3. Split step-budget summary from turn-budget warning and delete unused decision states.
4. Trim navigation/event/observation scaffolding to the behavior that still exists.
