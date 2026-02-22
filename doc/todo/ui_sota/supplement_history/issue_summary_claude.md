# Supplement History — Debug Run Issue Analysis

**Run**: `debug-output/run_20260221_183205`
**Model**: `qwen/qwen3.5-plus-02-15`
**Task**: "open youtube and play a 容祖儿 song"
**User supplement**: "改成听陈奕迅" (injected during Takeover at 18:33:47)
**Outcome**: Agent played 容祖儿 instead of 陈奕迅 — GOAL_ACHIEVED but wrong artist

---

## Issue 1: Suggestion click ineffective (Action execution — P1)

### Symptom

After typing "陈奕迅" into YouTube's search field, the agent clicked the "陈奕迅" autocomplete suggestion twice (turns 6 and 8). Both taps reported "Success" but the screen did not navigate to search results — element count stayed at 36.

### Timeline

| Turn | Action | Result | Elements |
|------|--------|--------|----------|
| 5 | `type "陈奕迅"` into search field | Success: Typed into element at (646,224) | 36→36 |
| 6 | `click element_index 4` ("陈奕迅" suggestion) | Success: Tapped (632,392) via gesture_tap | **36→36** |
| 7 | `system_button "enter"` (agent workaround) | Error: Unknown action: 'system_button' | 36→36 |
| 8 | `click element_index 4` again | Success: Tapped (632,392) via gesture_tap | **36→36** |

The LLM history at turn 6 confirms the screen correctly showed "陈奕迅" typed in the search field with autocomplete suggestions (陈奕迅, 陈奕迅 歌, 陈奕迅歌曲, 陈奕迅 浮夸…). Element 4 was the "陈奕迅" ViewGroup at center (632, 392). The tap coordinates were correct.

### Root Cause

**`gesture_tap` reports false success — NOT a §1.1 overlay touchability regression.**

The OverlayTouchGate worked — `gesture_tap` was dispatched and `dispatchGesture` returned success. Three issues compound:

1. **`gesture_tap` reports "success" even when the target app doesn't process the tap.** `dispatchGesture` reports success when gesture injection completes at the Android input system level, not when the app actually handles it. The system thinks the action succeeded and doesn't fall back to `node_click`.

2. **YouTube's search suggestion handler likely doesn't respond to injected gesture taps the same way as real touches.** YouTube suggestions may use custom gesture detection (e.g. checking `MotionEvent` source or using `GestureDetector`) that filters out programmatic taps.

3. **`ActionPriorityOrder` (gesture-first) prevents automatic fallback.** Since we switched to `gesture_tap → node_click`, and `gesture_tap` reports "success", `node_click` (accessibility `ACTION_CLICK`) is never tried. The `node_click` approach directly invokes the click handler via the accessibility API and would likely have worked.

### Fix Applied — node-first action priority

Switched `ActionPriorityOrder` from gesture-first to node-first for all dual-path actions:

- **click**: `node_click → gesture_tap` (was `gesture_tap → node_click`)
- **long_press**: `node_long_click → gesture_long_press` (was `gesture_long_press → node_long_click`)
- **scroll**: `a11y_scroll → gesture_swipe` (was `gesture_swipe → a11y_scroll`)

For semantic targets (element_index, text), `node_click` (accessibility `ACTION_CLICK`) is tried first — this directly invokes the click handler via the accessibility API and avoids the `gesture_tap` false-success problem. If `node_click` fails, `gesture_tap` is still tried as fallback. For coordinate-only targets, `node_click` is skipped (via `isSemantic()` guard) and `gesture_tap` runs directly.

### Remaining: false-success detection

The deeper issue — `gesture_tap` reporting success when the app doesn't process the tap — is mitigated but not fully solved. A post-action screen-change check could detect false successes and trigger fallback even when `gesture_tap` is used. This is lower priority now that node actions run first.

---

## Issue 2: Agent reverts to 容祖儿 (History management — P0)

### Symptom

After turns 2–8 consistently pursuing 陈奕迅, the agent suddenly writes todos about 容祖儿 at turn 9 with no mention of 陈奕迅 or the supplement. It proceeds to search for and play 容祖儿, completing the task with the wrong artist.

