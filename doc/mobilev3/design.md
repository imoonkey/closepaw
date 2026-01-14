# Mobile-Agent-v3 Android Architecture Design

**Status**: Draft
**Author**: Engineering Team (Antigravity)
**Based on**: Mobile-Agent-v3 Research & Current Android MVP

## 1. Executive Summary

This document outlines the architectural redesign of our Android Agent to adopt the **Mobile-Agent-v3** orchestration logic. We are transitioning from a simple "Snapshot -> Action" loop to a robust **Multi-Agent System** that separates high-level planning from low-level execution and verification.

This design targets an **industry-grade implementation**, prioritizing modularity, testability, and state management within the Android ecosystem.

## 2. High-Level Architecture

The system follows a **Clean Architecture** approach, adapted for an Autonomous Agent.

```mermaid
graph TD
    User[User / UI] -->|Start Goal| Service[Agent Service]
    
    subgraph Core Logic
        Service --> Orchestrator
        Orchestrator --> InfoPool[InfoPool (State)]
        
        Orchestrator --> Manager[Manager Agent]
        Orchestrator --> Executor[Executor Agent]
        Orchestrator --> Reflector[Reflector Agent]
        Orchestrator --> Notetaker[Notetaker Agent]
    end
    
    subgraph Infrastructure
        Manager & Executor & Reflector & Notetaker --> LLM[LLM Client]
        LLM --> OpenAI[OpenAI API]
    end
    
    subgraph Android System
        Orchestrator --> Perception[Perception Engine (Sanitizer)]
        Orchestrator --> Controller[Action Controller]
        Perception --> Accessibility[Accessibility Service]
        Controller --> Accessibility
    end
```

### Key Modules

1.  **Orchestrator (`AgentOrchestrator`)**: The brain's main loop. It coordinates the lifecycle of the agents and manages the `InfoPool`.
2.  **InfoPool (`SessionState`)**: A centralized data store holding the current instruction, plan, execution history, and memory.
3.  **Agents**: Stateless (or near-stateless) logic units that transform state + context into decisions using an LLM.
4.  **Perception (`Perceptor`)**: Converts raw Android accessibility trees into semantic, token-optimized textual representations.
5.  **Controller (`ActionDispatcher`)**: Maps abstract agent actions (e.g., `click(element_id=5)`) to Android system calls (`dispatchGesture`).

---

## 3. Core Components Design

### 3.1. InfoPool (State Management)
**Class**: `com.moonkey.androidagent.domain.state.InfoPool`

We will implement a thread-safe Data Class to hold the session state.

```kotlin
data class InfoPool(
    val instruction: String,
    var plan: String = "",
    var currentSubgoal: String = "",
    val actionHistory: MutableList<AgentAction> = mutableListOf(),
    val summaryHistory: MutableList<String> = mutableListOf(), // Explanations
    val outcomes: MutableList<ValidationOutcome> = mutableListOf(), // A/B/C from Reflector
    var textMemory: String = "" // Notes from Notetaker
)
```

### 3.2. Agent Interface
**Interface**: `com.moonkey.androidagent.domain.agents.Agent`

All specialized agents share a common behavioral pattern: receiving the current state and producing a structured result.

```kotlin
interface Agent<Result> {
    suspend fun think(scope: InfoPool, context: ScreenContext): Result
}
```

#### The Specialized Agents

1.  **Manager (`PlanningAgent`)**:
    *   **Goal**: Decompose high-level instruction into a sub-goal plan.
    *   **Input**: `instruction`, `history`, `current_screen`.
    *   **Output**: `PlanUpdate` (new plan, current step).
    *   **Logic**: "If step 1 is done, move to step 2. If blocked, replan."

2.  **Executor (`ExecutionAgent`)**:
    *   **Goal**: Choose the *atomic* action to achieve the `currentSubgoal`.
    *   **Input**: `currentSubgoal`, `current_screen`.
    *   **Output**: `AtomicAction` (Type: Click/Type/Scroll, Target: ID/Text).
    *   **Constraint**: Must reference elements present in `ScreenContext`.

3.  **Reflector (`ValidationAgent`)**:
    *   **Goal**: Verify if the *previous* action succeeded.
    *   **Input**: `ScreenContext` (Before), `ScreenContext` (After), `LastAction`.
    *   **Output**: `ValidationResult` (Success / Failed-Backtrack / Failed-NoChange).
    *   **Heuristic**: Since we use Accessibility Trees, we can diff the tree structure or check for specific expected keywords.

