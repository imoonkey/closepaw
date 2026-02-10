# Smart Capsule V2

## The Problem

The current overlay capsule is a dumb status bar. It shows text and has pause/stop/open-app buttons. The user has no idea what the agent is thinking, can't talk to it mid-task, and when the agent needs user input (login, disambiguation, permission), it just fails.

The agent-human interaction model is broken. It's one-directional: user gives goal, agent runs, agent finishes. Real collaboration requires a two-way channel that works while the agent is operating.

## The Solution

Four changes. Each one is small. Together they make the agent actually usable.

1. **Agent thought** — show the user what the agent is doing, in one line
2. **Takeover** — let the user grab the phone, do something, hand back
3. **Supplement** — let the user inject a message while the agent runs
4. **ask_user tool** — let the agent ask the user for info or action

---

## 1. Agent Thought

### What

The LLM already emits text alongside tool calls. We use that text as "agent thought" — a one-line status shown on the capsule overlay.

### Why this is simple

We already have:
- `MessageDelta` events streaming LLM text to the capsule
- `SmartCapsuleManager.onMessageDelta()` displaying it
- The LLM's `textContent` in responses (non-tool-call text)

All we need is a prompt change. Tell the LLM:

> Before calling tools, emit a brief thought (one short sentence) explaining what you're about to do. This is shown to the user. Write in the user's language.

That's it. The existing `MessageDelta` pipeline carries the thought to the capsule. No new events, no new plumbing.

### What to delete

Remove `agent_thought` from individual tool parameters (`mobile_action`, `open_app`, etc.). It was a bad idea — scattering display text inside tool arguments. One thought per turn via text output is cleaner. One source of truth.

### Prompt addition (all agent defs)

```
## Agent Thought
Before calling tools, write ONE short sentence explaining your next action.
This sentence is shown to the user on their screen as a status line.
Keep it under 40 characters. Write in the same language as the user's goal.
Do not write anything else besides this thought — no markdown, no explanation.
Examples: "打开淘宝搜索" / "Scrolling for prices" / "点击第一个结果"
```

---

## 2. Smart Capsule V2 (UI Redesign)

### Current layout (one row)

```
[●] [status text               ] [⏸] [⏹] [↗]
```

### New layout (two rows)

```
┌─────────────────────────────────────────────┐
│  打开淘宝搜索包臀裙                            │  ← agent thought
├─────────────────────────────────────────────┤
│  [💬 补充]  [🖐 接管]              [⏹ 停止]  │  ← action buttons
└─────────────────────────────────────────────┘
```

Drop the "Open App" button. Users know how to switch apps. Three buttons is enough. Don't clutter.

### Capsule states

The capsule has four visual states. Each is just a different configuration of the same two-row layout.

#### State 1: Running (normal)

```
┌─────────────────────────────────────────┐
│  [agent thought text, single line]      │
├─────────────────────────────────────────┤
│  [补充]  [接管]                  [停止]  │
└─────────────────────────────────────────┘
```

Thought text updates on every `MessageDelta`. Status dot pulses blue.

#### State 2: Takeover (paused)

```
┌─────────────────────────────────────────┐
│  [last thought, dimmed]                 │
├─────────────────────────────────────────┤
│  [补充]  [▶ 继续]                [停止]  │
└─────────────────────────────────────────┘
```

User has taken over. [接管] flips to [继续]. Status dot amber.

#### State 3: Input mode (supplement or ask_user answer)

```
┌─────────────────────────────────────────┐
│  [prompt label]                    [✕]  │
├─────────────────────────────────────────┤
│  [text input field          ] [发送 →]  │
└─────────────────────────────────────────┘
```

Prompt label:
- Supplement: "补充你的想法"
- ask_user: the agent's question

[✕] cancels input mode, returns to previous state.
Overlay temporarily drops `FLAG_NOT_FOCUSABLE` for keyboard input.

#### State 4: Action request (ask_user type=action)

```
┌─────────────────────────────────────────┐
│  [agent's instruction text]             │
├─────────────────────────────────────────┤
│              [✅ 完成]           [停止]  │
└─────────────────────────────────────────┘
```

User performs action on phone, taps [完成] when done.

### Data model

