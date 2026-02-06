# Note 2: Memory and Note-Taking Mechanisms

> Comparative analysis of how agents maintain context, store information, and pass data across steps.

---

## Overview

Memory in mobile agents serves multiple purposes:
1. **Cross-step context**: Remembering what was done and observed
2. **Cross-app data transfer**: Carrying information from one app to another
3. **Error recovery**: Understanding what failed and why

| Framework | Memory Type | Note-Taking Agent | Trigger |
|-----------|-------------|-------------------|---------|
| **DroidRun** | Append-only string | Executor (via `remember` tool) | On demand |
| **AutoDev** | Scratchpad (key-value) | Planner/Executor | On demand |
| **Mobile Agent v3** | InfoPool fields | Notetaker (dedicated) | After successful action |
| **MiniTap** | scratchpad + agents_thoughts | save_note/read_note tools | On demand |

---

## Memory Mechanisms by Framework

### 1. DroidRun: Append-Only Memory String

**Storage**: Single `memory` string in `DroidAgentState`

**Write Mechanism**:
```python
# Executor uses remember tool
{"action": "remember", "information": "At step 3, I obtained email 'john@test.com' from contact card"}
```

**Read Mechanism**: Manager sees full `memory` string in prompt

**When to Write**: 
- Executor can call `remember` tool at any time
- Manager suggests memory via `<add_memory>` tag in response

**Format Convention**:
```
"At step X, I obtained [content] from [source]"
```

**Key Points**:
- Append-only (never deleted)
- Executor requested to NOT copy-paste, but use memory
- Manager includes memory in `<important_notes>` section of prompt

---

### 2. AutoDev: Scratchpad Key-Value Store

**Storage**: Dictionary accessed via `createItem` / `fetchItem` tools

**Write Mechanism**:
```python
# Both Planner and Executor can use
createItem(key='PAD-1', title='Task Items', text='["item1", "item2"]')
```

**Read Mechanism**:
```python
fetchItem(key='PAD-1')  # Returns stored content
```

**When to Write**:
- Multi-item tasks: Extract all items first → store → process one by one
- Cross-app transfers: Store in app A, retrieve in app B

**Usage Pattern** (from prompts):
```
Multi-item workflow:
1. Extract all items from source app
2. createItem(key='PAD-1', ...) to store
3. Navigate to destination app
4. fetchItem(key='PAD-1') to retrieve
5. Process items one by one
```

**Key Points**:
- Persistent across Executor sessions
- Planner orchestrates when to store/retrieve
- Used for "copy data between apps" tasks
- Executor is **stateless** (no memory between tool calls), so must use Scratchpad

---

### 3. Mobile Agent v3: InfoPool + Notetaker Agent

**Storage**: Multiple fields in `InfoPool` dataclass

```python
# Working Memory (all agents can access)
action_history: list        # All executed actions
summary_history: list       # Action descriptions
action_outcomes: list       # "A", "B", "C" outcomes
error_descriptions: list    # Error feedback

# Cross-step Memory (Notetaker manages)
important_notes: str        # Key information for task completion
```

**Write Mechanism**: Dedicated **Notetaker Agent**

**Trigger Conditions**:
1. After successful action (outcome == "A")
2. Notetaker flag enabled (`--notetaker True`)
3. Task requires memory (answers, transactions, products)

**Notetaker Prompt Rules**:
```
IMPORTANT:
- Do not take notes on low-level actions
- Only keep track of significant textual or visual information
- Do not repeat user request or progress status
- Do not make up content
```

**Read Mechanism**: Manager sees `important_notes` in re-planning prompt
```
### Important Notes ###
{important_notes or "No important notes recorded."}
```

**Key Points**:
- Notetaker is **optional** (not all tasks need it)
- Runs after ActionReflector confirms success
- Task-specific guidelines can be injected (e.g., "Only record DCIM transactions")

---

### 4. MiniTap: Scratchpad + Agent Thoughts

**Storage**: Two mechanisms

**1. Scratchpad (Persistent Key-Value)**:
```python
# Tools available
save_note(key: str, value: str)  # Persist data
read_note(key: str) -> str       # Retrieve data
list_notes() -> list[str]        # List all keys
```

