# Stage 2: Takeover & Supplement

> **Status: IMPLEMENTED** (2026-02-12)
>
> User takes control. User injects mid-task messages.
> Depends on Stage 1 (CapsuleMode, new capsule UI).

## Scope

Implement the takeover/resume flow (user grabs control, agent pauses, user resumes) and the supplement flow (user injects a message into agent context without interrupting).

**After this stage:** User can tap 接管 to take control, 继续 to resume, and 补充 to inject mid-task guidance. Agent seamlessly incorporates supplements on its next turn.

---

## Protocol Changes

### Op Changes

→ Modify `protocol/Op.kt`

```kotlin
sealed interface Op {
    // Replace Op.Pause with Op.Takeover
    data object Takeover : Op       // User takes control (was: Pause)
    data object Resume : Op         // User returns control (unchanged)
    
    // New: user injects a message mid-task
    data class Supplement(val text: String) : Op
    
    // Unchanged: UserInput, Interrupt, Shutdown, Approve
}
```

**Remove:** `Op.Start` (deprecated), `Op.Pause` (replaced by `Op.Takeover`)

### Event Changes

→ Modify `protocol/AgentEvent.kt`

```kotlin
// Rename for clarity
data class SessionTakeover(
    override val sessionId: SessionId,
    override val timestamp: Long
) : AgentEvent

// New: supplement was received
data class SupplementReceived(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val text: String
) : AgentEvent
```

**Remove:** `AgentEvent.SessionPaused` (replaced by `SessionTakeover`)
**Keep:** `AgentEvent.SessionResumed` (unchanged)

---

## Takeover Flow

### Mechanism

Takeover reuses the existing pause mechanism in `Agent.kt`:
- `Agent.pause()` sets `pauseState = true`
- Between turns, agent waits on `pauseState.first { !it }`
- `Agent.resume()` sets `pauseState = false`
- Next turn starts with fresh perception (screen capture)

No change to `Agent.kt` internals. The rename is purely at the protocol layer.

### Session Integration

→ Modify `session/AgentSession.kt`

```kotlin
private suspend fun handleTakeover() {
    if (state.value != SessionState.Running) return
    agentRunner.pause()  // same as before
    _state.value = SessionState.Paused
    emit(AgentEvent.SessionTakeover(...))
}

private suspend fun handleResume() {
    if (state.value != SessionState.Paused) return
    agentRunner.resume()
    _state.value = SessionState.Running
    emit(AgentEvent.SessionResumed(...))
}
```

### Capsule State Flow

```
User taps [接管]
  ├── ServiceOverlayController sends Op.Takeover to session
  ├── Capsule immediately sets mode = TakeoverPending(lastThought)
  │   (capsule shows "正在交接..." with amber dot)
  │
  ├── Session calls agent.pause()
  ├── Agent finishes current action (if mid-turn)
  ├── Agent enters pause wait
  ├── Session emits SessionTakeover event
  │
  └── Capsule receives SessionTakeover → mode = Takeover(lastThought)
      (capsule shows dimmed thought with amber dot, [补充] [继续] [停止])
```

```
User taps [继续]
  ├── ServiceOverlayController sends Op.Resume to session
  ├── Session calls agent.resume()
  ├── Session emits SessionResumed
  ├── Agent starts fresh turn with perception
  └── Capsule mode = Running("思考中...")
```

**Edge case:** If agent is between turns when Takeover is requested, `SessionTakeover` fires almost immediately. TakeoverPending state is visible for only a frame. This is fine — the user sees the transition.

---

## Supplement Flow

### Mechanism

A supplement is a user message injected into the agent's conversation history. The agent sees it on its next turn alongside the screen perception and previous turns.

### Session Integration

→ Modify `session/AgentSession.kt`

```kotlin
private suspend fun handleSupplement(text: String) {
    if (state.value !in listOf(SessionState.Running, SessionState.Paused)) return
    
    // Add user message to history
    services.historyManager.addUserMessage(text)
    
    emit(AgentEvent.SupplementReceived(sessionId, now(), text))
}
```

### History Integration

→ Modify `history/SessionHistoryManager.kt` (or equivalent)

Need a method to inject a user message into the conversation history outside the normal turn flow:

