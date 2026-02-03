# Multi-Agent Infrastructure Design (Android Agent)

> **Date**: 2026-02-03
> **Goal**: Extend Android Agent infra to support multi-agent delegation while preserving the current session/task/turn model and approval UX.

## 1) Goals & Non‑Goals

### Goals
- **Delegate sub-tasks** to specialized sub-agents.
- **Preserve UX**: single approval stream, coherent event timeline, minimal UI confusion.
- **Scoped capability**: sub-agents run with tool subsets and explicit limits.
- **Reusable core**: reuse existing `Agent` loop; avoid parallel architecture.

### Non‑Goals (Phase 1)
- Parallel multi-agent orchestration.
- Cross-device orchestration.
- Remote agent execution (A2A).

## 2) Reference Patterns (Borrowed)

### From Codex
- **Sub-agent = full agent instance** (same loop, same protocol).
- **Approval bubbling** to parent session.
- **Cancellation propagation** from parent to child.
- **Headless event drain** to prevent queue growth.

### From Gemini CLI
- **Agent registry** (declarative local definitions, optional remote).
- **Agent-as-tool** delegation via a single tool.
- **Per-agent tool isolation** and recursion guardrails.

## 3) Proposed Architecture

### 3.1 Component Overview

```
Parent Agent (Tool Router)
   └─ DelegateTaskTool (tool)
         └─ SubAgentRuntime
               ├─ Child Agent (same Agent.kt loop)
               ├─ EventBridge (child → parent events)
               └─ ApprovalBridge (child approvals → parent → child)
```

### 3.2 New/Extended Components

#### A) `SubAgentManager`
- Lives in `SessionServices`.
- Responsible for creating sub-agent sessions, scoping tools, and tracking lifecycle.
- API sketch:

```kotlin
interface SubAgentManager {
    suspend fun runSubAgent(request: SubAgentRequest): SubAgentResult
}
```

#### B) `AgentRegistry` (local definitions)
- Defines **named sub-agents** with:
  - prompt template
  - model overrides
  - tool allowlist
  - max turns / timeout
- Definitions are static in code (Phase 1), with future room for file-based definitions.

#### C) `DelegateTaskTool`
- A standard tool (like `CompleteTaskTool`) that invokes a sub-agent.
- Tool arguments:
  - `agent_name`: enum or string (validated)
  - `goal`: string
  - optional structured inputs per agent

#### D) `SubAgentRuntime`
- Creates a **child Agent** using:
  - Derived `SessionServices` (new history, child tool registry)
  - Shared `AndroidPlatform`, `Perceptor`, and `LLMClient`
- Runs the child agent to completion in a **child coroutine scope**.

#### E) `EventBridge` + `ApprovalBridge`
- **EventBridge** maps child `AgentEvent` into parent stream:
  - `MessageDelta` → optionally prefixed with `↳` or wrapped as `SubAgentMessageDelta`.
  - `StatusUpdate` → prefixed with `[SubAgent: X]`.
  - `TaskStarted/Completed` → collapsed into `SubAgentStarted/Completed` to avoid confusing UI state.
- **ApprovalBridge** intercepts `ApprovalRequired` from child:
  - Emits a parent `ApprovalRequired` with `subAgentId` metadata.
  - Parent `Op.Approve(...)` routes back to child.

## 4) Protocol Extensions

### 4.1 New Event Types (proposal)
- `SubAgentStarted(subAgentId, agentName, goal)`
- `SubAgentProgress(subAgentId, messageDelta/status)`
- `SubAgentCompleted(subAgentId, result)`
- `SubAgentFailed(subAgentId, error)`

### 4.2 Approval Routing
- Add `subAgentId` to approval requests and decisions.
- Parent session stores a **pending approvals map** keyed by `(subAgentId, actionId)`.

## 5) Lifecycle & Concurrency Model

### Phase 1 (Sequential)
- Sub-agents run **synchronously** inside a tool call.
- Parent agent **waits** for tool completion.

### Phase 2 (Parallel, future)
- Introduce a `SubAgentTaskGroup` with bounded concurrency.
- UI shows nested progress streams.

## 6) Tool Scoping & Guardrails

- **Tool allowlist** per sub-agent; default subset excludes `delegate_task` to prevent recursion.
- **Max depth**: e.g., `maxDelegationDepth = 1` in `SessionServices`.
- **Timeouts**: sub-agent has strict time/turn limits.
- **Policy**: child tools must still go through approval via parent (no direct UI prompts).

## 7) History & Persistence

- Sub-agent uses **separate HistoryManager**.
- At completion, parent receives a **summary result** and stores it as a normal agent message.
- Optionally persist sub-agent logs as `SessionRecord.subTasks` (future extension).

## 8) Minimal MVP UI Behavior

- Streaming output from sub-agent is **prefixed** (e.g., `↳ [Planner] ...`).
- No new UI components required.
- Later: nested bubbles or sub-task cards.

## 9) Open Questions
- Should sub-agents share the same `SessionId` or use `SubSessionId`?
- How to expose sub-agent definitions to the user (UI list, config file)?
- Should sub-agents be allowed to call external LLMs/tools with separate credentials?

