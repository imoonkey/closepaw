# Tool System Review

> **Module**: `infra/tools/`, `infra/registry/`, `tools/`
> **Reviewer**: Claude
> **Date**: January 19, 2026

## Summary

The Tool System provides a declarative framework for defining and executing UI actions:
- `ToolSpec`: Interface for tool definitions with validation
- `ToolRouter`: State machine for tool execution with approval flow
- `ToolRegistry`: Tool discovery and schema generation
- `BaseTool`: Abstract base for UI-action tools
- Concrete tools: click, type, scroll, swipe, back, home, wait

---

## High-Risk Issues (Must Fix)

### H1. Approval Timeout Can Block Forever
**Location**: `ToolRouter.kt:125-131`

**Problem**: When awaiting user approval, the code blocks indefinitely:
```kotlin
val deferred = CompletableDeferred<ApprovalDecision>()
pendingApprovals[callId] = deferred

val decision = try {
    deferred.await()  // BLOCKS FOREVER if user never responds
} finally {
    pendingApprovals.remove(callId)
}
```

If the user closes the approval dialog, navigates away, or the app crashes, this coroutine hangs forever, leaking resources and blocking the agent.

**Impact**: Agent becomes unresponsive, potential coroutine leak, poor UX.

**Fix**: Add timeout:
```kotlin
val decision = try {
    withTimeout(APPROVAL_TIMEOUT_MS) {
        deferred.await()
    }
} catch (e: TimeoutCancellationException) {
    Log.w(TAG, "Approval timed out for $callId")
    ApprovalDecision.DENIED  // Default to deny on timeout
} finally {
    pendingApprovals.remove(callId)
}

companion object {
    private const val APPROVAL_TIMEOUT_MS = 60_000L  // 1 minute
}
```

---

### H2. activeToolCalls Never Populated for Non-Terminal States
**Location**: `ToolRouter.kt:265-269`

**Problem**: The `updateState` function only adds non-terminal states to `activeToolCalls`:
```kotlin
private fun updateState(state: ToolCallState, callback: ((ToolCallState) -> Unit)?) {
    if (!state.isTerminal()) {
        activeToolCalls[state.callId] = state
    }
    callback?.invoke(state)
    Log.d(TAG, "State: ${state.callId} -> ${state::class.simpleName}")
}
```

But states are removed in `execute()` only on terminal results:
```kotlin
activeToolCalls.remove(callId)  // Only on Success/Error/Cancelled
```

This means if execution exits abnormally (exception before terminal state), the call remains in `activeToolCalls` forever.

**Impact**: Memory leak, incorrect `getActiveCallIds()` results.

**Fix**: Ensure cleanup in all paths:
```kotlin
suspend fun execute(...): ToolCallResult {
    val callId = generateCallId()
    
    try {
        // ... execution logic
    } finally {
        activeToolCalls.remove(callId)  // Always cleanup
    }
}
```

---

### H3. SCHEDULED State Transition Missing on Auto-Approve
**Location**: `ToolRouter.kt:157-161`

**Problem**: When policy allows auto-execution, the state transitions directly to `Scheduled` then immediately to `Executing`:
```kotlin
PolicyDecision.Allow -> {
    state = ToolCallState.Scheduled(callId, toolName, params, invocation)
    updateState(state, onStateChange)
    // Falls through immediately to EXECUTING
}
```

The `Scheduled` state is emitted but the tool is never actually "scheduled" - it executes immediately. This breaks the state machine semantics documented in `ToolCallState.kt`.

**Impact**: State listeners may be confused by instant Scheduled→Executing transition. The "Scheduled" state is meaningless.

**Fix**: Either remove `Scheduled` state or add actual scheduling:
```kotlin
// Option 1: Remove Scheduled, go directly to Executing
PolicyDecision.Allow -> {
    // Skip Scheduled state
}

// Continue to EXECUTING...
state = ToolCallState.Executing(callId, toolName, params, invocation)
```

---

### H4. ToolInvocation.execute() Context Has Wrong Snapshot
**Location**: `ToolRouter.kt:177-181`

**Problem**: The execution context is created with the snapshot that was current at validation time:
```kotlin
val execContext = object : ToolExecutionContext {
    override val platform: AndroidPlatform = context.platform
    override val currentSnapshot: ScreenSnapshot? = context.currentSnapshot  // May be stale!
    override fun isCancelled(): Boolean = context.isCancelled()
}
```

If there's an approval wait (which takes time), the snapshot becomes stale. Tools using element indices will reference incorrect elements.

**Impact**: Wrong element clicked/typed after approval delay.

**Fix**: Re-capture snapshot before execution if approval was required:
```kotlin
// After approval wait
val freshSnapshot = if (policyDecision is PolicyDecision.AskUser) {
    context.platform.captureScreen()
} else {
    context.currentSnapshot
}

val execContext = object : ToolExecutionContext {
    override val currentSnapshot: ScreenSnapshot? = freshSnapshot
    // ...
}
```

