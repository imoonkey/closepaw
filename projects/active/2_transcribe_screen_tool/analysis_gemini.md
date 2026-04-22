# transcribe_screen Tool Analysis

## Overview

Research on how screen transcription/OCR is implemented across reference mobile agent repos.

---

## Reference Implementations

### 1. AutoDev (autodevice_android_world)

**Approach**: LLM-based vision transcription (VLM)

**Tool Definition** ([executor_tools.py](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/executor_tools.py#L311-330)):
```python
def transcribe_screen() -> str:
    """Transcribe all text and UI elements visible on the current screen.
    
    This tool provides a complete transcription of the current screen, including:
    - All visible text content
    - UI elements (buttons, icons, text fields, etc.)
    - Labels and descriptions
    - Any other readable content
    
    Use this when you need to:
    - Read file content
    - Extract list items
    - Read form fields, search results, or any text on screen
    - Find UI elements and their labels (buttons, icons, text fields)
    - Understand the current screen state
    
    Returns:
        A complete transcription of the current screen as a string.
    """
```

**Implementation** ([transcription.py](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/transcription.py#L12-69)):
```python
def transcribe_screen(screenshot: np.ndarray, model: str = "gemini/gemini-3-pro-preview") -> Optional[str]:
    # Convert numpy array to PIL Image, then base64
    # LLM prompt:
    messages = [{
        "role": "user",
        "content": [
            {"type": "text", "text": "Transcribe all text visible on this screen. Include all UI elements, labels, buttons, text fields..."},
            {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{image_data}"}}
        ]
    }]
    response = litellm.completion(model=model, messages=messages, max_tokens=2000)
```

**Related Functions**:

| Function | Purpose | Prompt Focus |
|----------|---------|--------------|
| `transcribe_screen()` | General screen transcription | All visible text, UI elements, labels |
| `get_ui_elements()` | Interactive element detection | Buttons, icons, text fields, navigation elements |
| `extract_data_from_screen()` | Structured data extraction | Lists, file content, specific data per instruction |

**Multi-Agent Usage**:
- **Planner Agent**: Has `transcribe_screen()` tool to request transcription when needed
- **Executor Agent**: Has same tool, must call it explicitly to read screen content
- Screenshot is provided but transcription is NOT automatic

**Prompt Guidance** ([prompts.py](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/prompts.py)):

> [!IMPORTANT]
> **When to call `transcribe_screen()`**:
> - Before/after scrolling (loop detection)
> - When stuck or not making progress (2+ failed attempts)
> - Reading file content, list items, form fields
> - Finding UI elements and their labels
> 
> **When NOT to call**:
> - If you can see what you need in screenshot directly AND making progress

---

### 2. MobileAgent (PC-Agent)

**Approach**: Traditional OCR pipeline (detection + recognition)

**Implementation** ([text_localization.py](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/MobileAgent/PC-Agent/PCAgent_v1/text_localization.py#L35-61)):
```python
def ocr(image_path, ocr_detection, ocr_recognition):
    text_data = []
    coordinate = []
    image_full = cv2.imread(image_path)
    det_result = ocr_detection(image_full)  # Detect text regions
    for polygon in det_result['polygons']:
        pts = order_point(polygon)
        image_crop = crop_image(image_full, pts)
        result = ocr_recognition(image_crop)['text'][0]  # Recognize text
        text_data.append(result)
        coordinate.append(box)
    return text_data, coordinate
```

**Key Differences from AutoDev**:
- Uses separate detection and recognition models (not LLM)
- Returns text + coordinates (for click targeting)
- No semantic understanding of UI elements
- Pure text extraction without context

---

### 3. DroidRun

**Approach**: Accessibility tree (no explicit transcription tool)

**State Model** ([state.py](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/droidrun/droidrun/agent/droid/state.py#L17-27)):
```python
class DroidAgentState(BaseModel):
    formatted_device_state: str = ""  # Text description for prompts
    focused_text: str = ""            # Text in focused input field
    a11y_tree: List[Dict] = Field(default_factory=list)  # Raw accessibility tree
    screenshot: str | bytes | None = None
```

**Key Points**:
- Uses accessibility tree (`a11y_tree`) for UI element info
- `formatted_device_state` provides text description (pre-formatted)
- No LLM-based transcription tool exposed to agent
- Perception is automatic (not requested by agent)

---

### 4. MiniTap (mobile-use)

**Approach**: UIAutomator-based screen data

**Implementation** ([android_controller.py](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/controllers/android_controller.py#L90-110)):
```python
async def get_screen_data(self) -> ScreenDataResponse:
    ui_data = self.ui_adb_client.get_screen_data()
    return ScreenDataResponse(
        base64=ui_data.base64,      # Screenshot
        elements=ui_data.elements,   # UI hierarchy
        width=ui_data.width,
        height=ui_data.height
    )

async def get_ui_hierarchy(self) -> list[dict]:
    device_data = await self.get_screen_data()
    return device_data.elements
```

**Key Points**:
- Uses UIAutomator2 for UI element extraction
- Returns structured element data (text, bounds, resource-id)
- No LLM-based transcription
- Elements come with coordinates for interaction

---

## Comparison Summary

| Aspect | AutoDev | MobileAgent | DroidRun | MiniTap |
|--------|---------|-------------|----------|---------|
| **Method** | LLM Vision | Traditional OCR | A11y Tree | UIAutomator |
| **Explicit Tool** | ✅ `transcribe_screen()` | ❌ Internal function | ❌ Automatic | ❌ Automatic |
| **Agent Control** | Agent requests | Pipeline step | System provides | System provides |
| **Output** | Natural text | Text + coords | Structured tree | Elements + coords |
| **Semantic Understanding** | ✅ High | ❌ None | ⚠️ Limited | ⚠️ Limited |
| **Token Cost** | High (LLM call) | Low (local) | Low | Low |

---

## Pros and Cons

### AutoDev LLM Approach
**Pros**:
- Rich semantic understanding (understands icons, context)
- Natural language output
- Can describe visual elements without text
- Agent controls when to request (token efficiency when not needed)

**Cons**:
- High token cost per call
- Latency (LLM inference)
- May hallucinate or miss small text
- Requires explicit tool call

### Traditional OCR (MobileAgent)
**Pros**:
- Fast, low cost
- Accurate for clear text
- Returns coordinates

**Cons**:
- No semantic understanding
- Misses icons, images, visual context
- Struggles with stylized text

### Accessibility Tree (DroidRun/MiniTap)
**Pros**:
- Fastest method
- Accurate element bounds
- Structured data
- Zero additional cost

**Cons**:
- Limited to exposed a11y properties
- May miss decorative UItext
- No visual context
- Varies by app a11y implementation quality

---

## Recommendations for Our Implementation

1. **Primary Method**: Accessibility tree (already have via `ScreenState`)
   - Use for most cases
   - Fast, reliable, structured

2. **Fallback/Enhancement**: LLM transcription tool (like AutoDev)
   - For reading complex content (documents, unstructured text)
   - For visual reasoning when a11y tree insufficient
   - Agent-controlled to minimize token cost

3. **Prompt Design** (from AutoDev):
   - Make transcription explicit tool call (not automatic)
   - Provide clear guidance on when to use
   - Use for loop detection (before/after scroll comparison)

4. **Tool Name Options**:
   - `transcribe_screen()` - AutoDev naming
   - `read_screen()` - More intuitive
   - `get_screen_text()` - Explicit about output
