# Tool Implementation Comparison

Side-by-side comparison of tools across top AndroidWorld agents.

## Tool List Comparison

| Tool | M3A | AutoDevice | DroidRun | Minitap | Our Agent |
|------|-----|------------|----------|---------|-----------|
| **Click/Tap** | click(index) | click(x,y) | tap_by_index(index) | tap(target) | click(index) |
| **Long Press** | long_press(index) | long_press(x,y) | - (swipe hold) | long_press_on(target) | long_press(index) |
| **Type/Input** | input_text(text, index) | input_text(text, x?, y?, clear?) | input_text(text, index?, clear?) | focus_and_input_text(text, target) | type(text, index?) |
| **Swipe/Scroll** | scroll(dir, index?) | scroll(dir), swipe(dir), swipe_coords() | swipe(sx,sy,ex,ey,dur) | swipe(target, direction) | swipe(start, end) |
| **Back** | navigate_back | navigate_back | back() | back() | system_button(back) |
| **Home** | navigate_home | navigate_home | press_key(3) | - | system_button(home) |
| **Enter** | keyboard_enter | keyboard_enter | press_key(66) | press_key(enter) | system_button(enter) |
| **Open App** | open_app(name) | open_app(name) | start_app(pkg, activity?) | launch_app(name) | open_app(name) |
| **Wait** | wait | wait | - | wait_for_delay(ms) | wait(duration_ms?) |
| **Memory** | ❌ (summaries) | scratchpad | remember() | save_note/read_note | ❌ |
| **Transcribe** | ❌ | transcribe_screen() | get_state() | ❌ (automatic) | ❌ |
| **Report/Complete** | status(complete/infeasible) | report(notes), finish_task | complete(success, reason) | - | complete_task |

---

## Targeting Strategy Comparison

| Agent | Primary | Fallback 1 | Fallback 2 | Fallback 3 | Index Disambig |
|-------|---------|------------|------------|------------|----------------|
| **M3A** | Index | ❌ | ❌ | ❌ | ❌ |
| **AutoDevice** | Coordinates | ❌ | ❌ | ❌ | ❌ |
| **DroidRun** | Index (cached) | Coordinates | ❌ | ❌ | ❌ |
| **Minitap** | Coordinates | resource_id | text | ❌ | ✅ (resource_id_index, text_index) |
| **Our Agent** | Index | ❌ | ❌ | ❌ | ❌ |

### Minitap Target Object (Best Practice)

```python
class Target:
    resource_id: str | None           # "com.app:id/button"
    resource_id_index: int | None     # For duplicates
    bounds: ElementBounds | None      # {x, y, width, height}
    text: str | None                  # "Submit"
    text_index: int | None            # For duplicate text
```

### Minitap Fallback Order

```
1. bounds (coordinates) → tap_at(center.x, center.y)
2. resource_id + index → tap_element(resource_id, index)
3. text + text_index → tap_element(text=text, index=text_index)
```

---

## Type/Input Text Comparison

| Agent | Focus Method | Clear Support | Cursor Position | Verification |
|-------|--------------|---------------|-----------------|--------------|
| **M3A** | Implicit (action includes focus) | ❌ | ❌ | ❌ |
| **AutoDevice** | Optional coords | ✅ `clear_text=True` | ❌ | ❌ |
| **DroidRun** | Optional index | ✅ `clear=True` | ❌ | ❌ |
| **Minitap** | Multi-selector fallback | Separate tool | ✅ tap near end | ✅ returns full content |
| **Our Agent** | Optional index | ❌ | ❌ | ❌ |

### Minitap focus_and_input_text Flow

```python
1. focus_element_if_needed(target)
   - Try resource_id (check if already focused, tap if not)
   - Fallback to coordinates
   - Fallback to text
   
2. move_cursor_to_end_if_bounds(target)
   - Tap at 99% x, 99% y to position cursor at end
   
3. input_text(text)
   - Type into now-focused element
   
4. Verify (if resource_id provided)
   - Read element text
   - Return full content for agent to verify
```

---

## Memory/Scratchpad Comparison

| Agent | Memory Type | Operations | Context Format | Size Limit |
|-------|-------------|------------|----------------|------------|
| **M3A** | Step summaries | Automatic | "Step N - summary" | History length |
| **AutoDevice** | Scratchpad | create/fetch | PAD-1, PAD-2, etc. | ❌ |
| **DroidRun** | Memory list | remember/get_memory | "At step N, obtained X from Y" | 10 items |
| **Minitap** | Scratchpad | save/read/list | Key-value | ❌ |
| **Our Agent** | Conversation | ❌ | ❌ | Context length |

### DroidRun Memory Format (Recommended)

```markdown
# Store format
remember("At step 5, I obtained recipe from RecipeApp: Chicken Pasta - chicken, pasta, cream")

# Rules
- Include step number for debugging
- Include source location
- Store ACTUAL content, not references
- Use memory instead of clipboard unless clipboard required
```

### Minitap Scratchpad Operations

```python
save_note(key="recipe_1", content="Chicken Pasta - chicken, pasta, cream")
read_note(key="recipe_1")
list_notes()  # Returns ["recipe_1", "recipe_2", ...]
```

---

## Error Handling Comparison

| Agent | Invalid Index | No Element Found | Action Failed | Reporting |
|-------|---------------|------------------|---------------|-----------|
| **M3A** | Range check only | N/A | Simple error | ❌ |
| **AutoDevice** | N/A (coordinates) | N/A | Narrative summary | To planner |
| **DroidRun** | List available indices | List available indices | Returns error string | Tool response |
| **Minitap** | N/A (multi-selector) | Try next selector | All attempts recorded | Detailed failure |
| **Our Agent** | Validation | "Element not found" | Returns failure | Simple error |

