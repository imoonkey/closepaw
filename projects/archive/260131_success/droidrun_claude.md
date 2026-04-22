# DroidRun Analysis

**Status**: 91.4% AndroidWorld benchmark  
**Architecture**: Multi-agent with LlamaIndex Workflows  
**Source**: `.reference/mobile_agent/droidrun/`

## Executive Summary

DroidRun is a production-grade framework achieving 91.4% on AndroidWorld through:
- **Flexible multi-agent modes**: Manager+Executor (reasoning) or CodeAct (direct execution)
- **App Cards**: Pre-loaded app-specific knowledge for better navigation
- **Memory system**: Persistent `remember()` tool with step context
- **Scripter agent**: Off-device Python execution for web requests, etc.
- **Custom tool extensibility**: MCP integration and credential management

---

## Architecture Overview

### Two Operating Modes

```
Mode 1 (reasoning=True):
User Goal → Manager → Executor → Loop until complete

Mode 2 (reasoning=False, CodeAct):
User Goal → CodeActAgent → Python code execution → Loop
```

### Agent Roles

| Agent | Role | Key Responsibility |
|-------|------|-------------------|
| **DroidAgent** | Orchestrator | Workflow coordination, mode selection |
| **ManagerAgent** | Planner | High-level planning, progress tracking, memory |
| **ExecutorAgent** | Action execution | Single action selection and execution |
| **CodeActAgent** | Direct execution | Generates Python code using atomic actions |
| **ScripterAgent** | Off-device | Executes Python for web requests, file ops |
| **TextManipulator** | Text editing | Manipulates text in focused input fields |

### Why This Works

1. **Mode flexibility**: Simple tasks use CodeAct (faster), complex tasks use reasoning
2. **App Cards**: Pre-loaded knowledge reduces exploration time
3. **Error escalation**: Consecutive failures trigger replanning
4. **Off-device capability**: Scripter handles web APIs, file operations
5. **Credential management**: Secure typing of secrets without exposure

---

## Tool Design

### Atomic Actions
```python
ATOMIC_ACTION_SIGNATURES = {
    "click": {
        "arguments": ["index"],
        "description": 'Click element at index. {"action": "click", "index": 5}'
    },
    "long_press": {
        "arguments": ["index"],
        "description": 'Long press at index. {"action": "long_press", "index": 5}'
    },
    "click_at": {
        "arguments": ["x", "y"],
        "description": 'Click at coordinates. {"action": "click_at", "x": 500, "y": 300}'
    },
    "click_area": {
        "arguments": ["x1", "y1", "x2", "y2"],
        "description": 'Click center of area. {"action": "click_area", ...}'
    },
    "long_press_at": {
        "arguments": ["x", "y"],
        "description": 'Long press at coordinates.'
    },
    "type": {
        "arguments": ["text", "index", "clear=False"],
        "description": 'Type text. Index focuses field. clear=True clears first.'
    },
    "system_button": {
        "arguments": ["button"],
        "description": 'Press system button (back, home, enter).'
    },
    "swipe": {
        "arguments": ["coordinate", "coordinate2", "duration=1.0"],
        "description": 'Swipe from coord to coord2.'
    },
    "wait": {
        "arguments": ["duration"],
        "description": 'Wait for duration seconds.'
    },
}
```

### Key Tool Innovations

#### 1. Multiple Targeting Options
- `click(index)` - Index-based (primary)
- `click_at(x, y)` - Coordinate-based (fallback)
- `click_area(x1, y1, x2, y2)` - Area center click

#### 2. Remember Tool for Memory
```python
remember(information: str)  # Store info for later use
complete(success: bool, reason: str)  # Signal completion
```

#### 3. Type with Clear Option
```python
# Append mode (default)
{"action": "type", "text": "hello", "index": 5}

# Replace mode (clear first)
{"action": "type", "text": "new text", "index": 5, "clear": True}
```

#### 4. Credential Management
```python
type_secret(secret_id: str, index: int)
# Types secret without agent seeing the value
```

---

## System Prompts Analysis

### Manager System Prompt (Key Sections)

#### Core Guidelines
```markdown
<guidelines>
1. Use `open_app` action to open apps, don't use app drawer
2. Use search to quickly find files/entries with specific names
3. Only use clipboard when task specifically requires it
4. Store information in Memory section instead of clipboard
5. File names must match exactly
6. Names must not be cutoff - check full names
7. Dates and file names must match user query exactly
8. Don't do more than what the user asks for
</guidelines>
```

#### Memory Usage Rules
```markdown
Memory Usage:
- Always include step context: "At step [number], I obtained [actual content] from [source]"
- Store the actual content you observe, not just references
- Use memory instead of copying text unless specifically requested
- Memory is append-only
- Update memory to track progress on multi-step tasks
```

#### Script Execution
```markdown
<scripter_execution>
Use <script> tags for off-device Python operations:
- Downloading files from internet
- Making HTTP API calls
- Sending webhooks
- Processing data (JSON, XML, CSV)

When NOT to use:
- Device interactions (use regular subgoals)
- Text manipulation (use TEXT_TASK)
</scripter_execution>
```

### Executor System Prompt (Key Sections)

#### Core Identity
```markdown
You are a LOW-LEVEL ACTION EXECUTOR for an Android phone.
You do NOT answer questions or provide results.
You ONLY perform individual atomic actions.
You are part of a larger system - your job is to execute actions, not to think.
```

#### Literal Execution Rule
```markdown
### LITERAL EXECUTION RULE ###
Whatever the current subgoal says to do, do that EXACTLY.
Do not substitute with what you think is better.
Do not optimize.
Do not consider screen state.
Parse the subgoal text literally and execute the matching atomic action.
```

