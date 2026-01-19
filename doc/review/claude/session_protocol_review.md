# Session & Protocol Review

> **Module**: `session/`, `protocol/`
> **Reviewer**: Claude
> **Date**: January 19, 2026

## Summary

The Session layer manages agent lifecycle through a state machine and Op/Event protocol:
- `AgentSession`: Receives `Op` commands, manages `Agent` lifecycle, emits `AgentEvent`s
- `SessionServices`: Dependency injection container for all session-scoped services
- Protocol types define the contract between UI and agent layers

---

## High-Risk Issues (Must Fix)

### H1. Event Channel Closed Before Final Event Delivery
**Location**: `AgentSession.kt:318-319`

**Problem**: In `handleShutdown()`, the `SessionCompleted` event is emitted, then the channel is immediately closed:
```kotlin
emit(AgentEvent.SessionCompleted(...))

// Close event channel
eventChannel.close()
```

With a `Channel.BUFFERED`, the close happens before collectors have a chance to process the final event. This is a race condition where the completion event may be lost.

**Impact**: UI may never receive `SessionCompleted` event, leaving user confused about agent state.

**Fix**: Use a coroutine with delay or close the channel after ensuring event delivery:
```kotlin
emit(AgentEvent.SessionCompleted(...))

// Give collectors time to receive the final event
// Better: use a separate "close" signal that collectors can use
scope.launch {
    delay(100) // Allow event to propagate
    eventChannel.close()
}
```

Or better, don't close eagerly - let the channel close naturally when scope is cancelled.

---

### H2. Double Completion Event on Normal Shutdown
**Location**: `AgentSession.kt:222-241` and `AgentSession.kt:307-316`

**Problem**: When the agent completes normally via `handleAgentComplete()`, it emits `SessionCompleted`. But if `handleShutdown()` is later called (e.g., service destruction), it emits another `SessionCompleted`:

```kotlin
// handleAgentComplete emits:
emit(AgentEvent.SessionCompleted(
    reason = CompletionReason.GOAL_ACHIEVED
))

// handleShutdown also emits:
emit(AgentEvent.SessionCompleted(
    reason = if (previousState == Running || Paused) USER_STOPPED else INTERRUPTED
))
```

**Impact**: UI receives two completion events, potentially causing double cleanup or confusing state transitions.

**Fix**: Track if completion was already emitted:
```kotlin
private var completionEmitted = false

private suspend fun handleAgentComplete(reason: AgentStopReason) {
    if (_state.value == SessionState.Shutdown || completionEmitted) {
        return
    }
    completionEmitted = true
    // ... emit SessionCompleted
}

private suspend fun handleShutdown() {
    // ...
    if (!completionEmitted) {
        emit(AgentEvent.SessionCompleted(...))
    }
    // ...
}
```

---

### H3. Agent Reference Not Nulled After Stop
**Location**: `AgentSession.kt:286-288`

**Problem**: In `handleInterrupt()`, `agent?.stop()` is called but the `agent` reference is not nulled:
```kotlin
private suspend fun handleInterrupt() {
    if (_state.value != SessionState.Running) {
        Log.w(TAG, "Cannot interrupt: not running (state: ${_state.value})")
        return
    }
    
    agent?.stop()  // Agent stopped but reference kept
    Log.i(TAG, "Interrupt requested")
}
```

This means the agent instance remains, and if `handleInterrupt` is called multiple times, it keeps calling `stop()` on a stopped agent.

**Impact**: Potential undefined behavior or resource leaks.

**Fix**: Either null the reference or add a stopped state check:
```kotlin
private suspend fun handleInterrupt() {
    val currentAgent = agent ?: return
    if (_state.value != SessionState.Running) return
    
    currentAgent.stop()
    agent = null  // Clear reference
    agentJob?.cancel()
    agentJob = null
}
```

---

### H4. State Transition Missing for Interrupt
**Location**: `AgentSession.kt:280-288`

**Problem**: When `Op.Interrupt` is handled, the agent is stopped but no state transition occurs and no event is emitted. The documentation says "Stays in: Running state" but that's misleading - the agent IS stopped.

