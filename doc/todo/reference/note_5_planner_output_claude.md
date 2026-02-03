# Note 5: Planner Output Formats and Granularity

> Comparative analysis of planner/manager output formats and abstraction levels.

---

## Overview

| Framework | Output Format | Granularity | Element Grounding |
|-----------|--------------|-------------|-------------------|
| **DroidRun** | XML tags + numbered list | Mixed (steps + scripts) | Executor grounds |
| **AutoDev** | Tool calls (semantic intent) | High-level intent | Executor grounds |
| **Mobile Agent v3** | Markdown sections | Step-level | Executor grounds |
| **MiniTap** | JSON subgoals | Checkpoint-level | Cortex grounds |

---

## Output Formats

### 1. DroidRun Manager (XML)

```xml
<thought>Reasoning about the task...</thought>
<add_memory>Key information to remember</add_memory>
<plan>
1. Open Chrome browser
2. Navigate to weather.com
<script>Use requests to fetch weather API</script>
3. Search for "Tokyo weather"
</plan>
<request_accomplished success="true">Temperature is 22°C</request_accomplished>
```

### 2. AutoDev Planner (Tool Calls)

```python
tap(intent="click on the login button")
scroll(intent="scroll down to find signup link")
type_text(text="john@email.com", intent="enter email")
```

**Key**: Planner never specifies coordinates—Executor handles element location.

### 3. Mobile Agent v3 Manager (Markdown)

```markdown
### Thought ###
Need to access WiFi settings and toggle...

### Historical Operations ###
- Opened Settings app

### Plan ###
1. Navigate to Network settings
2. Toggle WiFi switch
3. Verify WiFi is disabled
```

### 4. MiniTap Planner (JSON)

```json
{
  "subgoals": [
    {"description": "Open Settings using launch_app"},
    {"description": "Navigate to Network settings"},
    {"description": "Disable WiFi toggle"},
    {"description": "Verify WiFi shows disabled"}
  ]
}
```

---

## Granularity Hierarchy

```
Intent (AutoDev: "tap on login")
    │
    ▼
Checkpoint (MiniTap: "Complete login flow")
    │
    ▼
Step (Mobile Agent v3: "Enter credentials")  
    │
    ▼
Atomic (Executor: click, type, swipe)
```

---

## Key Insights

1. **Semantic abstraction** (AutoDev): Planner focusing on intent prevents coordinate errors

2. **Checkpoint verification** (MiniTap): Including "Verify X" subgoals enables self-correction

3. **Script delegation** (DroidRun): `<script>` tags separate UI from off-device operations

4. **Multi-item window** (Mobile Agent v3): Showing first 3 items gives Executor context

---

## Design Recommendations

- Keep Planner abstract; let Executor handle grounding
- Include explicit verification subgoals
- Use structured output (Pydantic/JSON) for reliable parsing
- Separate UI vs off-device operations
- No loops in plans—unroll into explicit steps
