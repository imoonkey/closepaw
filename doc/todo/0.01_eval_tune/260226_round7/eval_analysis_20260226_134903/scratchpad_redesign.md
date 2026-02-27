# Scratchpad Redesign: Unified Write + Full Prompt Injection

> Design doc for round 8 scratchpad improvements.
> Date: 2026-02-26

## Motivation

Round 7 eval revealed **scratchpad inefficiency** as a root cause of failure:

- **ExpenseAddMultipleFromGallery** (score 0.0): 17/30 turns spent on data extraction. LLM emitted separate `scratchpad(action="write", key="item_1_name", value="...")` calls per field — 6+ tool calls per screen just to store extracted data.
- **ExpenseAddMultipleFromMarkor** (score 0.0): Similar pattern — excessive turns on data capture, left insufficient budget for data entry.
- **General pattern**: The current 1-key-per-call schema forces N tool calls for N facts from one screen. Each call is a separate output block = token-expensive + turn-wasteful.

Additionally, the current prompt shows **keys only** in Working Memory. The LLM must waste a `read` call to retrieve values it previously wrote — especially wasteful for cross-screen handoffs where the data is needed immediately.

## Reference Agent Analysis

| Agent | Write Mechanism | Prompt Injection | Batch? |
|-------|----------------|-----------------|--------|
| **AutoDev** | `createItem(key, title, text)` — freeform `text` field | Keys + titles only (catalog), `fetchItem()` for values | No API batch, but freeform text allows JSON arrays in one call |
| **Minitap** | `save_note(key, content)` | Zero injection — tool-only retrieval | No |
| **MobileAgent V3** | Separate Notetaker LLM agent | Full text verbatim in Manager prompt | N/A — single string |
| **DroidRun** | `remember(information: str)` append-only | Full list in prompt | Single string per call |
| **Android World** | No scratchpad | N/A | N/A |

**Key takeaway**: AutoDev and DroidRun achieve "one call captures all screen info" through freeform text fields. No reference agent has a structured batch API — they just allow flexible content.

## Design

### Change 1: Unified `write(content)` with JSON String

**Current schema**:
```json
{
  "action": "write",
  "key": "item_1_name",
  "value": "Apple"
}
```
Requires N calls for N keys.

**New schema**:
```json
{
  "action": "write",
  "content": "{\"item_1_name\": \"Apple\", \"item_1_price\": \"$3.50\", \"item_2_name\": \"Banana\", \"item_2_price\": \"$1.20\"}"
}
```

**Semantics**:
- `content` is a JSON string representing a key-value object
- Tool execution parses the JSON and writes each top-level key→value pair into `ScratchpadState`
- **Write = `dict.update()`**: existing keys are overwritten (upsert), new keys are inserted, unmentioned keys are untouched. No deep merge — if a key's value is a nested structure, the entire value is replaced.
- Single key writes are just `{"my_key": "my_value"}` — no separate code path needed
- If parsing fails, return error with the parse exception message

**Value types — any JSON value accepted**:
- Values can be any JSON type: string, number, boolean, array, object
- Tool execution stores native JSON types directly into `JSONObject` (no coercion)
- Examples:
  ```json
  {
    "items": [{"name": "Apple", "price": 3.5}, {"name": "Banana", "price": 1.2}],
    "total": 142.5,
    "confirmed": true,
    "note": "Check prices again"
  }
  ```
  Stored as: `items` → JSONArray, `total` → Number, `confirmed` → Boolean, `note` → String

**Validation**:
- `content` must be valid JSON and parse to a `JSONObject`
- Total entries after write must not exceed `MAX_ENTRIES` (20)
- Each key must satisfy `MAX_KEY_LENGTH` (100 chars)
- Each value must satisfy `MAX_VALUE_LENGTH` (2048 chars) — checked via `value.toString().length` for non-string types

**Prompt/description guidance** (in tool description):
```
Actions:
- write: Store one or more key-value pairs. content is a JSON object string.
  Example: {"email_subject": "Meeting at 3pm", "sender": "alice@example.com"}
  If you have multiple fields to store, include them all in a single write call, to minimize tool call turns for the same result.
- read: Get value for key
- delete: Remove key
```

