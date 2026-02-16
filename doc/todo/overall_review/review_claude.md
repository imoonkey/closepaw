# Codebase Review: Android Agent

**Date**: 2026-02-16
**Scope**: `app/src/main/kotlin/` — 166 Kotlin files, ~25,836 lines
**Focus**: Code quality, KISS, DRY, scalability, modernness

---

## Executive Summary

The codebase is well-structured with clean package boundaries and good use of Kotlin idioms (sealed classes, coroutines, data classes). The ReAct agent loop (`Agent → AgentTurnRunner → Turn`) is sound. However, organic growth has introduced duplication, god objects, and files that exceed the project's 400-line max rule. This review identifies 10 major refactors and ~25 smaller cleanups.

**Severity legend**: `P0` = bug/race condition, `P1` = structural/architectural, `P2` = duplication/DRY, `P3` = minor cleanup

---

## 1. Files Violating 400-Line Max

The project convention states max 400 lines/file. 11 files exceed this:

| File | Lines | Over by |
|------|-------|---------|
| `agent/AgentTurnRunner.kt` | 788 | +388 |
| `platform/virtualdisplay/VirtualDisplayPlatform.kt` | 645 | +245 |
| `app/AgentService.kt` | 572 | +172 |
| `platform/virtualdisplay/ShizukuClient.kt` | 544 | +144 |
| `app/MainActivity.kt` | 536 | +136 |
| `trace/AgentTrace.kt` | 507 | +107 |
| `ui/chat/ChatViewModel.kt` | 449 | +49 |
| `session/AgentSession.kt` | 443 | +43 |
| `ui/settings/SettingsDropdowns.kt` | 413 | +13 |
| `history/SessionRecordingService.kt` | 410 | +10 |
| `history/HistoryManager.kt` | 402 | +2 |

**Design docs**: [Large File Splits](./design/08_large_file_splits_claude.md)

---

## 2. Major Refactor Opportunities (P1-P2)

Each has a dedicated design doc in `./design/`.

### 2.1 LLM Client Consolidation — P2
`OpenAIResponseClient` (326 lines) and `ChatCompletionClient` (303 lines) duplicate ~200 lines of identical retry/backoff/streaming logic. Extract a shared `CloudLLMClient` base.

**Design doc**: [01_llm_client_consolidation.md](./design/01_llm_client_consolidation_claude.md)

### 2.2 SessionServices Decomposition — P1
`SessionServices` (343 lines) is a god object holding 12 services. Split into domain-specific groups (LLM, Tools, Platform) to improve testability and reduce coupling.

**Design doc**: [02_session_services_decomposition.md](./design/02_session_services_decomposition_claude.md)

### 2.3 AgentEvent Domain Split — P2
`AgentEvent.kt` (360 lines) is a monolithic sealed interface with 20+ event types. Group into domain sub-interfaces for readability and compilation.

**Design doc**: [03_agent_event_domain_split.md](./design/03_agent_event_domain_split_claude.md)

### 2.4 SessionConfig Restructuring — P2
`SessionConfig` (64 lines of fields, 5 deprecated) has 17 top-level fields. Nest into domain sub-configs, remove deprecated fields.

**Design doc**: [04_session_config_restructuring.md](./design/04_session_config_restructuring_claude.md)

### 2.5 System Prompt Composition — P2
`PlannerAgentDef`, `StandaloneAgentDef`, `ExecutorAgentDef` duplicate ~60% of their system prompt text. Extract a composable prompt module system.

**Design doc**: [05_system_prompt_composition.md](./design/05_system_prompt_composition_claude.md)

### 2.6 Tool System DRY-up — P2
10+ tool implementations repeat the same `buildDescription()` boilerplate and observation construction patterns. Extract shared helpers.

**Design doc**: [06_tool_system_dryup.md](./design/06_tool_system_dryup_claude.md)

### 2.7 Platform Abstraction Cleanup — P1
`VirtualDisplayPlatform` (645 lines) has scope creep with viewer touch forwarding and live preview. `AccessibilityNodeFinder` has 95% duplicate `findClickable`/`findLongClickable`. Node action performers are similarly duplicated.

**Design doc**: [07_platform_abstraction_cleanup.md](./design/07_platform_abstraction_cleanup_claude.md)

