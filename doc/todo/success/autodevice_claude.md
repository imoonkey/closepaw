# AutoDevice (Android World) Analysis

**Status**: High-performing AndroidWorld agent  
**Architecture**: Dual-agent (Planner + Executor)  
**Source**: `.reference/autodevice_android_world/`

## Executive Summary

AutoDevice uses a simpler dual-agent architecture (Planner + Executor) compared to Minitap's multi-agent system. Key innovations:
- **Planner orchestrates, Executor executes** - clear separation
- **Todo list tracking** for complex multi-step tasks
- **Scratchpad memory** shared between agents
- **Screen transcription tool** for explicit text extraction
- **Comprehensive failure handling** with narrative summaries

---

## Architecture Overview

```
User Goal → Planner → [Tool Calls: go_back, answer, finish_task, update_todos, etc.]
                 ↓
           execute_step(planner_tool_call)
                 ↓
            Executor → [Low-level actions: click, scroll, input_text, etc.]
                 ↓
            report() back to Planner
```

### Agent Roles

| Agent | Model | Responsibility |
|-------|-------|----------------|
| **Planner** | Gemini 3 Pro / Claude Sonnet | Strategic planning, todo management, high-level decisions |
| **Executor** | Claude Sonnet/Haiku | Low-level UI interactions, precise screen navigation |

### Why Dual-Agent Works

1. **Model selection by difficulty**: Easy/medium tasks use faster models
2. **Clear boundary**: Planner never touches UI, Executor never strategizes
3. **Shared scratchpad**: Data flows between agents via persistent memory
4. **Step limits**: Executor has MAX_EXECUTOR_STEPS (10) to prevent infinite loops

---

## Tool Design

### Planner Tools
```python
# High-level control tools
go_back                # Navigate back
answer(text)           # Answer user question
finish_task           # Mark task complete
update_todos(todos)   # Manage todo list
createItem(key, title, text)  # Store in scratchpad
fetchItem(key)        # Read from scratchpad
transcribe_screen()   # Get screen text content
```

### Executor Tools
```python
# Low-level UI actions
click(x, y)
double_tap(x, y)
long_press(x, y)
scroll(direction, x?, y?)
swipe(direction, x?, y?)
swipe_coords(start_x, start_y, end_x, end_y)
input_text(text, x?, y?, clear_text?)
keyboard_enter()
navigate_back()
navigate_home()
open_app(app_name)
wait()
tap(x, y)
type_text(text, clear_first?)

# Communication tools
report(notes)         # Report back to planner
extracted_data(data)  # Return extracted data
transcribe_screen()   # Read screen content
createItem/fetchItem  # Scratchpad access
```

### Key Tool Innovations

#### 1. Coordinate Scaling
```python
SCALE = 0.4  # Screenshots scaled down for model input

def click(x: int, y: int) -> JSONAction:
    return JSONAction(
        action_type="click", 
        x=int(int(x) / SCALE), 
        y=int(int(y) / SCALE)
    )
```

All coordinates are scaled for model input and unscaled for execution.

#### 2. Transcribe Screen Tool
Explicit tool for text extraction (not automatic):
```python
def transcribe_screen() -> str:
    """Transcribe all text and UI elements visible on screen.
    
    Use when you need to:
    - Read file content
    - Extract list items
    - Read form fields, search results, or any text
    - Find UI elements and their labels
    """
```

This is **on-demand** - agents must call it explicitly.

#### 3. Report Tool
Executor reports back with structured feedback:
```python
def report(notes: str):
    """Reports achievement status and observations.
    
    Include:
    - What was completed
    - Success/failure
    - Verification result
    - Current screen state
    """
```

---

## System Prompts Analysis

### Planner System Prompt (Key Sections)

#### Workflow
```markdown
=== YOUR WORKFLOW ===
1. ANALYZE: Read goal, analyze screenshot directly
2. PLAN: Create todo list using update_todos()
3. EXECUTE: Issue tool calls with precise intent
4. VERIFY: Check progress, update todos
5. ANSWER: For count/search tasks, call answer() then finish_task()
```

#### Critical Instructions
```markdown
**CRITICAL**: Give COMPLETE, DETAILED subgoals. 
Executor has NO MEMORY - every instruction must be self-contained.

**For multi-item tasks**: 
- Extract ALL items based on criteria FIRST
- Call transcribe_screen() to read list
- Scroll and transcribe again
- Store ALL in scratchpad
- Then process ALL items in target app
```

#### Failure Handling
```markdown
=== EXECUTOR FAILURE HANDLING ===
When executor reports "Max executor steps reached":
1. READ the narrative summary carefully
2. ANALYZE the failure - understand strategy attempted
3. TRY ALTERNATIVE APPROACH - do NOT repeat same approach
4. LEARN FROM FAILURES
```

### Executor System Prompt (Key Sections)