**Why JSON string instead of separate key/value params or a JSON array param**:
- One parameter (`content`) vs two (`key`, `value`) — simpler schema
- Naturally supports 1-to-N writes with no schema change
- LLMs are good at generating JSON objects
- The tool execution layer is a thin JSON parse + loop over entries

### Change 2: Full Key→Value Display in Prompt (JSON Format)

**Current** (`ScratchpadState.toPromptContext()`):
```
### Scratchpad
- email_subject
- total_price
- item_list
```
Keys only. LLM must call `read` to access values.

**New** — JSON format, consistent with write input:
```
### Scratchpad
{
  "email_subject": "Meeting at 3pm tomorrow",
  "total_price": 142.5,
  "item_list": [{"name": "Apple", "price": 3.5}, {"name": "Ba... // truncated, 847 chars
}
```

JSON-in (write), JSON-store (JSONObject), JSON-out (prompt) — no format translation at any stage. Truncated values are visually obvious (broken JSON with `...`), signaling the LLM to use `read` for the full value. Non-truncated entries are valid JSON.

**Logic**:
```
DISPLAY_TRUNCATE_LENGTH = 200  // per-value char limit before truncation
TOTAL_BUDGET = 3000            // total scratchpad text budget in chars

Build a JSONObject-style string:
  "{\n"
  for each (key, value) sorted by key:
      serialized = value.toString()  // native JSON serialization
      if remaining_budget <= 0:
          append '  "{key}": "..." // use read\n'
      elif serialized.length <= DISPLAY_TRUNCATE_LENGTH:
          append '  "{key}": {serialized}\n'   // native type, no extra quoting
          remaining_budget -= line.length
      else:
          truncated = serialized.take(DISPLAY_TRUNCATE_LENGTH) + "..."
          append '  "{key}": {truncated} // truncated, {serialized.length} chars\n'
          remaining_budget -= line.length
  "}"
```

**Empty state message update**:
```
(empty) Store important facts with scratchpad(action="write", content='{"key": "value"}') before navigating away.
```

**Token budget impact**:
- Current (keys-only, 20 entries max): ~100-200 tokens
- New (key+value, truncated): ~500-1500 tokens worst case (bounded by TOTAL_BUDGET=3000 chars ~ 750 tokens)
- Well within the 18,000-token history budget

### `read` and `delete` — Unchanged

- `read(key)` — returns native JSON value. Still useful for truncated values that exceed DISPLAY_TRUNCATE_LENGTH
- `delete(key)` — unchanged

### Internal Storage: JSONObject

`ScratchpadState` changes from `MutableMap<String, String>` to `JSONObject`.

**Why**: JSON-in (write) → JSON-store → JSON-out (prompt) — no lossy coercion, no double-escaping. Types preserved end-to-end.

```
LLM writes: {"items": [1,2,3], "count": 5}
     ↓ jsonObject.put(key, value) for each entry
Store:      JSONObject with native types (JSONArray, Number, etc.)
     ↓ toString(2) + truncation
Prompt:     "items": [1, 2, 3]     // clean JSON, not "[1, 2, 3]" string
```

With the old `Map<String, String>`, storing non-string values required coercion (e.g., `[1,2,3]` → `"[1, 2, 3]"` string), which produces double-escaped output in the prompt (`"items": "[1, 2, 3]"`). `JSONObject` eliminates this entirely.

**Key changes to ScratchpadState**:
- `data: MutableMap<String, String>` → `data: JSONObject`
- `write(key, value)` → `write(key: String, value: Any)` — accepts any JSON-compatible type
- `read(key)` → returns `Any?` (String, Number, JSONArray, JSONObject, Boolean, or null)
- `toMap()` → `toJsonObject(): JSONObject` (deep copy)
- `toPromptContext()` → builds truncated JSON string from `data.toString(2)` style output
- Checkpoint serialization: `data.toString()` / `JSONObject(str)` — trivial
- Thread safety: same `synchronized(lock)` around `JSONObject` instance
- `MAX_VALUE_LENGTH` validation: check `value.toString().length` for non-string types

