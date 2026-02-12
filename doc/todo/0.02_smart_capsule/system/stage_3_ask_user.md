# Stage 3: ask_user Tool

> Agent asks the user for help. Suspension mechanism. Expandable capsule.
> Depends on Stage 1 (CapsuleMode, capsule UI) and Stage 2 (keyboard handling).

## Scope

Implement the `ask_user` tool that lets the agent pause and request user input (text answer) or user action (physical operation like login). Includes the suspension mechanism, capsule expansion for WaitingForInput/WaitingForAction states, timeout handling, and end-to-end wiring.

**After this stage:** Agent can call `ask_user` when it hits login screens, ambiguous choices, or needs user help. The capsule expands to show the question/instruction, user responds, and the agent continues.

---

## Design Decisions

### Tool Suspension via CompletableDeferred

The `ask_user` tool is a normal tool in the registry. When executed, it creates a `CompletableDeferred<String>` and suspends on it. The session delivers the user's response by completing the deferred.

This is clean: no special-casing in the turn loop. The tool just takes longer to execute (it waits for user input). From the agent's perspective, it's a normal tool call that returns a result.

### Two ask_user Types

```kotlin
enum class AskUserType {
    QUESTION,  // Agent needs a text answer
    ACTION     // Agent needs user to do something on phone
}
```

Both types suspend the tool. The difference is in the capsule UI:
- QUESTION → WaitingForInput (text input field, user types answer)
- ACTION → WaitingForAction (instruction display, user taps "完成")

### One Pending Request at a Time

