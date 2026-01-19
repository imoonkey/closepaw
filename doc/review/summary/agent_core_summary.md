# Agent Core - Consolidated Review Summary

> **Files**: `agent/Agent.kt`, `agent/Turn.kt`, `agent/AgentConfig.kt`, `agent/AgentSource.kt`
> **Reviewers**: Claude, Codex, Gemini

## High-Risk Issues (Must Fix)

### 1. Double Screen Capture in Observation Phase
**Consensus**: All three reviewers (Claude, Codex, Gemini)
**Location**: `Agent.kt:216-227`, `BaseTool.kt:193-208`

**Problem**: Screen is captured twice after each tool execution:
1. `BaseTool.capturePostActionObservation()` captures screen (result discarded)
2. `Agent.captureObservation()` captures screen again

The observation from BaseTool is populated but never surfaced to the Agent.

**Impact**: 
- Wasted performance (~30-50% slower per tool, ~800ms+ total delay)
- Potential state inconsistency if screen changes between captures

**Fix**: Propagate `ToolObservation` from BaseTool through `ToolCallResult.Success` and use it in Agent instead of re-capturing.

**Team Note**: Go with this fix, but note in the code or document that this is worth revisiting later, because if there is delay between this capture and the next cycle of llm call, then it could be stale. This is less of a concern at this stage.

---

### 2. Snapshot Staleness in Multi-Tool Execution
**Consensus**: Claude, Codex
**Location**: `Agent.kt:191-193`

**Problem**: When executing multiple tool calls from a single LLM response, all tools use the same snapshot captured before the first tool:
```kotlin
val context = SimpleToolRouterContext(
    platform = services.platform,
    currentSnapshot = snapshot  // Same stale snapshot for all tools
)
```

**Impact**: Element indices become invalid after the first action modifies the screen.

**Fix**: Either enforce single tool per turn, or re-capture snapshot before each subsequent tool execution.

**Team Note**: repcature snapshots before each subsequent tool execution (this should already be available as the return of tool execution results, see the above item 1), don't enforce single tool per turn.

---

### 3. Network Error Recovery Logic Inverted
**Consensus**: Claude, Gemini
**Location**: `Agent.kt:254-263`

**Problem**: Network errors are marked as non-recoverable:
```kotlin
val isNetworkError = ... // detects network issues
TurnOutcome.Error(
    message = ...,
    recoverable = !isNetworkError  // WRONG: network errors ARE often recoverable
)
```

Mobile networks are flaky. Transient connectivity issues should allow retry, not kill the session.

**Impact**: Agent stops unnecessarily on temporary network issues.

**Fix**: Implement retry with exponential backoff for transient network errors. Make `SocketTimeoutException` and connection errors recoverable, keep `UnknownHostException` (DNS failure) as non-recoverable.

---

### 4. Tool Call Parsing Regex Brittleness
**Consensus**: Claude, Codex
**Location**: `Turn.kt:188`, `Turn.kt:199`

**Problem**: 
1. Primary pattern requires `tool` immediately after backticks - LLM might add spaces
2. Fallback JSON pattern `[^{}]*` cannot match nested JSON (arguments with nested objects fail)
3. Neither handles CRLF line endings properly

**Impact**: Valid tool calls may be missed.

**Fix**: Use a more robust primary pattern and implement proper JSON extraction with balanced brace detection.

**Team Note**: Use openai api's official interface of tool_call, instead of asking it to put it in the text portion, and do your own json parsing. The official api way should be more robust.

---

### 5. Completion Detection False Positives
**Consensus**: Claude, Gemini
**Location**: `Turn.kt:212-217`

**Problem**: Completion detection uses substring matching:
```kotlin
response.contains("goal achieved", ignoreCase = true) ||
response.contains("task completed", ignoreCase = true)
```

This can trigger false positives if LLM says "I haven't achieved the goal yet" or discusses past context.

**Impact**: Agent may terminate prematurely thinking goal is achieved.

**Fix**: Use more specific patterns or require explicit DONE marker at line start.

**Team Note**: Don't rely on string patterns. Make this a tool_call like "complete_task", or exit when there is no tool call in a Turn.
---

### 6. Tool Instructions Hardcoded (Not Dynamic)
**Consensus**: Claude, Gemini
**Location**: `Turn.kt:108-173`

**Problem**: System prompt's tool instructions are hardcoded as a string, duplicating logic in `ToolRegistry`. Adding a tool requires modifying this string manually.

**Impact**: If registry and prompt drift, agent will hallucinate tools or fail to use new ones.

**Fix**: Use `ToolRegistry.generateFunctionSchemas()` to dynamically generate tool documentation for the prompt.