4.  **Notetaker (`MemoryAgent`)**:
    *   **Goal**: Update long-term memory with relevant info.
    *   **Input**: `ScreenContext`, `instruction`.
    *   **Output**: `String` (New notes).

### 3.3. Perception & Controller (Android Bridge)

*   **`Perceptor` (Renamed from Sanitizer)**: Enhance to return a stable `ScreenSnapshot` object that contains both the raw tree (for diffing) and the simplified list (for LLM).
*   **`ActionDispatcher`**:
    *   Input: `atomic_action: { type: "click", target_id: 5 }`
    *   Logic: Lookup ID 5 in the `Perceptor` map -> Get generic `Rect` -> `dispatchGesture`.

---

## 4. The Orchestration Loop

This logic lives in `AgentService.runAgent()`, managed by `AgentOrchestrator`.

```kotlin
suspend fun runLoop(goal: String) {
    val infoPool = InfoPool(instruction = goal)
    var previousSnapshot: ScreenSnapshot? = null

    while (isActive) {
        // 1. Perception
        val currentSnapshot = perceptor.snapshot()
        
        // 2. Reflection (Did the LAST thing work?)
        if (previousSnapshot != null && infoPool.lastAction != null) {
            val outcome = reflector.think(previousSnapshot, currentSnapshot, infoPool.lastAction)
            infoPool.recordOutcome(outcome)
            
            if (outcome.isFailure) {
                // Determine if we need to Backtrack or Re-plan
            }
        }

        // 3. Planning (What are we doing now?)
        // Only run Manager if we just started, finished a subgoal, or failed hard.
        if (shouldReplan(infoPool)) {
            val validPlan = manager.think(infoPool, currentSnapshot)
            infoPool.updatePlan(validPlan)
        }
        
        if (infoPool.isFinished) break

        // 4. Execution (Do it.)
        val action = executor.think(infoPool, currentSnapshot)
        controller.perform(action, currentSnapshot)
        
        // 5. Update State
        infoPool.lastAction = action
        previousSnapshot = currentSnapshot
        
        delay(1000) // Wait for UI functionality
    }
}
```

---

## 5. Implementation Strategy

### Phase 1: Refactoring (The Foundation)
1.  **Extract `LLMClient`**: Make it a generic provider that accepts `List<Message>` instead of hardcoded prompts.
2.  **Create `InfoPool`**: Define the data structure in a new package `component.state`.
3.  **Enhance `Perceptor`**: Ensure generated IDs are stable enough for the action phase.

### Phase 2: The Agents (The Brains)
1.  **Implement `ManagerAgent`**: Port the prompt logic from Mobile-Agent-v3.
2.  **Implement `ExecutorAgent`**: Port the prompt logic, adapting "Coordinates" to "Element IDs".
3.  **Implement `ReflectorAgent`**: Create a text-diff based prompt.

### Phase 3: Orchestration (The Wiring)
1.  rewire `AgentService` to use the `Orchestrator` class instead of direct LLM calls.
2.  Implement the loop.

### Phase 4: Polish
1.  **Error Handling**: What if LLM returns bad JSON? Adaptation: Retry logics.
2.  **Logging**: detailed logcat traces for each agent's "Thought".

## 6. Prompt Engineering Adaptation

Mobile-Agent-v3 relies on VLM (Vision). We are using LLM (Text).
*   **Visual Logic replacement**: "The screenshot shows..." -> "The screen contains the following interactable elements:..."
*   **Coordinate replacement**: "Click at [100, 200]" -> "Click element with ID: 12".

**Example Executor Prompt**:
```text
Goal: Send a message to John.
Plan: 1. Open Messages. 2. Search John. ...
Screen:
[1] App Icon: Messages
[2] App Icon: Camera
...
Task: Execute the next step.
Action: {"type": "click", "element_id": 1}
```

## 7. Quality Assurance
*   **Unit Tests**: Test Agents with mock ScreenSnapshots. Verify Prompts are constructed correctly.
*   **Integration Checks**: Verify `ActionDispatcher` correctly hits the bounds of elements from `Sanitizer`.
