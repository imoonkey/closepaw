# AutoDevice Tools Analysis

**Leaderboard**: High performer on AndroidWorld  
**Source**: `.reference/autodevice_android_world/android_world/agents/autodev/`

## Tool List (Executor)

```python
# Coordinate-based Actions
click(x, y)                      # Tap at scaled coordinates
double_tap(x, y)                 # Double-tap (zoom gesture)
long_press(x, y)                 # Long press

# Directional Actions
scroll(direction, x?, y?)        # Scroll up/down/left/right
swipe(direction, x?, y?)         # Swipe (smaller than scroll)
swipe_coords(start_x, start_y, end_x, end_y)  # Precise swipe

# Text Input
input_text(text, x?, y?, clear_text?)
type_text(text, clear_first?)    # Simplified (no coords)
keyboard_enter()

# Navigation
navigate_back()
navigate_home()
open_app(app_name)

# Utility
wait()
transcribe_screen()              # Get all visible text
report(notes)                    # Report status to planner
extracted_data(data)             # Return extracted data
```

---

## Key Innovation 1: Coordinate Scaling

### The Problem
LLM receives scaled-down screenshots to save tokens, but device expects full-resolution coordinates.

### The Solution

```python
SCALE = 0.4  # Screenshots scaled to 40% of original

def click(x: int, y: int) -> JSONAction:
    """Create click at scaled coordinates."""
    return JSONAction(
        action_type="click",
        x=int(int(x) / SCALE),  # 250 in image → 625 on device
        y=int(int(y) / SCALE)
    )

def scroll(direction: str, x: int = None, y: int = None) -> JSONAction:
    scaled_x = int(int(x) / SCALE) if x is not None else None
    scaled_y = int(int(y) / SCALE) if y is not None else None
    return JSONAction(action_type="scroll", direction=direction, x=scaled_x, y=scaled_y)
```

### Why This Matters
- Screenshots sent to LLM are 40% of device resolution
- LLM outputs coordinates based on what it sees
- All coordinates automatically unscaled before execution
- Consistent behavior across all tools

---

## Key Innovation 2: Clear Text Before Input

```python
def input_text(
    text: str,
    x: Optional[int] = None,
    y: Optional[int] = None,
    clear_text: bool = False,
) -> JSONAction:
    """
    Input text with optional field clearing.
    
    Args:
        text: Text to input
        x, y: Optional coordinates to click before typing
        clear_text: Clear existing text first (default False)
    """
    scaled_x = int(int(x) / SCALE) if x is not None else None
    scaled_y = int(int(y) / SCALE) if y is not None else None
    return JSONAction(
        action_type="input_text",
        text=text,
        x=scaled_x,
        y=scaled_y,
        clear_text=clear_text if clear_text else None,
    )
```

### Use Cases
```python
# URL bar - replace existing URL
input_text("https://example.com", x=540, y=100, clear_text=True)

# Search field - clear previous search
input_text("new search query", clear_text=True)

# Append to existing (default)
input_text(" additional text")
```

---

## Key Innovation 3: Scroll vs Swipe Distinction

### Scroll (Large Movement)
```python
def scroll(direction: str, x: Optional[int] = None, y: Optional[int] = None):
    """
    Large scroll in specified direction.
    Direction is READING direction (scroll 'down' = see content below).
    """
    valid_directions = ("up", "down", "left", "right")
    # ...
```

### Swipe (Small Movement)
```python
def swipe(direction: str, x: Optional[int] = None, y: Optional[int] = None):
    """
    Small swipe for fine adjustments.
    To scroll down a tiny bit, SWIPE UP from upper half.
    """
```

### Precise Swipe with Coordinates
```python
def swipe_coords(start_x: int, start_y: int, end_x: int, end_y: int):
    """
    Explicit start and end coordinates for precise control.
    
    Example:
        swipe_coords(100, 1200, 800, 1200)  # Horizontal swipe
    """
    return JSONAction(
        action_type="swipe",
        x=int(start_x / SCALE),
        y=int(start_y / SCALE),
        end_x=int(end_x / SCALE),
        end_y=int(end_y / SCALE),
    )
```

---

## Key Innovation 4: On-Demand Screen Transcription

```python
def transcribe_screen() -> str:
    """
    Transcribe all text and UI elements visible on current screen.
    
    Provides:
    - All visible text content
    - UI elements (buttons, icons, text fields)
    - Labels and descriptions
    - Any readable content
    
    Use when:
    - Reading file content
    - Extracting list items
    - Reading form fields, search results
    - Finding UI elements and their labels
    - Understanding current screen state
    """
    pass  # Implementation handled by executor framework
```

