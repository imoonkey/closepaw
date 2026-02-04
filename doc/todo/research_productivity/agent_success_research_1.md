# Agent Success Research Productivity Design

> **Date**: 2026-02-04  
> **Goal**: Consolidate agent success factors for fast iteration research

---

## Problem Statement

Agent success rate is suboptimal. Currently, factors affecting success are scattered across:

| Factor | Current Location | Pain Point |
|--------|------------------|------------|
| **Prompts** | `Turn.kt`, `ExecutorAgent.kt`, `AgentPromptBuilder.kt` | Inline strings, hard to compare/iterate |
| **Observability** | `trace/` module (basic) | Can't see full prompt sent to LLM |
| **Context flow** | `HistoryManager`, `TurnInputBuilder` | No visibility into what context was injected |
| **Evaluation** | (none) | No systematic way to measure improvements |

---

## Proposed Solution: Research Layer

Create a focused "research layer" that enables rapid iteration on agent behavior without touching core infra.

### Module Structure

```
app/src/main/kotlin/com/moonkey/androidagent/
└── research/                      # NEW: Research iteration layer
    ├── prompt/                    # Prompt management
    │   ├── PromptTemplates.kt     # All prompt strings (centralized)
    │   ├── PromptBuilder.kt       # Assembly logic
    │   └── PromptConfig.kt        # A/B test flags, variants
    │
    ├── observability/             # Enhanced visibility
    │   ├── TurnLogger.kt          # Full prompt + response logging
    │   └── ContextDumper.kt       # Dump context to trace
    │
    └── eval/                      # Evaluation framework (optional)
        ├── TaskBenchmark.kt       # Task definitions
        └── MetricsCollector.kt    # Success/failure metrics
```

---

## Phase 1: Prompt Consolidation (High Value, Low Risk)

### Goal
Move all prompt text to one place, enable A/B comparison.

### Design

#### [NEW] `PromptTemplates.kt`

```kotlin
object PromptTemplates {
    // ======== Planner Prompts ========
    val PLANNER_SYSTEM_V1 = """
        You are an Android automation agent...
    """.trimIndent()

    val PLANNER_ROLE_RULES_V1 = """
        ## Planner Rules
        1. You are a planner. Do NOT attempt low-level UI actions directly.
        ...
    """.trimIndent()

    // ======== Executor Prompts ========
    val EXECUTOR_SYSTEM_V1 = """
        You are an Executor agent...
    """.trimIndent()

    val EXECUTOR_ROLE_RULES_V1 = """
        ## Executor Rules
        1. Execute ONE action per turn...
        ...
    """.trimIndent()

    // ======== Shared Rules ========
    val ELEMENT_SELECTION_RULES = """
        ## Element Selection (CRITICAL)
        Before acting, SCAN the screen JSON...
    """.trimIndent()

    // ======== Current Active Versions ========
    val ACTIVE = object {
        val plannerSystem = PLANNER_SYSTEM_V1
        val plannerRules = PLANNER_ROLE_RULES_V1
        val executorSystem = EXECUTOR_SYSTEM_V1
        val executorRules = EXECUTOR_ROLE_RULES_V1
    }
}
```

#### [MODIFY] `Turn.kt`

```diff
-private fun buildSystemPrompt(basePrompt: String): String {
-    val roleRules = if (hasDelegate && !hasMobileAction) {
-        """
-        ## Planner Rules
-        1. You are a planner...
-        """.trimIndent()
-    } else {
-        """
-        ## Executor Rules
-        ...
-        """.trimIndent()
-    }
-    ...
-}
+private fun buildSystemPrompt(basePrompt: String): String {
+    val roleRules = if (hasDelegate && !hasMobileAction) {
+        PromptTemplates.ACTIVE.plannerRules
+    } else {
+        PromptTemplates.ACTIVE.executorRules
+    }
+    ...
+}
```

#### [MODIFY] `ExecutorAgent.kt`

