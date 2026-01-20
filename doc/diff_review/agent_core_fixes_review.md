# Diff Review: Agent Core Fixes (870d236 + 33602d9)

> **Reviewer**: Code review following `sop/diff_review.md`
> **Files Reviewed**: `Agent.kt`, `Turn.kt`, `LLMClient.kt`, `ToolRouter.kt`, `ToolRegistry.kt`, `CompleteTaskTool.kt`, and related files
> **Commits**: 870d236 (main fixes), 33602d9 (additional fixes)

---

## 1) Summary

These commits implement fixes for all high and medium priority issues from `doc/review/summary/agent_core_summary.md`:

1. **Migrates to OpenAI Responses API** - Eliminates brittle regex-based tool call parsing (H4)
2. **Adds `complete_task` tool** - Structured completion signaling instead of string pattern matching (H5)
3. **Fixes double screen capture** - Tool observation now propagates through ToolCallResult (H1)
4. **Fixes snapshot staleness in multi-tool execution** - Updates currentSnapshot after each tool (H2)
5. **Fixes network error recovery logic** - DNS failures non-recoverable, transient errors recoverable (H3)
6. **Dynamic tool schema generation** - Uses ToolRegistry.generateResponsesApiTools() (H6)
7. **Fixes tool call ID linkage** - ToolRouter accepts caller-provided callId (M1)
8. **Thread-safe pause/resume** - Uses Mutex and AtomicBoolean (M2)
9. **Uses session-configured model** - Passes model from config to LLM (M3)
10. **Clarifies completion logic** - complete_task OR text-only response marks completion (M4)

---

## 2) High-Risk Issues (Must Fix)

**None identified.** All original high-risk issues have been addressed correctly.

---

## 3) Medium Issues (Should Fix)

### M1. Documentation Out of Sync with Code

**Why it matters**: Outdated documentation causes confusion and maintenance burden.

**Location**: `doc/main/agent_infra.md`

**Issues**:
1. **Line 119**: Package structure still lists `ChatMessage.kt` which was deleted
2. **Lines 401-402**: Contains stale TODO note saying observation is not surfaced, but it IS now surfaced through `ToolCallResult.Success.observation`

**Fix**: Update the documentation to reflect current code state:

```markdown
# Line 119 - Remove ChatMessage.kt from package structure
├── data/                  # External services
│   ├── llm/
│   │   └── LLMClient.kt   # OpenAI Responses API wrapper
│   └── perception/

# Lines 401-402 - Update the note
Note: Tool observation is captured in `BaseTool` and propagated through 
`ToolCallResult.Success.observation`. The Agent uses this observation directly,
avoiding double screen capture.
```

---

### M2. complete_task Tool Missing from Built-in Tools Table

**Why it matters**: Documentation doesn't list the new tool, causing discoverability issues.

**Location**: `doc/main/agent_infra.md`, lines 367-377

**Fix**: Add to the Built-in Tools table:

```markdown
| `complete_task` | Signal task completion | `summary: string` |
```

---

### M3. Edge Case: complete_task in Middle of Multi-Tool Response

**Why it matters**: If LLM calls `complete_task` followed by other tools in the same response, subsequent tools will execute with stale snapshot (since `complete_task` doesn't capture screen).

**Location**: `Agent.kt` lines 241-249 (special handling for complete_task)

**Current behavior**: All tools execute in order, but `complete_task` skips screen capture. If it's not the last tool, subsequent tools use potentially stale snapshot.

**Assessment**: This is an unlikely edge case since:
1. LLM is instructed to call `complete_task` when the goal is achieved (implying no further actions)
2. The completion check happens after ALL tools execute

**Recommendation**: Accept current behavior. Add a defensive log or comment noting this edge case. If it becomes a real issue, consider either:
- Enforcing `complete_task` must be the only tool call
- Having `complete_task` return early from the tool loop

---

## 4) Low-Risk Suggestions (Nice to Have)

| Issue | Location | Suggestion |
|-------|----------|------------|
| Unused methods in ToolRegistry | `ToolRegistry.kt:105-127` | `generateFunctionSchemas()` and `generateToolsParam()` are unused after migration to Responses API. Consider removing or marking as deprecated. |
| Store responseId for future use | `LLMClient.kt:162` | `ResponsesResult.responseId` is captured but not used. Can be used for `previous_response_id` to reduce prompt size in future. |
| Missing model mappings | `Turn.kt:86-100` | Consider adding `gpt-4.5-turbo`, `o3`, `o3-mini` when available |

---

## 5) Verification Checklist

### Original Issues - All Fixed

| Issue | Status | Verification |
|-------|--------|--------------|
| H1. Double Screen Capture | ✅ Fixed | `Agent.kt:226-238` uses observation from tool result |
| H2. Snapshot Staleness | ✅ Fixed | `Agent.kt:176-177, 251-255` updates currentSnapshot per tool |
| H3. Network Error Recovery | ✅ Fixed | `Agent.kt:297-317` correct recovery logic |
| H4. Tool Call Parsing | ✅ Fixed | `Turn.kt` uses Responses API, no regex |
| H5. Completion Detection | ✅ Fixed | `complete_task` tool + text-only completion |
| H6. Tool Instructions Hardcoded | ✅ Fixed | `Turn.kt:59` uses `toolRegistry.generateResponsesApiTools()` |
| M1. Tool Call ID Linkage | ✅ Fixed | `ToolRouter.kt:62, 66` accepts caller callId |
| M2. Pause State Race | ✅ Fixed | `Agent.kt:18-20, 57-58` uses Mutex + AtomicBoolean |
| M3. Model Config Ignored | ✅ Fixed | `Turn.kt:51, 65-66` passes model from config |
| M4. Ambiguous Completion | ✅ Fixed | `Turn.kt:228-232` clear completion logic |

### Code Quality

- [x] No obvious memory leaks
- [x] Thread safety addressed for lifecycle operations
- [x] Error handling improved with categorized network errors
- [x] Logging is adequate for debugging
- [x] No hardcoded secrets or credentials

### New Code Review

- [x] `CompleteTaskTool.kt` - Clean implementation, follows BaseTool pattern appropriately
- [x] `LLMClient.chatWithTools()` - Proper Responses API integration
- [x] `ToolRegistry.generateResponsesApiTools()` - Correct JSON conversion

---

## 6) Action Items

1. **[Should Fix]** Update `doc/main/agent_infra.md` to remove `ChatMessage.kt` reference and update tool observation note
2. **[Should Fix]** Add `complete_task` to the Built-in Tools table in documentation
3. **[Nice to Have]** Consider cleaning up unused ToolRegistry methods

---

## Conclusion

The fixes comprehensively address all high and medium priority issues from the original code review. The migration to OpenAI Responses API is well-executed, eliminating the brittle regex parsing. The thread-safety improvements are appropriate. The main remaining work is documentation cleanup.

**Verdict**: Ready for merge after documentation updates.
