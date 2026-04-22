# Screenshot Compression Strategies in Mobile Agent Repositories

**Analysis Date**: 2026-02-09  
**Source**: `.reference/mobile_agent` directory

## Executive Summary

Analysis of screenshot processing strategies across multiple mobile agent implementations reveals three primary approaches to reduce token costs when sending screenshots to LLMs:

1. **JPEG Compression** (minitap-mobile-use)
2. **Fixed Ratio Scaling** (MobileAgent-v1)
3. **Token-Aware Smart Resize** (MobileAgent-v3)

## Detailed Findings

### 1. minitap-mobile-use: JPEG Compression

**Location**: `minitap/mobile_use/controllers/android_controller.py`, `ios_controller.py`, `limrun_controller.py`

```python
def get_compressed_b64_screenshot(self, image_base64: str, quality: int = 50) -> str:
    if image_base64.startswith("data:image"):
        image_base64 = image_base64.split(",")[1]
    
    image_data = base64.b64decode(image_base64)
    image = Image.open(BytesIO(image_data))
    
    compressed_io = BytesIO()
    image.save(compressed_io, format="JPEG", quality=quality, optimize=True)
    
    compressed_base64 = base64.b64encode(compressed_io.getvalue()).decode("utf-8")
    return compressed_base64
```

**Strategy**:
- Convert to JPEG format with quality=50 (default)
- Enable PIL optimization
- Maintain original resolution

**Usage**: Called in `cortex.py` before sending screenshot to LLM:
```python
# Line 86-90
controller = create_device_controller(self.ctx)
compressed_image_base64 = controller.get_compressed_b64_screenshot(
    state.latest_screenshot
)
messages.append(get_screenshot_message_for_llm(compressed_image_base64))
```

**Estimated Savings**: ~70% file size reduction

---

### 2. MobileAgent-v1: Fixed 50% Scaling

**Location**: `MobileAgent/Mobile-Agent-v1/Mobile-Agent-qwen/host.py`

```python
# Lines 73-77, 102-106
ori_image = Image.open(screenshot)
original_width, original_height = ori_image.size

new_width = int(original_width * 0.5)
new_height = int(original_height * 0.5)
resized_image = ori_image.resize((new_width, new_height))
resized_image.convert("RGB").save(resize_image_path, "JPEG")
```

**Strategy**:
- Resize to 50% of original dimensions
- Convert to JPEG format
- Maintain aspect ratio

**Coordinate Handling**: Coordinates are scaled back to original resolution for action execution

**Estimated Savings**: 
- Pixel count: 75% reduction
- File size: ~80%+ reduction

---

### 3. MobileAgent-v3: Token-Aware Smart Resize

**Location**: `MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/agents/coordinate_resize.py`

```python
def smart_resize(height, width, factor=28, 
                 min_pixels=56 * 56, 
                 max_pixels=14 * 14 * 4 * 1280,
                 max_long_side=8192):
    """Resize image with constraints:
    1. Dimensions divisible by factor (28)
    2. Total pixels within [min_pixels, max_pixels]
    3. Longest side within max_long_side
    4. Preserve aspect ratio
    """
```

**Key Parameters**:
- `factor=28`: Aligns with vision transformer patch size (14) × merge base (2)
- `max_pixels=1,003,520`: ~14×14×4×1280 pixels
- Dynamically adjusts based on original aspect ratio

**Integration with VLM**:
```python
def update_image_size_(image_ele: dict, min_tokens=1, max_tokens=12800, 
                       merge_base=2, patch_size=14):
    pixels_per_token = patch_size * patch_size * merge_base * merge_base
    resized_height, resized_width = smart_resize(
        height, width,
        factor=merge_base * patch_size,
        min_pixels=pixels_per_token * min_tokens,
        max_pixels=pixels_per_token * max_tokens,
    )
    image_ele.update({
        "resized_height": resized_height,
        "resized_width": resized_width,
        "seq_len": resized_height * resized_width // pixels_per_token + 2,
    })
```

**Strategy**:
- Token-aware: directly calculates target resolution based on VLM's patch mechanism
- Adaptive: adjusts to different aspect ratios while respecting token budget
- Coordinate conversion: provides utilities to map between original and resized coordinates

**Estimated Savings**: Dynamic, typically reduces to ~1M pixels max

---

### 4. autodevice_android_world / SeeAct: Display-Only Thumbnail

**Location**: `android_world/agents/seeact_utils.py`

```python
# Line 285 (display_prompt function)
image.thumbnail((512, 512))
display.display(image)
```

**Note**: This is only for visualization in notebooks, **not** applied to LLM input. The actual LLM receives full-resolution images encoded as JPEG via `infer.py`.

---

### 5. Open-AutoGLM: No Compression

**Location**: `Open-AutoGLM/phone_agent/adb/screenshot.py`

```python
def get_screenshot(...) -> Screenshot:
    # Captures via adb shell screencap -p
    # Returns PNG format, no compression
```

**Strategy**: None - sends original PNG screenshots

---

### 6. droidrun: No Compression

**Location**: `droidrun/agent/utils/tracing_setup.py`

```python
# Line 238
image_b64 = base64.b64encode(screenshot).decode()
```

**Strategy**: None - base64 encodes original screenshot for tracing only

---

## Comparison Table

| Repository | Method | Resolution Change | Format | Quality | Est. Savings |
|------------|--------|-------------------|--------|---------|--------------|
| **minitap** | JPEG compression | None | JPEG | 50 | ~70% |
| **MobileAgent-v1** | Fixed 50% resize | 75% pixel reduction | JPEG | Default | ~80%+ |
| **MobileAgent-v3** | Smart resize | Dynamic (token-aware) | - | - | Variable |
| **autodevice** | None (display only) | - | JPEG | Default | - |
| **Open-AutoGLM** | None | - | PNG | - | 0% |
| **droidrun** | None | - | Original | - | 0% |

---

## Recommendations

### Best Practice: Hybrid Approach

Combine MobileAgent-v3's smart resize with minitap's JPEG compression:

```python
# 1. Smart resize based on token budget
image_ele = {
    "height": original_height,
    "width": original_width,
}
image_ele = update_image_size_(
    image_ele, 
    min_tokens=1, 
    max_tokens=12800  # Adjust based on model
)

# 2. Resize image
resized_image = image.resize(
    (image_ele["resized_width"], image_ele["resized_height"])
)

# 3. JPEG compression
compressed_io = BytesIO()
resized_image.save(compressed_io, format="JPEG", quality=50, optimize=True)
```

### Considerations

1. **Token Budget**: MobileAgent-v3's approach directly maps to VLM token consumption
2. **Coordinate Mapping**: Must convert LLM-returned coordinates back to original resolution
3. **Quality vs. Cost**: JPEG quality=50 provides good balance for UI screenshots
4. **Aspect Ratio**: Smart resize preserves aspect ratio while meeting token constraints

### Implementation Priority

1. **Quick Win**: Add JPEG compression (quality=50) - minimal code, ~70% savings
2. **Medium Term**: Implement fixed 50% scaling - simple, predictable ~80% savings
3. **Optimal**: Implement token-aware smart resize - complex but maximizes efficiency

---

## Code References

- **minitap compression**: [android_controller.py:L87-90](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/controllers/android_controller.py#L87-L90)
- **MobileAgent-v1 resize**: [host.py:L73-77](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/MobileAgent/Mobile-Agent-v1/Mobile-Agent-qwen/host.py#L73-L77)
- **MobileAgent-v3 smart resize**: [coordinate_resize.py:L19-45](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/agents/coordinate_resize.py#L19-L45)