### DroidRun Error Message (Best Practice)

```python
if not element:
    indices = sorted(collect_all_indices(cache))
    indices_str = ", ".join(str(idx) for idx in indices[:20])
    if len(indices) > 20:
        indices_str += f"... and {len(indices) - 20} more"
    raise ValueError(f"No element found with index {index}. Available indices: {indices_str}")
```

### Minitap All-Attempts Recording

```python
# Track all attempts
attempts: list[dict] = []
attempts.append({"selector": "coordinates (100, 200)", "error": "Out of bounds"})
attempts.append({"selector": "resource_id='btn'", "error": "No element found"})
attempts.append({"selector": "text='Submit'", "error": "Element not visible"})

# Return detailed failure
return f"Failed to tap. Attempts: {'; '.join([f\"{a['selector']}: {a['error']}\" for a in attempts])}"
```

---

## Overlap/Occlusion Handling

| Agent | Overlap Detection | Solution |
|-------|-------------------|----------|
| **M3A** | ❌ | Tap center blindly |
| **AutoDevice** | ❌ | Tap center blindly |
| **DroidRun** | ✅ | Find clear point via quadrant subdivision |
| **Minitap** | ❌ (relies on fallbacks) | Resource_id/text may find right element |
| **Our Agent** | ❌ | Tap center blindly |

### DroidRun Overlap Avoidance

```python
def tap_on_index(self, index):
    target_bounds = get_bounds(element)
    
    # Find overlapping elements (higher index = on top)
    blockers = [
        el_bounds for el in all_elements
        if el.index > index and rects_overlap(target_bounds, el_bounds)
    ]
    
    # Find clear point that avoids blockers
    point = find_clear_point(target_bounds, blockers)
    if not point:
        raise ValueError(f"Element {index} fully obscured")
    
    tap_at(point)
```

---

## Coordinate Handling

| Agent | Screenshot Scaling | Coordinate Transform | Bounds Validation |
|-------|-------------------|---------------------|-------------------|
| **M3A** | ❌ | N/A (index-based) | Range check |
| **AutoDevice** | 40% | Auto-unscale | ❌ |
| **DroidRun** | Configurable | Uses element bounds | ❌ |
| **Minitap** | ❌ | Device coordinates | ✅ (within screen) |
| **Our Agent** | ❌ | Device coordinates | Negative check |

### AutoDevice Coordinate Scaling

```python
SCALE = 0.4  # Screenshot at 40%

def click(x: int, y: int):
    return JSONAction(
        x=int(x / SCALE),  # LLM sees 250, device gets 625
        y=int(y / SCALE)
    )
```

### Minitap Bounds Validation

```python
def validate_coordinates_bounds(target, screen_width, screen_height):
    center = target.bounds.get_center()
    errors = []
    
    if center.x < 0 or center.x >= screen_width:
        errors.append(f"x={center.x} outside screen width")
    if center.y < 0 or center.y >= screen_height:
        errors.append(f"y={center.y} outside screen height")
    
    return "; ".join(errors) if errors else None
```

---

## Tool Parameter Documentation

| Agent | Docstrings | Examples | Validation Rules |
|-------|------------|----------|------------------|
| **M3A** | Minimal | In prompt | In prompt |
| **AutoDevice** | Comprehensive | ✅ | In docstring |
| **DroidRun** | Comprehensive | ❌ | In validation |
| **Minitap** | Moderate | ❌ | In schema |
| **Our Agent** | Basic | ❌ | In validation |

### AutoDevice Docstring Example

```python
def scroll(direction: str, x: Optional[int] = None, y: Optional[int] = None):
    """Create a scroll action in the specified direction.
    In order to scroll a tiny bit, use swipe in the inverse direction instead.
    
    Args:
        direction: Must be one of: 'up', 'down', 'left', 'right'.
        x: Optional x-coordinate for scroll origin.
        y: Optional y-coordinate for scroll origin.

    Raises:
        ValueError: If direction is not valid.

    Example:
        >>> scroll('down')
        # Scrolls down from center of screen
    """
```

---

## Agent Thought / Reasoning Tracking

| Agent | Required | Format | Storage |
|-------|----------|--------|---------|
| **M3A** | Implicit | "Reason: ..." prefix | In history |
| **AutoDevice** | ❌ | N/A | N/A |
| **DroidRun** | ❌ | N/A | N/A |
| **Minitap** | ✅ `agent_thought` param | Free text | In state |
| **Our Agent** | ❌ | N/A | N/A |

### Minitap agent_thought Parameter

```python
async def tap(
    agent_thought: str,  # WHY this action is performed
    target: Target,
    ...
):
    """
    Every tool requires agent_thought parameter.
    Creates audit trail and helps with failure analysis.
    """
    
    # Stored in state for history
    return Command(update={
        "agents_thoughts": [agent_thought, agent_outcome],
        ...
    })
```

---

## Summary: Feature Gap Analysis

| Feature | Best Implementation | Our Agent Status |
|---------|---------------------|------------------|
| Multi-selector targeting | Minitap | ❌ Missing |
| Index disambiguation | Minitap | ❌ Missing |
| Clear text option | AutoDevice/DroidRun | ❌ Missing |
| Memory/scratchpad | All top performers | ❌ Missing |
| Overlap avoidance | DroidRun | ❌ Missing |
| Helpful error messages | DroidRun | Partial |
| Agent thought tracking | Minitap | ❌ Missing |
| Cursor positioning | Minitap | ❌ Missing |
| Bounds validation | Minitap | Partial |
| On-demand transcription | AutoDevice | ❌ Missing |
