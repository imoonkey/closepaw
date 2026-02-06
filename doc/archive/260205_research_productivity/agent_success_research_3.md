# Design: Agent Research Productivity Infrastructure

> **Status**: Draft
> **Date**: 2026-02-04
> **Goal**: Centralize "Agent Success" components (Prompts, Tools, Strategies) to enable rapid iteration and research.

---

## 1. Problem Statement

Currently, the "Brain" of the agent—the prompt construction logic, system instructions, and decision-making rules—is tightly coupled with the "Body" (execution runtime).

- **Scattered Prompts**: System prompts are hardcoded in `AgentRuntime.kt` and `AgentPromptBuilder.kt`.
- **Implicit Logic**: Role behavior (Planner vs. Executor) is determined by `if` checks on tool presence, rather than explicit strategy.
- **Low Observability**: It is difficult to see the *exact* final prompt text sent to the LLM for a specific turn, making debugging "why did it do that?" hard.
- **Slow Iteration**: Changing a prompt requires code recompilation and navigating complex runtime files.

## 2. Core Philosophy: "The Lab & The Factory"

We need to separate **The Factory** (stable, high-performance runtime, tool execution, state management) from **The Lab** (experimental, rapidly changing prompts, strategies, and heuristics).

- **Factory**: `AgentTurnRunner`, `Turn`, `AgentRuntime`, `ToolRegistry`. (Keep stable)
- **Lab**: `agent/prompt/`, `agent/strategy/`. (Iterate wildy)

## 3. Architecture Proposal

### 3.1. Centralized Prompt Module

Move all prompt generation logic to `com.moonkey.androidagent.agent.prompt`.

**New Components:**
- **`PromptAssembler`**: The single source of truth for building the final prompt string. It takes the `AgentDefinition` (or Role) and `TurnContext` and outputs the full prompt.
- **`PromptTemplate`**: A sealed interface or abstract class defining how specialized prompts are constructed.
    - `PlannerPromptTemplate`
    - `ExecutorPromptTemplate`
- **`TraceWriter`**: A dedicated component to write the *full, raw* prompt to disk for every turn.

**Directory Structure:**
```text
app/src/main/kotlin/com/moonkey/androidagent/agent/
├── prompt/
│   ├── PromptAssembler.kt       <-- Entry point
│   ├── PromptTemplate.kt        <-- Interface
│   ├── templates/
│   │   ├── PlannerSystemPrompt.kt
│   │   ├── ExecutorSystemPrompt.kt
│   │   └── CommonPrompts.kt     <-- Shared parts (response format, etc.)
│   └── context/
│       ├── ScreenContext.kt     <-- Logic to format screen JSON
│       └── HistoryContext.kt    <-- Logic to format history
```

### 3.2. Explicit Agent Identity

Instead of inferring roles from tools, strict `AgentRole` or `AgentStrategy` should define the prompt.

```kotlin
enum class AgentRole {
    PLANNER,
    EXECUTOR,
    // Future: CRITIC, MEMORY_MANAGER
}
```

`AgentPromptBuilder` will rely on this `AgentRole` to select the correct `PromptTemplate`.

### 3.3. Enhanced Observability (The "Black Box" Recorder)

For every turn, we generate a trace artifact:
`session_{id}/turn_{N}_full_prompt.txt`

This file must contain the **exact character-for-character** string sent to the LLM. This is critical for:
1.  Debugging hallucination vs. bad context.
2.  Data collection for fine-tuning.
3.  Regression testing prompts.

---

## 4. Implementation Plan

### Phase 1: Prompt Refactor (Immediate)
Target: `app/src/main/kotlin/com/moonkey/androidagent/agent/prompt/`

1.  **Extract**: Move `DEFAULT_SYSTEM_PROMPT` and `LOCAL_PROMPT_SUFFIX` from `AgentRuntime` to `templates/SystemPrompts.kt`.
2.  **Extract**: Move dynamic logic (e.g. "if has delegate") from `AgentPromptBuilder` to `templates/PlannerSystemPrompt.kt` and `templates/ExecutorSystemPrompt.kt`.
3.  **Refactor**: Create `PromptAssembler` to replace most logic in `AgentPromptBuilder`.
4.  **Wire up**: Update `AgentRuntime` to use `PromptAssembler`.

### Phase 2: Full Trace Recording
Target: `AgentTurnRunner.kt`

1.  Intercept the final built prompt.
2.  Write it to `Trace` or `HistoryManager` as a debug artifact.

### Phase 3: Research Enablement (Future)

1.  **Prompt Config**: Allow loading prompt templates from Assets (text files) instead of hardcoded Kotlin strings, enabling hot-swapping or A/B testing without recompilation.
2.  **Eval Integration**: Tools to run a fixed set of tasks against different Prompt versions and measure Success Rate.

---

## 5. Benefits

- **Focus**: Agent researchers work in `agent/prompt/`, Infra engineers work in `agent/`.
- **Clarity**: "What instructions does the agent have?" is answered by looking at one folder.
- **Speed**: Debugging is faster with full prompt traces.
