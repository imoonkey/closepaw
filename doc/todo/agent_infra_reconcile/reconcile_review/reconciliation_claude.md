# Agent Infrastructure Reconciliation

> **Author**: Claude  
> **Date**: 2026-02-03  
> **Documents Compared**:
> - [two_level_multiagent_design.md](../reference/two_level_multiagent_design.md) — Planner-Executor pattern from mobile-use research
> - [design.md](../multiagent_infra/design.md) — Sub-agent-as-tool system extension

---

## Executive Summary

Both documents aim to extend AndroidAgent beyond a single ReAct loop, but they propose **fundamentally different paradigms**:

| Aspect | `two_level_multiagent_design.md` | `design.md` |
|--------|----------------------------------|-------------|
| **Core Pattern** | Planner-Executor (hierarchical) | Sub-agent-as-tool (horizontal) |
| **Agent Relationship** | Nested loop (Planner spawns Executor) | Tool invocation (Parent delegates to Child) |
| **State Model** | Shared `AgentState` + fresh Executor sessions | Isolated services, event bridging |
| **Use Case** | Single complex task decomposition | Multiple specialized agents for different capabilities |
| **Source Inspiration** | AutoDev, MiniTap, Mobile Agent v3 | Codex, Gemini CLI patterns |

---

## Part 1: Key Misalignments

### 1.1 Architectural Philosophy

```
two_level_multiagent_design.md       design.md
┌─────────────────────────┐          ┌─────────────────────────┐
│    Planner Agent        │          │    Parent Agent         │
│    (strategic)          │          │    (full ReAct loop)    │
│        │                │          │        │                │
│        ▼                │          │        ▼ tool call      │
│    Executor Agent       │          │    ┌────────────────┐   │
│    (tactical, stateless)│          │    │ DelegateTask   │   │
│        │                │          │    └────────┬───────┘   │
│        ▼                │          │             ▼           │
│    Tools (grounded)     │          │    Child Agent          │
└─────────────────────────┘          │    (specialized)        │
                                     └─────────────────────────┘
```

> [!IMPORTANT]
> **Planner-Executor** = one task, two cognitive levels (WHAT vs HOW)  
> **Sub-agent-as-tool** = one orchestrator, multiple specialized workers

### 1.2 State Management

| Aspect | Two-Level Design | Sub-Agent-as-Tool |
|--------|------------------|-------------------|
| **Shared state** | Central `AgentState` (subgoals, scratchpad, errors) | No shared state; child is isolated |
| **Executor memory** | None (stateless per session) | Child has own `HistoryManager` |
| **Cross-step context** | Planner maintains all context | Parent maintains; child reports back |

**Conflict**: The two-level design's executor has *intentional amnesia* to force complete instructions, while sub-agent-as-tool design gives each child full memory within its session.

### 1.3 Protocol Events

| Two-Level Design | Sub-Agent-as-Tool |
|------------------|-------------------|
| `SubgoalsUpdated` | `SubAgentStarted` |
| `SubgoalStarted/Completed` | `SubAgentActivity` |
| `ExecutorSessionStarted/Completed` | `SubAgentCompleted` |
| `PlannerReplanning` | (none) |

**Conflict**: Different event vocabularies. Subgoals are a first-class concept in the two-level design but absent in sub-agent-as-tool.

### 1.4 Agent Definition Model

| Two-Level Design | Sub-Agent-as-Tool |
|------------------|-------------------|
| Just Planner + Executor (implicit) | Explicit `AgentDefinition` with `InputConfig`, `OutputConfig` |
| Hardcoded in `PlannerLoop` / `ExecutorSession` | Registry-based (`AgentRegistry`) |
| Single pair per task | Multiple agents registered |

**Conflict**: The two-level design has no concept of a registry or multiple agent types—just one hardcoded pair.

### 1.5 Tool Access

| Two-Level Design | Sub-Agent-as-Tool |
|------------------|-------------------|
| Planner: semantic tools (intent-based) | Parent: full tool registry |
| Executor: grounded tools (coordinates) | Child: filtered tool subset |
| Clear separation | Overlapping (child may have same tools as parent) |

**Conflict**: Two-level design enforces *semantic abstraction* (Planner never sees coordinates). Sub-agent-as-tool has no such abstraction—children just have filtered tools.

### 1.6 Error Handling & Replanning

| Two-Level Design | Sub-Agent-as-Tool |
|------------------|-------------------|
| `consecutiveFailures` counter → `errorFlagReplan` | Timeout → report result back to parent |
| Replanning is first-class (Planner rewrites subgoals) | No explicit replanning; parent decides next action |
| `ERR_TO_REPLAN_THRESH = 2` | No threshold concept |

**Conflict**: Two-level design has sophisticated replanning logic. Sub-agent-as-tool treats failures as tool results and leaves recovery to the parent.