```kotlin
private suspend fun handleInterrupt() {
    if (_state.value != SessionState.Running) {
        Log.w(TAG, "Cannot interrupt: not running (state: ${_state.value})")
        return
    }
    
    agent?.stop()  // Stopped but state still "Running"
    Log.i(TAG, "Interrupt requested")
    // No state change, no event!
}
```

**Impact**: Session state says "Running" but agent is actually stopped. UI cannot distinguish between running and interrupted states.

**Fix**: Either transition to a new state or emit an event:
```kotlin
private suspend fun handleInterrupt() {
    if (_state.value != SessionState.Running) return
    
    agent?.stop()
    
    // Option 1: Emit an event (keep Running state for cooperative wait)
    emit(AgentEvent.StatusUpdate(
        sessionId = sessionId,
        timestamp = now(),
        status = "Interrupt requested",
        emoji = "⚠️"
    ))
    
    // The actual completion will be handled by handleAgentComplete when agent stops
}
```

---

### H5. SessionServices.create() Not Actually Suspend
**Location**: `SessionServices.kt:65`

**Problem**: `SessionServices.create()` is marked `suspend` but performs no suspending operations:
```kotlin
suspend fun create(
    config: SessionConfig,
    platform: AndroidPlatform
): SessionServices {
    Log.d(TAG, "Creating SessionServices...")
    
    val policyEngine = PolicyEngine(config.approvalMode)  // Not suspend
    val toolRegistry = ToolRegistry().apply { ... }       // Not suspend
    // ... all synchronous
}
```

**Impact**: Misleading API - callers might expect this can be called from non-suspend context but are forced into coroutine context for no reason.

**Fix**: Remove `suspend` modifier:
```kotlin
fun create(
    config: SessionConfig,
    platform: AndroidPlatform
): SessionServices {
    // ...
}
```

And update `AgentSession.create()` accordingly (though it legitimately needs to be suspend for other reasons).

---

## Medium Issues (Should Fix)

### M1. Op.UserInput Not Implemented
**Location**: `AgentSession.kt:322-326`

**Problem**: `Op.UserInput` is accepted but does nothing:
```kotlin
private suspend fun handleUserInput(op: Op.UserInput) {
    // TODO: Forward to agent for handling
    Log.w(TAG, "UserInput not yet supported: ${op.text}")
    emitStatus("User input not yet supported")
}
```

This is documented in protocol.md as supported, creating a contract violation.

**Impact**: Users expecting to provide runtime input will be confused.

**Fix**: Either implement or remove from protocol:
```kotlin
// To implement:
private suspend fun handleUserInput(op: Op.UserInput) {
    val currentAgent = agent ?: run {
        Log.w(TAG, "No agent for user input")
        return
    }
    
    services.historyManager.addItem(
        ResponseItem.Message(role = "user", content = op.text)
    )
    
    emit(AgentEvent.StatusUpdate(
        sessionId = sessionId,
        timestamp = now(),
        status = "User input received",
        emoji = "📝"
    ))
}
```

---

### M2. SessionState Has Unused States
**Location**: `SessionState.kt:31-33`

**Problem**: `SessionState.Cancelled` and `SessionState.Error` are defined but never transitioned to in the code. The session uses `Completed` and `Shutdown` for all endings.

```kotlin
/** Session was cancelled by user */
data object Cancelled : SessionState  // Never used

/** Session encountered an error */
data class Error(val exception: Throwable) : SessionState  // Never used
```

**Impact**: Dead code, potential confusion about intended state machine.

**Fix**: Either use these states appropriately or remove them:
```kotlin
// Option 1: Use Cancelled for user-initiated stops
private suspend fun handleShutdown() {
    val previousState = _state.value
    _state.value = SessionState.Cancelled  // Instead of Shutdown
    // ...
}

// Option 2: Remove if truly unused (break API compatibility)
```

---

### M3. CancellationReason Never Used
**Location**: `SessionState.kt:44-53`

**Problem**: `CancellationReason` sealed interface is defined with three variants but never instantiated anywhere.

**Impact**: Dead code.

