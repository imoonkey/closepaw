# Phase 1: Sub-Agent Infrastructure Design (Reconciled)

> **Goal**: Add minimal, safe delegation infra without introducing new loop types.
> 
> **Status**: Updated to match current AndroidAgent codebase constraints.

---

## 0. Constraints from Current Code

This phase is designed for the existing architecture:

- Keep single ReAct loop (`AgentRuntime` + `AgentTurnRunner`) as-is.
- `SessionServices` currently has no `sessionId` field, so delegation wiring belongs in `SessionAgentRunner` (where `sessionId` + event emitter are available).
- `Agent.run()` returns `AgentStopReason` (not a rich result object), so sub-agent success/failure mapping must use stop reason.
- `ToolSpec` + `ToolInvocation` is the current tool abstraction; `delegate_task` should follow this pattern.

---

## 1. Delegation Contract

When parent delegates to executor, pass:

- `query` (required): complete instruction
- `current_subgoal` (optional): short current focus
- `important_notes` (optional): compact list of key facts

Do not pass:

- full parent history
- prior screenshots/a11y trees
- parent todos/scratchpad

The runner will build a compact child goal string from these fields.

---

## 2. AgentDefinition (Minimal)

```kotlin
data class AgentDefinition(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolNames: List<String>,
    val maxTurns: Int = 10,
    val timeoutMs: Long = 60_000
)
```

KISS decisions:

- No InputConfig/OutputConfig schema types.
- Natural language input/output.
- Tool allowlist per agent.

---

## 3. AgentRegistry (Minimal)

```kotlin
class AgentRegistry {
    private val agents = mutableMapOf<String, AgentDefinition>()

    fun register(definition: AgentDefinition)
    fun get(name: String): AgentDefinition?
    fun getAll(): List<AgentDefinition>
    fun getDirectoryPrompt(): String

    companion object {
        fun createDefault(): AgentRegistry
    }
}
```

`createDefault()` initially registers only `ExecutorAgent.definition`.

---

## 4. Tool Filtering (for Isolation)

Add helper on `ToolRegistry`:

```kotlin
fun createFilteredCopy(
    allowedNames: Set<String>,
    excludedNames: Set<String> = emptySet()
): ToolRegistry
```

Rules:

- Include only tools in `allowedNames`.
- Always exclude recursion paths like `delegate_task` for child agents.

---

## 5. SubAgentRunner (Core Isolation)

```kotlin
interface SubAgentRunner {
    suspend fun run(request: SubAgentRequest): SubAgentResult
}

class IsolatedSubAgentRunner(
    private val definition: AgentDefinition,
    private val parentServices: SessionServices,
    private val parentSessionId: SessionId,
    private val eventEmitter: suspend (AgentEvent) -> Unit
) : SubAgentRunner
```

Isolation guarantees:

- Fresh `HistoryManager`
- Fresh `AgentSessionState`
- New child `ToolRouter` with filtered tools
- Shared platform/LLM/config (to execute on same device session)

Timeout behavior:

- Wrap child `Agent.run()` with `withTimeoutOrNull(timeoutMs)`
- Return `SubAgentResult(success=false, message="Timeout after ...")` on timeout

Success mapping:

- `AgentStopReason.GoalAchieved` => success
- `MaxTurnsReached`/`UserRequested`/`Error` => failure with readable message

---

## 6. DelegateTaskTool

```kotlin
class DelegateTaskTool(
    private val sessionId: SessionId,
    private val registry: AgentRegistry,
    private val runnerFactory: (AgentDefinition) -> SubAgentRunner,
    private val eventEmitter: suspend (AgentEvent) -> Unit
) : ToolSpec
```

Parameters:

- `agent_name` (required)
- `query` (required)
- `current_subgoal` (optional)
- `important_notes` (optional string array)

Execution flow:

1. Validate agent exists.
2. Emit `SubAgentStarted`.
3. Run child via runner.
4. Emit `SubAgentCompleted`.
5. Return `ToolExecutionResult.Success` with observation text.

For child failures/timeouts, still return success observation text (`"Sub-agent failed: ..."`) so planner can recover next turn.

---

## 7. Protocol Events (Minimal)

Add to `AgentEvent`:

```kotlin
data class SubAgentStarted(...)
data class SubAgentActivity(...)
data class SubAgentCompleted(...)
```

`SubAgentActivity` is a compact bridged string, not raw nested event structures.

---

## 8. Built-in Executor Agent

```kotlin
object ExecutorAgent {
    val definition = AgentDefinition(
        name = "executor",
        description = "Execute grounded UI actions on the current screen",
        systemPrompt = "...",
        toolNames = listOf("mobile_action", "app_control", "complete_task"),
        maxTurns = 10,
        timeoutMs = 60_000
    )
}
```

---

## 9. Integration Points

### 9.1 Session wiring

Register `delegate_task` from `SessionAgentRunner.start(...)` (not `SessionServices.create(...)`), because the runner has both:

- `sessionId`
- suspend event emitter

Registration should be idempotent (`if (!toolRegistry.contains("delegate_task"))`).

### 9.2 Tool taxonomy and policy

Update:

- `ToolName` to include `DelegateTask`
- `PolicyEngine.DEFAULT_RISK_LEVELS` to include `delegate_task`
- `ToolUi` icon/name mapping

### 9.3 Parent prompt

Add brief planner guidance in `AgentRuntime`:

- use `delegate_task(agent_name="executor", query="...")` for grounded execution when appropriate.

---

## 10. TDD Scope (Core First)

Core tests first:

1. `ToolRegistry.createFilteredCopy` behavior.
2. `AgentRegistry` register/get/directory prompt.
3. `DelegateTaskTool` validation + success/failure event flow.
4. `IsolatedSubAgentRunner` success + timeout mapping.

Then implement minimum integration wiring in `SessionAgentRunner`.

---

## 11. Not Included (KISS)

- Approval bridging into parent approval UI (future phase)
- Nested delegation
- Parallel sub-agents
- Structured agent-to-agent protocol
- New loop classes (`PlannerLoop`, etc.)