### Timeline

| Turn | history_items | Agent's intent |
|------|--------------|----------------|
| 2 | 8 | "User changed request to play 陈奕迅" ✓ |
| 3 | 12 | Clearing 容祖儿 from search to type 陈奕迅 ✓ |
| 4 | 15 | Clearing search text ✓ |
| 5 | 18 | Typing "陈奕迅" ✓ |
| 6 | **13** (TRIMMED!) | Clicking 陈奕迅 suggestion ✓ |
| 7 | 16 | Trying system_button enter ✓ |
| 8 | 15 | Clicking suggestion again ✓ |
| 9 | 15 | **write_todos: "Search for 容祖儿"** ✗ |

At turn 6, `history_items` dropped from 18→13 — the `HistoryManager.compress()` function fired.

### Root Cause

**`HistoryManager.compress()` drops supplement messages — they have no pin/priority protection.**

The supplement "改成听陈奕迅" was stored as `ResponseItem.Message(role="user")` — identical to any other history item. The compression logic in `HistoryManager.kt`:

```kotlin
fun compress(targetTokens: Long) {
    // Strategy 1: Truncate older tool outputs
    // Strategy 2: Remove oldest items until within budget
    while (estimateTokenCount() > targetTokens && items.size > 2) {
        removeFirstItem()  // removes from front of list
    }
}
```

Being an early message in the conversation, the supplement was among the first items removed by `removeFirstItem()`. The original task "open youtube and play a 容祖儿 song" lives in the **system prompt** (never trimmed), so it survived compression. By turn 9, the agent no longer sees any mention of 陈奕迅 — only the original 容祖儿 task.

### Fix Direction

Supplements must be protected from compression. Options:

- **A. Pin flag**: Mark supplements with `pinned=true` that `compress()` skips during removal
- **B. Task description merge**: Append supplement text to the task description in the system prompt (survives all trimming)
- **C. Separate amendments section**: Maintain a "user amendments" list that's always included in the prompt, outside the compressible history window

Option B is simplest and most robust — when a supplement is received, update the system prompt's task description to include the amendment.

---

## Issue 3: Supplement at end of chat UI (UI rendering — P2)

### Symptom

In the chat UI, "改成听陈奕迅" appears at the end of the entire action sequence instead of at its chronological position (between turn 1 and turn 2, where it was actually injected at 18:33:47).

### Root Cause

**Structural: single Agent message accumulates all turns; supplement User message appended after it.**

The chat uses one `ChatMessage.Agent` per task, updated in-place by `updateLastAgentMessage`. All turns' actions accumulate into this single object. When `handleSupplement` appended a User message, it landed after the Agent — but `updateLastAgentMessage` kept finding (and growing) that same Agent for all subsequent turns.

Result during this run:
```
[0] User: "open youtube and play a 容祖儿 song"
[1] Agent: { turns 1-17 actions + completion }   ← keeps growing via updateLastAgentMessage
[2] User: "改成听陈奕迅"                          ← stuck at end forever
```

This is **not** a race condition or async timing issue — it's a structural design problem.

### Root Cause (deeper)

A supplement and a new task are fundamentally the **same operation from the chat UI's perspective**: "user message splits the agent response". Both need to:

1. Close the current Agent message (mark Complete)
2. Insert a User message
3. Create a new Agent message for subsequent actions

`handleTaskStarted` did steps 2+3 (step 1 was already done by `TaskCompleted`). `handleSupplement` only did a broken step 2 (no close, no new Agent).

### Fix Applied — `insertUserTurn` + recording + intent guard

**Chat UI fix** (`ChatEventReducer.kt`): Extracted a shared `insertUserTurn(text, timestamp, agentId?)` method that both paths use:

```kotlin
// ChatEventReducer.kt

private fun handleTaskStarted(event: TaskStarted) {
    uiState.update { it.copy(showEmptyState = false) }
    insertUserTurn(event.input, event.timestamp, agentId = event.taskId)
}

private fun handleSupplement(event: SupplementReceived) {
    insertUserTurn(event.text, event.timestamp)
}

/**
 * Universal "user message splits the conversation" operation.
 */
private fun insertUserTurn(text: String, timestamp: Long, agentId: String? = null) {
    // 1. Close current agent message (idempotent if already Complete or absent)
    updateLastAgentMessage { msg ->
        msg.copy(state = AgentMessageState.Complete)
    }

    // 2. Insert user message
    messages.add(ChatMessage.User(
        id = UUID.randomUUID().toString(),
        timestamp = timestamp,
        text = text
    ))

    // 3. New agent message for subsequent actions
    val id = agentId ?: "supplement-$timestamp"
    streamingBuffer.clear()
    setCurrentAgentMessageId(id)
    messages.add(ChatMessage.Agent(
        id = id, timestamp = timestamp,
        contentBlocks = emptyList(), state = AgentMessageState.Thinking
    ))
}
```

After fix, the same run would produce:
```
[0] User: "open youtube and play a 容祖儿 song"
[1] Agent: { turn 1 actions }                    ← closed at supplement time
[2] User: "改成听陈奕迅"                          ← correct chronological position
[3] Agent: { turns 2-17 actions + completion }    ← new segment
```

### Remaining: Recording/Replay

~~`SessionRecordingService` may not record `SupplementReceived` events~~ — **Fixed.** `AgentServiceEventHandler` now calls `recordUserMessage` + `startAgentMessage` for `SupplementReceived`, mirroring the `TaskStarted` pattern. When the chat is reconstructed via `restoreMessagesFromRecords`, supplements are included.

### Issue 3b: Follow-up message after completed task creates new session (debug-run.sh only)

**Symptom**: After a task completes (via `debug-run.sh`), typing a follow-up message in the chat UI starts a brand new session instead of continuing the existing conversation.

**Root Cause**: `debug-run.sh` passes `fresh_session=true` in the intent. When Android destroys and recreates the Activity (common while the agent is controlling another app in the foreground), `handleIntent()` reprocesses the stale intent — calling `clearCurrentSession()` → `Op.Shutdown` → clears chat → creates new session with original goal.

**Fix Applied** (`MainActivity.kt`): Added `intentPayloadConsumed` flag persisted via `savedInstanceState`. Each intent's action dispatch (clear session + start goal) runs only once. `onNewIntent` resets the flag so genuinely new intents are always processed. Settings application remains idempotent and always runs.

---

## Summary

| # | Issue | Category | Severity | Root Cause | Status |
|---|-------|----------|----------|------------|--------|
| 1 | Suggestion click ineffective | Action execution | P1 | `gesture_tap` false-success on YouTube suggestions; no fallback to `node_click` | **Fixed** (node-first priority) |
| 2 | Agent reverts to 容祖儿 | History management (code bug) | P0 | `HistoryManager.compress()` drops supplement messages — no pin/priority protection | Open |
| 3 | Supplement at end of chat | UI structure (code bug) | P2 | Single Agent message accumulates all turns; supplement appended after it | **Fixed** |
| 3b | Follow-up creates new session | Intent lifecycle (code bug) | P1 | Stale `fresh_session=true` intent reprocessed on activity recreation | **Fixed** |

### Code References

- `HistoryManager.kt` — `compress()`, `removeFirstItem()`
- `AgentSession.kt:259-276` — `handleSupplement()`
- `ChatEventReducer.kt` — `insertUserTurn()` (shared method), `handleSupplement()`, `handleTaskStarted()`
- `AgentServiceEventHandler.kt` — `SupplementReceived` recording (recordUserMessage + startAgentMessage)
- `MainActivity.kt` — `intentPayloadConsumed` guard, `handleIntent()`, `onSaveInstanceState()`
- `ResponseItem.kt` — `Message` data class (no pinning support)
- Action priority: `ActionPriorityOrder` node-first ordering (click, long_press, scroll)
- `ClickExecutor.kt`, `LongPressExecutor.kt`, `ScrollExecutor.kt` — executor doc comments updated