#### Output Format
```markdown
### Thought ###
Break down the current subgoal into:
(1) What atomic action is required?
(2) What target/location is specified?
(3) What parameters do I need?

### Action ###
Valid JSON: {"action": "...", ...}

### Description ###
Brief description of chosen action. Do not describe expected outcome.
```

---

## State Management

### Shared State (DroidAgentState)
```python
@dataclass
class DroidAgentState:
    instruction: str
    step_number: int = 0
    
    # Planning state
    plan: str = ""
    previous_plan: str = ""
    current_subgoal: str = ""
    
    # Execution history
    action_history: list = field(default_factory=list)
    summary_history: list = field(default_factory=list)
    action_outcomes: list = field(default_factory=list)  # bool
    error_descriptions: list = field(default_factory=list)
    
    # Memory
    memory: str = ""  # Append-only memory
    
    # Error handling
    err_to_manager_thresh: int = 2  # Errors before escalation
    error_flag_plan: bool = False
    
    # Device state
    formatted_device_state: str = ""
    previous_formatted_device_state: str = ""
    screenshot: bytes | None = None
    
    # App tracking
    visited_packages: set = field(default_factory=set)
    visited_activities: set = field(default_factory=set)
    current_package_name: str = ""
```

### Error Escalation
```python
# Check error escalation in DroidAgent
err_thresh = self.shared_state.err_to_manager_thresh

if len(self.shared_state.action_outcomes) >= err_thresh:
    latest = self.shared_state.action_outcomes[-err_thresh:]
    error_count = sum(1 for o in latest if not o)
    if error_count == err_thresh:
        logger.warning(f"⚠️ Error escalation: {err_thresh} consecutive errors")
        self.shared_state.error_flag_plan = True
```

---

## Unique Features

### 1. App Cards
Pre-loaded app-specific knowledge:
```python
class AppCardProvider:
    async def load_app_card(self, package_name: str, instruction: str) -> str:
        """Load app card with navigation hints."""
```

Example app card (Gmail):
```markdown
## Gmail Navigation
- Compose: Tap floating action button (bottom right)
- Search: Use search bar at top
- Settings: Menu → Settings
- Labels: Swipe left/right on message to access actions
```

### 2. CodeAct Mode
Direct Python code execution:
```python
# Agent generates code like:
await click(5)
text = await get_element_text(3)
await remember(f"Found: {text}")
await type("hello", index=7)
await complete(True, "Task finished")
```

### 3. Text Manipulator
Python-based text editing:
```markdown
In plan: TEXT_TASK: Add "Hello World" at the beginning

TextManipulator generates Python code to modify focused text field
```

### 4. MCP Integration
Model Context Protocol for custom tools:
```python
if self.config.mcp and self.config.mcp.enabled:
    self.mcp_manager = MCPClientManager(self.config.mcp)
    await self.mcp_manager.discover_tools()
    mcp_tools = mcp_to_droidrun_tools(self.mcp_manager)
```

---

## Success Factors

### 1. Dual Mode Operation
- **Reasoning mode**: Better for complex, multi-step tasks
- **CodeAct mode**: Faster for simple, direct tasks

### 2. App Cards
- Pre-loaded knowledge reduces exploration
- App-specific navigation hints
- Reduces wrong turns

### 3. Strict Executor Role
- "Dumb robot" execution prevents overengineering
- Manager handles strategy, Executor just acts
- Clear separation prevents confusion

### 4. Memory with Context
- "At step X, I obtained Y from Z" format
- Append-only prevents overwrites
- Step context helps debug

### 5. Error Escalation
- Consecutive failures trigger replanning
- Prevents infinite retry loops
- Manager can pivot strategy

### 6. Off-Device Operations
- Scripter handles web APIs, file operations
- Keeps device agent focused on UI
- Enables complex workflows

---

## Comparison with Other Approaches

| Aspect | DroidRun | Minitap | AutoDevice |
|--------|----------|---------|------------|
| Agents | 5+ (flexible) | 6 | 2 |
| Framework | LlamaIndex Workflows | LangGraph | Custom |
| Targeting | Index + Coordinates | Multi-selector | Coordinates |
| Memory | remember() tool | Scratchpad | Scratchpad + TodoList |
| App Knowledge | App Cards | None | None |
| Off-Device | Scripter Agent | None | None |
| Modes | Reasoning + CodeAct | Single | Single |

---

## Applicability to Our Agent

### Can Adopt Immediately
1. **remember() tool** for persistent memory with step context
2. **Multiple click variants** (index, coordinates, area)
3. **Type with clear option** for replace vs append
4. **Error escalation logic** - consecutive failures trigger different strategy
5. **Strict executor role** - "dumb robot" execution

### Medium Effort
1. **App Cards** - Pre-loaded app knowledge
2. **CodeAct mode** - Direct Python code generation
3. **Text Manipulator** - Python-based text editing

### Requires Architecture Change
1. Dual mode operation (reasoning vs direct)
2. LlamaIndex Workflows style
3. Scripter agent for off-device operations
4. MCP integration for custom tools

### Key Takeaways
- **App Cards accelerate navigation**: Pre-loaded knowledge beats exploration
- **Strict role separation works**: "Dumb robot" executor prevents overthinking
- **Memory needs context**: "At step X, obtained Y from Z" format
- **Error escalation is critical**: Consecutive failures → replan
- **Dual modes enable flexibility**: Simple tasks don't need full reasoning
- **Off-device operations expand capability**: Web APIs, file ops via Scripter
