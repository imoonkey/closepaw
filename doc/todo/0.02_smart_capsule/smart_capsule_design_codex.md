# Smart Capsule 2.0 Design (Codex)

## 0. Scope

This doc defines a clean rewrite of Smart Capsule interaction for:

1. One-line `agent thought` in overlay.
2. `接管/继续` takeover semantics with correct cancellation.
3. `补充` text injection during task execution.
4. `ask_user` tools for explicit human assistance.

Reference inputs used:
- `doc/todo/0.02_smart_capsule/qi_note.md`
- screenshot references in `doc/todo/0.02_smart_capsule/`:
  - `two_row_ui.png`
  - `补充button.png`
  - `ask_user_for_info.png`

No backward compatibility constraints. Prefer clean replacement over patch layering.

---

## 0.1 Visual Requirements Extracted From The 3 Screenshots

The screenshots are not decorative references. They define three concrete interaction modes.

1. **Normal control capsule** (third screenshot).
- Bottom sheet style capsule.
- Top line shows one-line thought, for example `记录包臀裙信息继续滑动`.
- Bottom row buttons are ordered: `补充` | `接管` | `停止`.

2. **Manual operation mode** (second screenshot).
- Capsule enters a compact panel with title `操作手机`.
- Main content asks for user supplement text (`请补充你的想法`).
- User can type quickly from panel without returning to full app.

3. **Ask-user answer mode** (first screenshot).
- Stronger waiting state: title `等待答复`.
- Large question area and a primary confirmation CTA (`告诉豆包` style).
- Stop remains available; supplement entry is constrained by current ask flow.

These 3 modes become first-class states in the design below.

---

## 1. The Problem (Current Gaps)

Current code is close, but not this feature.

1. Overlay text source is wrong.
- `SmartCapsuleManager` displays streaming `MessageDelta` text (`ui/overlay/SmartCapsuleManager.kt`).
- Requirement is to display concise, user-facing `agent thought` for current action.

2. Pause semantics are incomplete for takeover.
- `Op.Pause` flips session to paused (`session/AgentSession.kt`, `agent/Agent.kt`), but tool queue semantics are not explicit.
- Requirement: when user resumes, unfinished pre-takeover tool calls must be cancelled, not resumed.

3. Supplement input is blocked by session state.
- `Op.UserInput` is rejected in `Running` / `Paused` (`session/AgentSession.kt`).
- Requirement: `补充` inserts one user message during active task.

4. No ask-user tool contract.
- Tool layer has no dedicated `ask_user` type (`tool/ToolName.kt`, `tool/impl/*`).
- Requirement: LLM can explicitly pause and ask user for input/action.

5. UI layout does not match target interaction.
- Current capsule is single-row + icon buttons (`ui/overlay/SmartCapsuleLayoutBuilder.kt`).
- Requirement is two-row model: thought + CTA row (`接管/继续`, `补充`, `停止`).

---

## 2. Design Principles

1. KISS.
- One straightforward control path for all human handoff cases.
- No nested state machines inside state machines.

2. Readability first.
- Small data classes, explicit names, minimal implicit behavior.

3. No legacy burden.
- Remove/replace outdated behavior directly.
- Do not keep dead compatibility branches.

4. Align with current architecture.
- Keep `Op -> AgentSession -> AgentEvent` flow.
- Keep `ToolSpec + ToolRouter` pattern.

---

## 3. Proposed Runtime Model

### 3.1 Session State

Replace generic `Paused` semantics with explicit handoff semantics.

```kotlin
sealed interface SessionState {
    data object Created : SessionState
    data object Running : SessionState
    data class Handoff(val reason: HandoffReason) : SessionState
    data object Idle : SessionState
    data object Shutdown : SessionState
}

enum class HandoffReason {
    USER_TAKEOVER,
    ASK_USER_INPUT,
    ASK_USER_ACTION
}
```

Why: this removes ambiguity. "Paused" is overloaded today.

### 3.2 Operations

Use explicit operations, not overloaded meanings.

```kotlin
sealed interface Op {
    data class UserInput(val text: String) : Op         // new task or supplement
    data object Takeover : Op                           // 接管
    data object Continue : Op                           // 继续
    data object StopTask : Op                           // 停止
    data class ResolveUserRequest(
        val requestId: String,
        val responseText: String? = null
    ) : Op
}
```

Notes:
- `UserInput` is always valid except `Shutdown`.
- In `Running`/`Handoff`, `UserInput` is treated as supplement input.
- `Op.Start` and `Op.Interrupt` should be deprecated and removed.