```kotlin
sealed interface CapsuleMode {
    data class Running(val thought: String) : CapsuleMode
    data class Paused(val thought: String) : CapsuleMode
    data class TextInput(val prompt: String, val callId: String?) : CapsuleMode
    data class ActionWait(val instruction: String, val callId: String) : CapsuleMode
}
```

`callId` is null for user-initiated supplement, non-null for agent-initiated ask_user. This distinction tells the capsule how to route the response: supplement → `Op.Supplement`, ask_user → `Op.UserResponse`.

### Layout implementation

Replace `SmartCapsuleLayoutBuilder` internals. Keep the class, rewrite `build()`.

The card becomes a vertical LinearLayout with two rows instead of one horizontal row. Row 1 is a full-width TextView. Row 2 is a horizontal LinearLayout with buttons. Mode changes swap the content of these rows.

No nested Compose. No complex view hierarchies. Just TextViews, Buttons, and an EditText for input mode. The overlay is a plain Android View — Compose overlay is overkill and creates accessibility service conflicts.

### Keyboard handling for input mode

When entering input mode:
1. Switch row 2 content to EditText + send button
2. Update window params: remove `FLAG_NOT_FOCUSABLE`
3. `windowManager.updateViewLayout(overlayView, updatedParams)`
4. `editText.requestFocus()` + show soft keyboard

When exiting input mode:
1. Restore row 2 to buttons
2. Add `FLAG_NOT_FOCUSABLE` back
3. `windowManager.updateViewLayout(overlayView, restoredParams)`

---

## 3. Takeover (replacing Pause)

### Current behavior

Pause is cooperative. `Agent.pause()` sets `pauseState = true`. The agent loop checks at the top of each turn:

```kotlin
while (shouldContinue()) {
    if (pauseState.value) {        // blocks here
        pauseState.first { !it }   // waits for resume
    }
    // perception → planning → action (new turn)
}
```

Resume sets `pauseState = false`. Loop continues with a **new turn** that starts with fresh screen perception.

### Why this already does what we need

The qi_note says: "resume时, 接管前未完成的tool_call应该被取消而不是继续执行, agent应该capture screen状态, 发给LLM来决定下一步。"

This is already what happens. Pause triggers between turns. The current turn's tool calls are complete. Resume starts a new turn with `capturePreTurnSnapshot()`. The agent perceives the new screen state (including whatever the user changed during takeover) and plans fresh.

### What changes

Nothing in `Agent.kt`, `AgentSession.kt`, or the session state machine.

`Op.Pause` and `Op.Resume` stay as-is. The UI labels them "接管" and "继续". Renaming the Op would be cosmetic noise — the protocol-level semantics (pause/resume) are correct.

### One edge case: mid-turn pause

If the user taps [接管] while the agent is mid-turn (e.g., LLM streaming or tool executing), the pause only takes effect after the turn completes. This is by design — we don't want to interrupt an in-flight API call or half-executed gesture.

The UI should indicate "pausing after current action..." during this brief window.

---

## 4. Supplement (mid-task user input)

### What

User taps [补充], types a message, sends. This injects a user message into the agent's conversation history. The agent sees it on its next turn.

### Protocol

New Op:
```kotlin
data class Supplement(val text: String) : Op
```

### Session handling

```kotlin
// AgentSession
private suspend fun handleSupplement(op: Op.Supplement) {
    if (_state.value != SessionState.Running && _state.value != SessionState.Paused) {
        return  // ignore when not active
    }
    services.historyManager.addItem(
        ResponseItem.Message(role = "user", content = op.text)
    )
    emit(AgentEvent.StatusUpdate(sessionId, now(), "💬 Supplement received"))
}
```

That's the entire implementation. Add item to history. Agent reads it on next turn. No state transitions, no new events, no complex routing.

### UI flow

1. User taps [补充] on capsule
2. Capsule enters input mode (`CapsuleMode.TextInput(prompt = "补充你的想法", callId = null)`)
3. User types and taps [发送]
4. Capsule callback fires: `onSupplement(text)`
5. `AgentService` submits `Op.Supplement(text)` to session
6. Capsule returns to Running/Paused mode

---

## 5. ask_user Tool

### What