**Fix**: Remove or integrate with completion event:
```kotlin
data class SessionCompleted(
    ...
    val cancellationReason: CancellationReason? = null  // Add this
) : AgentEvent
```

---

### M4. SessionServices Cleanup Incomplete
**Location**: `SessionServices.kt:164-173`

**Problem**: `cleanup()` only cancels tool calls and clears history:
```kotlin
fun cleanup() {
    Log.d(TAG, "Cleaning up SessionServices...")
    toolRouter.cancelAll()
    historyManager.clear()
    Log.i(TAG, "SessionServices cleaned up")
}
```

It doesn't cleanup `policyEngine` (reset lists) or potentially release platform resources.

**Impact**: State may leak between sessions if services are reused (currently they aren't, but it's a design issue).

**Fix**:
```kotlin
fun cleanup() {
    Log.d(TAG, "Cleaning up SessionServices...")
    
    toolRouter.cancelAll()
    historyManager.clear()
    policyEngine.reset()  // Reset allow/deny lists
    
    Log.i(TAG, "SessionServices cleaned up")
}
```

---

### M5. ApprovalDetails.description Has Default Empty String
**Location**: `ApprovalTypes.kt:64`

**Problem**: `description` has a default value making it effectively optional:
```kotlin
data class ApprovalDetails(
    val callId: String,
    val toolName: String,
    val args: JSONObject,
    val description: String = "",  // Empty default
    val riskLevel: RiskLevel = RiskLevel.MEDIUM
)
```

This means approval dialogs might show empty descriptions to users.

**Impact**: Poor UX for approval dialogs.

**Fix**: Make description required or add validation:
```kotlin
data class ApprovalDetails(
    val callId: String,
    val toolName: String,
    val args: JSONObject,
    val description: String,  // Required
    val riskLevel: RiskLevel = RiskLevel.MEDIUM
) {
    init {
        require(description.isNotBlank()) { "description must not be blank" }
    }
}
```

---

### M6. TurnPhase.REFLECTION Never Emitted
**Location**: `AgentEvent.kt:207`

**Problem**: `TurnPhase.REFLECTION` is defined but no code ever emits a phase change to REFLECTION. The agent goes PERCEPTION → PLANNING → EXECUTION directly.

**Impact**: Dead code, unclear design intent.

**Fix**: Either implement reflection phase or remove:
```kotlin
enum class TurnPhase {
    PERCEPTION,
    // REFLECTION,  // Remove if not used
    PLANNING,
    EXECUTION
}
```

---

## Low-Risk Suggestions (Nice to Have)

### L1. AgentError.from() Should Be Exhaustive
**Location**: `AgentError.kt:146-169`

The `from()` factory handles specific exceptions but falls back to `UnexpectedError`. Consider adding more cases:
```kotlin
is org.json.JSONException -> LLMParseError(...)
is kotlinx.coroutines.CancellationException -> SessionClosedError()
```

---

### L2. SessionId Could Include Timestamp
**Location**: `SessionId.kt:13`

For debugging, including a timestamp prefix aids log correlation:
```kotlin
fun generate(): SessionId = SessionId("${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
```

---

### L3. CompletionReason.TIMEOUT Never Used
**Location**: `AgentEvent.kt:230`

`TIMEOUT` is defined but no timeout mechanism exists in the codebase. Consider removing or implementing.

---

### L4. Event Interface Could Have Default Timestamp
**Location**: `AgentEvent.kt:17-19`

Every event implementation requires `timestamp = System.currentTimeMillis()`. Consider a companion factory:
```kotlin
sealed interface AgentEvent {
    val sessionId: SessionId
    val timestamp: Long
    
    companion object {
        fun now(): Long = System.currentTimeMillis()
    }
}
```

---

## Questions

1. **Channel capacity**: `Channel.BUFFERED` uses a default capacity of 64. Is this sufficient for burst event scenarios? Should it be unlimited?

2. **Event ordering**: Events are emitted via `eventChannel.send()` which maintains order, but if the collector is slow, could events be dropped with a bounded channel?

3. **Multiple sessions**: The architecture seems to support one session at a time per `AgentService`. Is parallel session support needed in the future?
