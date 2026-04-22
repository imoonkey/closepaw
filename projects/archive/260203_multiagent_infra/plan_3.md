# Multi-Agent Implementation Plan

> **Author**: Gemini
> **Date**: 2026-02-02
> **Context**: Implementation plan for "Sub-Agent as a Tool" architecture.

## 1. Core Infrastructure Updates

### 1.1 `SessionServices` Support
We need a way to create a lightweight copy of `SessionServices` for the child agent. Child agents share stateless services (LLM, Platform) but need their own stateful services (History, ToolRegistry).

**Action Items:**
- [ ] Add `deriveForSubAgent(goal: String): SessionServices` to `SessionServices` class.
    - Create new `SessionHistoryManager` (in-memory or separate file).
    - Create new `ToolRegistry` (subset of tools, excluding the delegate tool itself to prevent infinite recursion).
    - Share `LLMClient`, `AndroidPlatform`.

### 1.2 `AgentTool` Abstraction
Create a base class for tools that wrap an agent.

**Action Items:**
- [ ] Create `agent/tool/AgentTool.kt`.
- [ ] Implement `execute()` logic:
    - Initialize `AgentConfig` for child.
    - Initialize `SessionServices` for child.
    - Instantiate `Agent`.
    - Run `agent.run()`.
    - Bridge events.

---

## 2. Event Bridging

To ensure the user sees what the sub-agent is doing, we must forward events.

**Strategy:**
- Wrap sub-agent events in a container event? Or just forward them?
- **Decision**: For Phase 1, **prefix** status updates and message deltas.
    - `MessageDelta` -> `MessageDelta(prefix="[SubAgent] ")`
    - `StatusUpdate` -> `StatusUpdate(status="↳ Checking map...")`
    - `TaskStarted/Completed` -> Ignored or converted to log events.

**Action Items:**
- [ ] Implement `EventBridge` helper class.
- [ ] Handle `AgentStopReason` -> `ToolCallResult` conversion.

---

## 3. Concrete Implementation

### 3.1 `DelegateTaskTool`
A generic tool that lets the agent delegate *any* broad goal to a sub-agent.

**Tool Spec:**
- Name: `delegate_task`
- Args: `goal` (string)
- Description: "Delegates a complex, self-contained sub-task to a sub-agent. Use this when the task requires multiple steps or is distinct from the main flow."

**Action Items:**
- [ ] Implement `DelegateTaskTool`.
- [ ] Register in `ToolRegistry`.

---

## 4. Verification Plan

### 4.1 Unit Tests
- `AgentToolTest.kt`:
    - Mock `AgentRuntime`.
    - Verify `execute()` spawns agent.
    - Verify events are forwarded.
    - Verify `ToolCallResult` matches agent exit reason.

### 4.2 Integration Test
- Create a test where Agent A calls Agent B.
- Agent B performs a simple mock action (e.g., specific log output).
- Verify Agent A completes successfully.

---

## 5. Future Considerations (Out of Scope)

- **Parallel Agents**: Running multiple sub-agents concurrently (requires `Task` state upgrade).
- **Sandboxed Execution**: Restricting sub-agent tools.
- **Inter-Agent Protocols**: Direct communication between agents beyond Goal/Result.
