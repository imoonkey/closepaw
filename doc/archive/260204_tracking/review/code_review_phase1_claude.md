# Code Review: Phase 1 – Agent Hierarchy Metadata

> Date: 2026-02-04  
> Reviewer: Claude  
> Design Reference: `final_design_codex.md`  
> Scope: Uncommitted changes implementing Phase 1 (P0) – parent/role/delegation linking

---

## Summary

Phase 1 adds 4 new fields to `AgentConfig` and writes them to `session_started` trace events, enabling parent-child agent linking for replay/visualization. The changes are **minimal and surgical**, matching the design intent.

**Files Changed**: 12 (6 main, 2 test, 2 tooling, 2 docs/scripts)

---

## 1. Style and Formatting

| Location | Icon | Issue |
|----------|------|-------|
| [ToolRouter.kt, Line 363 (removed)] | 🔧 | Trailing blank line removed – good cleanup. |
| [AgentConfig.kt, Lines 50-68] | ✅ | Doc comments on all new fields – excellent. |

**No issues found.**

---

## 2. Clarity and Readability

### 2.1 AgentExecutionRole Placement

[AgentConfig.kt, Lines 10-14] 🔍

```kotlin
enum class AgentExecutionRole {
    PLANNER,
    EXECUTOR,
    STANDALONE
}
```

**Observation**: Enum is declared at file top, above the data class. This is fine for now, but if it grows (e.g., `CRITIC`, `TOOL_AGENT`), consider moving to its own file.

**Suggestion**: Add KDoc describing each role's semantics for future maintainers.

---

### 2.2 Default agentId Uses sessionId

[AgentConfig.kt, Line 56]

```kotlin
val agentId: String = sessionId.value
```

**Observation**: Good default – avoids null checks. However, this ties identity to session. If sessions are ever reused or recycled, this could cause trace collisions.

**Rating**: Acceptable for Phase 1. Document assumption in design doc.

---

### 2.3 Hardcoded Role in SubAgentRunner

[SubAgentRunner.kt, Line 63]

```kotlin
agentRole = AgentExecutionRole.EXECUTOR
```

**Question**: All delegated sub-agents are hardcoded as `EXECUTOR`. Is this intentional? What if a sub-agent acts as a nested `PLANNER`?

**Suggestion**: Consider accepting `role` as parameter in `AgentDefinition` or `SubAgentRequest` for future flexibility.

---

## 3. Security and Common Bug Patterns

### 3.1 Null Safety ✅

[AgentTrace.kt, Lines 40-43]

```kotlin
config.parentSessionId?.let { put("parent_session_id", JsonPrimitive(it.value)) }
config.delegationCallId?.let { put("delegation_call_id", JsonPrimitive(it)) }
```

**Good**: Correct use of `?.let` – optional fields only written when present.

---

### 3.2 callId Flow Through ToolExecutionContext

[ToolSpec.kt, Line 109]

```kotlin
val callId: String? get() = null
```

[ToolRouter.kt, Line 229]

```kotlin
override val callId: String = resolvedCallId
```

**Analysis**: Default getter returns `null`, but `ToolRouter` overrides with non-null. This is safe but could be clearer.

**Suggestion**: Consider making interface property type `String` (non-nullable) and requiring implementations to provide it, or document why `null` is valid.

---

### 3.3 DelegateTaskTool Mutation Pattern

[DelegateTaskTool.kt, Lines 160-161]

```kotlin
val requestWithCallId = request.copy(delegationCallId = context.callId)
```

**Good**: Uses immutable `copy()` pattern instead of mutation.

---

## 4. Test Coverage

### 4.1 AgentTraceObservabilityTest

[AgentTraceObservabilityTest.kt, Lines 64-67]

```kotlin
assertThat(sessionStarted!!.data.toString()).contains("\"agent_role\":\"standalone\"")
assertThat(sessionStarted.data.toString()).contains("\"agent_id\":\"session-1\"")
```

**Issue** 🔎: Uses string `contains()` on JSON. Fragile if field order changes or extra whitespace appears.

**Suggestion**: Parse JSON and assert on structured data:

```kotlin
val dataObj = Json.parseToJsonElement(sessionStarted.data.toString()).jsonObject
assertThat(dataObj["agent_role"]?.jsonPrimitive?.content).isEqualTo("standalone")
```

---

### 4.2 DelegateTaskToolTest

[DelegateTaskToolTest.kt, Line 82]

```kotlin
assertThat(capturedRequests.single().delegationCallId).isEqualTo("call-123")
```

**Good**: Properly verifies `callId` flows through the delegation chain.

---

## 5. Documentation & Tooling

### 5.1 debug-run.sh Integration

[debug-run.sh, Lines 233-236]

```bash
python3 "$PROJECT_ROOT/inspection_tool/replay_compiler.py" "$LOCAL_TRACE_DIR" \
    > "$DEBUG_DIR/replay_compile.log" 2>&1 || true
```

**Good**: Fails gracefully with `|| true`. Logs to file for debugging.

**Note**: `replay_compiler.py` doesn't exist yet (Phase 2). Script will silently fail until implemented.

---

### 5.2 README.md Updates

[inspection_tool/README.md, Lines 109-125]

**Good**: Documents the new `replay_v2` workflow clearly.

---

## 6. Alignment with Design

| Design Requirement | Status | Notes |
|-------------------|--------|-------|
| AgentConfig: `agentId`, `agentRole`, `parentSessionId`, `delegationCallId` | ✅ | All 4 fields added |
| session_started writes new fields | ✅ | Conditional writes for optional fields |
| Minimal Android changes | ✅ | No structural refactoring |
| callId in ToolExecutionContext | 🆕 | Bonus: enables tracing delegation origin |

---

## 7. Recommendations Summary

| Priority | Item | Effort |
|----------|------|--------|
| **P1** | Add KDoc to `AgentExecutionRole` values | 5 min |
| **P1** | Parse JSON in test assertions instead of string contains | 15 min |
| **P2** | Consider parameterizing role in `AgentDefinition` | Design discussion |
| **P2** | Document `agentId == sessionId` assumption | 5 min |

---

## 8. Verdict

**✅ LGTM with minor suggestions**

The implementation correctly delivers Phase 1 of the design. Code is clean, well-documented, and follows Kotlin best practices. The test additions validate the happy path. Ready for commit after addressing P1 items.