```kotlin
fun addUserMessage(text: String) {
    // Add as a user message in Responses API format
    // { "type": "message", "role": "user", "content": [{ "type": "input_text", "text": text }] }
}
```

This message appears in the agent's context on the next `PromptBuilder` invocation.

### Capsule State Flow

```
User taps [补充]
  ├── Capsule mode = SupplementInput(previousMode = current mode)
  │   Row 1: "补充你的想法" + [✕]
  │   Row 2: [text input] [发送]
  │   Keyboard rises (FLAG_NOT_FOCUSABLE removed)
  │
  ├── User types and taps [发送]
  │   ├── ServiceOverlayController sends Op.Supplement(text) to session
  │   ├── Capsule mode = previous mode (Running or Takeover)
  │   ├── Keyboard dismissed (FLAG_NOT_FOCUSABLE restored)
  │   └── Brief flash "已收到" on thought line for 1.5s
  │
  └── User taps [✕]
      └── Cancel: capsule mode = previous mode, keyboard dismissed
```

### Keyboard Handling

→ Modify `ui/overlay/SmartCapsuleLayoutBuilder.kt`

When entering SupplementInput:
1. Remove `FLAG_NOT_FOCUSABLE` from window params
2. `windowManager.updateViewLayout(container, newParams)`
3. Focus the EditText
4. `inputMethodManager.showSoftInput(editText, 0)`

When exiting SupplementInput:
1. `inputMethodManager.hideSoftInputFromWindow(editText.windowToken, 0)`
2. Add `FLAG_NOT_FOCUSABLE` back
3. `windowManager.updateViewLayout(container, newParams)`

---

## Implementation Phases

### Phase 1: Protocol Changes

- Remove `Op.Start`, `Op.Pause`, `AgentEvent.SessionPaused`
- Add `Op.Takeover`, `Op.Supplement`
- Add `AgentEvent.SessionTakeover`, `AgentEvent.SupplementReceived`
- Update `AgentSession.kt` submit() dispatch
- Update all callers of `Op.Pause` → `Op.Takeover`

### Phase 2: Takeover/Resume in Session

- Implement `handleTakeover()` and update `handleResume()` in AgentSession
- Wire `ServiceOverlayController` to send `Op.Takeover` / `Op.Resume`
- Update capsule callbacks: `onTakeover`, `onResume`

### Phase 3: Capsule Takeover States

- Implement `TakeoverPending` and `Takeover` rendering in SmartCapsuleManager
- TakeoverPending: amber dot, "正在交接...", disabled buttons
- Takeover: amber dot, dimmed thought, [补充] [继续] [停止]
- Transition logic: TakeoverPending → Takeover on SessionTakeover event

### Phase 4: Supplement Flow

- Add `addUserMessage()` to history manager
- Implement `handleSupplement()` in AgentSession
- Implement SupplementInput rendering in SmartCapsuleManager
- Keyboard handling (FLAG_NOT_FOCUSABLE toggle)
- "已收到" confirmation flash

---

## Testing

- Unit test Op.Takeover → SessionState.Paused transition
- Unit test Op.Resume → SessionState.Running transition
- Unit test Op.Supplement → history contains new user message
- Integration: takeover during running task, verify agent pauses
- Integration: supplement while running, verify agent sees message on next turn
- Visual: debug-run, tap 接管, verify capsule shows Takeover state

---

## Files Summary

| Action | File | Description |
|--------|------|-------------|
| Modify | `protocol/Op.kt` | Replace Pause with Takeover, add Supplement |
| Modify | `protocol/AgentEvent.kt` | Add SessionTakeover, SupplementReceived |
| Modify | `session/AgentSession.kt` | Handle Takeover, Resume, Supplement |
| Modify | `history/SessionHistoryManager.kt` | addUserMessage() for supplement injection |
| Modify | `ui/overlay/SmartCapsuleManager.kt` | Render TakeoverPending, Takeover, SupplementInput |
| Modify | `ui/overlay/SmartCapsuleLayoutBuilder.kt` | EditText for supplement, keyboard handling |
| Modify | `app/ServiceOverlayController.kt` | Wire Takeover/Resume/Supplement callbacks |
| Modify | `app/AgentService.kt` | Handle new events |
