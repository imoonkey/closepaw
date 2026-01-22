# Android Agent Test Plan

> **Goal**: Protect codebase correctness with meaningful tests, not maximize coverage metrics.

## Philosophy

This test plan prioritizes:

1. **State machine correctness** - Protocols, transitions, and invariants
2. **Business logic correctness** - Policy decisions, history management, validation
3. **Minimal LLM mocking** - Fake API responses don't validate real behavior
4. **Manual integration tests for agent flows** - Use `scripts/agent_process_visual_debug.md` for E2E

### What NOT to Test via Unit Tests

- **LLM response handling**: Mocking `LLMClient` produces fake responses that don't validate real API behavior. Real LLM interactions should be tested manually with visual debugging.
- **AccessibilityPlatform**: Requires real device/emulator. Use instrumented tests or manual testing.
- **Compose UI**: Use Compose preview and manual testing; UI snapshot tests are fragile.

---

## Architecture Test Coverage Map

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           TESTABILITY MATRIX                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  PURE UNIT TESTS (No Android, No Mocks)              Priority: HIGH     │
│  ├── protocol/SessionState.kt          ← State machine transitions      │
│  ├── protocol/AgentEvent.kt            ← Event equality/properties      │
│  ├── protocol/Op.kt                    ← Command data classes           │
│  ├── model/Models.kt                   ← Bounds, Point, PerceptionElement│
│  ├── tool/ToolCallState.kt             ← Tool state machine             │
│  ├── tool/PolicyEngine.kt              ← Policy logic                   │
│  ├── tool/ToolRegistry.kt              ← Registration/lookup            │
│  ├── history/HistoryManager.kt         ← History logic                  │
│  └── agent/AgentStopReason.kt          ← Stop reason types              │
│                                                                          │
│  UNIT TESTS WITH MINIMAL MOCKING                     Priority: HIGH     │
│  ├── tool/impl/*.kt validation         ← Tool parameter validation      │
│  ├── perception/Perceptor.kt           ← JSON generation (mock nodes)   │
│  └── tool/ToolRouter.kt                ← State machine (mock tools)     │
│                                                                          │
│  INTEGRATION TESTS (Mock Platform)                   Priority: MEDIUM   │
│  ├── session/SessionServices.kt        ← Service wiring                 │
│  ├── agent/Turn.kt                     ← Turn lifecycle                 │
│  └── session/AgentSession.kt           ← Session state machine          │
│                                                                          │
│  MANUAL/VISUAL TESTING                               Priority: HIGH     │
│  ├── agent/Agent.kt                    ← Full ReAct loop                │
│  ├── llm/LLMClient.kt                  ← Real API behavior              │
│  ├── platform/AccessibilityPlatform.kt ← Real device                    │
│  └── ui/**                             ← Visual correctness             │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Priority 1: State Machines & Protocols (CRITICAL)

These tests protect the core invariants of the system. Pure unit tests with no dependencies.

### 1.1 SessionState Transitions

**File**: `protocol/SessionState.kt`

**Why**: Session lifecycle is critical. Invalid transitions cause bugs that are hard to debug.

```kotlin
// Test cases:
class SessionStateTest {
    // Valid transitions
    @Test fun `Created → Running via Start`()
    @Test fun `Running → Paused via Pause`()
    @Test fun `Paused → Running via Resume`()
    @Test fun `Running → Idle via TaskCompleted`()
    @Test fun `Idle → Running via UserInput`()
    @Test fun `Any → Shutdown via Shutdown`()
    
    // Terminal state checks
    @Test fun `Completed is terminal`()
    @Test fun `Shutdown is terminal`()
    
    // Invalid transition rejection (if we add validation)
    @Test fun `Cannot pause when not running`()
    @Test fun `Cannot resume when not paused`()
}
```

**Estimated Value**: HIGH - Catches lifecycle bugs early

---

### 1.2 ToolCallState Machine

**File**: `tool/ToolCallState.kt`

**Why**: Tool execution has complex state transitions with approval flow.

```kotlin
class ToolCallStateTest {
    // State identification
    @Test fun `isTerminal returns true for Success`()
    @Test fun `isTerminal returns true for Error`()
    @Test fun `isTerminal returns true for Cancelled`()
    @Test fun `isTerminal returns false for Validating`()
    @Test fun `isTerminal returns false for AwaitingApproval`()
    @Test fun `isTerminal returns false for Executing`()
    
    // Property access
    @Test fun `all states have consistent callId`()
    @Test fun `all states have consistent toolName`()
    @Test fun `all states have consistent params`()
}
```

---

### 1.3 AgentStopReason and TurnOutcome

**File**: `agent/Agent.kt` (sealed classes)

```kotlin
class AgentStopReasonTest {
    @Test fun `GoalAchieved is distinguishable`()
    @Test fun `UserRequested is distinguishable`()
    @Test fun `MaxTurnsReached is distinguishable`()
    @Test fun `Error carries message`()
}

class TurnOutcomeTest {
    @Test fun `Continue signals more work`()
    @Test fun `Complete carries message`()
    @Test fun `Error carries message and recoverability`()
    @Test fun `Cancelled is distinguishable`()
}
```

---

## Priority 2: Business Logic (HIGH VALUE)

Pure logic tests that don't need Android or network.

### 2.1 PolicyEngine

**File**: `tool/PolicyEngine.kt`

**Why**: Policy decisions determine UX (approval dialogs) and safety.

```kotlin
class PolicyEngineTest {
    // Approval modes
    @Test fun `AUTO_APPROVE allows all tools`()
    @Test fun `ALWAYS_ASK asks for all tools`()
    @Test fun `SMART mode allows LOW risk tools`()
    @Test fun `SMART mode allows MEDIUM risk tools`()
    @Test fun `SMART mode asks for HIGH risk tools`()
    
    // Allow/deny lists
    @Test fun `deny list blocks tools regardless of mode`()
    @Test fun `allow list permits tools regardless of risk`()
    @Test fun `deny list takes precedence over allow list`()
    
    // Risk levels
    @Test fun `click has LOW risk`()
    @Test fun `type has MEDIUM risk`()
    @Test fun `purchase has HIGH risk`()
    @Test fun `unknown tools default to MEDIUM risk`()
    
    // Runtime configuration
    @Test fun `setApprovalMode changes behavior`()
    @Test fun `setRiskLevel overrides default`()
    @Test fun `reset clears customizations`()
}
```

**Estimated Value**: HIGH - Prevents accidental approval of dangerous actions

---

### 2.2 HistoryManager

**File**: `history/HistoryManager.kt`

**Why**: History management affects context window and conversation correctness.

```kotlin
class HistoryManagerTest {
    // Basic operations
    @Test fun `addItem increases size`()
    @Test fun `clear removes all items`()
    @Test fun `getAll returns copy`()
    
    // Token estimation
    @Test fun `estimateTokenCount returns reasonable estimate`()
    @Test fun `isApproachingLimit detects threshold`()
    
    // Truncation policies
    @Test fun `NONE policy preserves full content`()
    @Test fun `CONSERVATIVE policy truncates long outputs`()
    @Test fun `AGGRESSIVE policy truncates more aggressively`()
    
    // History normalization (forPrompt)
    @Test fun `orphaned FunctionCallOutput is removed`()
    @Test fun `FunctionCall without output gets placeholder`()
    @Test fun `matched pairs are preserved`()
    
    // Rollback
    @Test fun `dropLastNUserTurns removes correct items`()
    @Test fun `dropLastNUserTurns with n=0 does nothing`()
    @Test fun `dropLastNUserTurns with large n clears from first user msg`()
    
    // Compression
    @Test fun `compress reduces token count`()
    @Test fun `compress preserves recent items`()
}
```

**Estimated Value**: HIGH - Context window bugs are subtle and cause agent confusion

---

### 2.3 ToolRegistry

**File**: `tool/ToolRegistry.kt`

```kotlin
class ToolRegistryTest {
    @Test fun `register adds tool`()
    @Test fun `register overwrites existing tool with warning`()
    @Test fun `unregister removes tool`()
    @Test fun `get returns null for unknown tool`()
    @Test fun `getNames returns all registered names`()
    @Test fun `generateResponsesApiTools creates valid FunctionTool objects`()
    @Test fun `generateResponsesApiTools respects filter`()
}
```

---

### 2.4 Tool Validation (All Tools)

**Files**: `tool/impl/*.kt`

**Why**: Invalid parameters cause cryptic failures. Validation is pure logic.

```kotlin
class ClickToolTest {
    @Test fun `valid element_index passes validation`()
    @Test fun `missing element_index fails validation`()
    @Test fun `negative element_index fails validation`()
    @Test fun `non-integer element_index fails validation`()
    @Test fun `createUIAction returns Click action`()
    @Test fun `getActionDescription formats correctly`()
}

class TypeToolTest {
    @Test fun `valid params pass validation`()
    @Test fun `missing element_index fails`()
    @Test fun `missing text fails`()
    @Test fun `empty text passes but may warn`()
}

class ScrollToolTest {
    @Test fun `valid direction passes`()
    @Test fun `invalid direction fails`()
    @Test fun `case insensitive direction`()
}

class SwipeToolTest {
    @Test fun `valid coordinates pass`()
    @Test fun `negative coordinates fail`()
    @Test fun `missing required params fail`()
}

// Similar for: BackTool, HomeTool, WaitTool, CompleteTaskTool
```

**Estimated Value**: MEDIUM-HIGH - Catches bad LLM outputs before execution

---

## Priority 3: Data Models (FOUNDATIONAL)

### 3.1 Geometry Models

**File**: `model/Models.kt`

```kotlin
class BoundsTest {
    @Test fun `width computed correctly`()
    @Test fun `height computed correctly`()
    @Test fun `centerX computed correctly`()
    @Test fun `centerY computed correctly`()
    @Test fun `equality works correctly`()
    @Test fun `hashCode consistent with equals`()
}

class PointTest {
    @Test fun `equality works correctly`()
    @Test fun `hashCode consistent with equals`()
}
```

---

### 3.2 Perception Models

```kotlin
class PerceptionElementTest {
    @Test fun `all properties accessible`()
    @Test fun `equality based on all fields`()
}

class ScreenSnapshotTest {
    @Test fun `elements list accessible`()
    @Test fun `timestamp recorded`()
}
```

---

## Priority 4: Integration Points (Mock Required)

These require mocking but test critical integration logic.

### 4.1 ToolRouter State Machine

**File**: `tool/ToolRouter.kt`

**Why**: The approval flow and state transitions are complex.

```kotlin
class ToolRouterTest {
    // Setup: Mock ToolRegistry with test tools, Mock PolicyEngine
    
    @Test fun `unknown tool returns Error result`()
    @Test fun `validation failure returns Error result`()
    @Test fun `policy Deny returns Error result`()
    @Test fun `policy Allow proceeds to execution`()
    @Test fun `policy AskUser waits for approval`()
    @Test fun `approval APPROVED continues execution`()
    @Test fun `approval DENIED returns Cancelled`()
    @Test fun `approval timeout returns Cancelled`()
    @Test fun `resolveApproval completes pending deferred`()
    @Test fun `cancel aborts pending approval`()
    @Test fun `cancelAll clears all state`()
    
    // State callback verification
    @Test fun `state transitions emit correct callbacks`()
}
```

---

### 4.2 Perceptor JSON Generation

**File**: `perception/Perceptor.kt`

**Why**: JSON format affects LLM understanding.

```kotlin
class PerceptorTest {
    // Use mock AccessibilityNodeInfo or test with ScreenSnapshot directly
    
    @Test fun `toPromptJson generates valid JSON`()
    @Test fun `toPromptJson includes all required fields`()
    @Test fun `toPromptJson handles empty elements`()
    @Test fun `toPromptJson truncates long strings`()
    @Test fun `MAX_ELEMENTS limits output size`()
}
```

**Note**: Testing `snapshot()` directly requires mocking `AccessibilityNodeInfo`, which is fragile. Consider testing `toPromptJson()` with pre-built `ScreenSnapshot` objects instead.

---

### 4.3 Turn Response Processing

**File**: `agent/Turn.kt`

```kotlin
class TurnTest {
    // Test processResponse logic (can extract to testable function)
    @Test fun `complete_task tool sets isComplete true`()
    @Test fun `no tools with text sets isComplete true`()
    @Test fun `tools without complete_task sets isComplete false`()
    @Test fun `tool calls parsed correctly from LLMToolCall`()
    
    // Test buildInputItems (with mock HistoryManager)
    @Test fun `user messages converted to EasyInputMessage`()
    @Test fun `assistant messages converted correctly`()
    @Test fun `function calls converted to ResponseFunctionToolCall`()
    @Test fun `function outputs converted correctly`()
}
```

---

## Priority 5: Session Integration

### 5.1 SessionServices Wiring

**File**: `session/SessionServices.kt`

```kotlin
class SessionServicesTest {
    // With mock platform and test API key
    
    @Test fun `create initializes all services`()
    @Test fun `all built-in tools registered`()
    @Test fun `cleanup cancels pending tool calls`()
    @Test fun `cleanup clears history`()
    @Test fun `updateApprovalMode changes policy`()
}
```

---

### 5.2 AgentSession State Machine

**File**: `session/AgentSession.kt`

**Why**: Session lifecycle with event emission is critical.

```kotlin
class AgentSessionTest {
    // With mock services
    
    // State transitions
    @Test fun `submit Start from Created transitions to Running`()
    @Test fun `submit UserInput from Idle transitions to Running`()
    @Test fun `submit Pause from Running transitions to Paused`()
    @Test fun `submit Resume from Paused transitions to Running`()
    @Test fun `submit Shutdown from any state transitions to Shutdown`()
    
    // Event emission
    @Test fun `Start emits SessionStarted and TaskStarted`()
    @Test fun `Pause emits SessionPaused`()
    @Test fun `Resume emits SessionResumed`()
    @Test fun `Shutdown emits SessionCompleted`()
    
    // Edge cases
    @Test fun `UserInput rejected when Running`()
    @Test fun `double Shutdown only emits once`()
}
```

---

## Manual Testing Strategy

For areas where unit tests provide low value:

### Agent E2E Testing

Use the visual debugging workflow from `scripts/agent_process_visual_debug.md`:

```bash
./scripts/debug-run.sh "Open Chrome"
```

**What to verify**:
- Perception captures correct elements
- LLM chooses appropriate actions
- Actions execute successfully
- Reflection detects state changes

### LLM Integration Testing

Manual verification of:
- Rate limit handling (429 responses)
- Transient error recovery (503, timeouts)
- Streaming delta delivery
- Tool call parsing

### UI Testing

- Compose Preview for component appearance
- Manual interaction testing on device
- Smart Capsule overlay behavior

---

## Implementation Plan

### Phase 1: Foundation (Week 1)
1. Set up test infrastructure (JUnit 5, AssertJ/Truth)
2. Implement state machine tests (SessionState, ToolCallState)
3. Implement model tests (Bounds, Point)

### Phase 2: Business Logic (Week 2)
1. PolicyEngine tests
2. HistoryManager tests
3. ToolRegistry tests

### Phase 3: Tool Validation (Week 3)
1. All tool validation tests
2. Tool parameter edge cases

### Phase 4: Integration (Week 4)
1. ToolRouter with mock tools
2. Perceptor JSON generation
3. Turn response processing

### Phase 5: Session (Week 5)
1. SessionServices wiring
2. AgentSession state machine

---

## Test Infrastructure Setup

### Dependencies (build.gradle.kts)

```kotlin
dependencies {
    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("com.google.truth:truth:1.1.5")
    
    // Coroutines Testing
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    
    // Mocking (minimal use)
    testImplementation("io.mockk:mockk:1.13.8")
    
    // Android Unit Tests (for models that use android.util.Log)
    testImplementation("org.robolectric:robolectric:4.11")
}
```

### Directory Structure

```
app/src/test/kotlin/com/moonkey/androidagent/
├── protocol/
│   ├── SessionStateTest.kt
│   └── AgentEventTest.kt
├── tool/
│   ├── ToolCallStateTest.kt
│   ├── PolicyEngineTest.kt
│   ├── ToolRegistryTest.kt
│   ├── ToolRouterTest.kt
│   └── impl/
│       ├── ClickToolTest.kt
│       ├── TypeToolTest.kt
│       └── ...
├── history/
│   └── HistoryManagerTest.kt
├── model/
│   └── ModelsTest.kt
├── perception/
│   └── PerceptorTest.kt
├── agent/
│   └── TurnTest.kt
└── session/
    ├── SessionServicesTest.kt
    └── AgentSessionTest.kt
```

---

## Success Metrics

Instead of code coverage, track:

1. **State machine coverage**: All valid transitions tested
2. **Policy decision coverage**: All approval modes × risk levels tested
3. **Validation coverage**: All tools × all parameter combinations
4. **Integration confidence**: Manual test runs documented

---

## Anti-Patterns to Avoid

1. ❌ **Mocking LLMClient responses** - Fake responses don't validate real behavior
2. ❌ **Testing AccessibilityPlatform without device** - Use interface abstraction
3. ❌ **UI snapshot tests** - Brittle, high maintenance
4. ❌ **Coverage-driven test writing** - Tests for unused code paths
5. ❌ **Over-mocking** - If you need >3 mocks, reconsider the test

---

## References

- [Visual Debugging Guide](../../scripts/agent_process_visual_debug.md)
- [Agent Protocol Design](../main/agent_protocol.md)
- [UI Stack Documentation](../main/ui_stack.md)