---

### H5. ValidationResult.Invalid Constructor Overload Confusion
**Location**: `ToolSpec.kt:65-68`

**Problem**: `ValidationResult.Invalid` has two constructors:
```kotlin
data class Invalid(
    val errors: List<String>
) : ValidationResult {
    constructor(error: String) : this(listOf(error))
}
```

The secondary constructor creates a single-element list, but the primary constructor takes a list. This pattern can cause confusion:
```kotlin
// These look similar but behave differently:
ValidationResult.Invalid("error")        // Single error
ValidationResult.Invalid(listOf("a", "b"))  // Multiple errors
ValidationResult.Invalid(errors)         // If errors is a List<String>
```

**Impact**: API confusion, potential misuse.

**Fix**: Use factory functions instead:
```kotlin
sealed interface ValidationResult {
    data object Valid : ValidationResult
    
    data class Invalid private constructor(
        val errors: List<String>
    ) : ValidationResult {
        companion object {
            fun single(error: String) = Invalid(listOf(error))
            fun multiple(errors: List<String>) = Invalid(errors)
        }
    }
}
```

---

## Medium Issues (Should Fix)

### M1. ToolRouter.resolveApproval() Silently Fails
**Location**: `ToolRouter.kt:220-228`

**Problem**: If approval resolution is called for an unknown callId, it only logs a warning:
```kotlin
fun resolveApproval(callId: String, decision: ApprovalDecision) {
    val deferred = pendingApprovals[callId]
    if (deferred != null) {
        deferred.complete(decision)
        Log.d(TAG, "Resolved approval for $callId: $decision")
    } else {
        Log.w(TAG, "No pending approval found for $callId")  // Silent failure
    }
}
```

This could happen if:
- UI sends stale approval response
- Race condition between timeout and user response

**Impact**: User thinks approval was handled but it wasn't.

**Fix**: Return status or throw:
```kotlin
fun resolveApproval(callId: String, decision: ApprovalDecision): Boolean {
    val deferred = pendingApprovals[callId]
    return if (deferred != null) {
        deferred.complete(decision)
        Log.d(TAG, "Resolved approval for $callId: $decision")
        true
    } else {
        Log.w(TAG, "No pending approval found for $callId")
        false
    }
}
```

---

### M2. ToolRegistry.register() Allows Silent Overwrite
**Location**: `ToolRegistry.kt:32-37`

**Problem**: Registering a tool with an existing name silently overwrites:
```kotlin
fun register(tool: ToolSpec) {
    if (tools.containsKey(tool.name)) {
        Log.w(TAG, "Overwriting existing tool: ${tool.name}")  // Just a warning
    }
    tools[tool.name] = tool
    Log.d(TAG, "Registered tool: ${tool.name}")
}
```

The docstring says `@throws IllegalArgumentException` but it doesn't actually throw.

**Impact**: Documentation lies, potential accidental tool replacement.

**Fix**: Match documentation or change behavior:
```kotlin
fun register(tool: ToolSpec) {
    require(!tools.containsKey(tool.name)) { 
        "Tool already registered: ${tool.name}" 
    }
    tools[tool.name] = tool
    Log.d(TAG, "Registered tool: ${tool.name}")
}

// Or if overwrite is intentional:
fun register(tool: ToolSpec, allowOverwrite: Boolean = false) {
    if (tools.containsKey(tool.name) && !allowOverwrite) {
        throw IllegalArgumentException("Tool already registered: ${tool.name}")
    }
    // ...
}
```

---

### M3. BaseTool.createUIAction() Returns Nullable Without Explanation
**Location**: `BaseTool.kt:26`

**Problem**: `createUIAction()` is nullable:
```kotlin
protected abstract fun createUIAction(params: JSONObject): UIAction?
```

But there's no documentation on when/why it should return null. Looking at implementations, they return null only when validation-like conditions fail (e.g., negative index). But `validate()` should have caught this.

**Impact**: Unclear contract, potential NPE if tools return null unexpectedly.

**Fix**: Either make non-nullable or document clearly:
```kotlin
/**
 * Create a UIAction from the validated parameters.
 * 
 * @return UIAction or null if parameters are invalid (should not happen after validate())
 */
protected abstract fun createUIAction(params: JSONObject): UIAction?
```

Or better, make non-nullable since validation should ensure valid state:
```kotlin
protected abstract fun createUIAction(params: JSONObject): UIAction
```

---

### M4. SwipeTool Validates Coordinates but Allows (0,0)
**Location**: `SwipeTool.kt:49-56`

**Problem**: Coordinates are checked for `< 0` but (0, 0) to (0, 0) is technically valid:
```kotlin
if (startX < 0 || startY < 0 || endX < 0 || endY < 0) return null
```

A swipe from (0,0) to (0,0) does nothing but wastes a gesture.

**Impact**: No-op tool calls waste time.