## Code Changes

### 1. `session/ScratchpadState.kt`

**Storage**: `MutableMap<String, String>` → `JSONObject`

**API changes**:
- `write(key: String, value: String)` → `write(key: String, value: Any)`
- `read(key: String): String?` → `read(key: String): Any?`
- `toMap(): Map<String, String>` → `toJsonObject(): JSONObject` (deep copy)
- `list(): List<String>` — unchanged (returns sorted keys)
- `delete(key: String): Boolean` — unchanged
- `clear()` — unchanged

**`toPromptContext()` rewrite**:

Current:
```
- (empty) Store important facts with scratchpad(action="write", key="...", value="...") before navigating away.
```
or:
```
- email_subject
- total_price
```

New (empty):
```
(empty) Store important facts with scratchpad(action="write", content='{"key": "value"}') before navigating away.
```

New (with entries): JSON object with truncation (see Change 2 design above).

### 2. `tool/impl/ScratchpadTool.kt`

**Tool description** — current:
```
Store key-value data for multi-step tasks and cross-app handoffs.

Scratchpad keys are always shown in context every turn.
Use read only when you need the full value for a specific key.

Good usage:
- Write facts before navigating away from the current screen
- Store actual extracted content (not vague references)
- Use short semantic keys (email_1_subject, total_price)

Actions:
- write: Store key=value
- read: Get value for key
- delete: Remove key

Limits:
- Max keys: 20
- Max key length: 100 chars
- Max value length: 2048 chars
```

New:
```
Store key-value data for multi-step tasks and cross-app handoffs.

Scratchpad is always shown in context every turn (values truncated if long).
Use read only when you need the full value for a truncated key.

Good usage:
- Write facts before navigating away from the current screen
- Store actual extracted content (not vague references)
- Use short semantic keys (email_1_subject, total_price)
- Capture ALL relevant data from the current screen in a single write call

Actions:
- write: Store one or more key-value pairs. content is a JSON object string.
  Example: {"email_subject": "Meeting at 3pm", "sender": "alice@example.com"}
  If you have multiple fields to store, include them all in a single write call, to minimize tool call turns for the same result.
- read: Get value for key
- delete: Remove key

Limits:
- Max keys: 20
- Max value length: 2048 chars per value
```

**Parameter schema** — current:
```json
{
  "type": "object",
  "properties": {
    "action": {"type": "string", "enum": ["write", "read", "delete"], "description": "Action to perform"},
    "key": {"type": "string", "description": "Key for write/read/delete"},
    "value": {"type": "string", "description": "Value for write action"},
    "agent_thought": {"type": "string", "description": "Brief reason for why this action is being performed"}
  },
  "required": ["action"],
  "additionalProperties": false
}
```

New:
```json
{
  "type": "object",
  "properties": {
    "action": {"type": "string", "enum": ["write", "read", "delete"], "description": "Action to perform"},
    "content": {"type": "string", "description": "JSON object string for write. Example: {\"key1\": \"value1\", \"key2\": \"value2\"}"},
    "key": {"type": "string", "description": "Key for read/delete"},
    "agent_thought": {"type": "string", "description": "Brief reason for why this action is being performed"}
  },
  "required": ["action"],
  "additionalProperties": false
}
```

Note: `value` param removed, `content` param added. `key` stays for read/delete only.

**Validation changes**:
- `write`: require `content` (not `key`+`value`). Parse as JSONObject. Validate each entry's key length, value length, total entry count.
- `read`/`delete`: unchanged — still require `key`.

**Execute changes**:
- `write`: parse `content` as JSONObject, iterate keys, call `state.write(key, value)` for each. Return summary like `"Stored 3 keys: email_subject, sender, total."`.
- `read`: call `state.read(key)`, return JSON with native value type.
- `delete`: unchanged.

**Description builder**:
- `write`: `"Write scratchpad: 3 keys"` (instead of `"Write scratchpad key 'email_subject'"`)

