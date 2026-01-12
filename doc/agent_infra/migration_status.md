# Agent Infrastructure Migration Plan & Status

**Last Updated**: January 2026

---

## Current Status: Phase 6 Complete ✅

All phases 1-6 have been implemented. Phase 7 (Polish & Cleanup) is pending.

---

## Phase Summary

| Phase | Description | Status | Duration |
|-------|-------------|--------|----------|
| 1. Protocol | Pure data types (Op, Event) | ✅ Complete | 2 days |
| 2. Session Bridge | AgentSession with Op/Event protocol | ✅ Complete | 2 days |
| 3. Platform | AndroidPlatform abstraction | ✅ Complete | 3 days |
| 4. Tools | Tool infrastructure & state machine | ✅ Complete | 5 days |
| 5. Services | SessionServices DI container | ✅ Complete | 3 days |
| 6. Orchestration | MobileV3Orchestration + legacy adapter | ✅ Complete | 5 days |
| 7. Polish | Error handling, cleanup, telemetry | ⏳ Pending | 5 days |

---

## Phase Details

### Phase 1: Protocol Layer ✅

**Goal**: Define the communication protocol as pure data types with no dependencies.

**Implemented Files**:
- `protocol/Op.kt` - Operations + SessionConfig + ApprovalMode
- `protocol/AgentEvent.kt` - Events + TurnPhase + CompletionReason
- `protocol/SessionId.kt` - Value class for session identification
- `protocol/SessionState.kt` - State machine states + CancellationReason
- `protocol/AgentError.kt` - Error type hierarchy
- `protocol/ApprovalTypes.kt` - ApprovalDecision, ApprovalDetails, RiskLevel

**Validation**: ✅ Project compiles, no runtime changes to existing app.

---

### Phase 2: Session Bridge ✅

**Goal**: Introduce AgentSession as a facade using the new protocol.

**Implemented Files**:
- `session/AgentSession.kt` - Main session class with Op/Event handling
- `AgentService.kt` - Updated to use AgentSession

**Key Features**:
- Multiple factory methods (`create()`, `createWithServices()`)
- State machine management
- Event emission via Kotlin Flow
- Backward compatibility with legacy orchestrator

**Validation**: ✅ App runs with identical behavior, overlay buttons work.

---

### Phase 3: Platform Abstraction ✅

**Goal**: Abstract Android-specific code behind interfaces for testability.

**Implemented Files**:
- `platform/AndroidPlatform.kt` - Interface
- `platform/AccessibilityPlatform.kt` - Real implementation
- `platform/UIAction.kt` - Action data types
- `platform/ActionResult.kt` - Result data types
- `platform/mock/MockPlatform.kt` - Test implementation

**Validation**: ✅ App runs, can write unit tests with MockPlatform.

---

### Phase 4: Tool Infrastructure ✅

**Goal**: Create tool registry and execution infrastructure.

**Implemented Files**:
- `infra/tools/ToolSpec.kt` - Tool specification interface
- `infra/tools/ToolCallState.kt` - State machine states
- `infra/tools/ToolCallResult.kt` - Result types
- `infra/tools/ToolRouter.kt` - Execution with state machine
- `infra/registry/ToolRegistry.kt` - Tool registration & lookup
- `infra/policy/PolicyEngine.kt` - Policy decisions (allow/deny/ask)
- `tools/base/BaseTool.kt` - Abstract base for tool implementations
- `tools/impl/*.kt` - Tool implementations (Click, Type, Scroll, Swipe, Back, Wait)

**Validation**: ✅ Tool calls flow through state machine (visible in logs).

---

### Phase 5: Infrastructure Services ✅

**Goal**: Complete infrastructure layer with remaining services.

**Implemented Files**:
- `infra/history/HistoryManager.kt` - Conversation history management
- `infra/registry/AgentRegistry.kt` - Agent definitions registry
- `session/SessionServices.kt` - DI container

**Validation**: ✅ Services properly initialized in correct order.

---

### Phase 6: New Orchestration ✅

**Goal**: Implement proper MobileV3Orchestration using new infrastructure.

**Implemented Files**:
- `orchestration/AgentOrchestration.kt` - Interface
- `orchestration/OrchestrationFactory.kt` - Factory interfaces
- `orchestration/v3/MobileV3Orchestration.kt` - Multi-agent implementation
- `orchestration/v3/SessionExecutionState.kt` - Orchestration state
- `orchestration/legacy/LegacyOrchestrationAdapter.kt` - Backward compatibility

**Key Features**:
- Multi-agent (Manager, Executor, Reflector) loop
- Cooperative pause/resume/interrupt
- Event emission for UI updates
- Config flag to choose orchestration (`useNewOrchestration`)

**Validation**: ✅ App works with both orchestrations (toggle via config).

---

### Phase 7: Polish & Cleanup ⏳

**Goal**: Production-ready with comprehensive error handling.

**Tasks**:
1. ⏳ Pause when LLM calls hit rate limit
2. ⏳ Implement proper CancellationSignal propagation
3. ⏳ Remove legacy code when new orchestration is stable
4. ⏳ Add telemetry/structured logging (need a separate design doc)
5. ⏳ Documentation cleanup
6. ⏳ Test coverage improvements

---

## Rollback Strategy

| Phase | Risk | Rollback Approach |
|-------|------|-------------------|
| 1. Protocol | None | N/A (additive only) |
| 2. Session | Low | Remove AgentSession, revert AgentService |
| 3. Platform | Low | Inline interface calls |
| 4. Tools | Medium | Use ActionDispatcher directly |
| 5. Services | Low | Remove unused services |
| 6. Orchestration | Medium | Config flag to use legacy |
| 7. Polish | Low | N/A |

---

## Known Differences from Original Design

The implementation differs from the original design doc in these ways:

1. **SessionConfig location**: Moved to `Op.kt` for convenience
2. **ToolSpec simplification**: Non-generic interface instead of `ToolSpec<TParams, TResult>`
3. **AgentFactory deferred**: Uses existing `Manager`, `Executor`, `Reflector` directly
4. **CancellationReason duplication**: Exists in both `protocol/` and `orchestration/` packages
5. **TurnPhase duplication**: Local enum in MobileV3Orchestration maps to protocol enum

These are intentional pragmatic simplifications that don't affect the architecture's integrity.

