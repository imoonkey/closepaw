# Design Decisions

> Each decision is grounded in the actual codebase. File paths are relative to `app/src/main/kotlin/com/moonkey/androidagent/`.

---

## Decision 1: Remove Screen State from Tool Results

### Current Behavior

When a tool executes (e.g., `mobile_action:click`), the pipeline does:

1. **Tool executor** captures a post-action snapshot and builds a `ToolObservation.ScreenState` containing the full a11y tree JSON + summary (`tool/action/ObservationBuilder.kt:13`)
2. **AgentTurnRunner** receives the observation, OR captures its own via `captureObservationWithSnapshot()` if the tool didn't provide one (`agent/AgentTurnRunner.kt:500-525`)
3. **formatToolResult()** formats the output as: `"Success: Clicked element 3\n\nScreen after action: <summary>"` (`agent/AgentTurnRunner.kt:631-647`)
4. This combined string goes into history as `FunctionCallOutput`

So the LLM sees in history:
```
function_call_output: "Success: Clicked element 3\n\nScreen after action: com.google.android.gm | elements=52, clickable=12, ..."
```

### Problem

This screen summary in the tool output is **nearly useless**:
- It's a one-line summary (package name + element counts + a few labels) — not enough to act on
- The FULL screen state is always provided in the next turn's user message anyway
- It adds ~15 tokens per tool call, compounding over many turns
- It creates a confusing dual-source: "Where is the screen state? In the tool output? In the user message?"

### Decision

**Remove screen state from tool results entirely.** Tool outputs become meta-only:

| Tool | Current Output | New Output |
|------|---------------|------------|
| `mobile_action:click` | `"Success: Clicked element 3 [text='Inbox']\n\nScreen after action: ..."` | `"Success: Clicked element 3 [text='Inbox']"` |
| `mobile_action:swipe` | `"Success: Swiped up (medium)\n\nScreen after action: ..."` | `"Success: Swiped up (medium)"` |
| `open_app` | `"Success: Gmail opened\n\nScreen after action: ..."` | `"Success: Gmail opened"` |
| `system_button` | `"Success: Pressed back\n\nScreen after action: ..."` | `"Success: Pressed back"` |
| `scratchpad` | `"Stored: key=value\n\nCompletion acknowledged..."` | `"Stored: key=value"` |
| `complete_task` | No change — already doesn't include screen | No change |

### What We Keep

The post-action snapshot capture is **not removed** — it serves two purposes that remain:

1. **Stale element prevention** — When multiple tool calls execute in one turn (rare, due to arbitration), subsequent calls need the updated snapshot (`AgentTurnRunner.kt:381-388`)
2. **Loop detection** — `NavigationState` uses post-action snapshots for screen signature comparison (`agent/cognition/context/NavigationState.kt:22-41`)

We just stop formatting the observation into the `FunctionCallOutput` content.

### Code Changes

```kotlin
// AgentTurnRunner.kt — formatToolResult simplified
private fun formatToolResult(result: ToolCallResult): String {
    return when (result) {
        is ToolCallResult.Success -> "Success: ${result.output}"
        is ToolCallResult.Error -> "Error: ${result.error}"
        is ToolCallResult.Cancelled -> "Cancelled: ${result.reason}"
    }
}
// No longer takes `observation` parameter
```

The `resolveObservation()` method still captures observations for internal use, but the observation is no longer passed to `formatToolResult()`.

---

## Decision 2: Minimal System Reminders

### Current Reminders

| Reminder | Trigger | Format | Code |
|----------|---------|--------|------|
| Loop warning | Screen unchanged / action repeated | `<system_reminder>LOOP WARNING (HIGH): ...</system_reminder>` | `LoopDetectionPolicy.kt` |
| Turn budget warning | 75% of maxTurns | `<system_reminder>TURN BUDGET WARNING: turn N of M...</system_reminder>` | `ExecutorStepPolicy.kt` |
| Final turn warning | At maxTurns | `<system_reminder>FINAL TURN WARNING: ...</system_reminder>` | `AgentTurnRunner.kt:656` |
| Todo reminder | Has actionable todos | `<system_reminder>Todo status: N actionable...</system_reminder>` | `PromptUtils.kt:105` |