### 3.3 Events

Add explicit thought and user-request events.

```kotlin
data class AgentThoughtUpdated(
    val taskId: String,
    val thought: String,
    val source: ThoughtSource
) : AgentEvent

data class UserRequestStarted(
    val requestId: String,
    val kind: UserRequestKind,
    val prompt: String
) : AgentEvent

data class UserRequestResolved(
    val requestId: String,
    val responseText: String?
) : AgentEvent
```

---

## 4. Thought Pipeline (Prompt -> Tool Args -> Overlay)

### 4.1 Contract

All actionable tools must accept `agent_thought`.
- Already present in many tools (`mobile_action`, `wait`, `open_app`, `system_button`, `write_todos`, `scratchpad`, `delegate_task`).
- Keep it optional in schema, required by prompt policy.

### 4.2 Prompt Rule

In all agent system prompts:

- Tell model `agent_thought` is user-visible.
- Require one-line, short, concrete reason.
- Prohibit chain-of-thought and long prose.

Example requirement text:

`When calling tools, include agent_thought. It is shown directly to users in one line (<= 24 Chinese chars or <= 48 ASCII chars).`

### 4.3 Sanitization in Runtime

Add `AgentThoughtSanitizer`:
- Trim whitespace.
- Replace newlines with spaces.
- Collapse multiple spaces.
- Truncate hard limit with ellipsis.

Even if prompt fails, UI still stays clean.

### 4.4 Display Source Priority

Overlay thought priority:
1. `agent_thought` from latest selected tool call.
2. If missing, fallback to formatted action description.
3. If no action yet, fallback to short status.

Do not stream full assistant prose into capsule.

---

## 5. Two-Row Capsule UI

### 5.1 Layout

Top row:
- One-line thought text.

Bottom row:
- Three text CTAs in fixed order:
  - Left: `补充`
  - Middle: `接管` / `继续` (state-dependent)
  - Right: `停止`

Remove "Open App" from capsule primary CTA row.

### 5.2 States