#### Core Responsibility
```markdown
YOUR RESPONSIBILITIES:
1. Understand task from query before acting
2. Execute steps in sequence
3. Interact with Android UI elements accurately
4. Verify actions succeeded
5. Handle errors gracefully
6. You MUST make a tool call on every turn
```

#### Loop Prevention
```markdown
**CRITICAL - LOOP PREVENTION**: 
- BEFORE scrolling: Call transcribe_screen(), note visible items
- AFTER scrolling: Call transcribe_screen(), compare to previous
- If transcription IDENTICAL: STOP scrolling, report failure
- If scrolled 3+ times without new content: verify stuck, STOP
```

#### Max Steps Summary
```markdown
**When to provide summary:**
- If stuck or cannot complete in remaining steps
- If tried multiple approaches without success
- When reaching maximum steps

**Summary must include:**
1. What you tried to accomplish
2. Approach taken (overall strategy)
3. What didn't work and why
4. What you observed on screen
5. Alternative approaches to try
```

---

## State Management

### Navigation State Tracking
```python
self.navigation_state = {
    "seen_items": set(),       # Items/dates seen in transcriptions
    "scroll_history": [],      # Scroll directions and visibility
    "visited_screens": [],     # Navigation paths
    "last_visible_dates": [],  # Dates visible in last transcription
    "scroll_direction": None,  # "up", "down", or None
    "scroll_count": 0,         # Count of scrolls in current search
    "seen_screenshots": set(), # Hash of seen screenshots
    "seen_text_hashes": set(), # Hash of seen transcriptions
}
```

### Duplicate Detection
```python
def _has_seen_content(self, transcription: Optional[str]) -> bool:
    """Check if we've seen this content before."""
    # Check exact text hash
    text_hash = hash(visible["text"])
    if text_hash in seen_hashes:
        return True
    
    # Check first few items (for list apps)
    first_items = visible["items"][:5]
    if all(item in seen_items for item in first_items):
        return True
    
    return False
```

---

## Memory System

### Shared Scratchpad
```python
class Scratchpad:
    """Shared storage for planner and executor across sessions."""
    
    def create_item(self, key: str, title: str, text: str):
        """Store data with PAD-1, PAD-2, etc. format."""
        
    def fetch_item(self, key: str):
        """Retrieve stored data by key."""
        
    def get_system_reminder(self) -> str:
        """Returns current scratchpad state for system prompt."""
```

### Todo List
```python
class TodoList:
    """Structured task tracking for complex tasks."""
    
    # Status: pending, in_progress, completed
    # Priority: high, medium, low
    
    def update(self, todos: List[Dict]):
        """Replace todo list with new state."""
        
    def get_system_reminder(self) -> str:
        """Returns todo state for system prompt."""
```

---

## Success Factors

### 1. Model Selection by Difficulty
```python
def _get_planner_model(task_difficulty: Optional[str]) -> str:
    if task_difficulty in ("easy", "medium"):
        return "anthropic/claude-sonnet-4-5-20250929"
    else:
        return "gemini/gemini-3-pro-preview"
```

### 2. Explicit Transcription
- Screenshots are sent to model but text is NOT auto-extracted
- Agent must call `transcribe_screen()` when needed
- Prevents token waste on irrelevant screens

### 3. Loop Prevention
- Navigation state tracks seen content
- Warnings injected when screen revisited
- Hard limits on scroll count

### 4. Structured Failure Reporting
- Executor provides narrative summary on failure
- Planner can pivot strategy based on summary
- No blind retrying

### 5. Complete Instructions
- Executor has NO memory between calls
- Every instruction must be self-contained
- Scratchpad bridges the memory gap

---

## Comparison: AutoDevice vs Minitap

| Aspect | AutoDevice | Minitap |
|--------|-----------|---------|
| Agents | 2 (Planner + Executor) | 6 (Planner, Orchestrator, Contextor, Cortex, Executor, Summarizer) |
| Framework | Custom Python | LangGraph |
| Targeting | Coordinates only | Multi-selector (coords, resource_id, text) |
| Memory | Scratchpad + TodoList | Scratchpad + Agent Thoughts |
| Transcription | On-demand tool | Automatic with UI hierarchy |
| Subgoals | Via TodoList | Via Subgoal Plan object |
| Loop Prevention | Navigation state hash | Agent thoughts analysis |

---

## Applicability to Our Agent

### Can Adopt Immediately
1. **TodoList tool** for complex task tracking
2. **Scratchpad tool** for cross-session memory
3. **Navigation state tracking** for loop prevention
4. **On-demand transcription** (not automatic)
5. **Narrative failure summaries** for better error recovery

### Requires Architecture Change
1. Dual-agent separation (Planner + Executor)
2. Executor step limits with reporting
3. Model selection based on task difficulty

### Key Takeaways
- **Self-contained instructions**: Executor has no memory, must be complete
- **Explicit is better**: Transcription on-demand saves tokens
- **Loop prevention is critical**: Hash-based duplicate detection
- **Failure narratives**: Better than raw error messages for recovery
