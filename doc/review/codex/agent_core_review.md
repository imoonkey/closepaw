# Agent Core Review

## Summary

Core agent loop (ReAct), LLM turn execution, history management, and perception serialization. This layer orchestrates screen capture, prompt construction, tool parsing, and completion detection.

## High-risk issues (must-fix)

### Multiple tool calls execute against a stale snapshot
- Why it matters: if the model returns more than one tool call in a single response, all calls use the same initial snapshot and raw node map. This can mis-target UI elements after the first action changes the screen.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt` — `executeTurn()` tool loop uses one `snapshot` for all calls.
- Fix: enforce a single tool call per turn (discard extras), or re-capture the screen and rebuild `ToolRouterContext` before each tool execution.

## Medium issues (should-fix)

### Post-action observation is captured twice and not used consistently
- Why it matters: Base tools capture observation, but Agent ignores it and captures again, increasing latency and token usage.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/tools/base/BaseTool.kt` (observation capture) and `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt` (`captureObservation()` + `formatToolResult()`).
- Fix: thread `ToolObservation` through `ToolCallResult` and use it in history formatting; remove the extra capture in `Agent`.

### Model configuration is ignored
- Why it matters: `SessionConfig.model` is never used, so model selection is locked to `GPT_4O`.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/data/llm/LLMClient.kt` — `executeChat()` always uses `ChatModel.GPT_4O`.
- Fix: pass a model parameter from `SessionConfig` into `LLMClient.chat(...)` and build the request with that model.

## Low-risk suggestions (nice-to-have)

### Tool outputs are injected as `USER` messages
- Why it matters: tool outputs framed as user messages can confuse the model’s role expectations.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt` — `buildMessages()` handling of `FunctionCallOutput`.
- Fix: introduce a “tool” role abstraction or use `SYSTEM`/`ASSISTANT` with a clear prefix.

### Fallback tool JSON parsing is brittle
- Why it matters: regex-based JSON extraction can mis-parse nested JSON or partial blocks.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt` — `parseResponse()` fallback regex.
- Fix: require ```tool blocks only, or implement a robust JSON parser that detects balanced braces.
