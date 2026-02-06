# Minitap Tools Analysis

**Leaderboard**: 100% AndroidWorld (First complete solution)  
**Source**: `.reference/mobile_agent/minitap-mobile-use/`

## Tool List

```python
EXECUTOR_WRAPPERS_TOOLS = [
    back_wrapper,
    open_link_wrapper,
    tap_wrapper,
    long_press_on_wrapper,
    swipe_wrapper,
    focus_and_input_text_wrapper,
    erase_one_char_wrapper,
    launch_app_wrapper,
    stop_app_wrapper,
    focus_and_clear_text_wrapper,
    press_key_wrapper,
    wait_for_delay_wrapper,
    # Scratchpad tools for persistent memory
    save_note_wrapper,
    read_note_wrapper,
    list_notes_wrapper,
]
```

---

## Key Innovation: Multi-Selector Fallback Targeting

### Target Object Structure

```python
class Target(BaseModel):
    """A comprehensive locator for a UI element, supporting a fallback mechanism."""

    resource_id: str | None = None                # Primary: by resource ID
    resource_id_index: int | None = None          # For duplicates
    text: str | None = None                       # Fallback: by text content
    text_index: int | None = None                 # For duplicate texts
    bounds: ElementBounds | None = None           # Fallback: by coordinates
```

### Fallback Execution Order in `tap.py`

```python
# Order: coordinates → resource_id → text
1. Try with COORDINATES FIRST (visual approach)
   - Validate bounds are within screen
   - If valid, attempt tap at center of bounds
   - If failed, record attempt and continue

2. If coordinates failed, try with resource_id
   - Use resource_id + optional index
   - If failed, record attempt and continue

3. If resource_id failed, try with text (last resort)
   - Use text + optional text_index
   - If failed, record all attempts in error message
```

### Why This Works

- **Robust to UI changes**: When element index changes, coordinates or text can still work
- **Multiple retry paths**: Each selector is independently attempted
- **Detailed failure reporting**: Records all attempts for debugging
- **Index disambiguation**: `resource_id_index` and `text_index` handle duplicates

---

## Tool Parameters Detail

### tap_wrapper

```python
async def tap(
    agent_thought: str,          # WHY this action is performed (audit trail)
    target: Target,              # Multi-selector target object
    tool_call_id: ...,           # Injected by framework
    state: ...,                  # Injected state
)
```

**Key Features:**
- `agent_thought` is **required** for every tool call - creates audit trail
- `target` uses multi-selector fallback
- Returns structured result with `attempts` list for debugging

### focus_and_input_text_wrapper

```python
async def focus_and_input_text(
    agent_thought: str,
    text: str,                   # Text to type
    target: Target,              # Element to focus first
)
```

**Key Features:**
- Focus element using multi-selector before typing
- Tap near end of input to position cursor
- Returns full content of input field after typing for verification

### Scratchpad Tools

```python
# save_note(key, content) - Store data persistently
# read_note(key) - Retrieve stored data
# list_notes() - List all stored keys
```

**Key Features:**
- Simple key-value storage
- Persists across agent loops
- Essential for cross-app data transfer

---

## Targeting Implementation Details

### Element Finding by Resource ID

```python
def find_element_by_resource_id(
    ui_hierarchy: list[dict],
    resource_id: str,
    index: int | None = None,
    is_rich_hierarchy: bool = False,
) -> dict | None:
    """Find element by resource_id with optional index for duplicates."""
```

### Element Finding by Text

```python
def find_element_by_text(
    ui_hierarchy: list[dict],
    text: str,
    index: int | None = None
) -> dict | None:
    """
    Case-insensitive exact match on text content.
    Searches recursively through hierarchy.
    """
```

### Bounds Validation

```python
def validate_coordinates_bounds(
    target: Target, screen_width: int, screen_height: int
) -> str | None:
    """
    Validate coordinates are within screen bounds.
    Returns error message if invalid, None if valid.
    """
    if center.x < 0 or center.x >= screen_width:
        errors.append(f"x={center.x} is outside screen width")
    if center.y < 0 or center.y >= screen_height:
        errors.append(f"y={center.y} is outside screen height")
```

---

## Focus Logic Before Typing

```python
async def focus_element_if_needed(ctx, target) -> str | None:
    """
    Ensures element is focused before typing.
    Returns focus method used: "resource_id", "coordinates", or "text"
    """
    # 1. Try resource_id first
    if target.resource_id:
        elt = find_element_by_resource_id(...)
        
        # Sanity check: if text provided, verify it matches
        if elt and target.text:
            actual_text = get_element_text(elt)
            if actual_text != target.text:
                # ID and text don't match - ignore ID, use fallbacks
                elt = None
        
        if elt and not is_element_focused(elt):
            tap(resource_id=target.resource_id)
        
        if is_element_focused(elt):
            return "resource_id"
    
    # 2. Fallback to coordinates
    if target.bounds:
        center = target.bounds.get_center()
        tap_at(center.x, center.y)
        return "coordinates"
    
    # 3. Fallback to text
    if target.text:
        elt = find_element_by_text(target.text, target.text_index)
        if elt:
            bounds = get_bounds_for_element(elt)
            tap_at(bounds.get_center())
            return "text"
    
    return None  # Failed to focus
```

---

## Cursor Positioning

```python
async def move_cursor_to_end_if_bounds(ctx, state, target):
    """
    Tap near bottom-right of input to move cursor to end.
    Essential for append-style typing.
    """
    bounds = get_bounds_for_element(element)
    bottom_right = bounds.get_relative_point(x_percent=0.99, y_percent=0.99)
    tap_at(bottom_right.x, bottom_right.y)
```

---

## Tool Execution Result Pattern

```python
# Success result
tool_message = ToolMessage(
    tool_call_id=tool_call_id,
    content="Tap on element with coordinates (100, 200) was successful.",
    status="success",
)

# Failure result with detailed attempts
tool_message = ToolMessage(
    tool_call_id=tool_call_id,
    content="Failed to tap. Attempts: coordinates (100, 200): Out of bounds; resource_id='btn': No element found",
    additional_kwargs={"attempts": [...]},  # Full attempt list
    status="error",
)
```

---

## What Makes This Implementation Successful

### 1. Multi-Selector Fallback
- Never relies on a single targeting method
- Graceful degradation when one method fails
- Comprehensive error reporting

### 2. Agent Thought Requirement
- Every tool call explains WHY
- Creates audit trail for debugging
- Helps identify repeated failures

### 3. Focus + Type Separation
- Explicit focus step before typing
- Cursor positioning at end of input
- Verification of actual content after typing

### 4. Index Disambiguation
- `resource_id_index` for duplicate IDs
- `text_index` for duplicate text
- Defaults to 0 when not specified

### 5. Scratchpad Memory
- Simple key-value storage
- Persists across agent loops
- Critical for cross-app workflows

---

## Applicability to Our Agent

### Can Adopt

1. **Multi-selector Target object** with fallback execution
2. **agent_thought parameter** for all tools
3. **Focus + cursor positioning** for text input
4. **Index disambiguation** for duplicates
5. **Detailed failure reporting** with all attempts

### Implementation Notes

- Our current `element_index` is similar to M3A's approach
- Need to add `resource_id`, `bounds`, `text` as additional selectors
- Fallback execution order: `element_index → bounds → resource_id → text`
- Always include agent_thought in tool schema
