# P2-7: Screenshot Perception for Canvas Tasks

## Problem

BrowserDraw and BrowserMaze are impossible with accessibility-only perception. HTML canvas elements expose zero visual content through the a11y tree.

User's note: "看看mobile agents里主要依赖a11y perception的agent这个问题都是怎么解决的？是把screenshot getting设置成一个tool，还是他们默认都改成有screenshot perception，不再只依赖a11y tree了？如果是一个tool，这个tool又是具体怎么设计的？"

## Reference Research Findings

Surveyed 6 reference mobile agent repos:

| Repo | Screenshot Approach | Dedicated Tool? |
|---|---|---|
| DroidRun | Ambient — captured every turn if `vision=True` | No |
| MAI-UI | Always-on — part of every prompt | No |
| MobileAgent v3.5 | Every step — captured in main loop | No |
| minitap-mobile-use | State-based — latest screenshot in context | No |
| AutoDevice | Direct analysis — screenshot with a11y tree | No |
| AndroidWorld baseline | Always-on via gRPC observation | No |

**Consensus across ALL references**: Screenshots are **environmental input**, never a dedicated tool. Every turn automatically includes a screenshot alongside the a11y tree when vision is enabled.

### Why No Reference Uses a Screenshot Tool

1. **Timing**: The agent needs to see the current screen to decide what to do. Making it a tool means the agent must first decide to request a screenshot, then reason about it — adding a turn of latency.
2. **Simplicity**: Always-on is simpler. The agent doesn't need to learn when to request screenshots.
3. **Models expect it**: Vision-language models are designed to receive images as part of the observation, not as tool results.

## Design: Always-On Screenshot Mode (Configurable)

### Architecture

Current perception flow (`AgentTurnRunner`):
```
1. Capture a11y tree → format as JSON
2. Add to LLM prompt as "current screen state"
3. LLM reasons about text-only context
```

New flow with vision enabled:
```
1. Capture a11y tree → format as JSON
2. Capture screenshot via platform API (screencap)
3. Add both to LLM prompt:
   - a11y tree as text
   - screenshot as base64 image (vision models)
4. LLM reasons about both
```

### Configuration

Add `perception_mode` to agent config:

```kotlin
enum class PerceptionMode {
    ACCESSIBILITY_ONLY,  // current default
    HYBRID               // a11y tree + screenshot
}
```

In eval config:
```yaml
bridge:
  perception_mode: accessibility_only  # or "hybrid"
```

In app settings UI: add a toggle for "Include screenshots" (default off to save bandwidth/tokens).

### Screenshot Capture

The agent already runs as an AccessibilityService with screen capture permission. Use:

```kotlin
// In platform layer
suspend fun captureScreenshot(): Bitmap? {
    return withContext(Dispatchers.IO) {
        // Use AccessibilityService.takeScreenshot() (API 30+)
        // or screencap shell command as fallback
    }
}
```

Encode to base64 JPEG (quality 60-80%) for the LLM prompt. Target: ~100-200KB per screenshot.

### LLM Integration

For vision-capable models (GPT-4o, Claude, Gemini), add the image as a content part:

```kotlin
// In LLM request builder
if (perceptionMode == PerceptionMode.HYBRID) {
    val screenshot = platform.captureScreenshot()
    if (screenshot != null) {
        messages.add(ImageContent(screenshot.toBase64Jpeg()))
    }
}
```

For non-vision models (qwen3.5 text-only), the screenshot is silently skipped.

### Phased Rollout

1. **Phase 1 (now)**: Add `PerceptionMode.HYBRID` infrastructure. Default to `ACCESSIBILITY_ONLY`. Available as config option.
2. **Phase 2**: Test with vision-capable model on BrowserDraw/BrowserMaze tasks.
3. **Phase 3**: If successful, make `HYBRID` the default for vision models.

## Files Changed

| File | Change |
|---|---|
| `app/.../agent/AgentExecutionConfig.kt` | Add `perceptionMode: PerceptionMode` |
| `app/.../agent/AgentTurnRunner.kt` | Add screenshot capture in perception phase |
| `app/.../platform/PlatformBridge.kt` | Add `captureScreenshot()` |
| `app/.../llm/LlmClient.kt` | Support image content in messages |
| `eval/config/default.yaml` | Add `perception_mode: accessibility_only` |
| `eval/aw_bridge/native_agent_bridge.py` | Pass perception_mode via intent extra |
| `app/.../ui/settings/` | Add perception mode toggle |

## Impact

- Unblocks BrowserDraw and BrowserMaze (and any future canvas/visual tasks)
- Enables richer perception for all tasks when used with vision models

## Risks

- Token cost: ~1000 tokens per screenshot for vision models
- Latency: screenshot capture adds ~200ms per turn
- Model capability: qwen3.5 is text-only, so this helps only when switching to vision-capable models
- Not actionable for current eval config (qwen3.5) — this is structural preparation