### 2.8 Large File Splits — P1
8 files need splitting to comply with the 400-line rule. Strategy: extract helper classes, formatters, and sub-components.

**Design doc**: [08_large_file_splits.md](./design/08_large_file_splits_claude.md)

### 2.9 AgentService Lifecycle Fix — P0
**Critical bug**: `onDestroy()` cancels the coroutine scope _before_ the `Op.Shutdown` it submits can execute. The shutdown op is lost to scope cancellation.

**Design doc**: [09_agent_service_lifecycle_fix.md](./design/09_agent_service_lifecycle_fix_claude.md)

### 2.10 Settings UI Generics — P2
`SettingsDropdowns.kt` (413 lines) contains 5 nearly identical dropdown composables. Extract `GenericDropdown<T>`.

**Design doc**: [10_settings_ui_generics.md](./design/10_settings_ui_generics_claude.md)

---

## 3. Per-Package Findings

### 3.1 `agent/` (8 files, ~1,600 lines)

**Strengths**:
- Clean ReAct loop: `Agent` → `AgentTurnRunner` → `Turn`
- `TurnOutcome` / `AgentStopReason` sealed classes are well-designed
- Pause/resume lifecycle in `Agent.kt` is correct

**Issues**:
- `AgentTurnRunner.kt:788` — largest file, does perception + planning + execution + error classification + observation capture. Needs splitting.
- `AgentExecutionConfig.kt:8-9` — dual doc comment (minor)
- `ActionDescriptionFormatter.kt` — `formatMobileAction` has complex nested `when` that could be a lookup map

### 3.2 `agent/cognition/` (5 files, ~623 lines)

**Strengths**:
- `NavigationState` screen signature with Jaccard similarity is elegant
- `LoopDetectionPolicy` with 3 heuristics is clean
- `TurnToolPolicy` arbitration logic is sound

**Issues**:
- `PromptBuilder.kt:267` — approaching limit. History compression logic could be extracted.
- `ExecutorStepPolicy.kt:91` — `narrativeSummaryOnLimit` constructor param is never actually used in the policy output

### 3.3 `agent/definition/` + `agent/subagent/` (6 files, ~574 lines)

**Strengths**:
- `AgentDef` abstract base contract is minimal and correct
- `SubAgentRunner` isolation with shared scratchpad is well-designed

**Issues**:
- `StandaloneAgentDef.kt` — contains commented-out dead code
- `ExecutorAgentDef.kt` — over-specified prompt with redundant re-statements
- `SubAgentRunner.kt:bridgeEvent()` — silently drops unknown events without logging. Should at minimum log at debug level.
- System prompt duplication across all 3 `AgentDef` implementations (~60% overlap)

### 3.4 `session/` (7 files, ~1,106 lines)

**Strengths**:
- `UserResponseChannel` — excellent coroutine suspension bridge pattern
- `AgentSessionState` — clean minimal container

**Issues**:
- `AgentSession.kt:443` — violates 400-line max. Mixes operation dispatch, state management, event emission, and lifecycle.
- `SessionServices.kt:343` — god object, 12 fields, long `create()` factory. See design doc.
- `SessionAgentRunner.kt` — 3 nullable mutable vars (`agent`, `agentJob`, `isToolRegistrationComplete`) that should be an atomic state machine
- `TodoState.kt` / `ScratchpadState.kt` — both use manual `synchronized {}` blocks; could unify into a generic `SyncMap<K,V>` or use `Mutex`

### 3.5 `protocol/` (4 files, ~700 lines)

**Strengths**:
- `Op` sealed interface is clean and well-documented
- `ApprovalDetails` / `ApprovalDecision` types are solid

**Issues**:
- `AgentEvent.kt:360` — monolithic, see design doc
- `SessionConfig` — 17 fields, 5 deprecated, needs restructuring
- `Op.kt` has mixed concerns: `Op` + `SessionConfig` + 5 enums in one file

### 3.6 `perception/` (3 files, ~500 lines)

**Strengths**:
- `Perceptor.traverse()` handles the a11y tree well
- JSON output format is compact and efficient

**Issues**:
- `Perceptor.kt:365` — approaching the limit. `traverse()` is over-parameterized (6+ params). Extract a `TraversalConfig` data class.
- Magic numbers scattered: pixel sizes for "too small" elements, Jaccard threshold, etc. Should be named constants.
- `PerceptionElement` has 13 fields — could collapse boolean flags into a `Set<ElementFlag>` or bitfield

