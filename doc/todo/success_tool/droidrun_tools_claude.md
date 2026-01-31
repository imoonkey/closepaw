# DroidRun Tools Analysis

**Leaderboard**: 91.4% AndroidWorld  
**Source**: `.reference/mobile_agent/droidrun/`

## Tool List (AdbTools)

```python
# Core Actions
tap_by_index(index)              # Tap element by cached index
tap_by_coordinates(x, y)         # Tap at absolute coordinates
tap_on_index(index)              # Smart tap avoiding overlapping elements
swipe(start_x, start_y, end_x, end_y, duration_ms)
input_text(text, index=-1, clear=False)
back()
press_key(keycode)

# App Control
start_app(package, activity?)
install_app(apk_path, reinstall?, grant_permissions?)
list_packages(include_system_apps?)
get_apps(include_system?)

# State & Perception
get_state()                      # Returns accessibility tree + phone state
take_screenshot(hide_overlay?)

# Memory
remember(information)            # Store to memory list
get_memory()                     # Retrieve all memory items

# Completion
complete(success, reason)        # Mark task complete
```

---

## Key Innovation 1: Index-Based Targeting with Element Cache

### How It Works

```python
class AdbTools:
    # Instance-level cache for clickable elements
    clickable_elements_cache: List[Dict[str, Any]] = []
    
    async def get_state(self):
        """Get device state and populate element cache."""
        combined_data = await self.portal.get_state()
        
        # Filter and format the tree
        filtered_tree = self.tree_filter.filter(raw_tree, device_context)
        formatted_text, focused_text, a11y_tree, phone_state = \
            self.tree_formatter.format(filtered_tree, phone_state)
        
        # CRITICAL: Cache elements for index-based targeting
        self.clickable_elements_cache = a11y_tree
        
        return (formatted_text, focused_text, a11y_tree, phone_state)
```

### Index Extraction

```python
def _extract_element_coordinates_by_index(self, index: int) -> Tuple[int, int]:
    """Extract center coordinates from element by index."""
    
    def find_element_by_index(elements, target_index):
        """Recursively find element with given index."""
        for item in elements:
            if item.get("index") == target_index:
                return item
            # Check children recursively
            children = item.get("children", [])
            result = find_element_by_index(children, target_index)
            if result:
                return result
        return None
    
    if not self.clickable_elements_cache:
        raise ValueError("No UI elements cached. Call get_state first.")
    
    element = find_element_by_index(self.clickable_elements_cache, index)
    
    if not element:
        # List available indices to help the user
        indices = sorted(collect_all_indices(self.clickable_elements_cache))
        indices_str = ", ".join(str(idx) for idx in indices[:20])
        raise ValueError(f"No element found with index {index}. Available indices: {indices_str}")
    
    # Parse bounds: "left,top,right,bottom"
    bounds_str = element.get("bounds")
    left, top, right, bottom = map(int, bounds_str.split(","))
    
    # Return center coordinates
    return (left + right) // 2, (top + bottom) // 2
```

---

## Key Innovation 2: Smart Tap Avoiding Overlaps

### Problem
Elements can overlap. Tapping center of a lower-z element might hit an overlapping element.

### Solution: `tap_on_index()` with Geometry Helpers

```python
async def tap_on_index(self, index: int) -> str:
    """Tap on element by index, avoiding overlapping elements."""
    
    element = find_element_by_index(self.clickable_elements_cache, index)
    target_bounds = tuple(map(int, element.get("bounds").split(",")))
    
    # Find all elements that might overlap
    all_elements = collect_all_elements(self.clickable_elements_cache)
    blockers = []
    for el in all_elements:
        el_idx = el.get("index")
        el_bounds = tuple(map(int, el.get("bounds").split(",")))
        # Higher index = rendered later = on top
        if el_idx > index and rects_overlap(target_bounds, el_bounds):
            blockers.append(el_bounds)
    
    # Find a clear point that avoids blockers
    point = find_clear_point(target_bounds, blockers)
    if not point:
        raise ValueError(f"Element {index} is fully obscured")
    
    x, y = point
    await self.device.click(x, y)
```