### Decision: Keep 3, Remove 1, Simplify Format

**KEEP:**
- **Loop warning** — Critical for preventing infinite loops. Without it, the agent happily repeats the same failed action forever.
- **Turn budget warning** — Important for executors to wrap up before hitting the limit.
- **Final turn warning** — Essential for graceful termination with summary.

**REMOVE:**
- **Todo reminder** — Redundant. Todos are already shown in the Memory section. Repeating "Todo status: 3 actionable items, In progress: X" in a `<system_reminder>` adds nothing.

**SIMPLIFY FORMAT:**
Replace `<system_reminder>` XML tags with plain-text warnings using clear emoji + prefix:

```
Current:
  <system_reminder>
  LOOP WARNING (HIGH): Screen state looks unchanged for 3 turns. Try a different strategy.
  </system_reminder>

Proposed:
  ⚠️ Screen unchanged for 3 turns. Try a different approach (back, search, filter, or open menu).
```

**Rationale for dropping XML tags:**
- The `<system_reminder>` tags were inspired by Cursor/Claude-style prompting, but they add parsing complexity and visual noise
- LLMs respond equally well to clear, direct text with emoji prefixes
- One fewer abstraction layer = simpler code

**Placement change:** Move warnings to the TOP of the current observation message (before screen JSON), not the bottom. The LLM reads top-to-bottom; warnings should prime its interpretation of the screen, not be an afterthought buried under 80 elements of JSON.

### Revised Warning Templates

```kotlin
// Loop warning (LoopDetectionPolicy)
"⚠️ Screen unchanged for $count turns. Try a different approach (back, search, filter, menu)."
"⚠️ Same action repeated $count times ($action). Pick an alternative."
"⚠️ $count consecutive scrolls with no progress. Stop scrolling, switch strategy."

// Turn budget (ExecutorStepPolicy)  
"⏰ Turn $current of $max — prioritize completion over exploration."

// Final turn (AgentTurnRunner)
"🛑 FINAL TURN ($max). Complete now or report what was accomplished."
```

---

## Decision 3: Screen State as User Messages in History

### Current Behavior

Screen state exists in exactly ONE place: the current turn's user message (rebuilt each turn, never stored in history). Past turns have no screen context in history at all — only tool output summaries like `"Screen after action: com.google..."`.

### Problem

The LLM has **zero visual context** for past turns. It can't see how the screen changed over time. When debugging a failed action or deciding on recovery strategy, it's flying blind about what the screen looked like 2 turns ago.

### Decision: Store Screen Observations in History

Each turn, after capturing the screen, store it as a user message in history:

```kotlin
// At the start of each turn (in capturePreTurnSnapshot or similar):
val observation = buildScreenObservationMessage(snapshot, image)
historyManager.addItem(ResponseItem.Message(
    role = "user",
    content = observation,
    isScreenObservation = true   // NEW metadata flag
))
```

The history then naturally contains the full conversation transcript:
```
user: "Goal: Open Gmail and read first email"
user: "Screen state (45 elements):\n```json\n[...]\n```"     ← Turn 1 screen
assistant: "I'll open Gmail."
function_call: open_app({app_name: "Gmail"})
function_call_output: "Success: Gmail opened"
user: "Screen state (52 elements):\n```json\n[...]\n```"     ← Turn 2 screen  
assistant: "I see the inbox. I'll tap the first email."
function_call: mobile_action({action: "click", element_index: 5})
function_call_output: "Success: Clicked 'Meeting notes'"
...
```

### Last-N Full Screen Retention

At prompt build time, screen observations are processed:

- **Last N (3) turns:** Full JSON retained
- **Older turns:** Compressed to summary: `"Screen: com.google.android.gm | elements=52, clickable=12, labels=Inbox, Compose, ..."`

This is done in a **post-processing pass** during `buildInputItems()`, NOT inside HistoryManager. The history stores full observations; the prompt builder decides what to show.