```diff
object ExecutorAgent {
    val definition: AgentDefinition = AgentDefinition(
        name = "executor",
-       systemPrompt = """
-           You are an Executor agent...
-       """.trimIndent(),
+       systemPrompt = PromptTemplates.ACTIVE.executorSystem,
        ...
    )
}
```

### Benefits
1. **Single source of truth**: All prompts in one file
2. **Version control**: Easy to add V2, V3 variants
3. **A/B testing**: Swap `ACTIVE` to test different prompts
4. **Diff-friendly**: Changes are visible in git

---

## Phase 2: Full Prompt Observability (High Value)

### Goal
Log the **complete prompt sent to LLM** every turn for debugging.

### Design

#### [NEW] `TurnLogger.kt`

```kotlin
object TurnLogger {
    private const val TAG = "TurnLogger"

    fun logTurnInput(
        turnNumber: Int,
        systemPrompt: String,
        userContext: String,
        historyItemsCount: Int,
        toolNames: List<String>
    ) {
        val fullPrompt = buildString {
            appendLine("=== TURN $turnNumber ===")
            appendLine("--- SYSTEM PROMPT ---")
            appendLine(systemPrompt)
            appendLine()
            appendLine("--- USER CONTEXT (truncated) ---")
            appendLine(userContext.take(2000))
            appendLine()
            appendLine("--- HISTORY ITEMS: $historyItemsCount ---")
            appendLine("--- TOOLS: ${toolNames.joinToString()} ---")
        }
        
        Log.d(TAG, fullPrompt)
        // Optional: write to trace file
    }

    fun logTurnOutput(
        turnNumber: Int,
        response: String?,
        toolCalls: List<ToolCallRequest>
    ) {
        val output = buildString {
            appendLine("=== TURN $turnNumber OUTPUT ===")
            appendLine("Response: ${response?.take(500)}")
            appendLine("Tool calls: ${toolCalls.map { it.name }}")
        }
        Log.d(TAG, output)
    }
}
```

#### Integration in `Turn.kt`

```kotlin
fun runStreaming(...): Flow<TurnStreamEvent> = flow {
    // Log input before LLM call
    TurnLogger.logTurnInput(
        turnNumber = turnNumber,
        systemPrompt = fullSystemPrompt,
        userContext = userContext.text,
        historyItemsCount = inputItems.size,
        toolNames = tools.map { it.name() }
    )
    
    // ... existing streaming code ...
    
    // Log output after completion
    TurnLogger.logTurnOutput(turnNumber, textContent, toolCalls)
}
```

### Trace File Output (Optional)

Write full prompt to `trace/{session_id}/turn_{n}_prompt.txt`:

```
=== SYSTEM PROMPT ===
You are an Android automation agent...

=== TODOS ===
- [✓] Open Gmail app
- [→] Find email from John
- [ ] Reply with "OK"

=== SCRATCHPAD ===
recipient: john@example.com

=== SCREEN STATE ===
{"elements": [...]}

=== AVAILABLE TOOLS ===
delegate_task, write_todos, scratchpad, complete_task
```

---

## Phase 3: Context Flow Visibility (Medium Value)

### Goal
Understand exactly what context reaches the LLM each turn.

### Design

#### [NEW] `ContextDumper.kt`

```kotlin
object ContextDumper {
    data class TurnContext(
        val systemPrompt: String,
        val roleRules: String,
        val todosContext: String,
        val scratchpadContext: String,
        val screenState: String,
        val historyItems: List<String>
    )

    fun capture(
        builder: AgentPromptBuilder,
        inputBuilder: TurnInputBuilder,
        snapshot: ScreenSnapshot
    ): TurnContext {
        return TurnContext(
            systemPrompt = builder.buildSystemPrompt(),
            roleRules = extractRoleRules(...),
            todosContext = sessionState.todos.toPromptContext(),
            scratchpadContext = sessionState.scratchpad.toPromptContext(),
            screenState = Perceptor.toPromptJson(snapshot),
            historyItems = historyManager.getItems().map { summarize(it) }
        )
    }

    fun dump(ctx: TurnContext, traceDir: File, turnNumber: Int) {
        File(traceDir, "turn_${turnNumber}_context.json").writeText(
            Json.encodeToString(ctx)
        )
    }
}
```

