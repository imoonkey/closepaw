# Agent Core Simplicity — Implementation Summary

**Status:** Complete (2026-04-09)
**Commits:** 55b597f..a4437b9 (10 commits)
**Design:** doc/todo/holistic-review/agent-core-simplicity/final/improvement_plan.md

## What Was Implemented

### P0: Action Signature Bug Fix
- `TurnExecutionPhaseRunner.executeActions()` now derives signatures from actually-executed tools, not a pre-computed plan
- If an earlier tool fails, the signature reflects only what ran
- Multi-action execution preserved (intentional for form-filling)

### P1: ExecutorStepPolicy Split
- Replaced `ExecutorStepPolicy` with `isFinalTurn()` boolean helper + `DelegationSummaryFormatter` standalone formatter
- Removed dead `WarnApproaching` state and always-true `narrativeSummaryOnLimit` parameter
- Renamed from "Executor" to "TurnBudget" since it applies to all agents

### P1: Unified Role Definitions
- Replaced `AgentDef` (abstract class) + `AgentDefinition` (sub-agent data class) with `AgentRoleDef`
- Both SessionAgentRunner and DelegateTaskTool derive from the same source
- Deleted ExecutorAgent bridge, duplicate AgentRegistry/AgentDefinition types, unused AgentDef.id

### P2: Dead Code Removal
- NavigationState: removed consecutiveScrollActions, recentActions, ScreenSignature.fingerprint, LoopWarningSeverity.CRITICAL
- PreTurnContext: removed vestigial appTier field (security migration residue)
- TurnToolPolicy: fixed any+find double traversal

### P3: Observation Unification
- Extracted `TurnObservation` as canonical per-turn screen payload
- PromptBuilder and history recording both project from the same source
- Eliminated temporal coupling (build-prompt-first ordering no longer required)

### P3: Event Emission Consolidation
- Added missing methods to AgentEventDispatcher (actionExecuted, approvalRequired, etc.)
- Removed raw eventEmitter passthrough from AgentTurnRunner and TurnExecutionPhaseRunner

### P3: Tool Argument Decoding
- Extracted shared `ActionTarget` decoder for ActionDescriptionFormatter and ActionSignature
- Single JSON parsing path for mobile_action arguments

### P4: Telemetry + Naming
- Added TextRecovery telemetry to Turn.kt recovery paths
- Named magic delay constants (PRE_EXECUTION_DELAY_MS, POST_ACTION_SETTLE_MS)

### Codex Review Fixes
- Screenshot-only mode now uses canonical screenBlock (was diverging)
- Removed vestigial action-signature return path from executeActions()

## Key Design Decision
Owner corrected the original P0 proposal: hard-enforcing one screen-action per turn would break form-filling. The runtime intentionally allows multiple screen-changing tools; navigation isolation is enforced at the prompt layer.

## Net Impact
- ~10 commits, ~200 net lines removed across 40+ files
- All tests pass (59 tasks)
- Codex-reviewed
