# Screenshot Input for Android Agent

## Problem
The agent only perceives UI state through the accessibility tree (`Perceptor.snapshot`).
For apps that render key content via OpenGL/SurfaceView/WebView, the a11y tree can be
empty or missing critical information (e.g., game HUDs, maps, video frames, canvas
drawings). This causes the agent to miss state changes and mis-plan actions.

We need to support screenshots (image input) alongside a11y, so the LLM can "see" visual
content when the tree is incomplete.

## Goals
- Add optional screenshot capture to the perception pipeline.
- Provide multi-modal input to supported LLM backends (image + a11y JSON).
- Keep privacy and performance guardrails (no unnecessary storage, throttle capture).
- Preserve current a11y-only flow as the default and safe fallback.

## Non-goals
- Full OCR pipeline or vision-to-structure modeling in this iteration.
- Persistent video recording / continuous streaming capture.
- Replacing the a11y tree as the primary interaction anchor.

## Current State (Code Touchpoints)
- `AccessibilityPlatform.captureScreen()` gets `rootInActiveWindow` and calls
  `Perceptor.snapshot()` to produce `ScreenSnapshot` (a11y only).
- `AgentPromptBuilder.buildUserContext()` injects `Perceptor.toPromptJson(snapshot)`
  into the LLM prompt.
- `ToolObservation.ScreenState` and `Observation.ScreenState` only include
  `accessibilityTree` strings.

## Proposed Design

### 1) Data Model Additions
Add optional image metadata to the perception model, without forcing storage:

- `ScreenSnapshot` gains:
  - `image: ScreenImage?` (nullable)
  - `imageHash: String?` (optional, for change detection and caching)

- `ScreenImage` (new):
  - `width`, `height`, `mimeType`
  - `bytes: ByteArray` (in-memory only) OR `cacheKey: String` (if we decide to store in cache)
  - `source: ScreenImageSource` (A11Y_TAKE_SCREENSHOT, MEDIA_PROJECTION, GLOBAL_ACTION)

Observation changes:
- `ToolObservation.ScreenState` and `Observation.ScreenState` add an optional
  `imageSummary` or `imageRef` (no large payloads written to history).

### 2) Screenshot Capture Pipeline
Introduce a small abstraction for screenshot capture:

```
interface ScreenCaptureProvider {
    suspend fun capture(displayId: Int): ScreenImageResult
}
```

Implementations:

1. **AccessibilityService.takeScreenshot (API 30+)**  
   - Use `AccessibilityService.takeScreenshot()` and `ScreenshotResult`.  
   - Requires `android:canTakeScreenshot="true"` in `agent_accessibility_config.xml`.  
   - Handles error codes like `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` and
     `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (API 34+).  
   - Reference: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)

2. **AccessibilityService.takeScreenshotOfWindow (API 34+)**  
   - Prefer window-scoped screenshots to avoid capturing our overlay.  
   - Use `getWindows()` to identify the active/top window and call
     `takeScreenshotOfWindow(windowId, ...)`.  
   - Reference: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)

3. **MediaProjection (Optional / fallback / user opt-in)**  
   - For API < 30 or if `takeScreenshot` is unreliable, allow MediaProjection with
     explicit user consent.  
   - On Android 14+, this requires `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` and
     `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission, and the capture intent must
     be created before starting the foreground service.  
   - Reference: https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection

4. **Global screenshot action (Last-resort, not default)**  
   - `performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)` and monitor screenshot output.
   - This is device/OEM dependent, slow, and storage-based, so we should not rely on it
     as a core path.

### 3) Capture Strategy
Introduce `ScreenshotPolicy` in `SessionConfig`:
- `OFF` (default)
- `ON_DEMAND` (capture only when heuristics detect missing a11y info)
- `ALWAYS` (capture every turn)

Heuristics for `ON_DEMAND`:
- Low element count (e.g., < 5)
- A11y tree unchanged for N turns but LLM keeps asking to re-check
- Detected SurfaceView/WebView heavy screens (class names or package allowlist)

Throttle rules:
- Minimum interval between screenshots (e.g., 1s–2s).
- Downscale to max dimension (e.g., 1024px) and JPEG quality (e.g., 70).

### 4) LLM Input Integration
Upgrade the prompt building to support multi-modal inputs:

- `TurnInputBuilder` should emit a user message with **text + image** content when:
  - LLM backend supports images (OpenAI models), and
  - `ScreenSnapshot.image != null`.

- For local LLMs or backends without image support:
  - Use a11y JSON only.
  - Optional future: run OCR and add a text-only "vision summary".

This should avoid storing raw image bytes in the history; only the latest turn’s
context contains the image.

### 5) Observation + History
We should **not** persist raw images in session history by default.
Instead:
- Store a small `imageSummary` (size, hash, source).
- Keep the in-memory image only for the current turn.
- If we need persistence later, add an explicit user opt-in with storage policy.

### 6) Privacy & Security
- If `takeScreenshot` returns secure window errors, fall back to a11y-only.
- Do not transmit/store screenshots when user has disabled image capture.
- Add a UI toggle with a clear privacy warning and per-session state.

### 7) UI/Settings Changes
Add a settings toggle:
- **Enable screenshot input** (default off)
- Optional: capture mode (Always / On-demand)

This connects to `AppSettingsStore` and `SessionConfig`.

## Implementation Plan (Phased)

**Phase 1: A11y screenshot path (API 30+)**
- Add `android:canTakeScreenshot="true"` to `agent_accessibility_config.xml`.
- Add `ScreenImage` and optional fields to `ScreenSnapshot`.
- Implement `AccessibilityScreenshotProvider` using `takeScreenshot`.
- Pass image to `AgentPromptBuilder` / `TurnInputBuilder` when backend supports it.
- Add config flags and a UI toggle.

**Phase 2: Overlay-aware screenshots (API 34+)**
- Prefer `takeScreenshotOfWindow` when available.
- Identify top window via `getWindows()` and `AccessibilityWindowInfo`.

**Phase 3: Optional MediaProjection fallback**
- Add foreground service flow + user consent UI.
- Enforce `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` requirements.
- Gate behind explicit user opt-in.

## Risks & Mitigations
- **Latency and battery**: throttle captures, downscale images, on-demand mode.
- **Secure windows**: detect screenshot failure and fall back to a11y-only.
- **Token/cost overhead**: send lower-res images and limit frequency.
- **Local LLM compatibility**: keep image optional and degrade gracefully.

## Open Questions
- Should we default to `ON_DEMAND` or keep `OFF` until explicitly enabled?
- Do we want to cache last N screenshots for debugging (opt-in)?
- Should we include an image-derived "screen change hash" in the event stream?

