# M3A (Multimodal Autonomous Agent for Android) Analysis

**Status**: Baseline agent in AndroidWorld benchmark  
**Architecture**: Single agent with summarization  
**Source**: `.reference/autodevice_android_world/android_world/agents/m3a.py`

## Executive Summary

M3A is a simpler, single-agent approach that serves as the baseline for AndroidWorld. Key features:
- **Single ReAct agent** with action selection + summarization
- **Set-of-Mark (SOM)** visual prompting with bounding boxes
- **Step summaries** as memory mechanism
- **Index-based element targeting** (no coordinate clicks)

---

## Architecture Overview

```
Goal + History → Action Selection → Execute → Summarization → Loop
                    (LLM Call 1)              (LLM Call 2)
```

### Single Agent Flow
1. Get screenshot with SOM annotations (bounding boxes + indexes)
2. Generate action selection prompt with UI elements list
3. LLM selects action with reason
4. Execute action on device
5. Get post-action screenshot
6. LLM summarizes what happened
7. Add summary to history
8. Loop until status=complete

---

## Tool Design

### Action Types (JSON Actions)
```python
# Completion
{"action_type": "status", "goal_status": "complete"}
{"action_type": "status", "goal_status": "infeasible"}

# Interaction
{"action_type": "answer", "text": "<answer_text>"}
{"action_type": "click", "index": <target_index>}
{"action_type": "long_press", "index": <target_index>}
{"action_type": "input_text", "text": <text_input>, "index": <target_index>}
{"action_type": "keyboard_enter"}
{"action_type": "navigate_home"}
{"action_type": "navigate_back"}
{"action_type": "scroll", "direction": <up|down|left|right>, "index": <optional>}
{"action_type": "open_app", "app_name": <name>}
{"action_type": "wait"}
```

### Key Design Choice: Index-Based Targeting
- UI elements are annotated with numeric indexes
- Agent refers to elements by index, not coordinates
- Bounding boxes are drawn on screenshots with index labels

```python
def _generate_ui_element_description(ui_element, index):
    """Generate description for UI element."""
    element_description = f'UI element {index}: {{"index": {index}, '
    if ui_element.text:
        element_description += f'"text": "{ui_element.text}", '
    if ui_element.content_description:
        element_description += f'"content_description": "...", '
    element_description += f'"is_clickable": {ui_element.is_clickable}, '
    element_description += f'"is_editable": {ui_element.is_editable}, '
    # ... more properties
    return element_description
```

---

## System Prompts Analysis

### Action Selection Prompt

#### Prefix
```markdown
You are an agent who can operate an Android phone on behalf of a user.
Based on user's goal/request, you may:
- Answer back if the request/goal is a question
- Complete tasks by performing actions step by step

When given a user request, you will try to complete it step by step.
At each step, you will be given:
- Current screenshot (original + with bounding boxes)
- History of what you have done (in text)
- List of UI elements with numeric indexes
```

#### Guidelines
```markdown
General:
- Usually multiple ways to complete a task, pick the easiest one
- When something doesn't work, sometimes a simple retry can solve it
- If retry doesn't work, SWITCH to other solutions
- May need to navigate phone to gather information
- For questions, remember to use `answer` action before finish!
- If desired state already achieved, just complete the task

Action Related:
- Use `open_app` action to open apps (not app drawer unless all else fails)
- Use `input_text` action for typing (not clicking keyboard keys)
- Index parameter must be VISIBLE in screenshot AND in UI element list
- Consider exploring with `scroll` action to reveal content
- Scroll direction is opposite to swipe (scroll "down" to see bottom content)

Text Related:
- To select text: long press → adjust range with pointers
- To delete text: select → backspace, or place cursor → backspace
- To copy: select text → click copy button
- To paste: long press text box → paste button
```

### Summarization Prompt
```markdown
Summarize the latest step. You will be given:
- Screenshot before action (labeled "before")
- The action you chose with reason
- Screenshot after action (labeled "after")
- UI element lists for both screenshots

Give a brief summary (< 50 words) including:
- What you intended to do
- Why
- If it worked as expected
- If not, what might be the reason
- What should/should not be done next

Given this summary will be added into action history, it can be used as
memory to include information that needs to be remembered, or shared
between different apps.
```

---

## Memory Mechanism

### History as Memory
```python
self.history = []  # List of step summaries

# Each step adds a summary like:
step_data['summary'] = f'Action selected: {action}. {summary}'
self.history.append(step_data)

# History passed to action selection:
history = [
    f'Step {i+1} - {step["summary"]}'
    for i, step in enumerate(self.history)
]
```

### Limitations
- No explicit scratchpad/notes
- Memory is purely in summaries
- Cross-app data transfer relies on "remember in summary"

---

## Visual Prompting (Set-of-Mark)

### Bounding Box Annotations
```python
def add_ui_element_mark(screenshot, ui_element, index, ...):
    """Add bounding box with index label to screenshot."""
    # Draw rectangle around element
    # Add numeric index in top-left corner
```

### Dual Screenshot Input
```python
# Both raw and annotated screenshots sent to model
action_output = self.llm.predict_mm(
    action_prompt,
    [
        step_data['raw_screenshot'],      # Original
        before_screenshot,                 # With SOM annotations
    ],
)
```

---

## Success Factors

### 1. Index-Based Targeting
- More reliable than coordinate clicks
- Elements validated against UI tree
- Out-of-range detection built-in

### 2. Step Summaries
- History provides context for decisions
- Can include cross-step information
- Self-reflection on success/failure

### 3. Simple Action Space
- Limited, well-defined actions
- No complex targeting parameters
- Clear JSON format

### 4. Dual Screenshot Approach
- Raw screenshot for visual understanding
- Annotated screenshot for element identification
- Before/after comparison for summarization

---

## Limitations

### 1. No Explicit Memory
- Relies on summaries for data retention
- Poor for complex cross-app workflows
- No structured data storage

### 2. Single Agent Bottleneck
- Same model does strategy + execution
- No separation of concerns
- Harder to debug failures

### 3. Two LLM Calls Per Step
- Action selection + Summarization
- Higher latency and cost
- But better reflection

### 4. Index Dependency
- Requires accurate UI tree
- Elements must be in accessibility tree
- Some elements may be missed

---

## Comparison with Other Approaches

| Aspect | M3A | AutoDevice | Minitap |
|--------|-----|-----------|---------|
| Agents | 1 | 2 | 6 |
| Targeting | Index | Coordinates | Multi-selector |
| Memory | Summaries | Scratchpad + TodoList | Scratchpad + Agent Thoughts |
| Visual | SOM annotations | Scaled screenshots | UI hierarchy + Screenshot |
| LLM calls/step | 2 | 1+ | Multiple (per agent) |

---

## Applicability to Our Agent

### Can Adopt Immediately
1. **Index-based targeting** (we already have this via element list)
2. **Step summaries** for reflection and memory
3. **Before/after screenshot comparison** for verification
4. **UI element descriptions** with properties

### Already Have
- Index-based element targeting
- UI element list with properties
- Basic action space

### Could Improve
1. Add summarization step after each action
2. Use summaries as explicit memory
3. Add before/after screenshot comparison
4. Better out-of-range/invalid index handling

### Key Takeaways
- **Summaries are memory**: Use them to persist information
- **Reflection helps**: Before/after comparison catches failures
- **Simple is baseline**: More complex architectures beat M3A
- **Index > coordinates**: More reliable for element targeting
