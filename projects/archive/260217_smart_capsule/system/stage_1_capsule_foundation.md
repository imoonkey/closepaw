# Stage 1: Capsule Foundation

> **Status: IMPLEMENTED** (2026-02-12)
>
> Data model, redesigned capsule UI, thought pipeline.
> This is the foundation everything else builds on.

## Scope

Rebuild the Smart Capsule from a simple status bar into a two-row collaboration surface driven by a single `CapsuleMode` sealed class. Wire the `agent_thought` tool parameter through to the capsule's thought line.

**After this stage:** The capsule shows what the agent is thinking (via `agent_thought`), has the new two-row layout, and correctly renders Running/Done/Error states end-to-end.

---

## Data Model

### CapsuleMode

→ New file: `ui/overlay/model/CapsuleMode.kt`

```kotlin
sealed interface CapsuleMode {
    data class Running(val thought: String) : CapsuleMode
    data class TakeoverPending(val lastThought: String) : CapsuleMode
    data class Takeover(val lastThought: String) : CapsuleMode
    data class SupplementInput(val previousMode: CapsuleMode) : CapsuleMode
    data class WaitingForInput(val question: String, val callId: String) : CapsuleMode
    data class WaitingForAction(val instruction: String, val callId: String) : CapsuleMode
    data class Done(val message: String) : CapsuleMode
    data class Error(val message: String) : CapsuleMode
    data object Hidden : CapsuleMode
}
```

One value. One render function. No boolean soup. You look at the mode, you know exactly what to draw.

**Stage 1 only uses:** `Running`, `Done`, `Error`, `Hidden`. Others are defined now to avoid refactoring the sealed class later.

---

## Protocol Changes

### New Event: ThoughtUpdate

→ Add to `protocol/AgentEvent.kt`

```kotlin
data class ThoughtUpdate(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val thought: String
) : AgentEvent
```

Emitted by `AgentTurnRunner` when it extracts `agent_thought` from the selected tool call. The capsule listens for this to update its thought line.

### Cleanup

- Remove `Op.Start` (deprecated, unused outside tests)
- Remove emoji from `StatusUpdate` events — the capsule uses `CapsuleMode` now, not status strings

---

## Implementation

### Phase 1: CapsuleMode + ThoughtUpdate Event

**Files changed:**
- New: `ui/overlay/model/CapsuleMode.kt`
- Modified: `protocol/AgentEvent.kt` — add `ThoughtUpdate`
- Modified: `agent/AgentTurnRunner.kt` — extract `agent_thought` from selected tool call, emit `ThoughtUpdate`
- Modified: `agent/AgentEventDispatcher.kt` — add `emitThoughtUpdate()` helper

**Thought extraction logic** (in `AgentTurnRunner`, after tool selection):
```kotlin
val selectedToolCall = policy.select(toolCalls)
val agentThought = selectedToolCall?.arguments
    ?.optString("agent_thought", "")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

if (agentThought != null) {
    dispatcher.emitThoughtUpdate(sanitizeThought(agentThought))
}
```

**Sanitizer** (pure function, no state):
```kotlin
fun sanitizeThought(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.length > 40) trimmed.take(40) + "..." else trimmed
}
```

### Phase 2: Capsule UI Rebuild

**Files changed:**
- Rewrite: `ui/overlay/SmartCapsuleLayoutBuilder.kt` — two-row layout
- Rewrite: `ui/overlay/SmartCapsuleManager.kt` — CapsuleMode-driven

**Layout structure:**
```
┌──────────────────────────────────────────────────────┐
│  ● [thought text]                           [nav] │  ← Row 1
├──────────────────────────────────────────────────────┤
│  [补充]           [接管]                    [停止]  │  ← Row 2
└──────────────────────────────────────────────────────┘
```

**SmartCapsuleManager redesign:**

The manager holds a `MutableStateFlow<CapsuleMode>` and renders based on mode:

```kotlin
class SmartCapsuleManager(private val service: AccessibilityService) {
    private val _mode = MutableStateFlow<CapsuleMode>(CapsuleMode.Hidden)
    val mode: StateFlow<CapsuleMode> = _mode.asStateFlow()

    // Public API — called by ServiceOverlayController
    fun updateMode(mode: CapsuleMode)
    fun updateThought(thought: String)  // convenience: Running(thought)
    fun show()
    fun hide()
    fun dispose()

    // Callbacks — wired by ServiceOverlayController
    var onTakeover: (() -> Unit)? = null
    var onResume: (() -> Unit)? = null
    var onSupplement: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null

    // Internal — render CapsuleMode to Views
    private fun render(mode: CapsuleMode)
}
```