---

## Phase 4: Evaluation Framework (Future)

### Goal
Systematic measurement of agent improvements.

> [!NOTE]
> This phase is **optional** and can be deferred. The first 3 phases provide immediate research value.

### Design Sketch

```kotlin
data class TaskBenchmark(
    val id: String,
    val description: String,
    val expectedSteps: Int,
    val successCriteria: (ScreenSnapshot) -> Boolean
)

object Benchmarks {
    val OPEN_GMAIL = TaskBenchmark(
        id = "open_gmail",
        description = "Open the Gmail app",
        expectedSteps = 1,
        successCriteria = { it.packageName == "com.google.android.gm" }
    )
    
    val SEND_EMAIL = TaskBenchmark(
        id = "send_email",
        description = "Send an email to test@example.com",
        expectedSteps = 5,
        successCriteria = { ... }
    )
}

class MetricsCollector {
    fun record(
        benchmark: TaskBenchmark,
        result: TaskResult
    ) {
        // Log: task_id, success, turns_used, time_taken
    }
}
```

---

## Phased Implementation

| Phase | Goal | Risk | Effort |
|-------|------|------|--------|
| **1** | Prompt consolidation | Very Low | 1-2 hours |
| **2** | Full prompt logging | Very Low | 1 hour |
| **3** | Context flow dump | Low | 1-2 hours |
| **4** | Evaluation framework | Medium | 3-5 hours |

### Recommended Order

```
Phase 1 (Prompt Consolidation)
    ↓
Phase 2 (Prompt Logging) 
    ↓
[START ITERATING ON PROMPTS]
    ↓
Phase 3 (Context Dump) - if needed
    ↓
Phase 4 (Eval Framework) - when ready for systematic testing
```

---

## Key Research Questions to Explore

Once infrastructure is in place, iterate on:

### 1. Prompt Variants
- **Element selection emphasis**: More examples? Different priority order?
- **Completion criteria**: When should executor call `complete_task`?
- **Error recovery hints**: How to guide replanning?

### 2. Context Optimization
- **History compression**: Summarize older turns more aggressively?
- **Scratchpad usage**: Prompt to use scratchpad more?
- **Todo format**: Structured JSON vs natural language?

### 3. Delegation Quality
- **Query specificity**: How detailed should planner queries be?
- **Context passing**: What info helps executor most?

---

## Files to Modify (Phase 1 Only)

| Action | File | Change |
|--------|------|--------|
| **NEW** | `research/prompt/PromptTemplates.kt` | All prompt strings |
| **MODIFY** | `agent/Turn.kt` | Import from PromptTemplates |
| **MODIFY** | `agent/subagent/ExecutorAgent.kt` | Import from PromptTemplates |
| **OPTIONAL** | `agent/AgentPromptBuilder.kt` | Could also use PromptTemplates |

---

## Design Principles

1. **Non-invasive**: Research layer wraps existing code, doesn't modify core behavior
2. **Toggleable**: Logging can be enabled/disabled via flag
3. **No new dependencies**: Pure Kotlin, no external libs
4. **Trace-friendly**: Output is human-readable for debugging

---

## References

- [prompt_refactor.md](../agent_infra_reconcile/prompt_refactor.md) - Original suggestion (Chinese)
- [Turn.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt) - Current inline prompts
- [ExecutorAgent.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/ExecutorAgent.kt) - Executor system prompt
- [note_3_planning_claude.md](../reference/note_3_planning_claude.md) - Planning patterns from research
