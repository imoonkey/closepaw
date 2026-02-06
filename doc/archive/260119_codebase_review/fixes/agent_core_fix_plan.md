# Agent Core - Fix Plan & Progress

> **Source**: `doc/review/summary/agent_core_summary.md`
> **Files**: `agent/Agent.kt`, `agent/Turn.kt`, `agent/AgentConfig.kt`, `agent/AgentSource.kt`
> **Related**: `tools/impl/CompleteTaskTool.kt`, `data/llm/LLMClient.kt`, `infra/registry/ToolRegistry.kt`, `infra/tools/*.kt`

---

## High-Risk Issues (Must Fix)

### H1. Double Screen Capture in Observation Phase
**Status**: DONE

**Changes Made**:
- `ToolObservation.ScreenState` now includes the actual `snapshot` object (in `ToolSpec.kt`)
- `BaseTool.capturePostActionObservation()` populates the snapshot in the observation (in `BaseTool.kt`)
- `ToolCallResult.Success` propagates the observation (in `ToolCallResult.kt`, `ToolRouter.kt`)
- `Agent.kt` uses observation from tool result instead of re-capturing (lines 225-241)
- Added note about potential staleness for future consideration (lines 225-228)

---

### H2. Snapshot Staleness in Multi-Tool Execution
**Status**: DONE

**Changes Made**:
- Added `var currentSnapshot = snapshot` to track current state (Agent.kt line 185)
- Updates `currentSnapshot` from tool observation's snapshot after each tool (lines 234-241)
- Uses `currentSnapshot` for subsequent tool executions in the loop (line 202)

---

### H3. Network Error Recovery Logic Inverted
**Status**: DONE

**Changes Made**:
- Separated DNS failures (non-recoverable) from transient network errors (recoverable)
- `UnknownHostException`, "Unable to resolve host", "No address associated" → non-recoverable
- `SocketTimeoutException`, "timeout", "connection refused", "connection reset" → recoverable
- Updated logic in Agent.kt (lines 282-302)

---

### H4. Tool Call Parsing Regex Brittleness
**Status**: DONE

**Changes Made**:
- Migrated to OpenAI Responses API with official tool calling (no regex needed)
- Created `LLMClient.chatWithTools()` using `ResponseCreateParams` and `FunctionTool`
- Turn.kt now uses structured `LLMToolCall` objects with `callId` directly from API
- Removed all regex-based parsing from Turn.kt
- Deleted `ChatMessage.kt` (no longer needed)

---

### H5. Completion Detection False Positives
**Status**: DONE

**Changes Made**:
- Created `CompleteTaskTool` for structured completion signaling
- Registered in `SessionServices.kt`
- Turn.kt checks for `complete_task` tool call to determine completion
- Fallback: `toolCalls.isEmpty() && textContent != null` (per Team Note: "exit when no tool call")
- Agent.kt extracts summary from `complete_task` tool arguments (lines 268-274)

**Note**: DEFAULT_SYSTEM_PROMPT still has old "DONE:" pattern - needs cleanup (see Remaining Work)

---

### H6. Tool Instructions Hardcoded (Not Dynamic)
**Status**: DONE

**Changes Made**:
- Created `ToolRegistry.generateResponsesApiTools()` to generate `FunctionTool` objects
- Turn.kt uses `toolRegistry.generateResponsesApiTools()` (line 59)
- Tool descriptions now come from each tool's `description` property
- Removed hardcoded tool instructions from Turn.kt's prompt
- OpenAI API automatically includes tool schemas in context

**Comparison with Old Hardcoded Prompts**:
| Tool | Old (Hardcoded) | New (Registry) |
|------|-----------------|----------------|
| click | "Click on a UI element" + example | "Click on a UI element identified by its index in the screen elements list." |
| type | "Type text into an editable field" + example | "Type text into an editable UI element (e.g., text field, search box)." |
| scroll | "Scroll the screen" + directions + example | "Scroll the screen in a specified direction (up, down, left, right)." |
| back | "Press the system back button" + example | "Press the system back button to go to the previous screen." |
| home | "Press the system home button" + example | "Press the system home button to go to the home screen." |
| wait | "Wait for UI to update" + example | "Wait for a specified duration in milliseconds (default: 1000ms, max: 30000ms)." |
| swipe | Coordinate-based swipe + example | "Swipe from one screen coordinate to another." |
| complete_task | N/A (new) | "Call this tool when you have successfully completed the user's goal." |

