# Multi-Agent Infra Implementation Plan (Android Agent)

> **Date**: 2026-02-03
> **Scope**: Add multi-agent delegation with sub-agent-as-tool + approval routing.

## Phase 1: Foundations (Low Risk)
1. **Define sub-agent types** (`protocol/AgentEvent.kt`, `protocol/Op.kt`)
   - Add `SubAgentStarted/Progress/Completed/Failed` events.
   - Add approval payload field `subAgentId` (optional, default null).
   - Risk: Low (additive protocol changes).

2. **Introduce `SubAgentManager`** (`session/SessionServices.kt` + new `agent/SubAgentManager.kt`)
   - Provide `runSubAgent(request)` and lifecycle tracking.
   - Maintain `maxDelegationDepth` + `activeSubAgents` map.
   - Risk: Low

## Phase 2: Sub-Agent Runtime (Medium Risk)
3. **Derive child services** (`session/SessionServices.kt`)
   - `deriveForSubAgent(...)` creates:
     - new `SessionHistoryManager`
     - new `ToolRegistry` with allowlist
     - shared `AndroidPlatform`, `Perceptor`, `LLMClient`
   - Risk: Medium (scoping bugs)

4. **Implement `SubAgentRuntime`** (`agent/SubAgentRuntime.kt`)
   - Instantiate child `Agent` with derived services.
   - Run inside child coroutine scope with cancellation propagation.
   - Map child stop reason → `SubAgentResult`.
   - Risk: Medium (lifecycle + cancellation)

## Phase 3: Tool Integration (Medium Risk)
5. **`DelegateTaskTool`** (`tool/impl/DelegateTaskTool.kt`)
   - Tool args: `agent_name`, `goal`, optional inputs.
   - Calls `SubAgentManager.runSubAgent()`.
   - Risk: Medium (tool schema + validation)

6. **Tool registry updates** (`tool/ToolRegistry.kt`)
   - Register `delegate_task` tool.
   - Ensure sub-agent allowlists exclude `delegate_task` to prevent recursion.
   - Risk: Low

## Phase 4: Event + Approval Bridging (Medium Risk)
7. **Event bridge** (`agent/SubAgentEventBridge.kt`)
   - Prefix or wrap child `MessageDelta`/`StatusUpdate`.
   - Suppress `TaskStarted/Completed` to avoid UI confusion.
   - Risk: Medium (UI observability)

8. **Approval bridge** (`agent/SubAgentApprovalBridge.kt`)
   - Route child approval requests to parent session.
   - Map approval responses back to child actions.
   - Risk: Medium (deadlocks / wrong routing)

## Phase 5: Tests (Medium Risk)
9. **Unit tests** (`app/src/test/...`)
   - SubAgentManager: lifecycle + depth guard.
   - EventBridge: mapping correctness.
   - Tool invocation: sub-agent result conversion.

10. **Integration test**
   - Mock tool action in sub-agent and confirm parent sees bridged events.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Approval deadlock | M | H | Timeout + explicit routing keys |
| Event spam | M | M | Filter + prefix only key events |
| Infinite recursion | L | H | Disallow `delegate_task` in child tool registry |
| Shared resource contention | M | M | Concurrency limits; child scope |

## Testing Strategy
- **Unit**: Bridges, registry allowlists, depth guard.
- **Integration**: Parent agent delegates to sub-agent; ensure completion + event flow.
- **Manual**: Run demo task that requires delegation; verify approval UX.