The agent can call `ask_user` to pause and wait for user input. Two types:
- **question**: agent asks a question, waits for text answer
- **action**: agent asks user to do something on the phone, waits for confirmation

### Tool definition

```kotlin
data object AskUser : ToolName(
    raw = "ask_user",
    canonical = "ask_user",
    displayName = "Ask user"
)
```

One tool, not two. The `type` parameter controls UI treatment. The mechanism is identical: pause, show message, wait for response.

### Tool spec

```
name: "ask_user"
description: "Pause execution and ask the user for information or to perform an action."
parameters:
  message: string, required — the question or instruction to show the user
  type: enum ["question", "action"], required
    - "question": ask user a question, wait for their text answer
    - "action": ask user to perform something on the phone, wait for confirmation
```

`isScreenChanging = false` — this tool doesn't change the screen. It changes the interaction mode.

### Execution flow

```
Agent calls ask_user("请登录您的账户", type="action")
    │
    ▼
ToolRouter.execute()
    │
    ├─ Tool validation passes
    ├─ Policy check: ALLOW (ask_user is always allowed)
    ├─ AskUserInvocation.execute() returns AskUserRequest marker
    │
    ▼
ToolRouter detects AskUserRequest
    │
    ├─ Creates CompletableDeferred<String>
    ├─ Stores in pendingUserInputs[callId]
    ├─ Calls onUserInputRequired callback
    │     └─ AgentTurnRunner emits AgentEvent.UserInputRequested
    │
    ├─ Awaits deferred (timeout: 5 minutes)
    │
    │   ... user sees question on capsule UI ...
    │   ... user types/confirms response ...
    │   ... AgentSession receives Op.UserResponse ...
    │   ... calls toolRouter.resolveUserInput(callId, text) ...
    │
    ├─ Deferred completes with user's response
    │
    ▼
ToolRouter returns ToolCallResult.Success(output = user's response)
    │
    ▼
Agent continues to next turn
```

### Protocol additions

New Op:
```kotlin
data class UserResponse(val callId: String, val text: String) : Op
```

New Event:
```kotlin
data class UserInputRequested(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val callId: String,
    val message: String,
    val type: UserInputType
) : AgentEvent
```

New enum:
```kotlin
enum class UserInputType { QUESTION, ACTION }
```

### ToolRouter changes

Add parallel to existing approval mechanism:

```kotlin
// ToolRouter
private val pendingUserInputs = ConcurrentHashMap<String, CompletableDeferred<String>>()

fun resolveUserInput(callId: String, response: String) {
    pendingUserInputs.remove(callId)?.complete(response)
}
```

This follows the exact same pattern as `pendingApprovals` / `resolveApproval`. No new abstraction. Copy the pattern. It works.

### AgentSession handling

```kotlin
// In submit():
is Op.UserResponse -> handleUserResponse(op)

private suspend fun handleUserResponse(op: Op.UserResponse) {
    services.toolRouter.resolveUserInput(op.callId, op.text)
}
```

### Prompt addition

Add to system prompt tool documentation:

```
- ask_user(message, type): Pause and wait for the user.
  - type="question": Ask user a question. Returns their text answer.
  - type="action": Ask user to do something (login, grant permission, etc). Returns "done".
  Use when: login required, user preference needed, permission prompt, ambiguous choice.
  Do NOT use for things you can figure out from the screen.
```

### What ask_user is NOT

- It is NOT a general-purpose "I'm stuck" tool. The agent should try before asking.
- It is NOT for progress updates. That's what agent thought is for.
- The LLM prompt should make this clear.

---

## 6. Protocol Summary

### New Ops

| Op | When | Handler |
|---|---|---|
| `Supplement(text)` | User sends mid-task message | Add to history |
| `UserResponse(callId, text)` | User responds to ask_user | Resolve tool deferred |

### New Events

| Event | When | Consumer |
|---|---|---|
| `UserInputRequested(callId, message, type)` | Agent calls ask_user | Capsule shows input UI |

### Removed

| Item | Reason |
|---|---|
| `Op.Start` | Deprecated. Only `Op.UserInput` exists now. |

### Unchanged

