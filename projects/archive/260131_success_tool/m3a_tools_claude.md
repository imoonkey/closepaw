# M3A (Baseline) Tools Analysis

**Leaderboard**: Baseline agent in AndroidWorld  
**Source**: `.reference/autodevice_android_world/android_world/agents/m3a.py`

## Tool List (JSON Actions)

```python
# Completion
{"action_type": "status", "goal_status": "complete"}
{"action_type": "status", "goal_status": "infeasible"}

# Answer
{"action_type": "answer", "text": "<answer_text>"}

# Interaction (Index-Based)
{"action_type": "click", "index": <target_index>}
{"action_type": "long_press", "index": <target_index>}
{"action_type": "input_text", "text": <text_input>, "index": <target_index>}

# Navigation
{"action_type": "keyboard_enter"}
{"action_type": "navigate_home"}
{"action_type": "navigate_back"}

# Scroll
{"action_type": "scroll", "direction": <up|down|left|right>, "index": <optional>}

# App Control
{"action_type": "open_app", "app_name": <name>}

# Utility
{"action_type": "wait"}
```

---

## Targeting: Index-Based Only

### How It Works

1. Screenshot taken with Set-of-Mark (SOM) annotations
2. Bounding boxes drawn around UI elements
3. Numeric indices placed in top-left corner of each box
4. LLM refers to elements by their index number

### UI Element Description Format

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

### Example Element List

```
UI element 0: {"index": 0, "text": "Settings", "is_clickable": true}
UI element 1: {"index": 1, "text": "Search", "is_clickable": true, "is_editable": true}
UI element 2: {"index": 2, "text": "Network", "is_clickable": true}
...
```

---

## Tool Validation: Index Range Check

```python
action_index = converted_action.index
num_ui_elements = len(before_ui_elements)

if (
    converted_action.action_type in ['click', 'long_press', 'input_text', 'scroll']
    and action_index is not None
):
    # Validate index is within range
    if action_index < 0 or action_index >= num_ui_elements:
        # Handle out-of-range index
        ...
```

### Limitations

- Only validates range, not visibility
- No fallback if index is wrong
- No duplicate handling

---

## Memory: Step Summaries Only

### Summary Prompt

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

This summary will be added to action history and can be used as
memory to include information that needs to be remembered, or shared
between different apps.
```

### How History is Used

```python
self.history = []  # List of step summaries

# Each step adds a summary
step_data['summary'] = f'Action selected: {action}. {summary}'
self.history.append(step_data)

# History passed to action selection
history = [
    f'Step {i+1} - {step["summary"]}'
    for i, step in enumerate(self.history)
]
```

### Limitations

- No explicit scratchpad
- Cross-app data transfer relies on "remember in summary"
- Summaries can lose precision over time

---

## Visual Input: Dual Screenshot

```python
# Both raw and annotated screenshots sent to model
action_output = self.llm.predict_mm(
    action_prompt,
    [
        step_data['raw_screenshot'],      # Original for visual understanding
        before_screenshot,                 # With SOM annotations for element IDs
    ],
)
```

### Why Dual Screenshots

- **Raw**: Visual context, colors, icons, layout
- **Annotated**: Element identification via bounding boxes and indices

---

## Prompt Structure

### Action Selection Prompt

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

[Action list with JSON formats...]
[Guidelines...]

Now output an action in correct JSON format, following the reason:
Reason: ...
Action: {"action_type":...}

Your Answer:
```

### Guidelines (Key Points)

```markdown
General:
- Usually multiple ways to complete a task, pick the easiest one
- If retry doesn't work, SWITCH to other solutions
- For questions, remember to use `answer` action before finish!
- If desired state already achieved, just complete the task

Action Related:
- Use `open_app` action to open apps (not app drawer)
- Use `input_text` for typing (not clicking keyboard keys)
- Index must be VISIBLE in screenshot AND in UI element list
- Consider `scroll` to reveal additional content
- Scroll direction is opposite to swipe ("down" = see bottom content)

Text Operations:
- To select text: long press → adjust range with pointers
- To delete: place cursor → backspace (or select → backspace)
- To copy: select text → click copy button
- To paste: long press text box → paste button
```

---

## What M3A Gets Right

### 1. Simple Action Space
- Limited, well-defined actions
- Clear JSON format
- Easy to validate

### 2. Index-Based Targeting
- More reliable than coordinates
- Elements validated against UI tree
- Out-of-range detection built-in

### 3. Step Summaries
- Creates action history
- Self-reflection on success/failure
- Can include cross-step information

### 4. Dual Screenshot Input
- Raw for visual understanding
- Annotated for element identification
- Before/after for summarization

---

## What M3A Lacks (vs Top Performers)

### 1. No Multi-Selector Fallback
- Single index-only targeting
- No `resource_id` or `text` fallback
- No coordinate backup

### 2. No Explicit Memory
- Relies on summaries
- No key-value storage
- Poor for cross-app data transfer

### 3. No Clear Text Option
- Must delete before typing
- Multi-step for replace scenarios

### 4. No Overlap Handling
- Taps center of bounding box
- May hit overlapping element

### 5. Single Agent Bottleneck
- Same model does strategy + execution
- No separation of concerns

---

## Comparison: Our Agent vs M3A

| Feature | M3A | Our Agent |
|---------|-----|-----------|
| Targeting | Index only | Index only |
| Fallback | None | None |
| Memory | Summaries | Conversation |
| Clear text | No | No |
| Overlap handling | No | No |
| Agent split | Single | Single |

### Similarity
Our current implementation is closest to M3A's approach - index-based targeting, single agent, basic action set.

### Opportunity
Adopting features from top performers (Minitap, DroidRun, AutoDevice) would move us beyond baseline.

---

## Applicability to Our Agent

### Already Have (Like M3A)

1. Index-based targeting
2. Simple action space
3. Conversation history
4. Action validation

### Should Add (Unlike M3A)

1. Multi-selector fallback (from Minitap)
2. Clear text option (from DroidRun/AutoDevice)
3. Explicit memory tool (from all top performers)
4. Overlap handling (from DroidRun)
5. Better error messages with available indices

### Key Insight

M3A is the baseline. Beating M3A requires:
- Robust targeting (multiple selectors)
- Persistent memory (scratchpad)
- Better error recovery (failure narratives)
- Smarter tapping (overlap avoidance)
