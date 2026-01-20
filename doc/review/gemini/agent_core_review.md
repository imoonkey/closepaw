# Core Agent Logic Review

## Summary
The `agent/` package implements the core ReAct loop ("Perceive-Think-Act-Observe") in `Agent.kt` and the LLM interaction logic in `Turn.kt`. The design simplifies the previous multi-agent architecture into a single agent loop, which is a positive move for maintainability.

## High-Risk Issues (Must-Fix)

### 1. Hardcoded Tool Instructions (Turn.kt)
**Location**: `agent/Turn.kt` lines 108-173 (`buildToolInstructions`)
**Issue**: The system prompt's tool instructions are hardcoded as a string. This duplicates the logic in `ToolRegistry` and violates the Open-Closed Principle. Adding a new tool requires modifying this string manually. If the registry and this string drift, the agent will hallucinate tools or fail to use new ones.
**Fix**: Inject `ToolRegistry` into `Turn.kt` (it is already passed to constructor) and use `toolRegistry.generateFunctionSchemas()` or a similar method to dynamically generate the tool documentation for the prompt.

### 2. Network Errors Treated as Non-Recoverable
**Location**: `agent/Agent.kt` line 262
**Code**: `recoverable = !isNetworkError`
**Issue**: The agent treats network errors (UnknownHostException, etc.) as fatal/non-recoverable. Mobile networks are flaky. A temporary drop in connectivity should not kill the entire session.
**Fix**: Implement a retry mechanism with exponential backoff for network errors within the Agent loop, or mark them as recoverable to allow the loop to retry naturally.

## Medium Issues (Should-Fix)

### 3. Redundant Screen Capture (Performance)
**Location**: `agent/Agent.kt` line 217 vs `tools/base/BaseTool.kt` line 174
**Issue**: `Agent.kt` explicitly calls `captureObservation()` after tool execution. However, `BaseTool` *also* calls `capturePostActionObservation()` internally. The `ToolRouter` discards the observation from the tool, and then `Agent.kt` captures it again. This doubles the latency of the "Observe" phase.
**Fix**: Update `ToolRouter` to return the `observation` from the tool execution result, and have `Agent.kt` use that instead of re-capturing.

### 4. Ambiguous Completion Logic
**Location**: `agent/Turn.kt` line 212
**Issue**: `isComplete` is determined by `toolCalls.isEmpty()` AND finding a "DONE" marker. If the LLM generates a tool call *and* says "DONE", the agent will execute the tool but *not* mark the task as complete. This might lead to an infinite loop if the LLM keeps trying to "finish up" with a final action.
**Fix**: If "DONE" is detected, consider the task complete even if there are tool calls (after executing them), or strictly enforce "no tool calls with DONE" in the prompt and handle the violation.

## Low-Risk Suggestions (Nice-to-Have)

### 5. Prompt Construction Optimization
**Location**: `agent/Turn.kt`
**Suggestion**: The prompt construction creates large strings. Using a template engine or a more structured builder would make it easier to maintain and test different prompt strategies.

### 6. Turn ID Generation
**Location**: `agent/Agent.kt` line 132
**Suggestion**: `turn-1`, `turn-2` is simple but `turnCount` is a mutable var. Ensure it's reset properly if the agent is restarted (current logic creates new Agent instance so it's fine, but good to keep in mind).