### 1.7 Nesting & Recursion

| Two-Level Design | Sub-Agent-as-Tool |
|------------------|-------------------|
| No nesting (Executor cannot spawn sub-executors) | Explicit `maxDelegationDepth = 1` guard |
| By design: flat two-level hierarchy | Architected for potential deeper nesting |

---

## Part 2: Proposed Solutions

### Solution A: Unified Hierarchical Model (Recommended)

**Idea**: The Planner-Executor pattern *IS* a specialized case of sub-agent-as-tool.

```kotlin
// Executor as a special "built-in" agent in the registry
object ExecutorAgent {
    val definition = AgentDefinition.Local(
        name = "executor",
        description = "Grounds semantic intent to UI actions",
        inputConfig = InputConfig(mapOf(
            "query" to InputSpec("Self-contained instruction", STRING, required = true)
        )),
        outputConfig = OutputConfig("report", "Narrative summary of result"),
        promptBuilder = ExecutorPromptBuilder::build,
        toolNames = listOf("mobile_action", "app_control"),
        maxTurns = 10,
        timeoutMs = 60_000
    )
}
```

**Benefits**:
- Registry is universal: Planner uses `delegate_task(agent_name = "executor", query = "...")`
- Same infrastructure for Executor and other specialized agents (ScreenAnalyzer, AppExplorer)
- Subgoal management remains Planner-level, not in child

**Implementation**:
1. Add `ExecutorAgent` to `AgentRegistry.createWithBuiltIns()`
2. `PlannerLoop` uses `DelegateTaskTool` to invoke Executor
3. Executor still has stateless sessions (enforced by `SubAgentRunner` creating fresh `HistoryManager`)

---

### Solution B: Merge State Models

**Current Conflict**: Two-level design has shared `AgentState`, sub-agent-as-tool has isolated services.

**Proposed Unified Model**:

```kotlin
// AgentState becomes a hierarchical structure
data class AgentState(
    // Global (accessible to all)
    val sessionId: SessionId,
    val goal: String,
    
    // Planner-owned (read by Executor, write by Planner)
    val subgoals: List<Subgoal>,
    val currentSubgoalIndex: Int,
    val scratchpad: Map<String, String>,
    
    // Transient per sub-agent call (isolated)
    // Managed by SubAgentRunner, NOT shared
)

// SubAgentRunner creates isolation:
class SubAgentRunner {
    suspend fun run(inputs: AgentInputs): ToolCallResult {
        // Child gets: filtered tools, own history, own events
        // Child does NOT get: parent's subgoals, parent's scratchpad
        // Child does get: query (self-contained instruction)
    }
}
```

**Key Insight**: "Shared state" in two-level design means **Planner ↔ Executor transient state** (within one dispatch). It does NOT mean global shared state across arbitrary sub-agents.

---

### Solution C: Unify Protocol Events

**Proposed Mapping**:

| Two-Level Event | Maps To |
|-----------------|---------|
| `SubgoalsUpdated` | New: `PlannerSubgoalsUpdated` (Planner-specific) |
| `SubgoalStarted/Completed` | New: `PlannerSubgoalProgress` |
| `ExecutorSessionStarted` | `SubAgentStarted(agentName = "executor")` |
| `ExecutorSessionCompleted` | `SubAgentCompleted(agentName = "executor")` |
| `PlannerReplanning` | New: `PlannerReplanning` |

**Implementation**:
```kotlin
// Unified event hierarchy
sealed interface AgentEvent {
    val sessionId: SessionId
    val timestamp: Long
}

// Planner-specific events (new)
sealed interface PlannerEvent : AgentEvent
data class PlannerSubgoalsUpdated(...) : PlannerEvent
data class PlannerSubgoalProgress(...) : PlannerEvent
data class PlannerReplanning(...) : PlannerEvent

// Sub-agent events (existing from design.md)
sealed interface SubAgentEvent : AgentEvent
data class SubAgentStarted(...) : SubAgentEvent
data class SubAgentActivity(...) : SubAgentEvent
data class SubAgentCompleted(...) : SubAgentEvent
```

---

### Solution D: Planner Mode as AgentConfig Option

**Proposal**: The two-level architecture is a **mode**, not a replacement.

```kotlin
data class AgentConfig(
    // ... existing fields ...
    
    val executionMode: ExecutionMode = ExecutionMode.SINGLE_REACT
)

enum class ExecutionMode {
    SINGLE_REACT,      // Current: one loop, all tools available
    PLANNER_EXECUTOR,  // Two-level: Planner spawns Executor via delegate_task
    ORCHESTRATOR       // Future: Parent dispatches to multiple sub-agents
}
```

