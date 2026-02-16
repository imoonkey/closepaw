# Design: System Prompt Composition

**Priority**: P2 — DRY
**Files affected**: `agent/definition/PlannerAgentDef.kt`, `agent/definition/StandaloneAgentDef.kt`, `agent/definition/ExecutorAgentDef.kt`

---

## Problem

The 3 `AgentDef` implementations contain system prompts with ~60% textual overlap:

- **Common sections** (duplicated in all 3):
  - Tool usage instructions (how to use `mobile_action`, `system_button`, `wait`, etc.)
  - Screen element reference format (`[index] "text" type @(x,y)`)
  - Action execution guidelines (tap vs type, scrolling, waiting)
  - Error recovery patterns (retry, try alternative, report)
  - `agent_thought` field instructions
  - `complete_task` usage rules

- **Unique sections**:
  - `PlannerAgentDef`: Planning instructions, todo/scratchpad usage, sub-task delegation
  - `ExecutorAgentDef`: "You are an executor" framing, narrow scope instructions, completion reporting
  - `StandaloneAgentDef`: Self-contained agent framing, direct goal execution

When common instructions are updated (e.g., a new tool is added or action guidelines change), all 3 files must be edited. This has already caused drift — `ExecutorAgentDef` has slightly different wording for the same instructions.

## Solution

Extract a prompt composition system with reusable fragments.

### PromptFragments object

```kotlin
// agent/definition/prompt/PromptFragments.kt
object PromptFragments {

    val TOOL_USAGE = """
        ## Tools Available
        You have access to the following tools:
        - `mobile_action`: Tap, type, scroll, swipe on screen elements...
        ...
    """.trimIndent()

    val SCREEN_FORMAT = """
        ## Screen State Format
        Screen elements are presented as:
        [index] "text" type @(x,y) [flags]
        ...
    """.trimIndent()

    val ACTION_GUIDELINES = """
        ## Action Guidelines
        - Always reference elements by their [index]
        - Wait after actions for the UI to settle
        ...
    """.trimIndent()

    val ERROR_RECOVERY = """
        ## Error Recovery
        If an action fails:
        1. Re-examine the screen state
        2. Try an alternative approach
        ...
    """.trimIndent()

    val AGENT_THOUGHT = """
        ## Thinking
        Every tool call MUST include an `agent_thought` parameter...
    """.trimIndent()

    val COMPLETION_RULES = """
        ## Task Completion
        Call `complete_task` when the goal is achieved...
    """.trimIndent()
}
```

### Prompt builder DSL

```kotlin
// agent/definition/prompt/SystemPromptBuilder.kt
class SystemPromptBuilder {
    private val sections = mutableListOf<String>()

    fun section(title: String, content: String) {
        sections.add(content)
    }

    fun include(fragment: String) {
        sections.add(fragment)
    }

    fun build(): String = sections.joinToString("\n\n")
}

fun buildSystemPrompt(block: SystemPromptBuilder.() -> Unit): String =
    SystemPromptBuilder().apply(block).build()
```

### Usage

```kotlin
// PlannerAgentDef.kt
override val systemPrompt = buildSystemPrompt {
    section("Role", "You are a planning agent that breaks complex tasks into sub-tasks...")
    include(PromptFragments.TOOL_USAGE)
    include(PromptFragments.SCREEN_FORMAT)
    include(PromptFragments.ACTION_GUIDELINES)
    section("Planning", "Create a todo list for the task...")
    section("Delegation", "Delegate sub-tasks to executor agents...")
    include(PromptFragments.AGENT_THOUGHT)
    include(PromptFragments.COMPLETION_RULES)
}
```

## Steps

1. Create `agent/definition/prompt/PromptFragments.kt` — extract common text from existing prompts
2. Create `agent/definition/prompt/SystemPromptBuilder.kt` — simple builder DSL
3. Refactor `PlannerAgentDef` to use builder + fragments
4. Refactor `StandaloneAgentDef` to use builder + fragments
5. Refactor `ExecutorAgentDef` to use builder + fragments
6. Delete commented-out code in `StandaloneAgentDef`
7. Verify prompts are functionally identical by diffing old vs new output

## Risks

- **Medium**: Prompt text changes must be tested empirically (LLM behavior is sensitive to wording). Diff old vs new carefully.
- **Low**: The builder is simple — no framework, no magic.
