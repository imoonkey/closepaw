# Review: Phase 0 Tools Implementation

> **Commit**: `faf2bba fix: harden planning state tools`  
> **Reviewer**: Codex  
> **Date**: 2026-02-04

---

## Summary

This commit implements Phase 0 of the multi-agent infrastructure: foundation tools for planning state.

**Files Changed**: 20+ files (tools, state, prompt, policy, tests)

**Components Added**:
- `WriteTodosTool` — Full-list replacement API for task tracking
- `ScratchpadTool` — Key-value store with read/write/delete/list actions
- `TodoState`, `ScratchpadState` — State holders
- `Todo`, `TodoStatus` — Protocol data classes
- `AgentSessionState` — Container for planning state
- Events: `TodosUpdated`, `ScratchpadUpdated`
- `ToolName` entries, policy risk levels, UI icons
- Unit tests for state classes and tools

**Design Compliance**: Implementation closely follows `phase0_tools_claude.md`:
- ✅ Full-list replacement for todos (Gemini-style)
- ✅ Max 1 IN_PROGRESS validation
- ✅ Scratchpad with 4 actions
- ✅ System prompt injection
- ✅ Events for UI updates

---

## Critical

None found.

---

## High

None found.

---

## Medium

### 1. Thread Safety in State Classes

**Where**: `TodoState.kt`, `ScratchpadState.kt`

**Issue**: Both classes use mutable collections (`MutableList`, `MutableMap`) without synchronization.

```kotlin
// TodoState.kt
class TodoState(
    private val todos: MutableList<Todo> = mutableListOf()  // Not thread-safe
)

// ScratchpadState.kt
class ScratchpadState(
    private val data: MutableMap<String, String> = mutableMapOf()  // Not thread-safe
)
```

**Status**: ✅ Fixed — Added explicit locking for reads/writes in both state classes.

---

### 2. Missing `clear()` on TodoState

**Where**: `TodoState.kt`

**Issue**: `ScratchpadState` has `clear()` but `TodoState` doesn't. Session reset would need to call `update(emptyList())` instead of `clear()`.

**Status**: ✅ Fixed — `TodoState.clear()` added and tested.

---

### 3. No Size Limits on Scratchpad Values

**Where**: `ScratchpadTool.kt`, `ScratchpadState.kt`

**Issue**: Arbitrarily large strings can be stored, potentially bloating the system prompt.

**Risk**: If LLM stores long extracted content (e.g., full webpage text), the prompt context could exceed token limits or degrade performance.

**Status**: ✅ Fixed — Limits added:
- Max entries: 20
- Max key length: 100 chars
- Max value length: 2048 chars
- Tool description + validation updated

---

### 4. Events Emitted Outside Tools

**Where**: `AgentTurnRunner.emitPlanningEvents()`

**Issue**: Events are emitted after tool execution in the turn runner, not by the tools themselves. This works but creates coupling — tools don't know about events.

```kotlin
private suspend fun emitPlanningEvents(toolCall: ToolCallRequest, toolResult: ToolCallResult) {
    if (toolResult !is ToolCallResult.Success) return
    when (ToolName.from(toolCall.name)) {
        ToolName.WriteTodos -> eventDispatcher.todosUpdated(...)
        // ...
    }
}
```

**Impact**: Low — design choice, not a bug. Events fire correctly.

**Note**: If tools ever need to emit events themselves (e.g., progress), this pattern would need refactoring.

---

### 5. Test Coverage Gaps

**Where**: Test files

**Current Coverage**: Basic happy paths and validation errors.

**Fixed**:
- Empty todo descriptions validation
- Very long scratchpad values validation
- `toPromptContext()` output format tests for todos/scratchpad

**Remaining**:
- Special characters / unicode in keys/values
- Full round-trip with prompt builder

**Recommendation**: Expand tests in follow-up, especially for prompt integration.

---

## Low

### 1. Duplicate Icon for WriteTodos

**Where**: `ToolUi.kt`

```kotlin
ToolName.WriteTodos -> ToolDisplay(tool.displayName, Icons.Rounded.CheckCircle)  // Same as CompleteTask
ToolName.CompleteTask -> ToolDisplay(tool.displayName, Icons.Rounded.CheckCircle)
```

**Status**: ✅ Fixed — switched to `Icons.AutoMirrored.Rounded.FormatListBulleted`.

---

### 2. Missing KDoc

**Where**: `TodoModels.kt`, `TodoState.kt`, `ScratchpadState.kt`

**Status**: ✅ Fixed — KDoc added for state classes (protocol already documented).

---

### 3. Status Case Inconsistency

**Where**: `TodoState.toPromptContext()`

```kotlin
val status = todo.status.name.lowercase()  // Outputs "in_progress"
"${index + 1}. [$status] ${todo.description}"
```

**Status**: ✅ Fixed — `toPromptContext()` now outputs uppercase status.

---

## Android-Specific Checks

| Check | Status | Notes |
|-------|--------|-------|
| Coroutines scoped correctly? | ✅ | Tools use suspend functions, no scope leaks |
| No Context leaks? | ✅ | No Android Context references |
| Main thread safe? | ✅ | State updates are simple in-memory ops |
| Permissions checked? | N/A | No permissions needed |
| A11y service best practices? | N/A | Agent-internal tools, not screen interaction |

---

## Positive Notes

1. **Clean architecture** — Clear separation: protocol → state → tool → integration
2. **Good validation** — Error messages are specific and actionable
3. **Follows design** — Full-list replacement as specified, not operations-based
4. **Appropriate risk levels** — Both tools marked LOW risk (no side effects)
5. **Tests exist** — Foundation for TDD on future changes
6. **Prompt integration done** — State flows into system prompt correctly
7. **Events wired** — UI can react to state changes

---

## Verification

```bash
./gradlew clean assembleDebug lint test
# BUILD SUCCESSFUL
# All tests pass
```

Manual check (tool usage in logs):

```bash
./scripts/debug-run.sh "请完成以下任务并使用规划工具：1) 先用 write_todos 写一个 3 步计划（其中 1 个 in_progress）。2) 在 scratchpad 写入 key=target_screen, value=Settings。3) 打开系统设置后，更新 todos：把已完成步骤标记 completed，把当前步骤设置为 in_progress。4) 最后从 scratchpad 读取 target_screen 并确认是否在该页面。"
rg -n "write_todos|scratchpad" debug-output/run_YYYYMMDD_HHMMSS/agent.log
```

---

## Recommendation

**APPROVE** — Implementation matches design, with key risks addressed.

### Suggested Follow-ups

1. Add prompt-builder round-trip tests
2. Add unicode/special-char tests for scratchpad keys/values
3. Consider whether event emission should move into tools (if UI needs richer progress)