### 3. `agent/definition/StandaloneAgentDef.kt` — System Prompt

Current scratchpad-related lines in Calling Conventions section:
```
- BATCH your tools. Always combine cognitive updates (`scratchpad`) with your next screen action if any (`mobile_action`, etc.) in the SAME turn. Do not wait for a separate turn just to update memory.
- Prefer at most ONE screen-affecting action per turn (`mobile_action`, `open_app`, `system_button`, `wait`), then observe.
- Use `scratchpad` to store extracted facts and avoid repeated extraction.
- Scratchpad context shows keys only; use `scratchpad(action="read", key="...")` when value is needed.
```

New:
```
- BATCH your tools. Always combine cognitive updates (`scratchpad`) with your next screen action if any (`mobile_action`, etc.) in the SAME turn. Do not wait for a separate turn just to update memory.
- Prefer at most ONE screen-affecting action per turn (`mobile_action`, `open_app`, `system_button`, `wait`), then observe.
- Use `scratchpad` to store extracted facts and avoid repeated extraction. Capture ALL relevant data from the current screen in a single write call.
- Scratchpad values are shown in context every turn (truncated if long). Use `scratchpad(action="read", key="...")` only for truncated values.
```

### 4. `agent/definition/PlannerAgentDef.kt` — System Prompt

Current scratchpad section:
```
### scratchpad (Shared with Executor)
Use scratchpad to store extracted data and progress so the Executor can read/write it:
- Scratchpad context shows keys only. Read values explicitly when needed.
- Write facts before navigation when data may disappear.
- scratchpad(action="write", key="email_1", value="From: X, Subject: Y")
- scratchpad(action="write", key="emails_read", value="3")
- scratchpad(action="read", key="email_1")
```

New:
```
### scratchpad (Shared with Executor)
Use scratchpad to store extracted data and progress so the Executor can read/write it:
- Scratchpad values are shown in context every turn (truncated if long). Use read only for truncated values.
- Write facts before navigation when data may disappear.
- Capture ALL relevant data from the current screen in a single write call.
- scratchpad(action="write", content='{"email_1_from": "X", "email_1_subject": "Y", "emails_read": 3}')
- scratchpad(action="read", key="email_1_from")
```

Also update Calling Conventions lines:
```
- You may combine `scratchpad` with that execution tool in the same turn.
- Use `scratchpad` to track progress and facts.
```
→
```
- You may combine `scratchpad` with that execution tool in the same turn.
- Use `scratchpad` to track progress and facts. Capture all relevant data in a single write call.
```

### 5. `agent/definition/ExecutorAgentDef.kt` — System Prompt

Current scratchpad section:
```
## Scratchpad (Shared with Planner)
Use scratchpad to store extracted data so the Planner can access it:
- Scratchpad context shows keys only. Use read when you need a stored value.
- scratchpad(action="write", key="email_1_sender", value="John Doe")
- scratchpad(action="read", key="...")
```

New:
```
## Scratchpad (Shared with Planner)
Use scratchpad to store extracted data so the Planner can access it:
- Scratchpad values are shown in context every turn (truncated if long). Use read only for truncated values.
- Capture ALL relevant data from the current screen in a single write call.
- scratchpad(action="write", content='{"email_1_sender": "John Doe", "email_1_subject": "Meeting"}')
- scratchpad(action="read", key="email_1_sender")
```

Also update Calling Conventions:
```
- BATCH your tools. Always combine cognitive updates (`scratchpad`) with your next screen action if any (`mobile_action`, etc.) in the SAME turn.
```
→ (unchanged — already correct)

### 6. Checkpoint: `history/model/SessionRuntimeSnapshot.kt`

Current:
```kotlin
val scratchpad: Map<String, String>,
```

New:
```kotlin
val scratchpad: Map<String, Any>,
```

Or keep as `Map<String, String>` for serialization and convert at hydration. The simplest approach: serialize `JSONObject` → JSON string field in snapshot, deserialize back. This avoids `Map<String, Any>` serialization complexity with Moshi/Gson.