**Team Note**: Yes, use the tool registry, also compare the toolregistry registered tools' prompts with the currently hardcoded prompt, make sure there is no performance degradation or unnecessarily long tool instruction prompts.

---

## Medium Issues (Should Fix)

### M1. Tool Call ID Linkage Inconsistency
**Reviewer**: Claude
**Location**: `Agent.kt:181-188` vs `Agent.kt:220-226`

Tool call is recorded with `toolCall.id` (from Turn.kt), but `ToolRouter` generates a different `result.callId`. The history uses `toolCall.id` for output, but events use `result.callId` for ActionExecuted.

**Fix**: Use consistent ID throughout - either pass Turn's ID to ToolRouter or ignore ToolRouter's ID.

**Team Note**: This one I am not sure and will lean on you. But if you fix the tool call item H4 above using openai tool call api, then maybe you can use its llm returned tool call ids. Make your own judgement on whether you should create a separate tool call id above the llm call layer, and preserve both, but unless there is strong reason, don't do it.

Btw, you should definitely use Responses API if not using it now instead of the Chat Completions API. Make sure to use the latest stable version of OpenAI API sdk, update dependency version if needed. Below is a reference for the API:
```
**每个 tool call 都会带一个唯一标识符**，但**字段名取决于你用的是哪套 OpenAI API**：

### 1) Chat Completions API（`/v1/chat/completions`）

模型返回的 `tool_calls` 里，**每个 tool call 都有 `id`**；你在回传工具结果时，用 `tool_call_id` 去对齐它： ([OpenAI平台][1])

* tool call：`message.tool_calls[i].id`
* tool 结果消息：`{"role":"tool", "tool_call_id":"<那个id>", ...}`

### 2) Responses API（推荐的新接口）

在 Responses API 里，**function tool call 用的是 `call_id`**（不是 `id`），你回传结果时同样用它来匹配： ([OpenAI平台][2])

* function call item：`{ "type":"function_call", "call_id":"..." }`
* function 输出：`{ "type":"function_call_output", "call_id":"...", "output":"..." }`

另外，某些**内置工具调用**（比如 `file_search_call`）会在输出 item 里直接带 `id`： ([OpenAI平台][3])
```

---

### M2. Pause State Race Condition
**Reviewer**: Claude
**Location**: `Agent.kt:60`, `Agent.kt:324-327`, `Agent.kt:329-332`

`pause()` and `resume()` modify `pauseState` without synchronization. Rapid succession calls can cause state/status inconsistency.

**Fix**: Use mutex or atomic operations for pause state changes.

---

### M3. Model Configuration Ignored
**Reviewer**: Codex
**Location**: `LLMClient.kt:95`

`SessionConfig.model` is never used - model selection is locked to `GPT_4O`.

**Fix**: Pass model parameter from config into LLM calls.

---

### M4. Ambiguous Completion Logic
**Reviewer**: Gemini
**Location**: `Turn.kt:212`

`isComplete` requires `toolCalls.isEmpty()` AND finding "DONE". If LLM generates a tool call AND says "DONE", the tool executes but task isn't marked complete.

**Fix**: If "DONE" detected, consider task complete even with tool calls, or enforce "no tool calls with DONE" strictly.

---

## Low-Risk Suggestions (Nice to Have)

| Issue | Reviewer | Location | Suggestion |
|-------|----------|----------|------------|
| Magic number for UI settle delay | Claude | `Agent.kt:273` | Make 500ms configurable via AgentConfig |
| Turn ID redundancy | Claude | `Agent.kt:369-376` | `turnNumber = turnCount` duplicates info in `turnId` |
| System prompt as string literal | Claude | `Agent.kt:38-55` | Load from resource file |
| Unused Observation.TextOutput | Claude | `Agent.kt:392-395` | Remove or document intended use |
| AgentSource enum unused | Claude | `AgentSource.kt` | Remove or add TODO for future multi-agent |
| Prompt construction optimization | Gemini | `Turn.kt` | Use template engine for easier maintenance |
| Turn ID generation safety | Gemini | `Agent.kt:132` | Ensure turnCount reset on agent restart |

---

## Open Questions

1. **Multi-tool per turn**: System prompt says "Use ONLY ONE tool call per response" but code handles multiple. Which is intentional?
Multiple is intentional.

2. **Sub-agent interface**: `cancellationSignal: CompletableDeferred<AgentStopReason>` seems designed for external cancellation. Is this for future parent agent use?
Yes.

3. **Context window management**: No explicit check for context window limits before calling LLM. Is this handled elsewhere?
I am not sure.