### Why On-Demand
- **Saves tokens**: Only transcribe when needed
- **Reduces noise**: Screenshots are primary, text is supplementary
- **Better accuracy**: Dedicated transcription call vs embedded in observation

---

## Key Innovation 5: Report Tool for Executor → Planner Communication

```python
def report(notes: str):
    """
    Report achievement status and observations to planner.
    
    Use when:
    - Task step completed
    - Unable to proceed
    - Need planner to make strategic decision
    
    Include:
    - What was completed
    - Success/failure status
    - Verification result
    - Current screen state
    - Alternative approaches to try
    """
```

### Report Content Structure (From Prompts)

```markdown
**Summary must include:**
1. What you tried to accomplish
2. Approach taken (overall strategy)
3. What didn't work and why
4. What you observed on screen
5. Alternative approaches to try
```

---

## Planner vs Executor Tool Split

### Planner Tools (High-Level Intent)

```python
def tap(intent: str):
    """
    Declare INTENT to tap a specific UI element.
    Give executor enough context on what we're accomplishing.
    """
    pass

def scroll(intent: str):
    """
    Prefer scan_for_element() and let executor make sense of what to do.
    """
    pass
```

### Executor Tools (Low-Level Actions)

```python
# All coordinate-based, no intent
click(x, y)
scroll(direction, x, y)
input_text(text, x, y, clear_text)
```

### Why This Split Works
- **Planner**: Describes WHAT and WHY
- **Executor**: Handles HOW with precise coordinates
- **Clear boundary**: Strategy vs Implementation

---

## Tool Parameter Documentation

### Comprehensive Docstrings

```python
def scroll(
    direction: str, x: Optional[int] = None, y: Optional[int] = None
) -> JSONAction:
    """Create a scroll action in the specified direction.
    In order to scroll a tiny bit, use swipe in the inverse direction instead.
    
    Args:
        direction: The scroll direction. Must be one of: 'up', 'down', 'left', 'right'.
        x: Optional x-coordinate for the scroll origin. If not provided, scrolls from center.
        y: Optional y-coordinate for the scroll origin. If not provided, scrolls from center.

    Raises:
        ValueError: If direction is not one of the valid scroll directions.

    Example:
        >>> scroll('down')
        # Scrolls down from center of screen

        >>> scroll('up', x=300, y=500)
        # Scrolls up from position (300, 500)
    """
```

### Benefits
- LLM understands usage from docstrings
- Examples show correct patterns
- Validation rules are explicit

---

## Convenience Wrappers

```python
def tap(x: int, y: int) -> JSONAction:
    """Alias for click() - more intuitive naming."""
    return click(x, y)

def type_text(text: str, clear_first: bool = False) -> JSONAction:
    """Simplified text input without coordinates."""
    return input_text(text, clear_text=clear_first)

def swipe_up(x: Optional[int] = None, y: Optional[int] = None) -> JSONAction:
    """Convenience function for swiping up."""
    return swipe("up", x, y)

def swipe_down(x: Optional[int] = None, y: Optional[int] = None) -> JSONAction:
    """Convenience function for swiping down."""
    return swipe("down", x, y)
```

---

## What Makes This Implementation Successful

### 1. Transparent Coordinate Scaling
- Single `SCALE` constant
- All tools scale consistently
- No manual conversion needed

### 2. Clear Text Option
- Built into `input_text`
- Simplifies replace scenarios
- Reduces action sequences

### 3. Scroll/Swipe Distinction
- Clear semantic difference
- `swipe_coords` for precision
- Direction naming follows reading direction

### 4. On-Demand Transcription
- Explicit tool call
- Only when text extraction needed
- Reduces token waste

### 5. Structured Reporting
- Executor → Planner communication
- Includes what worked/failed
- Suggests alternatives

---

## Applicability to Our Agent

### Can Adopt Immediately

1. **`clear` option** for type action
2. **Scroll vs swipe distinction** in tool descriptions
3. **`swipe_coords`** for precise coordinate swipes
4. **Comprehensive docstrings** with examples
5. **Convenience wrappers** for common patterns

### Requires Consideration

1. **Coordinate scaling** - depends on our screenshot approach
2. **On-demand transcription** - separate tool or always include?
3. **Report tool** - only useful with planner/executor split

### Implementation Notes

For coordinate scaling (if screenshots are scaled):
```kotlin
object CoordinateScaler {
    const val SCALE = 0.4f  // Match screenshot scaling
    
    fun toDevice(x: Int): Int = (x / SCALE).toInt()
    fun toDevice(y: Int): Int = (y / SCALE).toInt()
}
```

For clear text option:
```kotlin
data class TypeAction(
    val text: String,
    val element_index: Int? = null,
    val clear: Boolean = false  // NEW
)
```