### Geometry Helpers

```python
def rects_overlap(a: Bounds, b: Bounds) -> bool:
    """Check if two rectangles overlap."""
    return not (a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1])

def find_clear_point(bounds, blockers, depth=0) -> Optional[Tuple[int, int]]:
    """Find a clear point in bounds using quadrant subdivision."""
    left, top, right, bottom = bounds
    cx, cy = (left + right) // 2, (top + bottom) // 2
    
    # Check if center is blocked
    blocked = any(b[0] <= cx < b[2] and b[1] <= cy < b[3] for b in blockers)
    
    if not blocked:
        return cx, cy
    
    # Recurse into quadrants
    if depth > 4 or (right - left) * (bottom - top) < 100:
        return None
    
    quadrants = [
        (left, top, cx, cy),
        (cx, top, right, cy),
        (left, cy, cx, bottom),
        (cx, cy, right, bottom),
    ]
    
    for q in quadrants:
        point = find_clear_point(q, blockers, depth + 1)
        if point:
            return point
    
    return None
```

---

## Key Innovation 3: Input Text with Clear Option

```python
async def input_text(self, text: str, index: int = -1, clear: bool = False) -> str:
    """
    Input text on device.
    
    Args:
        text: Text to input (supports spaces, newlines, special chars, non-ASCII)
        index: Element index to input into. -1 = use focused element
        clear: Whether to clear existing text before inputting
    """
    if index != -1:
        await self.tap_by_index(index)
    
    # Use PortalClient for text input with clear support
    success = await self.portal.input_text(text, clear)
```

### Why `clear=True` Matters

- **URL bars**: Need to clear before typing new URL
- **Search fields**: Often have previous query
- **Form fields**: May have default/placeholder values
- **Replace scenarios**: Editing existing text

---

## Key Innovation 4: Memory Tool with Context

```python
def remember(self, information: str) -> str:
    """
    Store important information for future context.
    
    Information is extracted and included in next steps.
    Use for critical facts, observations, or preferences.
    """
    if not information or not isinstance(information, str):
        return "Error: Please provide valid information."
    
    self.memory.append(information.strip())
    
    # Limit memory size (keep most recent)
    max_memory_items = 10
    if len(self.memory) > max_memory_items:
        self.memory = self.memory[-max_memory_items:]
    
    return f"Remembered: {information}"

def get_memory(self) -> List[str]:
    """Retrieve all stored memory items."""
    return self.memory.copy()
```

### Memory Format (From Prompts)

```markdown
Always include step context:
"At step [number], I obtained [actual content] from [source]"

Examples:
- "At step 5, I obtained recipe from RecipeApp: Chicken Pasta - chicken, pasta, cream"
- "At step 12, I successfully added Recipe 1. Still need to add Recipe 2."

Rules:
- Store ACTUAL content, not just references
- Memory is append-only (new info added, not replaced)
- Use memory instead of clipboard unless clipboard required
```

---

## Key Innovation 5: Element Search Filters

### Composable Filter System

```python
class Filters:
    # Text Matching
    @staticmethod
    def text_matches(pattern: str | re.Pattern) -> ElementFilter:
        """Match by text, contentDescription, or hint."""
    
    @staticmethod
    def id_matches(pattern: str | re.Pattern) -> ElementFilter:
        """Match by resource ID (full or short form)."""
    
    # Spatial Filters
    @staticmethod
    def below(anchor_filter: ElementFilter) -> ElementFilter:
        """Find elements positioned below anchor."""
    
    @staticmethod
    def above(anchor_filter: ElementFilter) -> ElementFilter:
        """Find elements positioned above anchor."""
    
    @staticmethod
    def left_of(anchor_filter: ElementFilter) -> ElementFilter:
    
    @staticmethod
    def right_of(anchor_filter: ElementFilter) -> ElementFilter:
    
    # Trait Filters
    @staticmethod
    def clickable() -> ElementFilter:
    
    @staticmethod
    def enabled(expected: bool = True) -> ElementFilter:
    
    @staticmethod
    def focused(expected: bool = True) -> ElementFilter:
    
    # Composition
    @staticmethod
    def compose(filters: List[ElementFilter]) -> ElementFilter:
        """Apply filters sequentially (pipeline)."""
    
    @staticmethod
    def intersect(filters: List[ElementFilter]) -> ElementFilter:
        """Return elements matching ALL filters (AND logic)."""
```

