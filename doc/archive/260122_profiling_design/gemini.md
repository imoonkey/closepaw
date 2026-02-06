# System Performance Profiling & Optimization Design

## 1. Objective
Reduce the end-to-end (E2E) execution time of the Android Agent by 50%.

## 2. Profiling Strategy

### 2.1. Metrics
We will break down the wall-clock time of a single Turn into the following components:

1.  **Perception Time (`t_perceive`)**: Time to capture the Android Accessibility tree and convert it to a `ScreenSnapshot`.
2.  **Prompt Engineering Time (`t_prompt`)**: Time to build the system prompt and user context (JSON generation).
3.  **LLM Latency (`t_llm`)**: Time waiting for the OpenAI API response (TTFT + Generation).
4.  **Action Execution Time (`t_act`)**: Time to execute the tool command on the device.
5.  **Observation Time (`t_observe`)**: Time to capture the post-action screen state.
6.  **Settle Time (`t_settle`)**: Explicit delays added for UI stability.
7.  **Overhead (`t_overhead`)**: JSON parsing, object allocation, and internal logic.

### 2.2. Instrumentation Plan
We will add high-precision logging (`System.nanoTime()`) to `Agent.kt` and `Turn.kt`.

**`Agent.kt`**:
-   Wrap `services.platform.captureScreen()`
-   Wrap `Perceptor.toPromptJson()`
-   Wrap `executeTurn()` loop
-   Wrap `services.toolRouter.execute()`
-   Wrap `delay()` calls

**`Turn.kt`**:
-   Wrap `llmClient.chatWithTools()`

**Log Format**:
`PERF: [SessionID] [TurnID] [Component] [DurationMs]`

Example:
```
PERF: 123 turn-1 PERCEPTION 150
PERF: 123 turn-1 PROMPT_GEN 20
PERF: 123 turn-1 LLM_LATENCY 2500
PERF: 123 turn-1 ACTION_EXEC 50
PERF: 123 turn-1 OBSERVE 160
PERF: 123 turn-1 SETTLE 2000
```

### 2.3. Analysis Tools
1.  **Log Parser**: A Python script (`scripts/analyze_perf.py`) to parse the logs and generate a CSV report.
2.  **Visualizer**: A script to generate a Gantt chart (using `matplotlib` or `plotly`) showing the timeline of a session.

## 3. Optimization Proposals (Hypotheses)

### 3.1. Reduce LLM Latency (High Impact)
*   **Hypothesis**: LLM call is the dominant factor (>60% of time).
*   **Idea 1: Model Distillation**: Use `gpt-4o-mini` for simple navigation steps (scroll, click obvious buttons) and switch to `gpt-4o` only for complex reasoning.
*   **Idea 2: Parallel Pre-fetching**: While the tool is executing, start pre-computing the next prompt context (if possible).
*   **Idea 3: Speculative Decoding**: Not applicable via API, but we could prompt for multiple steps at once ("Plan the next 3 actions").

### 3.2. Optimize UI Settle Time (Medium Impact)
*   **Hypothesis**: Fixed 2000ms delay + 500ms observation delay is conservative and often unnecessary.
*   **Idea**: **Adaptive Wait**. instead of `delay(2000)`, poll the screen state every 100ms. If the accessibility tree hash remains stable for 300ms, proceed immediately. This could cut average settle time from 2000ms to ~500ms.

### 3.3. Optimize Perception (Low/Medium Impact)
*   **Hypothesis**: `Perceptor.snapshot` might be slow on complex trees.
*   **Idea**: Optimize the DFS traversal or offload JSON serialization to a background thread if it blocks the main loop.
*   **Idea**: Only re-capture the part of the screen that changed (if Android supports partial updates, hard to do with AccessibilityNodeInfo).

### 3.4. Token Reduction
*   **Hypothesis**: Large prompts increase TTFT and cost.
*   **Idea**: Compress the `accessibilityTree` JSON. Remove redundant fields, use shorter keys, or a more compact format (e.g. simplified XML or pseudo-code).

## 4. Execution Plan

1.  **Baseline**: Run the agent on "Open Settings" 5 times without changes. Collect logs.
2.  **Instrumentation**: Apply the logging changes.
3.  **Profile**: Run the standard test set and generate the breakdown.
4.  **Implement Adaptive Wait**: Replace fixed delays with stability polling. Measure improvement.
5.  **Experiment with Prompting**: Test `gpt-4o-mini`.

## 5. Visual Debugging Integration
Leverage existing `scripts/agent_process_visual_debug.md` workflow but overlay performance data on the screenshots (e.g., "Time taken: 3.2s").

---
*Created by Gemini*
*Date: 2026-01-21*