### `isScreenObservation` Metadata

Add a boolean flag to `ResponseItem.Message`:

```kotlin
data class Message(
    val role: String,
    val content: String,
    val name: String? = null,
    val isScreenObservation: Boolean = false   // NEW
) : ResponseItem() { ... }
```

This flag enables:
- Identifying which messages to compress in the prompt builder
- Different token estimation for screen vs. non-screen messages
- Clean compression logic without content-sniffing heuristics

### Token Budget Impact

Adding screen observations to history increases token count by ~4K tokens (for 2 additional full screen states in the last-3 window). The existing compression in `HistoryManager.compress()` handles the rest — older observations are compressed, then if still over budget, the oldest items (including compressed observations) are dropped.

See [01_prompt_structure](./01_prompt_structure_design_claude.md#5-token-budget-analysis) for detailed token math.

---

## Decision 4: Current Observation Message — Lean and Focused

### What Goes In

```
[⚠️ warnings, if any]

Screen state (N elements):

[full a11y tree JSON]


[Screenshot attached (compressed).]
```

### What Goes Away

| Removed | Reason |
|---------|--------|
| `Available tools: complete_task, mobile_action, ...` | Redundant with the `tools` API parameter |
| `## Current Todos\n...` | Moved to Memory section |
| `## Scratchpad\n...` | Moved to Memory section |
| `What action should I take next to achieve the goal?` | Implicit from system prompt — wastes 15 tokens per turn |
| `<system_reminder>Todo status: ...</system_reminder>` | Moved to Memory section; redundant |

The current turn message drops from ~3000 tokens to ~2000 tokens — a clean 33% reduction of overhead per turn.

---

## Decision 5: Memory Message — One Combined Message

### Format

```
## Working Memory

### Todo List
1. [COMPLETED] Open Gmail app
2. [IN_PROGRESS] Read first email  ← current focus
3. [PENDING] Extract sender info
4. [PENDING] Summarize findings

### Scratchpad
- email_count
- current_app  
- email_1_subject
```

### Placement

Immediately before the current observation — the second-to-last input item. The LLM reads memory, then sees the screen, then decides.

### When Empty

If BOTH todo list and scratchpad are empty: **omit the memory message entirely**. No "Working Memory: (empty)" noise. The first turns of a session (before any todos or scratchpad writes) simply won't have a memory message.

If only one is empty, still include the message with the non-empty section only.

### Why Not Separate Messages

The qi note suggests scratchpad and todo as separate user messages. I recommend combining because:
1. They're conceptually one thing: "what I know + what I plan to do"
2. A single message reduces consecutive-user-message count (3 → 2 before current observation)
3. Simpler code: one function, one message

If future experience shows they should be separate (e.g., for independent caching), the split is trivial.

---

## Summary: Before vs. After

### Before (Current Turn Input Items)
```
user: "Goal: ..."
[...history: assistant + function_call + function_call_output...]
user: "Current screen state (80 elements):\n```json\n[...]\n```\n\n
       Available tools: ...\n\n
       ## Current Todos\n...\n\n
       ## Scratchpad\n...\n\n
       Screenshot attached.\n
       What action should I take?\n\n
       <system_reminder>LOOP WARNING...</system_reminder>\n
       <system_reminder>Todo status: ...</system_reminder>"
```

### After (Proposed Turn Input Items)
```
user: "Goal: ..."
user: "Screen: <summary>"                          ← Turn 1 (compressed)
assistant: "..."
function_call: ...
function_call_output: "Success: ..."               ← meta only
user: "Screen state (N elements):\n[JSON]"          ← Turn K (full, within last 3)
assistant: "..."
function_call: ...
function_call_output: "Success: ..."
user: "## Working Memory\n### Todo List\n...\n### Scratchpad\n..."
user: "⚠️ Screen unchanged...\n\nScreen state (N elements):\n[JSON]\n\nScreenshot attached."
```

The prompt is a **clean narrative**: what happened → what I know → what I see.
