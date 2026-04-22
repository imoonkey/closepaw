# Session and Protocol Review

## Summary
The `session/` and `protocol/` packages define the lifecycle and communication contract. The design follows a clean "Thin Session" architecture where the session manages state and routing but delegates intelligence to the Agent.

## High-Risk Issues (Must-Fix)

### 1. Hardcoded Built-in Tools Registration
**Location**: `session/SessionServices.kt` lines 108-116
**Issue**: `registerBuiltInTools` hardcodes the list of tools (`ClickTool`, `TypeTool`, etc.). This violates the Open-Closed Principle.
**Fix**: Use a ServiceLoader pattern or a reflection-based scanner (if appropriate for Android) or a central `ToolConfig` to register tools, making it easier to add extensions without modifying the core service container.

## Medium Issues (Should-Fix)

### 2. Unimplemented User Input Handling
**Location**: `session/AgentSession.kt` line 323
**Issue**: `handleUserInput` is a TODO. The protocol defines `Op.UserInput`, but the session drops it. If the user tries to correct the agent, it will be ignored.
**Fix**: Implement `handleUserInput`. It should likely inject a new `UserMessage` into the `HistoryManager` so the agent sees it on the next turn.

### 3. Race Condition in Shutdown
**Location**: `session/AgentSession.kt` line 301
**Issue**: `handleShutdown` completes the `cancellationSignal`. If `handleAgentComplete` is called concurrently (e.g. agent finishes exactly when user hits stop), there might be a race.
**Fix**: The check `_state.value == SessionState.Shutdown` in `handleAgentComplete` mitigates this, but ensuring atomic state transitions would be safer.

## Low-Risk Suggestions (Nice-to-Have)

### 4. SessionId Generation
**Location**: `protocol/SessionId.kt`
**Suggestion**: Ensure `SessionId` generation uses a secure random or UUID (it likely does, but verify) to avoid collisions if logs are aggregated centrally.

### 5. Event Flow Buffering
**Location**: `session/AgentSession.kt` line 105
**Suggestion**: `Channel.BUFFERED` is good, but consider what happens if the UI is slow to consume events. A defined buffer size or `BufferOverflow.DROP_OLDEST` (for status updates) might be safer than unbounded buffering behavior (though `BUFFERED` defaults to 64).