1. Running (normal control capsule, screenshot #3).
- Middle button label: `接管`.
- Thought: current `agent_thought`.

2. Handoff(USER_TAKEOVER) (manual operation mode, screenshot #2).
- Panel title: `操作手机`.
- Middle button label: `继续`.
- Body prompt: `请补充你的想法` (fast input entry point).

3. Handoff(ASK_USER_INPUT) (waiting answer mode, screenshot #1).
- Panel title: `等待答复`.
- Show question body from tool payload.
- Show primary submit CTA for direct answer.
- Middle button is `继续` but disabled before answer submission.

4. Handoff(ASK_USER_ACTION).
- Panel title: `操作手机`.
- Show instruction body from tool payload.
- Middle button label: `继续` (enabled; user confirms done manually).

### 5.3 Supplement Dialog

Tap `补充` -> lightweight input surface.

Submit behavior:
- Emits `Op.UserInput(text)`.
- Inserts one user message into history immediately.
- If state is `Handoff(ASK_USER_INPUT)`, also resolves pending request and auto-continues.

UI gating:
- In waiting-answer mode, `补充` should route to the same pending request instead of creating parallel flows.

---

## 6. Correct Takeover Semantics

### 6.1 Rule

`接管` means: user owns the phone now. Agent must stop issuing new actions.

### 6.2 Cancellation Behavior

On `Op.Takeover`:
1. Session -> `Handoff(USER_TAKEOVER)`.
2. Mark turn as paused.
3. Cancel pending approvals / pending user waits via `toolRouter.cancelAll()`.
4. End current action batch early: remaining not-yet-started tool calls are dropped.

On `Op.Continue`:
1. Session -> `Running`.
2. Start next turn from fresh `captureScreen()`.
3. Never continue old unstarted tool queue.

### 6.3 Turn Runner Rule

In `AgentTurnRunner.executeActions(...)`, before each tool call:
- If state is handoff, stop iterating and exit action phase.

This single check guarantees "no stale queued tools after takeover".

---

## 7. `ask_user` Tool Design

Split into two tools for readability and strict schemas.

### 7.1 Tool A: `ask_user_for_input`

Purpose:
- Ask user a question and wait for text input.

Schema:
- `question: string` (required)
- `agent_thought: string` (optional)

Result:
- `{"response":"..."}`

UI mapping:
- Drives screenshot #1 style waiting-answer panel.
- Requires explicit submit action before continuation.

### 7.2 Tool B: `ask_user_to_operate`

Purpose:
- Ask user to do manual phone action (login, permission grant, captcha).

Schema:
- `instruction: string` (required)
- `success_hint: string` (optional)
- `agent_thought: string` (optional)

Result:
- `{"done": true, "note": "optional"}`

UI mapping:
- Drives screenshot #2 style operation panel.
- User can perform phone action and then tap `继续`.

### 7.3 Runtime Handling

Both tools:
1. Emit `UserRequestStarted`.
2. Session enters `Handoff` with corresponding reason.
3. Tool call waits on deferred resolution from UI op.
4. On resolve, emit `UserRequestResolved`, return success, continue next turn.

This mirrors existing approval wait pattern in `ToolRouter`, but for richer user interaction.

Hard rule:
- Only one pending user request per session. Reject or cancel any second ask-user call.

---

## 8. File-Level Change Plan

### 8.1 Protocol / Session

- `protocol/SessionState.kt`
  - Replace `Paused` with `Handoff(HandoffReason)`.
- `protocol/Op.kt`
  - Introduce `Takeover`, `Continue`, `StopTask`, `ResolveUserRequest`.
  - Remove deprecated `Start` and `Interrupt`.
- `protocol/AgentEvent.kt`
  - Add `AgentThoughtUpdated`, `UserRequestStarted`, `UserRequestResolved`.
- `session/AgentSession.kt`
  - Accept `UserInput` while running/handoff as supplement.
  - Route takeover/continue semantics.

### 8.2 Agent Loop

- `agent/Agent.kt`
  - Rename pause logic to handoff-aware gating.
- `agent/AgentTurnRunner.kt`
  - Emit thought events from selected tool calls.
  - Stop action-loop early when entering handoff.

### 8.3 Tools

- `tool/ToolName.kt`
  - Add `AskUserForInput`, `AskUserToOperate`.
- `tool/impl/AskUserForInputTool.kt` (new)
- `tool/impl/AskUserToOperateTool.kt` (new)
- `session/SessionServices.kt`
  - Register new tools.
- `tool/ToolRouter.kt`
  - Add deferred request waiting path for ask-user tools.

### 8.4 Overlay/UI

- `ui/overlay/SmartCapsuleLayoutBuilder.kt`
  - Rewrite as two-row layout with text CTAs.
- `ui/overlay/SmartCapsuleManager.kt`
  - Consume `AgentThoughtUpdated` instead of raw streaming text for primary line.
  - Add supplement dialog trigger callback.
- `app/ServiceOverlayController.kt`
  - Wire new ops/events for takeover/continue/supplement/user-request state.

### 8.5 Chat

- `ui/chat/components/InputDock.kt`
  - Optional: allow sending while working as supplement (or keep only overlay supplement button).
- `ui/chat/ChatViewModel.kt`
  - Handle supplement/user-request events if surfaced in chat.

---

## 9. Deprecation / Cleanup

Remove old behavior directly:

1. `Op.Start` compatibility mapping.
2. `Op.Interrupt` stop semantics in UI path.
3. Capsule "Open App" button from primary interaction row.
4. Overlay reliance on raw `MessageDelta` as thought display.
5. Any pause-specific naming that no longer reflects takeover semantics.

---

## 10. Test Plan (Must-Have)

### 10.1 Unit Tests

1. Session handoff transitions.
- Running -> Handoff(USER_TAKEOVER) -> Running.

2. Supplement acceptance.
- `UserInput` in Running/Handoff is accepted and appended.

3. Ask-user deferred flow.
- Tool waits, resolves, returns structured output.

4. Tool queue cancellation.
- On takeover, remaining unstarted tool calls are dropped.

5. Thought sanitizer.
- Multiline/long thought becomes single-line bounded text.

### 10.2 Integration Tests

1. Manual takeover scenario.
- Agent starts action batch, user taps `接管`, user操作, taps `继续`, next turn re-captures screen and replans.

2. ask_user input scenario.
- Agent calls `ask_user_for_input`, UI enters `等待答复` panel, user submits, tool returns response, agent continues.

3. ask_user action scenario.
- Agent calls `ask_user_to_operate`, UI enters `操作手机` panel, user performs action, taps继续, agent continues with fresh screen.

---

## 11. Why This Is the Right Cut

- It solves exactly the four requested behaviors.
- It stays aligned with existing architecture (`Op/Event`, `ToolRouter`, `SmartCapsuleManager`).
- It removes ambiguous pause semantics instead of stacking hacks.
- It keeps code readable: explicit states, explicit ops, explicit events.

No magic. No hidden branches. Clear ownership boundaries.
