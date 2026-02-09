# Prompt Structure Design

> The prompt is a **story** the LLM reads sequentially: who I am → what happened → what I know → what I see → what should I do.

---

## 1. Design Philosophy

Three principles drive the redesign:

1. **Sequential narrative** — The LLM processes tokens left-to-right. Information should flow from background context to immediate action context: identity → history → memory → observation.
2. **Single responsibility** — Each section of the prompt has exactly one job. Screen state lives in user messages, not tool results. Memory lives in its own message, not embedded in the observation.
3. **KISS** — One function builds the input items. No intermediate data classes that exist solely to be transformed into another data class. No `PromptContext` → `UserMessage` → `ResponseInputItem` pipeline when `→ ResponseInputItem` suffices.

---

## 2. Target Per-Turn Request Structure

```
Request {
  "instructions":  system_prompt,           // ① WHO: Role identity
  "tools":         [tool_schemas...],       // ② WHAT: Available actions
  "input": [                                // ③ CONTEXT: Sequential narrative
     // ─── HISTORY ───
     user("Goal: <original user goal>"),
     
     // Turn 1
     user("Screen: <SUMMARY>"),             // compressed (outside last-N window)
     assistant("..."),
     function_call(...),
     function_call_output("Success: ..."),  // meta only, NO screen state
     
     // Turn 2
     user("Screen: <SUMMARY>"),             // compressed
     assistant("..."),
     function_call(...),
     function_call_output("Success: ..."),
     
     // Turn K (within last-N window, N=3)
     user("Screen state (M elements):\n```json\n[FULL JSON]\n```"),  // FULL
     assistant("..."),
     function_call(...),
     function_call_output("Success: ..."),
     
     // ... more recent turns with FULL screen ...
     
     // ─── MEMORY ───
     user("## Working Memory\n### Todo List\n...\n### Scratchpad\n..."),
     
     // ─── CURRENT OBSERVATION ───
     user("[warnings]\n\nScreen state (M elements):\n```json\n[FULL JSON]\n```\n[screenshot]")
  ]
}
```

### 2.1 Why This Order

| Position | Section | Rationale |
|----------|---------|-----------|
| First | History | Background context. The LLM needs to know what happened before interpreting anything else. Older screen states are compressed to summaries; recent ones keep full JSON. |
| Middle | Memory | Bridges past and present. Scratchpad/todos summarize extracted facts and pending work. The LLM reads these before seeing the current screen, so it knows what to look for. |
| Last | Observation | The most important input. Full screen state JSON + optional screenshot. Placed last because it's the most immediately relevant for the next action decision. |

### 2.2 Differences from Current Implementation

| Aspect | Current | Proposed |
|--------|---------|----------|
| Screen state in history | Not stored — only in current turn user message | Stored as user messages; last N full, older compressed to summary |
| Tool result content | `"Success: ...\n\nScreen after action: <summary>"` | `"Success: ..."` — meta only, no screen |
| Memory location | Embedded inside current turn user message as `## Current Todos` and `## Scratchpad` sections | Separate user message before current observation |
| Reminders | `<system_reminder>` XML tags at end of user message | Plain text warnings at TOP of current observation |
| Tool name listing | `Available tools: ...` in user message | Removed — redundant with `tools` parameter |
| `"What action should I take?"` | At end of user message | Removed — implicit from system prompt |

---

## 3. Section Details

### 3.1 History Section

Each turn in history produces these items:

```
user:                 screen observation (full JSON/screenshot or compressed summary)
assistant:            LLM's reasoning/response text
function_call:        tool invocation
function_call_output: tool result (meta-only)
```

**Screen observation compression rule:** The last `N` turns (hardcoded to 3 initially) retain full screen state JSON/screenshot. Older observations are compressed to a one-line summary using `ScreenSnapshot.toSummary()`.

**Goal message:** Remains as the first history item (`"Goal: <text>"`), unchanged.

**No screen state in tool outputs:** `function_call_output` contains only the tool's own result text (e.g., `"Success: Clicked element 5 [text='Inbox']"`). The screen state after the action is captured at the START of the next turn, not embedded in the tool output. See [02_design_decisions](./02_design_decisions_claude.md) for rationale.

### 3.2 Memory Section

A single user message before the current observation:

```
## Working Memory

### Todo List
1. [COMPLETED] Open Gmail app
2. [IN_PROGRESS] Read first email
3. [PENDING] Extract sender info

### Scratchpad
- email_count
- current_app
- email_1_subject
```

**Why one message, not two:** The note suggests separate messages for scratchpad and todos. I recommend combining them into one "Working Memory" message because: (a) they're both small and conceptually related, (b) one fewer consecutive user message reduces potential model confusion, (c) simpler code.

**Scratchpad format:** Keys only (values require explicit `scratchpad(action="read")`). This is already the current behavior and works well.

**When empty:** If both todo and scratchpad are empty, this message is omitted entirely. No "empty memory" noise.

### 3.3 Current Observation Section

The final user message. Contains everything the LLM needs to decide its next action:

```
⚠️ Screen unchanged for 3 turns. Try a different approach.

Screen state (52 elements):

[
  {"index": 0, "text": "Inbox", "class": "TextView", "clickable": true, ...},
  ...
]

Screenshot attached (compressed).
```

**Structure:** Warnings (if any) → Screen JSON → Screenshot hint (if attached)

**No `Available tools:` line** — the `tools` parameter already tells the LLM what's available. Listing them in text is redundant.

**No `"What action should I take next?"` line** — the system prompt already instructs the agent to observe and act. This trailing question wastes tokens and adds no information.

**Warnings** are placed at the TOP, before the screen state, because:
- The LLM reads sequentially — warnings should influence interpretation of the screen
- If placed after the JSON (as currently), the warning is buried under hundreds of lines of elements

---

## 4. Executor Sub-Agent Variation

The Executor receives a **delegated goal** instead of the user's original goal. Its history structure is identical but isolated:

```
input: [
  user("Delegated query: Tap on the first email in the inbox\n..."),
  user("Screen state (52 elements):\n```json\n[...]\n```"),   // Turn 1 observation
  assistant("I'll tap element 5 which shows 'Meeting notes'."),
  function_call(mobile_action, {...}),
  function_call_output("Success: Clicked element 5"),
  // Memory message (shared scratchpad)
  user("## Working Memory\n### Scratchpad\n- email_count\n..."),
  // Current observation
  user("Screen state (38 elements):\n```json\n[...]\n```")
]
```

Executors typically run 1-3 turns, so screen state compression rarely activates. All turns keep full JSON.

---

## 5. Token Budget Analysis

| Component | Tokens (approx) | Notes |
|-----------|-----------------|-------|
| System prompt | 600-800 | Varies by agent role |
| Goal message | 20-100 | Short |
| Full screen state | 1500-2500 | 80 elements × ~30 tokens each |
| Compressed screen summary | 10-20 | One-line summary |
| Tool call + output pair | 50-200 | Per tool invocation |
| Memory message | 100-500 | Depends on todo/scratchpad size |
| Warnings | 20-50 | When present |

**For a 20-turn session with last-3-full:**
- 3 full screen states: 3 × 2000 = 6000 tokens
- 17 compressed summaries: 17 × 15 = 255 tokens  
- 20 tool call pairs: 20 × 125 = 2500 tokens
- Memory + system + goal: ~1500 tokens
- Current observation: ~2000 tokens
- **Total: ~12,250 tokens** — well within typical 128K context windows

Compare to current (only last-1-full): ~8,250 tokens. The 4K increase for 2 extra full screen states is a worthwhile trade for significantly better situational awareness.
