# Scratchpad Tool Analysis

> Analyst: Claude (Opus)
> Date: 2026-02-06
> Goal: max(agent success rate) + min(token usage cost)

---

## 1. Current Implementation Summary

### Tool Schema (as sent to LLM)

```json
{
  "name": "scratchpad",
  "description": "Store and retrieve key-value data for multi-step tasks.\n\nUse cases:\n- Store extracted info (e.g., contact list from one screen to use in another)\n- Remember values across navigation\n- Track intermediate results\n\nActions:\n- write: Store key=value\n- read: Get value for key\n- delete: Remove key\n- list: Show all keys\n\nLimits:\n- Max keys: 20\n- Max key length: 100 chars\n- Max value length: 2048 chars",
  "parameters": {
    "type": "object",
    "properties": {
      "action": { "type": "string", "enum": ["write", "read", "delete", "list"] },
      "key": { "type": "string" },
      "value": { "type": "string" },
      "agent_thought": { "type": "string" }
    },
    "required": ["action"]
  }
}
```

### Context Rendering

**Two places** scratchpad data appears in every user message:

1. **Context block** (in `buildAdditionalContextBlocks()` → `buildBaseText()`):
   ```
   ## Scratchpad
   - email_count: 5
   - current_app: Gmail
   ```
   Shows **all** key-value pairs, full content.

2. **System reminder** (in `buildScratchpadReminder()` → `buildReminders()`):
   ```
   <system_reminder>
   Scratchpad has 2 key(s). Reuse stored facts before repeating extraction. Keys: current_app, email_count
   </system_reminder>
   ```
   Shows only keys (first 4) as a nudge.

### State Backend

- `ScratchpadState`: Thread-safe `MutableMap<String, String>`, limits: 20 entries, 100 char keys, 2048 char values.
- Shared between Planner and Executor via `SessionState.scratchpad`.

### Tool Output (goes into history)

Write returns: `{"action":"write","key":"email_1","value":"From: X, Subject: Y"}`
Read returns: `{"action":"read","key":"email_1","value":"From: X, Subject: Y"}`
List returns: `{"action":"list","keys":["email_1","email_count"],"count":2}`

### Agent Access

| Agent | Has scratchpad? |
|-------|----------------|
| Planner | ✓ |
| Executor | ✓ |
| Standalone | ✓ |

---

## 2. Reference Implementation Findings

### 2.1 AutoDev (android_world fork)

**Tools**: `createItem(key, title, text)` + `fetchItem(key)`

| Aspect | Detail |
|--------|--------|
| **File** | `autodev/scratchpad.py` |
| **Key format** | `PAD-1`, `PAD-2`, `PAD-3` (sequential, auto-uppercased) |
| **Schema** | `createItem(key, title, text)` — stores both a human-readable title and content |
| **Retrieval** | `fetchItem(key)` — returns `{success, key, title, text, is_json}` |
| **Agent access** | Both Planner and Executor |
| **Context** | `<system_reminder>` with key listing including titles |
| **Empty state** | Proactive reminder: "your scratchpad is currently empty. If you need to store data, use createItem..." |
| **Non-empty state** | "**SCRATCHPAD DATA AVAILABLE** - Use fetchItem(key) to retrieve stored data before processing items." |
| **Size limits** | None |
| **JSON handling** | Validates if text is JSON, returns `is_json` flag |

**System prompt mentions** (Planner):
> "**Scratchpad**: Use PAD-1, PAD-2 format. createItem(key, title, text) to store, fetchItem(key) to retrieve. **CRITICAL**: After storing data, you MUST call fetchItem(key) to retrieve it before using it in the next app or step."

**System prompt mentions** (Executor):
> "Call `transcribe_screen()` to read screen → Extract items → createItem → Scroll → Call `transcribe_screen()` again → Extract new → fetchItem previous → Compare → Update scratchpad → Report"

### 2.2 DroidRun

**Two mechanisms**: tool-level `remember()` + shared state `<add_memory>` tags.

| Aspect | Tool-level memory | Shared state memory |
|--------|------------------|---------------------|
| **Access** | CodeAct agent, tool calls | Manager agent (primary) |
| **Storage** | `List[str]` in tool instance | `str` in `DroidAgentState` |
| **Write** | `remember(information: str)` tool | `<add_memory>` XML tags in LLM response |
| **Read** | `get_memory() -> List[str]` | Injected as `<memory>` block in user message |
| **Size limit** | 10 items (keeps most recent) | No explicit limit |
| **Format** | Plain strings | "At step X, I obtained [content] from [source]" |
| **Mutability** | Replace (keeps latest) | Append-only |