| Item | Reason |
|---|---|
| `Op.Pause` / `Op.Resume` | Semantics are correct. UI labels as 接管/继续. |
| `SessionState` | No new states needed. ask_user blocks inside Running. |
| `Agent.pause()` / `Agent.resume()` | Already does takeover semantics correctly. |

---

## 7. ToolName Registry Update

```kotlin
sealed class ToolName(...) {
    // ... existing tools ...

    data object AskUser : ToolName(
        raw = "ask_user",
        canonical = "ask_user",
        displayName = "Ask user"
    )

    val isScreenChanging: Boolean
        get() = when (this) {
            MobileAction, OpenApp, Wait, SystemButton, DelegateTask -> true
            CompleteTask, WriteTodos, Scratchpad, AskUser -> false
            is Unknown -> true
        }
}
```

Add `AskUser` to allowed tools in all agent defs (Standalone, Executor, Planner).

---

## 8. What to Delete

Deletion is a feature. Less code = fewer bugs = easier to read.

| Delete | Why |
|---|---|
| `agent_thought` param from `MobileActionTool`, `OpenAppTool`, etc. | Replaced by per-turn LLM text output |
| `agent_thought` handling in `MobileActionInvocation.getDescription()` | Same |
| `Op.Start` | Dead code. `Op.UserInput` replaced it ages ago. |
| `SmartCapsuleManager.updateStatus()` | Legacy method. New capsule uses thought text from MessageDelta. |
| "Open App" button from capsule | Unnecessary. Users can switch apps via recents. |
| `StatusUtils` usage in capsule | No longer parsing emoji status strings. Capsule has explicit state model. |

---

## 9. SmartCapsuleManager Rewrite

The current `SmartCapsuleManager` is 287 lines of mixed layout, state, and animation. The `SmartCapsuleLayoutBuilder` is another 195 lines of manual View construction.

### New structure

```
SmartCapsuleManager.kt     — lifecycle (show/hide/destroy), event routing
SmartCapsuleLayout.kt      — view construction, mode switching
CapsuleMode.kt             — sealed interface for capsule states
```

~300 lines total. The current code is ~480 lines total across two files, so this is a net reduction.

### SmartCapsuleManager responsibilities

```kotlin
class SmartCapsuleManager(
    private val context: AccessibilityService,
    private val windowManager: WindowManager,
    private val onStop: () -> Unit,
    private val onPauseToggle: () -> Unit,      // pause or resume
    private val onSupplement: (String) -> Unit,  // user typed supplement
    private val onUserResponse: (callId: String, text: String) -> Unit  // ask_user response
) {
    private var mode: CapsuleMode = CapsuleMode.Running("")

    fun show() { ... }
    fun hide() { ... }

    // Events from agent
    fun onThoughtDelta(turnId: String, delta: String) { ... }  // was: onMessageDelta
    fun onActionExecuted(toolName: String, success: Boolean) { ... }
    fun onTaskCompleted() { ... }
    fun onError(message: String) { ... }
    fun onPauseStateChanged(paused: Boolean) { ... }
    fun onUserInputRequested(callId: String, message: String, type: UserInputType) { ... }
}
```

Clean API surface. Each method maps to exactly one event type. No god-methods, no legacy compat.

---

## 10. File Changes Summary

### New files

| File | What |
|---|---|
| `tool/impl/AskUserTool.kt` | Tool spec + invocation |
| `ui/overlay/CapsuleMode.kt` | Sealed interface for capsule states |

### Modified files

