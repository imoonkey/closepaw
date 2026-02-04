# Review: Phase 1 – Agent Hierarchy Metadata

## Summary

Implementing Phase 1 of `final_design_codex.md`: adds 4 new fields to `AgentConfig` (`agentId`, `agentRole`, `parentSessionId`, `delegationCallId`), writes them to trace `session_started` events, and exposes `callId` through `ToolExecutionContext` for delegation tracking.

**Files**: 6 main, 2 test, 2 tooling, 2 docs/scripts

---

## Critical

None found.

---

## High

1. **Hardcoded EXECUTOR role in SubAgentRunner**
   - Where: [SubAgentRunner.kt, Line 63]
   - Why: All delegated sub-agents are `EXECUTOR`. Prevents nested planning patterns.
   - Fix: Consider adding `role` to `AgentDefinition` for flexibility:
     ```kotlin
     agentRole = definition.executionRole ?: AgentExecutionRole.EXECUTOR
     ```

2. **callId interface default is null but always overridden**
   - Where: [ToolSpec.kt, Line 109] / [ToolRouter.kt, Line 229]
   - Why: Interface declares `val callId: String? get() = null`, but `ToolRouter` always provides non-null. Inconsistent contract.
   - Fix: Either make interface non-nullable, or document when `null` is valid.

---

## Medium

1. **String-based JSON assertions in tests**
   - Where: [AgentTraceObservabilityTest.kt, Lines 64-67]
   - Why: Uses `contains("\"agent_role\":\"standalone\"")` – fragile if formatting changes.
   - Fix: Parse JSON and assert on structured data:
     ```kotlin
     val dataObj = Json.parseToJsonElement(sessionStarted.data.toString()).jsonObject
     assertThat(dataObj["agent_role"]?.jsonPrimitive?.content).isEqualTo("standalone")
     ```

2. **Missing KDoc on AgentExecutionRole values**
   - Where: [AgentConfig.kt, Lines 10-14]
   - Why: Enum values lack documentation; future maintainers may misunderstand semantics.
   - Fix: Add inline KDoc for each value.

3. **replay_compiler.py referenced but not yet implemented**
   - Where: [debug-run.sh, Line 234]
   - Why: Script will silently fail until Phase 2 is complete.
   - Fix: Acceptable for now; consider adding a warning log if file missing.

---

## Low

1. **Documentation gap: agentId == sessionId assumption**
   - Where: [AgentConfig.kt, Line 56]
   - Why: Default `val agentId: String = sessionId.value` ties identity to session. Not a bug, but undocumented assumption.
   - Fix: Add a brief comment explaining this design choice.

---

## Android-Specific Checks

- [x] Coroutines scoped correctly? (N/A – no new coroutine code)
- [x] No Context leaks? (N/A – no Context references added)
- [x] Main thread safe? (Yes – trace writes are already background-safe)
- [x] Permissions checked? (N/A)
- [x] A11y service best practices? (N/A – no a11y changes)

---

## Recommendation

**APPROVE** – Clean implementation matching design. Address High/Medium items before or after merge as appropriate.
