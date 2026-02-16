# Design: Large File Splits

**Priority**: P1 — Convention enforcement
**Rule**: Max 400 lines per file (project convention in CLAUDE.md)

---

## Files Requiring Splits

### 1. AgentTurnRunner.kt (788 lines → ~3 files)

**Current responsibilities**:
- Turn orchestration (perception → planning → execution)
- Screen capture + observation recording
- LLM model resolution
- Tool execution loop with observation capture
- Error classification with cause-chain walking
- Warning building (loop + step budget)
- Arbitration decision building + event emission

**Split plan**:
```
agent/AgentTurnRunner.kt       (~300 lines) — orchestration: executeTurn(), prepareTurn(), decideTurnOutcome()
agent/TurnPlanningPhase.kt     (~200 lines) — runPlanningPhase(), resolveTurnModel(), buildWarnings()
agent/TurnExecutionPhase.kt    (~200 lines) — executeActions(), executeSingleToolCall(), resolveObservation()
agent/TurnErrorClassifier.kt   (~80 lines)  — handleTurnFailure() → TurnErrorClassifier.classify()
```

### 2. VirtualDisplayPlatform.kt (645 lines → 2 files)

Split into platform + viewer. See [07_platform_abstraction_cleanup.md](./07_platform_abstraction_cleanup.md).

### 3. AgentService.kt (572 lines → 2 files)

**Split plan**:
```
app/AgentService.kt            (~350 lines) — lifecycle, session management, broadcast receiver
app/AgentServiceEventCollector.kt (~150 lines) — event collection, capsule state updates, VD viewer bridge
```

The event collector is a natural extraction — it's the `collectEvents()` coroutine which can be a standalone class.

### 4. ShizukuClient.kt (544 lines)

**Split plan**:
```
platform/virtualdisplay/ShizukuClient.kt     (~300 lines) — IPC, display operations
platform/virtualdisplay/ShizukuInputInjector.kt (~150 lines) — touch/key injection via IInputManager
```

### 5. MainActivity.kt (536 lines)

**Split plan**:
```
app/MainActivity.kt           (~300 lines) — lifecycle, permissions, navigation
app/SessionLauncher.kt        (~200 lines) — session creation, config building, service binding
```

### 6. AgentTrace.kt (507 lines → 2 files)

**Split plan**:
```
trace/AgentTrace.kt            (~250 lines) — event recording (turnStarted, llmRequest, etc.)
trace/TraceArtifactBuilder.kt  (~200 lines) — snapshot writing, file generation, JSON formatting
```

### 7. ChatViewModel.kt (449 lines → 2 files)

**Split plan**:
```
ui/chat/ChatViewModel.kt      (~250 lines) — ViewModel, state management, op dispatch
ui/chat/EventReducer.kt       (~150 lines) — AgentEvent → ChatUiState reducer logic
```

### 8. AgentSession.kt (443 lines → 2 files)

**Split plan**:
```
session/AgentSession.kt        (~250 lines) — state machine, lifecycle, event emission
session/AgentOpDispatcher.kt   (~150 lines) — Op processing (handleStart, handleTakeover, etc.)
```

## General principles

- **Extract by responsibility**, not by size
- **New files should be under 300 lines** with room to grow
- **Minimize interface changes** — extracted classes are `internal` where possible
- **Each extraction must compile independently** — no circular dependencies

## Steps

1. Start with `AgentTurnRunner` (largest, most critical)
2. Do `VirtualDisplayPlatform` as part of [platform cleanup](./07_platform_abstraction_cleanup.md)
3. Do `AgentService` as part of [lifecycle fix](./09_agent_service_lifecycle_fix.md)
4. Remaining files in any order

## Risks

- **Low**: These are all internal implementation splits — public APIs don't change
- **Medium**: `AgentTurnRunner` split requires careful attention to shared state between phases