### Usage Example

```python
# Find clickable element with text "Submit" below the "Email" label
filter = Filters.compose([
    Filters.below(Filters.text_matches("Email")),
    Filters.clickable(),
    Filters.text_matches("Submit")
])
results = filter(a11y_tree)
```

---

## Tool Response Patterns

### Tap Success Response

```python
response_parts = []
response_parts.append(f"Tapped element with index {index}")
response_parts.append(f"Text: '{element.get('text', 'No text')}'")
response_parts.append(f"Class: {element.get('className', 'Unknown class')}")
response_parts.append(f"Type: {element.get('type', 'unknown')}")

# Add children text if present
children = element.get("children", [])
if children:
    child_texts = [child.get("text") for child in children if child.get("text")]
    if child_texts:
        response_parts.append(f"Contains text: {' | '.join(child_texts)}")

response_parts.append(f"Coordinates: ({x}, {y})")
return " | ".join(response_parts)
```

### Error Response with Available Indices

```python
if not element:
    indices = sorted(collect_all_indices(self.clickable_elements_cache))
    indices_str = ", ".join(str(idx) for idx in indices[:20])
    if len(indices) > 20:
        indices_str += f"... and {len(indices) - 20} more"
    raise ValueError(f"No element found with index {index}. Available indices: {indices_str}")
```

---

## Retry with `get_state()` Retries

```python
async def get_state(self) -> Tuple[str, str, List[Dict], Dict]:
    """Get device state with automatic retries."""
    max_retries = 3
    
    for attempt in range(max_retries):
        try:
            combined_data = await self.portal.get_state()
            
            # Validate response
            required_keys = ["a11y_tree", "phone_state", "device_context"]
            missing_keys = [k for k in required_keys if k not in combined_data]
            if missing_keys:
                raise Exception(f"Missing: {', '.join(missing_keys)}")
            
            # Process and cache
            self.clickable_elements_cache = a11y_tree
            return (formatted_text, focused_text, a11y_tree, phone_state)
            
        except Exception as e:
            if attempt < max_retries - 1:
                await asyncio.sleep(0.5)  # Brief delay before retry
            else:
                raise Exception(f"Failed after {max_retries} attempts: {e}")
```

---

## What Makes This Implementation Successful

### 1. Cached Element Index
- `get_state()` populates cache
- Actions reference cached indices
- Avoids stale index issues within a turn

### 2. Smart Overlap Handling
- Detects overlapping elements by z-order (index)
- Finds clear point using quadrant subdivision
- Prevents "wrong element tapped" errors

### 3. Clear Text Option
- Single parameter for replace scenarios
- Reduces multi-step sequences (select-all + delete + type)

### 4. Memory with Context
- Step number for debugging
- Source location
- Actual content (not references)
- Size-limited to prevent overflow

### 5. Composable Element Filters
- Spatial relationships (above, below, left, right)
- Trait filtering (clickable, enabled, focused)
- Composition for complex queries

---

## Applicability to Our Agent

### Can Adopt Immediately

1. **Overlap avoidance** with `find_clear_point()`
2. **`clear` option** for type action
3. **Memory tool** with step context format
4. **Helpful error messages** with available indices

### Requires Architecture Changes

1. **Element cache pattern** (already have via snapshot)
2. **Composable filters** for complex element queries
3. **`get_state()` retries** pattern

### Implementation Notes

- Our `UIAction.Type` could add `clear: Boolean = false`
- Error messages should list available indices on failure
- Memory tool needs step tracking integration