**Behavior per mode**:
- `SINGLE_REACT`: Existing behavior, no change
- `PLANNER_EXECUTOR`: Use `PlannerLoop`, Executor is auto-registered, semantic abstraction enforced
- `ORCHESTRATOR`: Parent has full tool access including `delegate_task`, can call any registered agent

---

### Solution E: Semantic Abstraction Layer for Planner

**Problem**: Sub-agent-as-tool has no semantic abstraction (Planner can see coordinates).

**Solution**: Tool proxy for Planner mode

```kotlin
// When in PLANNER_EXECUTOR mode, Planner's tool registry is wrapped
class SemanticToolProxy(
    private val delegateTaskTool: DelegateTaskTool
) : ToolRegistry {
    
    override fun getAvailableTools(): List<Tool> = listOf(
        // Semantic wrappers
        TapIntentTool(delegateTaskTool),       // "tap the login button"
        ScrollIntentTool(delegateTaskTool),    // "scroll to find settings"
        TypeIntentTool(delegateTaskTool),      // "type email in the input field"
        
        // Direct Planner tools (no Executor needed)
        OpenAppDirectTool(),
        UpdateSubgoalsTool(),
        CreateItemTool(),
        FinishTaskTool()
    )
}

// TapIntentTool internally calls:
// delegate_task(agent_name = "executor", query = "Find and tap: ${intent}")
```

---

## Part 3: Reconciliation Summary

### Recommended Approach

```
┌─────────────────────────────────────────────────────────────────────┐
│                     AndroidAgent Architecture                        │
│                                                                      │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │                      AgentRegistry                            │  │
│   │   - ExecutorAgent (built-in, for Planner-Executor mode)      │  │
│   │   - ScreenAnalyzerAgent (built-in)                           │  │
│   │   - AppExplorerAgent (built-in)                              │  │
│   │   - (future: user-defined agents)                            │  │
│   └──────────────────────────────────────────────────────────────┘  │
│                              │                                       │
│                              ▼                                       │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │                   ExecutionMode Router                        │  │
│   │                                                               │  │
│   │   SINGLE_REACT:        Standard ReAct loop                   │  │
│   │   PLANNER_EXECUTOR:    PlannerLoop + delegate_task("executor")│  │
│   │   ORCHESTRATOR:        Parent + delegate_task(any agent)     │  │
│   └──────────────────────────────────────────────────────────────┘  │
│                              │                                       │
│                              ▼                                       │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │                     SubAgentRunner                            │  │
│   │   - Creates isolated services (history, filtered tools)      │  │
│   │   - Event bridging to parent                                 │  │
│   │   - Approval routing to parent session                       │  │
│   └──────────────────────────────────────────────────────────────┘  │
│                              │                                       │
│                              ▼                                       │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │                   Protocol Events                             │  │
│   │   - SubAgentStarted/Activity/Completed (all modes)           │  │
│   │   - PlannerSubgoalsUpdated (PLANNER_EXECUTOR only)           │  │
│   │   - PlannerReplanning (PLANNER_EXECUTOR only)                │  │
│   └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### Implementation Order

| Phase | Action | Reconciles |
|-------|--------|------------|
| **1** | Add `ExecutionMode` to `AgentConfig` | Mode selection |
| **2** | Add `ExecutorAgent` definition to registry | Executor as agent |
| **3** | Implement `SubAgentRunner` with isolation | State isolation |
| **4** | Add Planner-specific events | Event unification |
| **5** | Implement `SemanticToolProxy` for Planner mode | Semantic abstraction |
| **6** | Port `PlannerLoop` using `DelegateTaskTool("executor")` | Loop integration |
| **7** | Add replanning logic to `PlannerLoop` | Error recovery |

---

## Appendix: Side-by-Side Comparison Table

| Feature | Two-Level | Sub-Agent-as-Tool | Reconciled |
|---------|-----------|-------------------|------------|
| Agent definition | Hardcoded | Registry-based | Registry (Executor is a registered agent) |
| State sharing | Shared `AgentState` | Isolated | Hierarchical (subgoals Planner-only, per-call isolated) |
| Tool abstraction | Semantic (Planner) / Grounded (Executor) | None | SemanticToolProxy for Planner mode |
| Error handling | Replanning with threshold | Timeout + parent decides | Both (threshold in Planner, timeout in Runner) |
| Events | Subgoal-centric | Agent-centric | Unified event hierarchy |
| Nesting | 2-level flat | Guard against recursion | Guard + Executor is max-depth-1 |
| Config | `AgentMode.PLANNER_EXECUTOR` | (N/A) | `ExecutionMode` enum |

---

## References

- [two_level_multiagent_design.md](../reference/two_level_multiagent_design.md)
- [design.md](../multiagent_infra/design.md)
- [note_loop_patterns.md](../reference/note_loop_patterns.md)
- [note_1_architecture_claude.md](../reference/note_1_architecture_claude.md)
