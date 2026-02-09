# Screenshot Processing and Optimization Analysis

This document summarizes the analysis of screenshot processing, resizing, and compression strategies across various mobile agent repositories in `.reference/mobile_agent`. The goal is to identify techniques for reducing token usage when sending screen data to LLMs.

## Summary of Findings

| Repository | Agent/Component | Processing Strategy | format |
| :--- | :--- | :--- | :--- |
| **Mobile-Agent-v1** | `controller.py` | **Resize & Compress**: Resizes to 50% of original dimensions. Converts to `JPEG`. | JPEG |
| **Mobile-Agent-v3** | `GUIOwlWrapper` | **Smart Resize**: Uses `qwen_vl_utils.smart_resize` (factor 28, min/max pixel constraints). | PNG (Base64) |
| **Open-AutoGLM** | `ModelClient` | **Smart Resize**: Uses `qwen_vl_utils.smart_resize` (factor 28). | PNG (Base64) |
| **Open-AutoGLM** | `adb/screenshot.py` | **Raw**: Captures raw PNG, no processing in capture step. | PNG |
| **Android World** | `AutoDev` Agent | **Logical Scale**: Resizes to logical screen size scaled by `0.4` (configurable). | Numpy Array |
| **Android World** | `M3A` Agent | **Raw**: Passes raw screenshot pixels to `LLM`. | Numpy Array |
| **Android World** | `Gpt4Wrapper` | **Compress**: Encodes as `JPEG` base64. No explicit resizing observed. | JPEG (Base64) |
| **droidrun** | `CodeActAgent` | **Raw**: Captures and sends raw PNG bytes. | PNG (Bytes) |
| **minitap-mobile-use** | `Cortex` Agent | **Compress**: Converts to `JPEG` (quality 50) before sending. | JPEG (Base64) |

## Detailed Analysis

### 1. Mobile-Agent-v1
*   **Source**: `MobileAgent/controller.py`
*   **Method**: `get_screenshot`
*   **Logic**:
    ```python
    image = Image.open(image_path)
    new_width = int(original_width * 0.5)
    new_height = int(original_height * 0.5)
    resized_image = image.resize((new_width, new_height))
    resized_image.convert("RGB").save(save_path, "JPEG")
    ```
*   **Impact**: Significant reduction in token count and latency due to 50% downscaling and JPEG compression.

### 2. Mobile-Agent-v3
*   **Source**: `mobile_v3/utils/call_mobile_agent_e.py`
*   **Method**: `image_to_base64`
*   **Logic**:
    *   Uses `qwen_vl_utils.smart_resize`.
    *   Parameters: `factor=28`, `min_pixels=3136`, `max_pixels=10035200`.
    *   This dynamically adjusts resolution based on model requirements (typical for Qwen-VL).

### 3. Open-AutoGLM
*   **Source**: `phone_agent/model/client.py`
*   **Method**: `image_to_base64`
*   **Logic**:
    *   Also uses `smart_resize` with similar parameters (`factor=28`).
    *   Basic `get_screenshot` in `adb/screenshot.py` is raw, but the client transforms it before sending to the model.

### 4. autodevice_android_world
*   **AutoDev Agent**:
    *   **Source**: `android_world/agents/autodev_agent.py`
    *   **Method**: `_resize_screenshot_to_logical_size`
    *   **Logic**: Scales to logical screen size * `self.scale` (default 0.4).
    *   Uses `cv2.resize`.
*   **M3A Agent**:
    *   **Source**: `android_world/agents/m3a.py`
    *   **Logic**: Passes `state.pixels.copy()` directly to `self.llm.predict_mm`.
*   **Wrappers (`infer.py`)**:
    *   `Gpt4Wrapper` converts to JPEG base64.
    *   `GeminiGcpWrapper` passes raw images (Gemini API handles resizing/compression internally or accepts high-res).

### 5. droidrun
*   **Source**: `droidrun/agent/utils/tools.py`, `droidrun/tools/android/portal_client.py`
*   **Method**: `AdbTools.take_screenshot` -> `PortalClient.take_screenshot`
*   **Logic**:
    *   `PortalClient` retrieves screenshot bytes (PNG) from the device (via internal HTTP server or ADB).
    *   `CodeActAgent` (and `ExecutorAgent`) adds these raw bytes directly to the message history.
    *   `chat_utils.py` ensures bytes are PNG format but performs no resizing.
    *   LlamaIndex integrations (`llm_picker.py`) generally pass the image payload as-is.
*   **Observation**: No explicit client-side optimization (resizing/compression) was found in the Python agent code.

### 6. minitap-mobile-use
*   **Source**: `minitap/mobile_use/agents/cortex/cortex.py`, `minitap/mobile_use/controllers/android_controller.py`
*   **Method**: `CortexNode.__call__` -> `get_compressed_b64_screenshot`
*   **Logic**:
    *   `Cortex` agent checks for `state.latest_screenshot`.
    *   Calls `controller.get_compressed_b64_screenshot(state.latest_screenshot)`.
    *   `AndroidDeviceController` converts the base64 PNG to **JPEG with quality 50**.
    ```python
    image.save(compressed_io, format="JPEG", quality=quality, optimize=True)
    ```
*   **Impact**: Strong compression (quality 50) significantly reduces payload size.

## Recommendations for Gemini Agent

To optimize token usage and latency for the Gemini agent:

1.  **Resize Strategy**: Implement a resizing logic similar to **Mobile-Agent-v1** (fixed scale, e.g., 50%) or **AutoDev** (logical scale * factor).
    *   *Pros*: Predictable token usage, faster transmission.
    *   *Cons*: Loss of fine detail (small text).
2.  **Compression**: Convert PNG to **JPEG** (quality ~80-90) before sending.
    *   *Pros*: Significant byte size reduction (faster upload).
    *   *Cons*: Compression artifacts.
3.  **Smart Resizing**: If utilizing a model that supports specific resolutions (like Qwen), adopt `smart_resize`. For Gemini, effective standard resizing is usually sufficient.