Alternative: change to `String` (raw JSON string):
```kotlin
val scratchpadJson: String,  // JSONObject.toString()
```

### 7. Checkpoint hydration: `session/AgentSession.kt`

Current (line 162-163):
```kotlin
snapshot.scratchpad.forEach { (key, value) ->
    services.sessionState.scratchpad.write(key, value)
}
```

Adapt to match the new snapshot format. If `scratchpadJson: String`:
```kotlin
val json = JSONObject(snapshot.scratchpadJson)
json.keys().forEach { key ->
    services.sessionState.scratchpad.write(key, json.get(key))
}
```

### 8. Checkpoint building: `session/SessionCheckpointCoordinator.kt`

Current (line 70, 77):
```kotlin
val scratchpad = sessionState.scratchpad.toMap()
// ...
scratchpad = scratchpad,
```

Change to:
```kotlin
val scratchpadJson = sessionState.scratchpad.toJsonObject().toString()
// ...
scratchpadJson = scratchpadJson,
```

### Files Unchanged

| File | Why |
|------|-----|
| `session/AgentSessionState.kt` | No structural change — still holds `ScratchpadState` |
| `tool/ToolName.kt` | `Scratchpad` tool name unchanged |
| `agent/cognition/prompt/PromptBuilder.kt` | Already calls `sessionState.scratchpad.toPromptContext()` — no change needed |
| `agent/cognition/policy/TurnToolPolicy.kt` | Scratchpad is still cognitive/non-screen-changing — no policy change |

### Test Updates

| Test File | Changes Needed |
|-----------|----------------|
| `session/ScratchpadStateTest.kt` | Update `write()` calls, `read()` assertions (now `Any?`), `toPromptContext` assertions (JSON format instead of `"- key"`), test truncation behavior |
| `tool/impl/ScratchpadToolTest.kt` | Update validation tests (content param instead of key+value), execution tests (JSON write, batch write), add JSON parse error test |
| `agent/cognition/prompt/PromptBuilderTest.kt` | Update `"- email_count"` assertion to JSON format assertion |
| `history/SessionStorageTest.kt` | Update `scratchpad = emptyMap()` to match new snapshot format |
| `agent/definition/AgentDefTest.kt` | No change — only tests tool name lists, not prompt content |
| `agent/cognition/policy/TurnToolPolicyTest.kt` | No change — only uses `"scratchpad"` as tool name string |

## Tradeoffs

| Aspect | Current | Proposed |
|--------|---------|----------|
| **Write granularity** | 1 key per call (N calls for N facts) | 1-N keys per call (1 call for all screen facts) |
| **Prompt visibility** | Keys only → requires `read` call | Key+value JSON (truncated) → most values visible immediately |
| **Schema complexity** | Simple `key`+`value` params | `content` JSON string — slightly more complex but LLM-friendly |
| **Value types** | String only | Any JSON type (string, number, boolean, array, object) |
| **Internal storage** | `Map<String, String>` — flat, lossy coercion for non-strings | `JSONObject` — native types, lossless |
| **Prompt format** | Markdown list of keys | JSON object (consistent with write format) |
| **Token cost per turn** | ~100-200 tokens for scratchpad section | ~500-1500 tokens (bounded by TOTAL_BUDGET=3000 chars) |
| **Turn efficiency** | N+1 turns (N writes + 1 read later) | 1 turn (single write) + 0 reads (values visible in prompt) |
| **Error surface** | Minimal (flat params) | JSON parse errors possible — mitigated by clear error messages |
| **Write semantics** | Single key upsert | `dict.update()` on top-level keys — no deep merge |

## Expected Eval Impact

- **ExpenseAddMultiple***: Data extraction drops from ~6 tool calls to 1 → frees 5+ turns for data entry
- **Cross-app handoffs**: No wasted `read` calls — LLM sees values in Working Memory immediately
- **Overall**: Reduced turn consumption for data-heavy tasks, no impact on simple tasks (single keys still work as `{"key": "value"}`)