The new descriptions are more concise. The OpenAI Responses API automatically provides parameter schemas, making examples unnecessary.

---

## Medium Issues (Should Fix)

### M1. Tool Call ID Linkage Inconsistency
**Status**: DONE

**Changes Made**:
- Now using OpenAI's `call_id` from Responses API consistently
- `LLMToolCall.callId` comes directly from OpenAI
- `ToolCallRequest.id` is set to `call.callId`
- History records use the same ID throughout
- No separate ID generation layer (per Team Note recommendation)

---

### M2. Pause State Race Condition
**Status**: DONE

**Changes Made**:
- Added `lifecycleMutex: Mutex` to protect pause/resume operations (Agent.kt line 66)
- Changed `stopRequested` to `AtomicBoolean` (line 65)
- `pause()` and `resume()` use `lifecycleMutex.withLock` (lines 364-376)
- `stop()` uses atomic `set()` operation (lines 378-382)

---

### M3. Model Configuration Ignored
**Status**: DONE

**Changes Made**:
- `Turn.run()` now accepts `modelName` parameter (Turn.kt line 51)
- Agent passes `services.config.model` to Turn (Agent.kt line 164)
- Added `modelNameToChatModel()` to convert string to `ChatModel` enum (Turn.kt lines 86-100)
- `LLMClient.chatWithTools()` accepts and uses the `model` parameter

---

### M4. Ambiguous Completion Logic
**Status**: DONE

**Changes Made**:
- Completion now determined by:
  1. `complete_task` tool was called, OR
  2. No tool calls and there's text content (per Team Note: "exit when no tool call")
- If `complete_task` is called alongside other tools, still marks as complete
- Implemented in Turn.kt lines 228-232

---

## Low-Risk Suggestions (Nice to Have)

| Issue | Status | Notes |
|-------|--------|-------|
| Magic number for UI settle delay (500ms) | NOT DONE | Agent.kt:314 - Consider making configurable |
| Turn ID redundancy | NOT DONE | Low priority, no functional impact |
| System prompt as string literal | NOT DONE | Low priority, works correctly |
| Unused Observation.TextOutput | NOT DONE | Keep for future non-UI tools |
| AgentSource enum unused | NOT DONE | Keep for future multi-agent |
| Prompt construction optimization | NOT DONE | Low priority |
| Turn ID generation safety | NOT DONE | Low priority |

---

## Remaining Work

### R1. Fix DEFAULT_SYSTEM_PROMPT Inconsistency (HIGH PRIORITY)
**Status**: DONE

**Problem**: `Agent.kt` DEFAULT_SYSTEM_PROMPT had:
```
When the goal is achieved, respond with "DONE: [summary of what was accomplished]" without any tool calls.
```
This conflicted with Turn.kt's instruction to use `complete_task` tool.

**Fix Applied**: Simplified DEFAULT_SYSTEM_PROMPT to only contain the core agent description. Turn.kt's `buildSystemPrompt()` appends the tool usage guidelines including completion instructions via `complete_task` tool. Removed redundant guidelines that were duplicated between Agent.kt and Turn.kt.

---

## Summary

| Category | Total | Done | Remaining |
|----------|-------|------|-----------|
| High-Risk (H1-H6) | 6 | 6 | 0 |
| Medium (M1-M4) | 4 | 4 | 0 |
| Low-Risk | 7 | 0 | 7 (deferred) |
| Remaining Critical | 1 | 1 | 0 |

**Overall**: All high-risk, medium, and critical issues are now addressed. Low-risk suggestions remain deferred (nice-to-have).