### 3.7 `tool/` (29 files, ~3,718 lines)

**Strengths**:
- `ToolRouter` state machine (IDLE → EXECUTING → WAITING_APPROVAL) is correct
- `PolicyEngine` risk-based classification is sensible
- `ToolSpec` interface + `ToolRegistry` is extensible

**Issues**:
- 10+ files duplicate `buildDescription()` pattern (5-15 lines each)
- `OpenAppTool.kt:278` — multi-strategy app resolution has fallback chain that's hard to test. Extract strategies.
- Observation construction (wrapping `ToolObservation.ScreenState` or `TextOutput`) is repeated in multiple tools
- `UIActionInvocation.kt` and `UiChangeDetector.kt` both implement scroll boundary detection independently

### 3.8 `platform/` (16 files, ~3,130 lines)

**Strengths**:
- `AndroidPlatform` interface is clean
- `AccessibilityPlatform` correctly bridges A11y service capabilities

**Issues**:
- `VirtualDisplayPlatform.kt:645` — scope creep with viewer touch forwarding, live preview switching, display config management
- `ShizukuClient.kt:544` — heavy reflection, display creation callback duplicated 3x
- `AccessibilityNodeFinder.kt` — `findClickable()` and `findLongClickable()` are 95% identical. Parameterize.
- `NodeActionPerformer.kt` — `performNodeClickAt()` and `performNodeLongClickAt()` nearly identical. Parameterize.

### 3.9 `llm/` (11 files, ~2,206 lines)

**Strengths**:
- `LLMClient` abstract base class is clean
- `OpenAIErrorClassifier` error taxonomy is thorough
- `ModelCatalog` JSON-driven model registry is flexible

**Issues**:
- `OpenAIResponseClient` + `ChatCompletionClient` — **~200 lines of duplicated retry/backoff/streaming boilerplate**. Most impactful DRY opportunity in the codebase.
- `LeapFunctionInterop.kt:308` — excessive defensive JSON parsing with 4 nested `try/catch` blocks
- `ChatCompletionInterop.kt:275` — deeply nested `convertInputItems()` function. Flatten with `when` + early returns.
- `LFMLLMClient.kt:325` — mixes model lifecycle management with chat execution. Extract `ModelManager`.

### 3.10 `history/` + `trace/` (20 files, ~2,608 lines)

**Strengths**:
- `HistoryManager` token budgeting and auto-compression is well-designed
- `SessionStorage` with JSONL format is robust
- `ResponseItem` sealed class hierarchy is clean

**Issues**:
- `AgentTrace.kt:507` — violates 400-line max. Heavy artifact generation mixed with trace event recording.
- `SessionRecordingService.kt:410` — violates 400-line max. Mixes recording lifecycle with event serialization.
- `HistoryManager.kt:402` — borderline. History compression could be extracted.
- `TraceRecorder` creates files synchronously in some paths — should be fully async

### 3.11 `ui/` (46 files, ~5,000+ lines)

**Strengths**:
- `CapsuleMode` state machine is well-defined with clear transitions
- `OverlayLocationPolicy` — excellent pure function pattern
- `ServiceLifecycleOwner` correctly bridges Service → Compose lifecycle
- Theme and color system is consistent

**Issues**:
- `ChatViewModel.kt:449` — violates 400-line max. `EventReducer` inline class should be extracted into its own file.
- `SettingsDropdowns.kt:413` — 5x duplicate dropdown pattern. See design doc.
- `CapsuleOverlayHost.kt:286` — 9 nullable lambda callbacks ("callback hell"). Use a single `CapsuleCallbacks` interface.
- `SmartCapsuleSurface` composable — 11+ parameters. Extract a `CapsuleUiState` data class.
- `NavigationDrawer.kt:356` — borderline. Has embedded session history list that could be its own composable.
- `SettingsSheet.kt:361` — borderline. Could extract `PerceptionSettingsSection`.

### 3.12 `app/` (7 files, ~2,115 lines)

**Strengths**:
- `ServiceOverlayController.kt:353` — good single-authority overlay visibility pattern
- `OverlayLocationPolicy.kt:148` — pure functions, highly testable
- RPC op system (`Op` sealed class) is clean