**Key rendering rules per mode:**

| CapsuleMode | Row 1 | Row 2 |
|-------------|-------|-------|
| Running(thought) | Blue pulsing dot + thought | [补充] [接管] [停止] |
| Done(message) | Teal dot + "✓ " + message | (hidden) |
| Error(message) | Red dot + "⚠ " + message | [关闭] |
| Hidden | — | — |

Other modes (Takeover, WaitingFor*, SupplementInput) are defined in CapsuleMode but rendered in Stage 2/3.

**SmartCapsuleLayoutBuilder redesign:**

```kotlin
object SmartCapsuleLayoutBuilder {
    fun buildCapsuleView(context: Context): CapsuleViews
    fun buildWindowParams(): WindowManager.LayoutParams
}

data class CapsuleViews(
    val container: ViewGroup,
    val row1: ViewGroup,
    val statusDot: View,
    val thoughtText: TextView,
    val navContainer: ViewGroup,  // for [1][2][3] buttons later
    val row2: ViewGroup,
    val supplementButton: View,
    val primaryButton: View,    // 接管/继续/发送/完成 (changes per mode)
    val primaryButtonText: TextView,
    val stopButton: View,
)
```

### Phase 3: Thought Pipeline End-to-End

**Files changed:**
- Modified: `app/ServiceOverlayController.kt` — route `ThoughtUpdate` to capsule
- Modified: `app/AgentService.kt` — handle `ThoughtUpdate` event in `handleEvent()`

**Event flow:**
```
AgentTurnRunner extracts agent_thought
  → emits AgentEvent.ThoughtUpdate(thought)
  → AgentService.handleEvent() routes to ServiceOverlayController
  → ServiceOverlayController calls capsuleManager.updateThought(thought)
  → SmartCapsuleManager sets mode = Running(thought) and renders
```

**Turn lifecycle mapping:**
```
TurnStarted       → capsule shows "思考中..." (Running("思考中..."))
ThoughtUpdate      → capsule shows agent_thought (Running(thought))
ActionExecuted     → thought stays (no change)
TurnCompleted      → thought stays until next TurnStarted
TaskCompleted      → Done(result) or Error(message)
```

### Phase 4: StatusIsland Integration

**Files changed:**
- Modified: `ui/overlay/StatusIslandManager.kt` — show thought text instead of generic status
- Modified: `app/ServiceOverlayController.kt` — route ThoughtUpdate to StatusIsland in VD mode

The StatusIsland already shows truncated text. Wire it to show the same thought.

---

## Testing

- Unit test `sanitizeThought()` — edge cases (empty, long, whitespace, CJK characters)
- Unit test `CapsuleMode` sealed class construction
- Integration: verify ThoughtUpdate event is emitted with correct thought from tool calls
- Visual: run `debug-run.sh` and verify capsule shows thought text in Running state

---

## Dependencies

- None. This stage is self-contained.
- Does NOT require changes to the agent's tool schemas (agent_thought already exists)
- Does NOT require changes to LLM prompts (agent_thought instruction already in prompts)

---

## Files Summary

| Action | File | Description |
|--------|------|-------------|
| New | `ui/overlay/model/CapsuleMode.kt` | Sealed class for capsule state |
| Modify | `protocol/AgentEvent.kt` | Add ThoughtUpdate event |
| Modify | `agent/AgentEventDispatcher.kt` | Add emitThoughtUpdate helper |
| Modify | `agent/AgentTurnRunner.kt` | Extract agent_thought, emit event |
| Rewrite | `ui/overlay/SmartCapsuleManager.kt` | CapsuleMode-driven manager |
| Rewrite | `ui/overlay/SmartCapsuleLayoutBuilder.kt` | Two-row layout builder |
| Modify | `app/ServiceOverlayController.kt` | Route ThoughtUpdate |
| Modify | `app/AgentService.kt` | Handle ThoughtUpdate in event loop |
| Modify | `ui/overlay/StatusIslandManager.kt` | Show thought in VD mode |