**System prompt** (Manager):
> "When you need to remember information for later use, store it in the Memory section (using <add_memory> tags) with step context"
> "Store the actual content you observe, not just references (e.g., store full recipe text, not 'found recipes')"
> "Use memory instead of copying text unless specifically requested"

### 2.3 Minitap

**Tools**: `save_note(key, content)`, `read_note(key)`, `list_notes()`

| Aspect | Detail |
|--------|--------|
| **Storage** | `dict[str, str]` in LangGraph state |
| **Agent access** | Executor only |
| **Overwrite** | Yes, existing keys overwritten |
| **Size limits** | None |
| **agent_thought** | Required parameter on all 3 tools |
| **Context surfacing** | Via LangChain tool binding (not explicit prompt text) |

**Planner prompt example**:
> "Save the ingredients list using the `save_note` tool → Open ShoppingApp → Read the saved note using the `read_note` tool and add items"

### 2.4 MobileAgent V3

**Mechanism**: Dedicated `Notetaker` agent (not a tool call).

| Aspect | Detail |
|--------|--------|
| **Storage** | `InfoPool.important_notes` (single string) |
| **Who writes** | Notetaker agent (runs after each successful action with screenshot) |
| **Who reads** | Manager agent (during plan updates) |
| **Accumulation** | Merges old + new notes via LLM |
| **Size limits** | None |
| **Activation** | Optional (--notetaker flag), task-type dependent |
| **Cost** | Extra LLM call per successful action |

**Notetaker prompt**:
> "Do not take notes on low-level actions; only keep track of significant textual or visual information relevant to the user's request."

### 2.5 Eval Repos (AndroidWorld, MobileWorld)

**No explicit scratchpad tools.** Use implicit memory via:
- Step summaries stored in `self.history` (AndroidWorld T3A/M3A)
- Conversation history lists (MobileWorld agents)

AndroidWorld's summarization prompt:
> "This summary will be added to action history... it can be used as memory to include information that needs to be remembered, or shared between different apps."

---

## 3. Comparative Analysis

### 3.1 Design Paradigms

| Paradigm | Used by | Pros | Cons |
|----------|---------|------|------|
| **Explicit K-V tool** | Android Agent, AutoDev, Minitap | Precise, agent-controlled, structured | Extra tool calls cost turns; requires good key naming |
| **Append-only string** | DroidRun, MobileAgent V3 | Simple, low-overhead, natural language | No selective access; grows unbounded; no delete |
| **Dedicated agent** | MobileAgent V3 | Automatic extraction, vision-grounded | Extra LLM call per action; no agent control |
| **Implicit via history** | Eval repos | Zero overhead, no extra mechanism | Diluted in long history; can't selectively recall |

### 3.2 Feature Comparison Matrix

| Feature | Android Agent | AutoDev | DroidRun | Minitap | MobileAgent V3 |
|---------|:------------:|:-------:|:--------:|:-------:|:---------------:|
| **Write** | ✓ (tool) | ✓ (tool) | ✓ (tag/tool) | ✓ (tool) | ✓ (agent) |
| **Read** | ✓ (tool) | ✓ (tool) | ✓ (auto-inject) | ✓ (tool) | ✓ (auto-inject) |
| **Delete** | ✓ | ✗ | ✗ | ✗ (overwrite) | ✗ |
| **List** | ✓ (tool) | ✗ (context) | ✗ | ✓ (tool) | ✗ |
| **Title/metadata** | ✗ | ✓ | ✗ | ✗ | ✗ |
| **Full data in context** | ✓ | ✗ | ✓ | ✗ | ✓ |
| **Key reminder** | ✓ (keys only) | ✓ (keys+titles) | N/A | ✗ | N/A |
| **Empty-state nudge** | ✗ | ✓ | ✗ | ✗ | N/A |
| **Size limits** | ✓ (20/100/2048) | ✗ | ✓ (10 items) | ✗ | ✗ |
| **Shared P↔E** | ✓ | ✓ | Partial | ✗ (Executor only) | ✓ (via InfoPool) |
| **agent_thought** | ✓ (optional) | ✗ | ✗ | ✓ (required) | N/A |

---