**2. Agent Thoughts (Running Log)**:
```python
agents_thoughts: list[str]  # All agent reasoning history
```

**Write Mechanism**:
- Scratchpad: Via explicit `save_note` tool calls
- Thoughts: Automatically appended after each agent runs

**Example Cross-App Workflow**:
```
Goal: "Copy ingredients from RecipeApp to ShoppingApp"

Subgoals:
1. Open RecipeApp and navigate to recipe
2. Use save_note to save ingredient list ("INGREDIENTS": "eggs, flour, sugar")
3. Open ShoppingApp
4. Use read_note("INGREDIENTS") and add items to shopping list
```

**Key Points**:
- `agents_thoughts` is used for failure analysis (Planner sees history)
- Scratchpad persists across entire task (survives replanning)
- Summarizer trims old thoughts to prevent context overflow
- Thoughts capped at 25 entries in Contextor

---

## Comparison Table

| Aspect | DroidRun | AutoDev | Mobile Agent v3 | MiniTap |
|--------|----------|---------|-----------------|---------|
| **Memory Format** | String | Dict | Dataclass fields | Dict + List |
| **Write Actor** | Executor/Manager | Planner/Executor | Notetaker | Tools |
| **Read Actor** | Manager | Any | Manager | Cortex |
| **Persistence** | Session-long | Session-long | Session-long | Session-long |
| **Structured?** | No (freeform) | Yes (JSON) | Partial | Yes (JSON) |
| **Cross-app** | Manual copy | Scratchpad | Notetaker | Scratchpad |
| **Failure Context** | error_history | Executor report | action_outcomes | agents_thoughts |

---

## Note-Taking Patterns

### Pattern 1: Immediate Capture (DroidRun)
```
Action: found contact email = "john@test.com"
         │
         ▼
Executor: remember("At step 3, obtained email john@test.com from contact")
         │
         ▼
Memory: "At step 3, obtained email john@test.com from contact"
```

### Pattern 2: Post-Verification Capture (Mobile Agent v3)
```
Action: viewed product price = "$49.99"
         │
         ▼
ActionReflector: Outcome = A (success)
         │
         ▼
Notetaker: "Product XYZ is priced at $49.99"
         │
         ▼
important_notes: "Product XYZ is priced at $49.99"
```

### Pattern 3: Explicit Persist (AutoDev, MiniTap)
```
Planner: "Store all contact names for later"
         │
         ▼
Executor: scan_for_element("find all contact names")
         │
         ▼
Executor: createItem(key="CONTACTS", text='["John", "Jane", "Bob"]')
         │
         ▼
Scratchpad: {"CONTACTS": '["John", "Jane", "Bob"]'}
```

---

## Failure Context Mechanisms

### DroidRun: error_history in `<potentially_stuck>`
```xml
<potentially_stuck>
The last 3 actions failed:
1. Action: click(5) | Outcome: Failed - element not found
2. Action: click(5) | Outcome: Failed - element not found
3. Action: swipe("up") | Outcome: Failed - no change
</potentially_stuck>
```

### Mobile Agent v3: action_outcomes + error_descriptions
```
### Latest Action History ###
Action: {"action": "click", "coordinate": [500, 800]} | Description: Click login button | Outcome: Failed | Feedback: Element not visible
Action: {"action": "swipe", ...} | Description: Scroll down | Outcome: Failed | Feedback: Content same as before
```

### MiniTap: Full agents_thoughts History
```python
# Planner sees recent thoughts
agents_thoughts[-25:]  # Last 25 reasoning steps from all agents
```

---

## Design Recommendations

1. **Separate memory types**:
   - **Working memory**: What happened (action history, outcomes)
   - **Important notes**: What to remember (extracted information)
   
2. **Key-value for cross-app**: Scratchpad pattern is robust for multi-app tasks

3. **Conditional note-taking**: Only invoke Notetaker when task requires memory

4. **Failure context window**: Show last N failed actions to help recovery

5. **Structured over freeform**: JSON structures in scratchpad enable reliable retrieval

6. **Context limits**: Summarize/trim old history to prevent overflow