**Issues**:
- **`AgentService.kt:572` — P0 `onDestroy()` race condition**. `scope.cancel()` runs before `Op.Shutdown` is processed. See design doc.
- `AgentService.kt` — event collector coroutine has no error boundary. A thrown exception kills the collector silently.
- `MainActivity.kt:536` — unscoped `sessionScope`, nullable service binding via `ServiceConnection`, complex permission dance
- `AgentService.instance` static var — classic Android anti-pattern but acceptable for accessibility service

---

## 4. Cross-Cutting Concerns

### 4.1 Coroutine Safety
- `AgentService.onDestroy()` scope cancellation race — P0 (see above)
- `SessionAgentRunner` 3 nullable mutable vars should be atomic state
- `TodoState` / `ScratchpadState` use `synchronized {}` instead of `Mutex` (mixing blocking + coroutines)

### 4.2 Error Handling
- `SubAgentRunner.bridgeEvent()` silently swallows unknown events
- `AgentService` event collector has no `try/catch` — collector crash is silent
- `handleTurnFailure` in `AgentTurnRunner` uses cause-chain walking which is correct but brittle

### 4.3 Testability
- `SessionServices` god object makes mocking painful — must provide 12 dependencies
- `VirtualDisplayPlatform` tightly couples display management + viewer + touch forwarding
- Tool `buildDescription()` duplication means description format diverges across tools

### 4.4 Deprecated Code
- `SessionConfig.model` — deprecated, replaced by `mainModel` but still referenced
- `SessionConfig.llmBackend` — deprecated, but is the only way to select backend
- `SessionConfig.localLLMConfig` — deprecated
- `StandaloneAgentDef` — has commented-out code blocks
- No migration path documented for any deprecated field

---

## 5. Quick Wins (P3 — Immediate Cleanups)

These can be done without design docs:

1. **Remove commented-out code** in `StandaloneAgentDef.kt`
2. **Fix dual doc comment** in `AgentExecutionConfig.kt:8-9`
3. **Add debug logging** to `SubAgentRunner.bridgeEvent()` for unknown events
4. **Extract named constants** for magic numbers in `Perceptor.kt` (pixel thresholds, Jaccard threshold)
5. **Unify lock pattern** — `TodoState` and `ScratchpadState` use identical manual `synchronized` blocks; extract a shared `SynchronizedMap` utility or switch to `Mutex`
6. **Remove unused param** — `ExecutorStepPolicy.narrativeSummaryOnLimit` is set but never read
7. **Simplify `ActionDescriptionFormatter.formatMobileAction()`** — replace nested `when` with a lookup map
8. **Add error boundary** to `AgentService` event collector coroutine: wrap in `try/catch` with `Log.e`
9. **Delete `SessionServicesBuilder`** (lines 318-343 of `SessionServices.kt`) — it adds 25 lines for zero value over `create()` + manual register/unregister
10. **Collapse `Op.kt`** — move `SessionConfig` and enums to their own files to keep Op.kt focused

---

## 6. Recommended Priority Order

| Priority | Refactor | Reason |
|----------|---------|--------|
| 1 | AgentService Lifecycle Fix (P0) | Critical race condition — data loss / crash |
| 2 | LLM Client Consolidation (P2) | Biggest DRY win, ~200 duplicated lines |
| 3 | Large File Splits (P1) | Enforces project convention, improves readability |
| 4 | SessionServices Decomposition (P1) | Enables testing, reduces coupling |
| 5 | Tool System DRY-up (P2) | Most files affected, prevents drift |
| 6 | Platform Abstraction Cleanup (P1) | VDP at 645 lines, clear extraction targets |
| 7 | Settings UI Generics (P2) | Quick win, eliminates 5x duplication |
| 8 | System Prompt Composition (P2) | Prevents prompt drift across agent modes |
| 9 | SessionConfig Restructuring (P2) | Cleans up deprecated fields |
| 10 | AgentEvent Domain Split (P2) | Better organization, no functional change |

Quick wins (section 5) should be sprinkled in opportunistically as you touch each file.

---

## 7. Stats

- **Total files**: 166 Kotlin files
- **Total lines**: 25,836
- **Files over 400 lines**: 11 (6.6%)
- **Major refactors identified**: 10
- **Quick wins identified**: 10
- **Critical bugs found**: 1 (AgentService.onDestroy race)