## 4. Issues with Current Implementation

### 4.1 CRITICAL: `read` and `list` actions are redundant (wasted turns)

**Evidence**: The scratchpad is fully rendered in every user message via `buildAdditionalContextBlocks()`:
```
## Scratchpad
- email_count: 5
- current_app: Gmail
```

The LLM already sees all keys and values. Calling `read(key="email_count")` just returns data that's already in the prompt — wasting a turn and adding ~100 tokens of redundant output to history. Same for `list` since keys are shown in the `<system_reminder>`.

**Impact**: Each unnecessary `read`/`list` call costs:
- 1 full agent turn (planning → LLM call → tool execution → observation)
- ~200-400 tokens in history (tool call + response)
- Potential 1-3 seconds of latency

In planner-executor mode, a Planner `read` costs the least. But an Executor `read` means a wasted delegation.

### 4.2 HIGH: Tool description lacks actionable guidance

Current description is generic ("Store and retrieve key-value data"). Compare with AutoDev's system prompt which includes workflow patterns:
> "After storing data, you MUST call fetchItem(key) to retrieve it before using it in the next app or step"
> "Extract items → createItem → Scroll → Extract new → fetchItem previous → Compare → Update"

And DroidRun's:
> "Store the actual content you observe, not just references"
> "At step X, I obtained [content] from [source]"

These patterns teach the LLM *when* and *how* to use the tool effectively.

### 4.3 MEDIUM: No empty-state encouragement

When scratchpad is empty, no reminder is shown. AutoDev proactively nudges:
> "Your scratchpad is currently empty. If you need to store data for later use, use createItem..."

This is valuable for tasks requiring cross-screen or cross-app data transfer, where agents often forget to store data before navigating away.

### 4.4 MEDIUM: Tool output is verbose and redundant in history

When writing, the response echoes back both key and value:
```json
{"action":"write","key":"email_1","value":"From: John, Subject: Meeting at 3pm, Body: ...long text..."}
```

This value is already visible in the next turn's scratchpad context block. Echoing it back doubles the token cost in history, especially for long values.

### 4.5 LOW: Context budget risk with many large entries

Worst case: 20 entries × 2048 chars ≈ 40K chars of scratchpad in context. This is excessive, especially combined with screen JSON (~3-5K) and history. No truncation mechanism exists for long values in `toPromptContext()`.

### 4.6 LOW: No guidance on key naming conventions

LLMs sometimes produce overly verbose or inconsistent keys (`"the_subject_of_the_first_email_I_read"` vs `"email_1_subject"`). AutoDev's `PAD-1`/`PAD-2` convention is extreme (loses semantics) but demonstrates that structured naming guidance helps.

---

## 5. Improvement Proposals

### Proposal A: Remove `read` and `list` actions [CRITICAL, Token Savings]

**Rationale**: Since all scratchpad data is already in the prompt context, these actions only waste turns.

**Change**:
- Remove `read` and `list` from the `enum`
- Update description to explain data is always visible
- Keep only `write` and `delete`

**Schema after change**:
```json
{
  "name": "scratchpad",
  "description": "Store key-value data to persist information across steps.\n\nAll stored data is automatically visible in your context each turn — no need to 'read' it back.\n\nUse when:\n- Extracting data from one screen to use later (e.g., contact list, email subject)\n- Transferring data between apps\n- Tracking intermediate results (e.g., items counted, prices compared)\n\nDo NOT use when:\n- Data is only needed this same turn (just use it directly)\n- Repeating what's already in your last tool output\n\nActions:\n- write: Store key=value (overwrites if key exists)\n- delete: Remove a key when no longer needed\n\nLimits: 20 keys max, key ≤100 chars, value ≤500 chars\n\nKey naming: Use short, semantic keys (email_1_subject, price_total, contact_list).",
  "parameters": {
    "type": "object",
    "properties": {
      "action": { "type": "string", "enum": ["write", "delete"] },
      "key": { "type": "string", "description": "Short semantic key (e.g., email_1_subject, contact_list)" },
      "value": { "type": "string", "description": "Value to store (write only)" },
      "agent_thought": { "type": "string", "description": "Brief reason for this action" }
    },
    "required": ["action", "key"]
  }
}
```

**Token savings estimate**:
- Removes ~50 tokens from schema (`read`/`list` enums + descriptions)
- Prevents wasted turns (each saves ~300 tokens in history)
- Typical savings: 1-3 turns per complex task = 300-900 tokens