| File | What |
|---|---|
| `protocol/Op.kt` | Add `Supplement`, `UserResponse`. Remove `Start`. |
| `protocol/AgentEvent.kt` | Add `UserInputRequested`. |
| `tool/ToolName.kt` | Add `AskUser`. |
| `tool/ToolRouter.kt` | Add `pendingUserInputs` + `resolveUserInput()`. Add `onUserInputRequired` callback to `execute()`. |
| `session/AgentSession.kt` | Handle `Supplement`, `UserResponse` Ops. |
| `session/SessionServices.kt` | Register `AskUserTool`. |
| `agent/definition/StandaloneAgentDef.kt` | Prompt update + add ask_user to allowed tools. |
| `agent/definition/ExecutorAgentDef.kt` | Prompt update + add ask_user to allowed tools. |
| `agent/definition/PlannerAgentDef.kt` | Prompt update + add ask_user to allowed tools. |
| `agent/AgentTurnRunner.kt` | Wire `onUserInputRequired` callback when calling ToolRouter. |
| `ui/overlay/SmartCapsuleManager.kt` | Rewrite. New mode-based state, two-row layout. |
| `ui/overlay/SmartCapsuleLayoutBuilder.kt` | Rewrite. Two-row layout builder with mode switching. |
| `app/ServiceOverlayController.kt` | Wire new capsule callbacks. Handle `UserInputRequested`. |
| `app/AgentService.kt` | Route new events to overlay. Route new Ops from overlay. |
| `tool/impl/MobileActionTool.kt` | Remove `agent_thought` parameter. |
| `tool/impl/MobileActionInvocation.kt` | Remove `agent_thought` from `getDescription()`. |
| `tool/impl/OpenAppTool.kt` | Remove `agent_thought` parameter if present. |

### Deleted code

| Location | What |
|---|---|
| `Op.Start` in `Op.kt` | Dead deprecated class. |
| `agent_thought` param in tool schemas | Parameter definition + parsing + usage. |
| `SmartCapsuleManager.updateStatus()` | Legacy status method. |
| `StatusUtils` references in capsule | No longer needed. |

---

## 11. Implementation Order

Do it in this order. Each step is independently shippable and testable.

### Phase 1: Agent thought (prompt only)
1. Update all system prompts with agent thought instruction
2. Remove `agent_thought` from tool params
3. Verify: agent emits thought text, capsule shows it via existing MessageDelta path

### Phase 2: Capsule layout redesign
1. Create `CapsuleMode` sealed interface
2. Rewrite `SmartCapsuleLayoutBuilder` for two-row layout
3. Rewrite `SmartCapsuleManager` with mode-based state
4. Wire mode transitions to existing events
5. Verify: capsule shows thought + buttons in two rows

### Phase 3: Takeover
1. Rename UI labels: [⏸] → [接管], [▶] → [继续]
2. Verify: takeover + resume works (it should already — just a label change)

### Phase 4: Supplement
1. Add `Op.Supplement` to protocol
2. Handle in `AgentSession.handleSupplement()`
3. Add input mode to capsule (keyboard handling)
4. Wire [补充] button → input mode → `Op.Supplement`
5. Verify: supplement message appears in agent's next turn context

### Phase 5: ask_user tool
1. Add `UserInputType`, `AgentEvent.UserInputRequested`, `Op.UserResponse` to protocol
2. Create `AskUserTool` (spec + invocation)
3. Add `pendingUserInputs` to ToolRouter
4. Register tool, add to agent defs
5. Wire `UserInputRequested` → capsule → `Op.UserResponse`
6. Verify: agent can ask question, receive answer, continue

---

## 12. What This Design Does NOT Do

- **No multi-modal capsule** (voice, images). Text only. Add later if needed.
- **No capsule drag/repositioning**. It stays at the bottom. Don't over-engineer.
- **No agent thought history in overlay**. Shows latest thought only. Full history is in the app.
- **No supplement during ask_user**. One input mode at a time. Keep it simple.
- **No smart timeout for ask_user**. Fixed 5-minute timeout. If it's a problem, tune it later.
- **No new session states**. `Running` covers everything. The blocked-on-ask_user state is implicit.

---

## Design Principles Applied

1. **Use what exists.** Agent thought uses the existing `MessageDelta` pipeline. Takeover uses existing `pause/resume`. No new plumbing where existing plumbing works.

2. **One mechanism per concern.** Thought = LLM text output. Not scattered across tool params. Supplement = inject into history. Not a new event/state dance.

3. **Parallel patterns.** ask_user follows the exact same deferred+callback pattern as the existing approval flow. Don't invent new concurrency patterns.

4. **Delete more than you add.** Removing `agent_thought` from tools, removing `Op.Start`, removing the Open App button, removing `StatusUtils` from capsule. The codebase gets smaller and more focused.

5. **Flat over nested.** Capsule has four modes, not a tree of states. Each mode is a flat data class. The UI switches between them. No state machine inside a state machine.