Only one `ask_user` can be pending per session. If the agent calls it again (shouldn't happen given tool arbitration), the second call fails with an error.

---

## Protocol Changes

### New Op

→ Add to `protocol/Op.kt`

```kotlin
data class UserResponse(val callId: String, val response: String) : Op
```

Sent when the user answers a question or taps "完成" for an action.

### New Events

→ Add to `protocol/AgentEvent.kt`

```kotlin
data class AskUser(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val type: AskUserType,
    val message: String,
    val callId: String
) : AgentEvent
```

Emitted when the `ask_user` tool starts waiting. The capsule transitions to WaitingForInput or WaitingForAction.

---

## Suspension Mechanism

### UserResponseChannel

→ New file: `session/UserResponseChannel.kt`

```kotlin
class UserResponseChannel {
    private var pending: CompletableDeferred<String>? = null
    private var pendingCallId: String? = null

    /**
     * Suspend until user responds. Called by ask_user tool.
     * Returns the user's response text.
     * Throws CancellationException if cancelled (stop/timeout).
     */
    suspend fun awaitResponse(callId: String): String {
        check(pending == null) { "Only one pending ask_user allowed" }
        val deferred = CompletableDeferred<String>()
        pending = deferred
        pendingCallId = callId
        return try {
            deferred.await()
        } finally {
            pending = null
            pendingCallId = null
        }
    }

    /**
     * Deliver user's response. Called by AgentSession on Op.UserResponse.
     * Returns true if delivered, false if no matching pending request.
     */
    fun deliver(callId: String, response: String): Boolean {
        val p = pending ?: return false
        if (pendingCallId != callId) return false
        pending = null
        pendingCallId = null
        return p.complete(response)
    }

    /**
     * Cancel pending request. Called on stop/timeout.
     */
    fun cancel() {
        pending?.cancel()
        pending = null
        pendingCallId = null
    }

    val hasPending: Boolean get() = pending != null
}
```

This is registered in `SessionServices` and shared between `AskUserTool` and `AgentSession`.

---

## ask_user Tool

→ New file: `tool/impl/AskUserTool.kt`

```kotlin
class AskUserTool(
    private val responseChannel: UserResponseChannel,
    private val eventDispatcher: AgentEventDispatcher
) : ToolSpec {

    override val name = "ask_user"

    override val description = """
Ask the user for help. Two types:
- question: Ask a question and wait for text answer.
- action: Ask user to perform a physical action (login, permission, captcha) and wait for confirmation.

Use when:
- Login/authentication required
- Ambiguous choice needs user preference
- Permission prompt appears
- Captcha or human verification needed

Do NOT use for:
- Progress updates (use agent_thought)
- Things you can determine from the screen
""".trimIndent()

    override val parameterSchema: JSONObject by lazy {
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("type", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("question", "action")))
                    put("description", "Type of request: question (need text answer) or action (need user to operate phone)")
                })
                put("message", JSONObject().apply {
                    put("type", "string")
                    put("description", "The question to ask or instruction for the user. Be clear and specific.")
                })
                put("agent_thought", JSONObject().apply {
                    put("type", "string")
                    put("description", "Brief reason for asking the user")
                })
            })
            put("required", JSONArray(listOf("type", "message")))
            put("additionalProperties", false)
        }
    }

    override fun validate(params: JSONObject): ValidationResult {
        val type = params.optString("type", "")
        if (type !in listOf("question", "action"))
            return ValidationResult.Invalid("type must be 'question' or 'action'")
        if (params.optString("message", "").isBlank())
            return ValidationResult.Invalid("message is required")
        if (responseChannel.hasPending)
            return ValidationResult.Invalid("Another ask_user request is already pending")
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val type = AskUserType.valueOf(params.getString("type").uppercase())
        val message = params.getString("message")
        val callId = java.util.UUID.randomUUID().toString()
        val thought = params.optString("agent_thought", "").trim()
        val description = if (thought.isNotEmpty()) thought else "Asking user: $message"

        return AskUserInvocation(
            type = type,
            message = message,
            callId = callId,
            description = description,
            responseChannel = responseChannel,
            eventDispatcher = eventDispatcher
        )
    }
}
```

### AskUserInvocation

```kotlin
class AskUserInvocation(
    private val type: AskUserType,
    private val message: String,
    private val callId: String,
    override val description: String,
    private val responseChannel: UserResponseChannel,
    private val eventDispatcher: AgentEventDispatcher
) : ToolInvocation {

    override val toolName = "ask_user"
    override fun getDescription() = description

    override suspend fun execute(
        platform: AndroidPlatform,
        snapshot: ScreenSnapshot?,
        isCancelled: () -> Boolean
    ): ToolExecutionResult {
        // Emit AskUser event → capsule transitions
        eventDispatcher.emitAskUser(type, message, callId)

        return try {
            // Suspend until user responds
            val response = responseChannel.awaitResponse(callId)

            ToolExecutionResult.Success(
                data = JSONObject().apply {
                    put("response", response)
                    put("type", type.name.lowercase())
                },
                observation = when (type) {
                    AskUserType.QUESTION -> "User answered: $response"
                    AskUserType.ACTION -> "User completed the requested action. Capture fresh screen to see result."
                }
            )
        } catch (e: CancellationException) {
            ToolExecutionResult.Success(
                data = JSONObject().apply {
                    put("timeout", true)
                    put("type", type.name.lowercase())
                },
                observation = "User did not respond within the timeout. Consider continuing without their input or trying a different approach."
            )
        }
    }
}
```

---

## Session Integration

### Handle Op.UserResponse

→ Modify `session/AgentSession.kt`

```kotlin
private suspend fun handleUserResponse(callId: String, response: String) {
    val delivered = services.userResponseChannel.deliver(callId, response)
    if (!delivered) {
        // No matching pending request — log and ignore
    }
}
```

### Timeout

→ In `AskUserInvocation.execute()`, wrap with `withTimeoutOrNull`:

```kotlin
val response = withTimeoutOrNull(5 * 60 * 1000L) {  // 5 minutes
    responseChannel.awaitResponse(callId)
}
if (response == null) {
    // Timeout — return timeout result
}
```

At 4 minutes, emit a nudge event (can be a StatusUpdate or a dedicated event).

### Cleanup on Stop

When `AgentSession.handleInterrupt()` or `handleShutdown()` is called:
```kotlin
services.userResponseChannel.cancel()  // Cancel any pending ask_user
```

---

## Capsule UI

### WaitingForInput

Capsule expands upward to ~160dp:

```
┌──────────────────────────────────────────────┐
│  💬 等待答复                                   │  Header
├──────────────────────────────────────────────┤
│  [Agent's question text, max 3 lines]         │  Body
├──────────────────────────────────────────────┤
│  [text input                    ] [发送 →]   │  Input
├──────────────────────────────────────────────┤
│                                    [⏹ 停止]  │  Bottom
└──────────────────────────────────────────────┘
```

Keyboard auto-raises. 补充 button disabled (input field IS the response channel).

### WaitingForAction

Capsule expands upward to ~120dp:

```
┌──────────────────────────────────────────────┐
│  ✋ 操作手机                                   │  Header
├──────────────────────────────────────────────┤
│  [Agent's instruction text, max 2 lines]      │  Body
├──────────────────────────────────────────────┤
│         [✅ 完成]                   [⏹ 停止]  │  Bottom
└──────────────────────────────────────────────┘
```

User operates phone, taps 完成 when done.

### Height Animation

Running → WaitingFor*: capsule expands (200ms, ease-out). Content fades in.
WaitingFor* → Running: capsule collapses (200ms, ease-out). Content fades out.

---

## Implementation Phases

### Phase 1: ask_user Tool + Suspension

- New: `tool/impl/AskUserTool.kt` + `AskUserInvocation`
- New: `session/UserResponseChannel.kt`
- Modify: `session/SessionServices.kt` — add `userResponseChannel`
- Modify: tool registration — add `ask_user` to built-in tools

### Phase 2: Protocol + Session Wiring

- Add: `Op.UserResponse`, `AgentEvent.AskUser` to protocol files
- Modify: `AgentSession.kt` — handle `Op.UserResponse`, cleanup on stop
- Modify: `AgentEventDispatcher.kt` — add `emitAskUser()` helper

### Phase 3: Capsule WaitingFor* States

- Modify: `SmartCapsuleManager.kt` — render WaitingForInput, WaitingForAction
- Modify: `SmartCapsuleLayoutBuilder.kt` — expandable layout, input field for question
- Keyboard handling for WaitingForInput (same pattern as SupplementInput from Stage 2)

### Phase 4: Timeout + Edge Cases

- Implement 5-minute timeout with 4-minute nudge
- Handle stop during ask_user (cancel pending deferred)
- Handle ask_user while supplement input is open (queue, resolve sequentially)
- Verify one-at-a-time enforcement

---

## Testing

- Unit test `UserResponseChannel`: await/deliver/cancel/double-request
- Unit test `AskUserTool`: validation, invocation creation
- Integration: agent calls ask_user → capsule transitions → user responds → agent continues
- Integration: timeout → agent gets timeout result
- Integration: stop during ask_user → deferred cancelled
- Visual: debug-run with a task that requires login

---

## Files Summary

| Action | File | Description |
|--------|------|-------------|
| New | `tool/impl/AskUserTool.kt` | ask_user tool definition + invocation |
| New | `session/UserResponseChannel.kt` | CompletableDeferred suspension channel |
| Modify | `protocol/Op.kt` | Add UserResponse |
| Modify | `protocol/AgentEvent.kt` | Add AskUser event, AskUserType enum |
| Modify | `session/SessionServices.kt` | Add userResponseChannel |
| Modify | `session/AgentSession.kt` | Handle UserResponse, cleanup |
| Modify | `agent/AgentEventDispatcher.kt` | Add emitAskUser() |
| Modify | `ui/overlay/SmartCapsuleManager.kt` | Render WaitingForInput/WaitingForAction |
| Modify | `ui/overlay/SmartCapsuleLayoutBuilder.kt` | Expandable layout, input field |
| Modify | `app/ServiceOverlayController.kt` | Wire AskUser event to capsule |
| Modify | `app/AgentService.kt` | Handle AskUser event |