**Risk**: None. `read` and `list` provide zero information the LLM doesn't already have.

### Proposal B: Reduce `write` output verbosity [HIGH, Token Savings]

**Rationale**: Current write output echoes the full value, which is already in context next turn.

**Change**: Return minimal confirmation instead of full key-value.

**Before**: `{"action":"write","key":"email_1","value":"From: John, Subject: Meeting at 3pm, Body: Please bring..."}`
**After**: `Stored 'email_1' (142 chars).`

**Token savings**: Depends on value length. For a 500-char value, saves ~120 tokens per write. For 10 writes in a session: ~1200 tokens.

### Proposal C: Smart truncation in context rendering [MEDIUM, Token Savings]

**Rationale**: Showing full 2048-char values for all entries is wasteful. Most reads are about key names + short values.

**Change in `toPromptContext()`**:
```kotlin
fun toPromptContext(maxValueDisplay: Int = 200): String {
    val snapshot = synchronized(lock) { data.toMap() }
    if (snapshot.isEmpty()) return ""
    return snapshot.entries
        .sortedBy { it.key }
        .joinToString("\n") { (k, v) ->
            if (v.length <= maxValueDisplay) {
                "- $k: $v"
            } else {
                "- $k: ${v.take(maxValueDisplay)}... [${v.length} chars total]"
            }
        }
}
```

**Caveat**: If we remove `read`, truncated values lose their full content. **Two options**:
1. Keep `read` only as a fallback for truncated values (add conditional: `read` only valid when value was truncated)
2. Increase `maxValueDisplay` to a generous 400 chars (covers 95%+ of use cases)

**Recommendation**: Option 2 (400 chars display limit). If a value is longer, it's usually data that should be structured into multiple keys anyway.

### Proposal D: Improve tool description with actionable patterns [HIGH, Success Rate]

**Rationale**: AutoDev and DroidRun teach the LLM *when* and *how* to use memory effectively through concrete patterns.

**Change**: Enrich the description (and/or system prompt) with patterns.

**Additions to system prompt** (for Planner and Standalone):
```
## Scratchpad (Persistent Memory)
- All scratchpad data is shown in your context every turn. No need to read it back.
- Use scratchpad to store extracted data BEFORE navigating away from the current screen.
- Write the actual content, not just references (e.g., store "John Doe" not "the sender name").
- Use short semantic keys: email_1_subject, price_total, items_found.
- Delete keys when no longer needed to keep context clean.
```

**Token cost**: ~60 tokens. Worth it for improved accuracy.

### Proposal E: Reduce MAX_VALUE_LENGTH from 2048 to 500 [LOW, Token Savings]

**Rationale**: 2048 chars per entry is generous but creates context budget risk. Most practical values are under 500 chars. If the agent needs to store a longer piece of data, it should structure it into multiple keys or summarize.

**Change**: `MAX_VALUE_LENGTH = 500`

**Token savings**: Reduces worst-case context from ~40K to ~10K chars.

**Risk**: Some extraction tasks may need longer values (e.g., full email body). However, agents should be summarizing rather than copying verbatim. The description can guide this: "Store concise facts, not raw content."

**Alternative**: Keep 2048 but add truncation in context rendering (Proposal C).

### Proposal F: Add empty-state nudge in reminder [LOW, Success Rate]

**Rationale**: AutoDev's empty-state reminder helps agents remember to use scratchpad proactively.

**Change in `buildScratchpadReminder()`**:
```kotlin
private fun buildScratchpadReminder(keys: List<String>): String? {
    // Show reminder when scratchpad is NOT empty
    if (keys.isNotEmpty()) {
        val preview = keys.take(4).joinToString(separator = ", ")
        return """<system_reminder>
            Scratchpad has ${keys.size} key(s). Reuse stored facts before repeating extraction. Keys: $preview
            </system_reminder>""".trimIndent()
    }
    return null  // Don't nudge when empty — reduces noise for simple tasks
}
```

**Decision**: After further consideration, keeping the current behavior (no empty nudge) is correct. Empty-state nudges add ~40 tokens every turn for simple tasks that don't need scratchpad. The system prompt guidance (Proposal D) is enough.

---

## 6. Recommended Implementation Priority

