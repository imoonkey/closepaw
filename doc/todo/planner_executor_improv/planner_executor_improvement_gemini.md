# Planner-Executor Improvement Plan

This document benchmarks the current AndroidAgent `Executor` implementation against the `MobileWorld` implementation to identify areas for improvement.

## 1. MobileWorld's Planner-Executor (`planner_executor.py`)

### Details
*   **Architecture**: Explicit two-stage process.
    *   **Planner**: LLM (e.g., GPT-4o) outputting `Thought` + abstract `Action` (e.g., `Click Search Button`).
    *   **Executor**: A dedicated grounding step (often using a smaller or same model) that translates the abstract action + screenshot into specific coordinates (`x, y`).
*   **Tooling**:
    *   Raw JSON output format for `click`, `scroll`, etc.
    *   **Coordinate-based**: Heavily relies on normalized (0-1000) coordinates.
    *   **History**: Explicitly manages image history, hiding older images to save tokens (`_hide_history_images`).
*   **Delegation**:
    *   The planner *explicitly* constructs a delegation instruction string if the action type is complex (e.g., `drag` splits into two clicks).
    *   The executor is treated as a function call `get_executor_action(instruction)`.

## 2. Your Current AndroidAgent Implementation (`SubAgentRunner.kt`)

### Details
*   **Architecture**: Sub-agent delegation via `delegate_task`.
    *   **Planner**: The main agent.
    *   **Executor**: An `IsolatedSubAgentRunner` with a specialized system prompt (`ExecutorPromptTemplate.kt`). It runs as a full "mini-agent" with its own history and loop (max 5 turns).
*   **Tooling**:
    *   Standard `function calling` (OpenAI format).
    *   **Semantic**: Prefers `element_index`, `resource_id`, or `text` over raw coordinates (though coordinates are possible).
    *   **Lifecycle**: Explicit `complete_task(status, answer)` tool to return control.
*   **Delegation**:
    *   The parent creates a `SubAgentRequest` with a natural language query.
    *   The child agent runs until `complete_task` or timeout.

## 3. Comparison & Gap Analysis

| Feature | MobileWorld (Ref) | AndroidAgent (Yours) | Eval / Gap |
| :--- | :--- | :--- | :--- |
| **Granularity** | **Atomic Action**: One planner step -> One executor step (mostly). | **Sub-task**: One planner step -> Full sub-agent loop (1-5 turns). | **Yours is more agentic.** MobileWorld's executor is just a "grounding model". Yours is a "problem solver". Yours handles "Click X, if not there scroll then click" better. |
| **Grounding** | **Visual Coordinates**: Heavily relies on VLM predicting x,y. | **Accessibility/Hybrid**: Uses A11y tree mostly. | **MobileWorld's approach is more robust for non-standard UI** (games, canvas) where A11y fails. You might want to add better coordinate support. |
| **History** | **Optimized**: Explicitly drops old images to save context. | **Standard**: Relies on `HistoryManager`. | **Gap**: You should check if `SubAgentRunner` is sending too much history or duplicate screenshots. |
| **Output** | **JSON/Text**: Custom parsing of `Thought`/`Action`. | **Tool Calls**: Native function calling. | **Yours is cleaner/modern.** |
| **Tools** | **Unified**: `mobile_use` tool with `action` param. | **Separated**: `mobile_action`, `app_control`, `scratchpad`. | **Yours is more modular.** |

## 4. Recommendations for Improvement

### A. Enhance Visual Grounding (Critical for generic apps)
MobileWorld proves that **coordinate-based interaction** is essential for robustness when A11y is messy.
*   **Action**: Update `ExecutorPromptTemplate` to explicitly encourage coordinate-based clicks (`x, y`) when `resource_id` or `text` logic fails.
*   **Action**: Ensure your `mobile_action` tool handles normalized (0-1000) coordinates well, mirroring MobileWorld's `normalize_coord_to_pixel`.

### B. Optimize Context Management
MobileWorld's `_hide_history_images` is a smart optimization.
*   **Action**: In `SubAgentRunner.kt` (or `Agent.kt` for subagents), implement a history filter that:
    1.  Keeps the *initial* screenshot (context).
    2.  Keeps the *latest* screenshot (current state).
    3.  **Drops intermediate screenshots** to save tokens and reduce latency.

### C. Refine Delegation Prompts
Your `ExecutorAgent` prompt focuses on "Atomic UI action", but allows 5 turns.
*   **Action**: Tighten the `ExecutorPromptTemplate` to strictly enforce **Analysis -> Action -> Verify -> Complete**.
*   **Action**: Add specific "Failure Recovery" examples from MobileWorld (e.g., "If click fails, double tap" or "If click fails, long press"). MobileWorld explicitly hardcodes this fallback logic; you can prompt your agent to do it.

### D. Add "User Simulation" for Training/Eval
MobileWorld has a `User Agent` that answers questions (`ask_user`).
*   **Action**: Implement a `UserSimulator` tool/agent that listens for `ask_user` tool calls in your dev/eval environment and auto-responds based on a "Persona" file. This allows headless testing of interactive flows.
