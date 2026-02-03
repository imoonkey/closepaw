# Multi-Agent Infrastructure Design

> **Author**: Gemini
> **Date**: 2026-02-02
> **Context**: Extension of Android Agent infrastructure to support multi-agent capabilities.

## 1. Reference Architecture Analysis

### 1.1 Codex (Hierarchical Delegation)
**Approach**: Parent session spawns a "Sub-Codex" (via `run_codex_thread_interactive`) which runs as an autonomous agent but delegates key decisions back to the parent.
- **Mechanism**: The sub-agent has its own independent loop but uses a "bridge" to forward its events to the parent.
- **Key Feature**: **Approval Delegation**. When the sub-agent wants to execute a command or apply a patch, it emits an `ExecApprovalRequest` or `ApplyPatchApprovalRequest` event. The parent session intercepts these (filtering them from the user) and processes them (e.g., asking the user or auto-approving based on policy) before sending a decision back to the sub-agent.
- **Pros**: Strong supervision, reuses the exact same agent code, clean separation of concerns.

### 1.2 Gemini CLI (Agent-as-a-Tool)
**Approach**: `Task` is the primary unit, and other agents are exposed as **Tools** (likely via MCP).
- **Mechanism**: The agent uses a tool (e.g., "ask_expert") just like any other tool. The tool implementation triggers the external agent.
- **Key Feature**: **Task Orchestration**. The main `Task` manages the state and tool loop. It can schedule multiple tools (agents) in parallel.
- **Pros**: Extremely flexible. Any agent can access any other agent if provided as a tool. Standard interface (Input -> Tool -> Output).

---

## 2. Design Proposal for Android Agent

We will adopt a hybrid approach: **"Sub-Agent as a Tool"** (like Gemini) with **"Event Bridging"** (like Codex) for observability.

### 2.1 Core Concept: `AgentTool`
We will create a specialized `Tool` implementation that wraps an `Agent` instance. This allows the main agent to "call" another agent to solve a sub-problem.

**Components:**
1.  **`AgentTool`**: A `BaseTool` that takes a natural language `goal` as input.
2.  **`SubAgent`**: An instance of `Agent` (reusing existing `Agent.kt` logic) that runs in a "child session" context.
3.  **Event Bridge**: A mechanism to forward `MessageDelta` (streaming text) and `StatusUpdate` events from the sub-agent to the parent's event stream, so the user sees the sub-agent's progress (nested or flattened).

### 2.2 Architecture

```
┌──────────────────────────────────────────────┐
│                Parent Session                │
│                                              │
│  ┌──────────────┐                            │
│  │ Parent Agent │ ── executes tool ──► ┌─────┴────────────────┐
│  └──────┬───────┘                      │      AgentTool       │
│         │                              │ (input: "fix bug")   │
│         │ events                       └─────┬────────────────┘
│         ▼                                    │ creates/runs
│  ┌──────────────┐                            ▼
│  │ Event Stream │ ◄── bridge events ── ┌──────────────┐
│  │ (to UI)      │                      │  Child Agent │
│  └──────────────┘                      └──────────────┘
```

### 2.3 Key Implementation Details

#### A. Reusable Agent Runtime
The existing `Agent` class is already designed as a facade over `AgentRuntime`. We can reuse it directly.
We need to ensure `SessionServices` can be scoped or shared. A child agent likely needs:
- **Shared**: `DeviceController` (same screen), `LLMClient`.
- **Scoped**: `HistoryManager` (child should have its own short-term history), `ToolRegistry` (child might have different tools).

#### B. `AgentTool` Implementation
```kotlin
class AgentTool(
    private val services: SessionServices,
    private val subAgentConfig: AgentConfig
) : BaseTool {
    override val spec = ToolSpec(
        name = "delegate_task",
        description = "Delegate a complex sub-task to a specialized agent.",
        arguments = listOf(
            ToolArgument("goal", "The goal for the sub-agent")
        )
    )

    override suspend fun execute(args: Map<String, Any?>): ToolCallResult {
        val goal = args["goal"] as String
        val childSessionId = UUID.randomUUID().toString()
        
        // 1. Create specialized services for child
        val childServices = services.deriveForSubAgent()
        
        // 2. Create the child agent
        val childAgent = Agent(
            config = subAgentConfig.copy(sessionId = childSessionId, goal = goal),
            services = childServices,
            eventEmitter = { event -> 
                // 3. Bridge events!
                // Transform child events to parent context if needed
                // e.g. Wrap in "SubAgentEvent" or just flat emit
                services.eventEmitter(event) 
            }
        )
        
        // 4. Run to completion
        val stopReason = childAgent.run()
        
        // 5. Return result
        return ToolCallResult.Success("Sub-agent finished with: $stopReason")
    }
}
```

#### C. Event Bridging Strategy
To avoid confusing the UI, we should probably wrap child events or use a specific "Sub-Task" UI pattern in the future. For MVP, we can flattened them but maybe prefix the status updates (e.g., "↳ [Sub] Thinking...").

### 2.4 Extensions
- **Specialists**: We can define different `AgentTool` instances with different `system prompts` (e.g., "CoderAgent", "NavigatorAgent").
- **Sandboxing**: The child agent could have a restricted set of tools (e.g., read-only tools).

---

## 3. Comparison with Codex & Gemini

| Feature | Codex | Gemini | Android Agent Proposal |
| :--- | :--- | :--- | :--- |
| **Invocation** | Static `Op` | Tool Call | Tool Call |
| **Communication** | Channel pair | Tool args/return | Tool args/return + Event Bridge |
| **State** | Shared Process | Task Object | Child Agent Instance |
| **Supervision** | Explicit Approvals | Task waits | Parent waits for Tool return |

This proposal aligns closest with **Gemini** (Agent-as-Tools) but borrows the **Event Bridging** concept from **Codex** to maintain the rich real-time feedback loop that is core to the Android Agent experience.