| Priority | Proposal | Impact | Token Δ | Effort |
|----------|----------|--------|---------|--------|
| **1** | A: Remove `read`/`list` actions | Prevents wasted turns | −300-900/task | Low |
| **2** | B: Reduce write output | Less history bloat | −100-1200/task | Low |
| **3** | D: Actionable description | Better tool usage | +60 (one-time) | Low |
| **4** | C: Smart truncation | Bounded context budget | −0-5000/task | Medium |
| **5** | E: Reduce max value length | Defense-in-depth | (bounded by C) | Low |

### Proposals NOT recommended:

- **Separate `title` field** (AutoDev style): Adds schema complexity, marginal benefit since we show full values in context.
- **Structured key format** (AutoDev's `PAD-1`/`PAD-2`): Loses semantic meaning. Semantic keys are better for LLM comprehension.
- **Dedicated Notetaker agent** (MobileAgent V3 style): Too expensive (extra LLM call per action). Our scratchpad tool is agent-controlled, which is more efficient.
- **Append-only memory** (DroidRun style): Can't delete, grows unbounded, no structure. Our K-V model is better.
- **Step context format** (DroidRun's "At step X, I obtained..."): Adds verbosity without clear benefit — the LLM can infer context from history.
- **`agent_thought` as required** (Minitap style): Would add tokens to every call. Keep optional for debugging.

---

## 7. Proposed Final Tool Definition

Combining Proposals A, B, C, D:

### Tool Schema

```json
{
  "name": "scratchpad",
  "description": "Store key-value data to persist information across steps. All stored data is visible in your context each turn.\n\nUse when:\n- Extracting data from one screen to use later\n- Transferring data between apps\n- Tracking intermediate results\n\nDo NOT use to store data you only need this turn.\n\nActions:\n- write: Store key=value (overwrites existing key)\n- delete: Remove key when no longer needed\n\nKeys: short and semantic (e.g., email_1_subject, price_total)\nValues: concise facts, not raw content. Max 500 chars.",
  "parameters": {
    "type": "object",
    "properties": {
      "action": {
        "type": "string",
        "enum": ["write", "delete"],
        "description": "Action to perform"
      },
      "key": {
        "type": "string",
        "description": "Short semantic key (e.g., email_1_subject, contact_list)"
      },
      "value": {
        "type": "string",
        "description": "Concise value to store (write only). Max 500 chars."
      },
      "agent_thought": {
        "type": "string",
        "description": "Brief reason for this action"
      }
    },
    "required": ["action", "key"],
    "additionalProperties": false
  }
}
```

### Write Output Format

```
Stored 'email_1_subject' (28 chars).
```

### Delete Output Format

```
Deleted 'email_1_subject'.
```
or
```
Key 'email_1_subject' not found.
```

### System Prompt Addition (Planner/Standalone)

```
## Scratchpad
All scratchpad data is shown in your context every turn — just read it directly.
Use scratchpad to store extracted data BEFORE navigating away.
Write actual content, not references. Delete keys when no longer needed.
```

### Context Rendering (toPromptContext)

```
## Scratchpad
- email_1_subject: Meeting at 3pm
- email_1_sender: John Doe
- items_found: ["Milk", "Bread", "Eggs", "Butter", "Chee... [312 chars total]
```

(Values truncated at 200 chars with char count hint.)

---

## 8. Appendix: Evidence Locations

| Repo | Key Files |
|------|-----------|
| **Android Agent** | `tool/impl/ScratchpadTool.kt`, `session/ScratchpadState.kt`, `agent/cognition/prompt/PromptUtils.kt:136`, `agent/AgentTurnRunner.kt:318-332` |
| **AutoDev** | `autodev/scratchpad.py` (tool defs + state + reminders), `autodev/prompts.py:106,345` (system prompt mentions) |
| **DroidRun** | `tools/android/adb.py:783-817` (remember tool), `agent/droid/state.py:69` (shared state), `config/prompts/manager/system.jinja2:40-63` (guidelines), `agent/manager/manager_agent.py:248-253,505-511` (injection + parsing) |
| **Minitap** | `tools/scratchpad.py` (3 tools), `graph/state.py:61-66` (state), `agents/planner/planner.md:107-116` (example) |
| **MobileAgent V3** | `utils/mobile_agent_e.py:317-351` (Notetaker agent), `run_mobileagentv3.py:282-299` (integration loop) |
| **Eval repos** | No scratchpad tools. `agents/t3a.py:156-183` (step summaries as implicit memory) |