**Fix**: Validate that start and end are different:
```kotlin
if (startX < 0 || startY < 0 || endX < 0 || endY < 0) return null
if (startX == endX && startY == endY) return null  // No-op swipe
```

---

### M5. WaitTool.validate() Uses Long, createUIAction() Uses Long with Coercion
**Location**: `WaitTool.kt:33-39` vs `WaitTool.kt:48-51`

**Problem**: Validation checks for max duration but createUIAction coerces anyway:
```kotlin
// validate()
if (durationMs > MAX_DURATION_MS) {
    errors.add("duration_ms must not exceed $MAX_DURATION_MS")
}

// createUIAction() - called after validation passes
val durationMs = params.optLong("duration_ms", DEFAULT_DURATION_MS)
    .coerceIn(0, MAX_DURATION_MS)  // Redundant coercion
```

**Impact**: Code duplication, validation is pointless since createUIAction coerces anyway.

**Fix**: Remove validation error and just coerce, OR remove coercion:
```kotlin
// Option 1: Validation only warns
override fun validate(params: JSONObject): ValidationResult {
    val durationMs = params.optLong("duration_ms", DEFAULT_DURATION_MS)
    if (durationMs > MAX_DURATION_MS) {
        Log.w(TAG, "duration_ms clamped from $durationMs to $MAX_DURATION_MS")
    }
    return ValidationResult.Valid
}

// Option 2: Validation rejects, remove coercion
override fun createUIAction(params: JSONObject): UIAction {
    val durationMs = params.optLong("duration_ms", DEFAULT_DURATION_MS)
    // No coercion needed - validation ensures bounds
    return UIAction.Wait(durationMs)
}
```

---

### M6. ScrollTool Direction Validation Case Mismatch
**Location**: `ScrollTool.kt:30-35` vs `ScrollTool.kt:46`

**Problem**: Validation checks lowercase:
```kotlin
if (direction.lowercase() !in validDirections) {
    errors.add("direction must be one of: ...")
}
```

But createUIAction also lowercases:
```kotlin
val direction = when (directionStr.lowercase()) { ... }
```

The validation could pass "UP" which then works in createUIAction. But if LLM sends "Up" it fails validation even though it would work.

**Impact**: Unnecessarily strict validation for case-insensitive operation.

**Fix**: Let validation handle case-insensitively since implementation does:
```kotlin
// Validation accepts any case
if (direction.lowercase() !in validDirections) { ... }

// Implementation already handles case
```

Actually this is correct - both lowercase. The issue is the error message doesn't mention case insensitivity. Add to error message:
```kotlin
errors.add("direction must be one of (case-insensitive): ${validDirections.joinToString(", ")}")
```

---

## Low-Risk Suggestions (Nice to Have)

### L1. Tool Call ID Too Short
**Location**: `ToolRouter.kt:273`

```kotlin
private fun generateCallId(): String = UUID.randomUUID().toString().take(8)
```

8 characters has ~4 billion combinations, which is fine for most cases but could theoretically collide in long-running sessions with many tool calls. Consider using 12 characters.

---

### L2. ToolObservation Not Used by Caller
**Location**: `ToolSpec.kt:123-127`

`ToolExecutionResult.Success.observation` is captured but never surfaced to the Agent (as noted in agent_core_review.md). This is a cross-module issue.

---

### L3. BaseTool Schema Helpers Could Be More Type-Safe
**Location**: `BaseTool.kt:122-138`

The `createSchema` helper uses string pairs for type/description:
```kotlin
protected fun createSchema(
    properties: Map<String, Pair<String, String>>,  // name -> (type, description)
    ...
)
```

Consider a sealed class for schema types:
```kotlin
sealed class SchemaType(val jsonType: String) {
    data object Integer : SchemaType("integer")
    data object String : SchemaType("string")
    data class Enum(val values: List<String>) : SchemaType("string")
}
```

---

### L4. ToolSpec.toFunctionSchema() Could Cache Result
**Location**: `ToolSpec.kt:45-54`

The schema is regenerated on every call. For static tools, this could be cached:
```kotlin
private val cachedSchema by lazy { generateFunctionSchema() }

fun toFunctionSchema(): JSONObject = cachedSchema
```

---

### L5. HomeTool in BackTool.kt
**Location**: `BackTool.kt:34-57`

Two tools in one file. Consider splitting for consistency:
- `BackTool.kt` → just BackTool
- `HomeTool.kt` → just HomeTool

---

## Questions

1. **Tool cancellation propagation**: When `ToolRouter.cancelAll()` is called during tool execution (not just awaiting approval), does the tool invocation actually stop? The `isCancelled()` check in `BaseToolInvocation.execute()` only checks before execution, not during.

2. **Concurrent tool calls**: Can multiple tool calls be in flight simultaneously? The ConcurrentHashMaps suggest yes, but the Agent executes tools sequentially. Is parallel tool execution planned?

3. **Tool execution timeout**: There's no timeout on tool execution itself. What if `platform.performAction()` hangs? Should there be a timeout?
