# Note
非常重要的两个问题，仔细阅读：
Q: What is the optimal architecture for Performance + UX? (The Hybrid Model)
- 这个问题回答如果属实，那这是最理想的path。就按这个设计和实现
Q: How does this "Hybrid Mode" discussion affect UI evaluation vs System evaluation?
- 用这个来guide product/UI design。product/UI design不需要被底层实现困扰。product/UI design只需要关注用户体验。

# Compatibility & FAQ


## Q: Are the designs compatible with future "Interactive Mode" (User Takeover)?

**Verdict: YES, all three designs are compatible.**

The core requirement for "User Takeover" is:
1.  **Decoupled Rendering:** The Agent's display must be viewable on the main screen without conflicts.
2.  **Input Injection Capability:** We must be able to send user touches back to the Virtual Display.

**Analysis:**
*   **Design 1:** Uses `VirtualDisplayViewerActivity` + `TextureView`. This is **100% compatible**. We can easily attach an `OnTouchListener` to the TextureView to capture user input and inject it into the Virtual Display.
*   **Design 2:** Uses `VirtualDisplayUiCoordinator` + `TextInputPolicy`. This is the **most future-proof**. The `TextInputPolicy` abstraction is perfect for switching between "Agent Input" (Accessibility) and "User Input" (Touch/IME).
*   **Design 3:** Uses `VirtualDisplayManager` + `Activity`. Also compatible.

**Recommendation:** Design 2 provides the best architectural "hooks" for the future switch, but all three are viable.

## Q: Is the Live View real-time (fluid) or screenshot-based?
**Q: Can I get 60fps smooth scrolling like the real screen?**

**Answer: YES, you can achieve 60fps hardware-accelerated rendering.**

**Why the current designs (Stage 4) proposed lower frame rates (5-15fps):**
They used a "Software Bitmap" approach (CPU copy) for simplicity (KISS) and battery saving, because the primary goal was just *monitoring* the Agent.

**How to achieve 60fps "Real Screen" performance (The Future Plan):**
To get 60fps smoothness, we switch from the "Software Path" to the "Hardware Path":

1.  **Hardware Path (The "Zero Copy" solution):**
    Instead of passing Bitmaps, we pass the raw `Surface`.
    *   **Method:** Use `VirtualDisplay.setSurface(Surface)`.
    *   **When User Watches:** We point the Virtual Display directly to the `SurfaceView` in your `VirtualDisplayViewerActivity`.
    *   **Result:** The GPU renders directly to your viewer activity. **60fps. Zero lag. Hardware accelerated.** Just like a real screen.

2.  **The Limitation of Hardware Path:**
    *   When the Virtual Display is pointed at the Viewer Surface, the `ImageReader` (Agent's eyes) is disconnected.
    *   **Solution:** This is perfect for "User Takeover". When you take over, the Agent "closes its eyes" and lets you drive. When you leave, we switch the Surface back to the `ImageReader` so the Agent can see again.

**Conclusion:**
There is no physical limitation preventing 60fps. It is purely a software buffering choice in the current designs. Switching to `setSurface()` in the future unlocks the full hardware performance.

## Q: If I use `setSurface` for 60fps, how do I capture screenshots? Does Shizuku support `READ_FRAME_BUFFER`?

**Answer: Yes, Shizuku supports it, but `PixelCopy` is better.**

When you switch the `VirtualDisplay` to output directly to your Activity's `SurfaceView` (for 60fps), you disconnect the original `ImageReader`. You have two strategies to get screenshots for the Agent:

1.  **Shizuku / Shell (`screencap`):**
    *   **Method:** Run `screencap -d <displayId> -p` via Shizuku.
    *   **Pros:** Works on any display (even main screen).
    *   **Cons:** **Slow.** Spawning a shell process takes 50-100ms. Parsing stdout adds latency. Not ideal for high-frequency AI perception.

2.  **The "Pro" Way: `PixelCopy` API (Recommended)**
    *   **Method:** Since the `VirtualDisplay` is rendering to *your* Activity's `SurfaceView`, you own that Surface!
    *   **Mechanism:** Use the standard Android `PixelCopy.request(surfaceView, bitmap, ...)` API.
    *   **Pros:** **Fast.** In-process memory copy. No shell commands. No Shizuku overhead.
    *   **Result:** You get the best of both worlds:
        *   **User:** Sees 60fps hardware video.
        *   **Agent:** Pulls screenshots on-demand via `PixelCopy` without interrupting the user.

**Verdict:**
You don't need to hack the capabilities. Use the standard **`PixelCopy`** API which is designed exactly for this "snapshotting a live Surface" use case.

## Q: Can I use `PixelCopy` on the Main Screen (in AccessibilityPlatform mode) to unify screenshot capturing?

**Answer: NO.**

*   **The Limitation:** `PixelCopy` only works on Surfaces that **target the same window** or SurfaceView that you own.
*   **Main Screen:** The Main Screen is owned by the Android OS (`SystemUI` / `WindowManager`). Your app (even with Accessibility permission) does **not** own the Main Screen's Surface.
*   **Result:** Trying to `PixelCopy` the Main Screen Surface will result in `ACCESS_DENIED` or failure.

**Conclusion:**
You **cannot unify the implementation mechanism**.
*   **Virtual Display Mode:** Use `PixelCopy` (fast, owned).
*   **Accessibility Mode:** MUST use `AccessibilityService.takeScreenshot()` (the only legal API for apps to capture other apps).

**Recommendation:** Unify at the **Interface** level (`AndroidPlatform.captureScreen()`), but keep two distinct **Implementations** (`PixelCopy` vs `takeScreenshot`) internally. This is the correct, platform-compliant way.

## Q: Can I use `PixelCopy` even for Background/Non-Live Virtual Display, so I can eliminate `ImageReader` entirely?

**Answer: Yes, but with one condition: You need a Surface.**

To use `PixelCopy`, you MUST have a `SortOfSurface` to copy from.
*   **Live Mode:** You have a visible `VirtualDisplayViewerActivity` with a `SurfaceView`. PixelCopy works great.
*   **Background Mode:** There is no visible Activity.

**How to unify? (The "Headless Surface" Trick)**
If you really hate `ImageReader` and want `PixelCopy` everywhere:
1.  **Create a Background Surface:** You can create an off-screen `SurfaceTexture` or a hidden, transparent 1x1 pixel `SurfaceView` in your Service.
2.  **Always target this Surface:** Point your `VirtualDisplay` to this invisible/background Surface 100% of the time.
3.  **Always use PixelCopy:** Even when the user isn't watching, `PixelCopy` works on off-screen Surfaces (as long as they are valid/attached).

**Trade-off:**
*   **ImageReader:** Is designed specifically for "Headless" consumption (buffer-to-buffer). It is the most efficient way to get raw bytes without a View system.
*   **PixelCopy w/ Hidden Surface:** Is slightly "hacky" for background work (you are simulating a screen just to read it back), but it **will work** and allows you to unify your code path to use `PixelCopy` 100% of the time.

# Q: What is the optimal architecture for Performance + UX? (The Hybrid Model)

**Answer: Hybrid Model (ImageReader Background + PixelCopy Live View).**

This is the "Gold Standard" architecture that gives you:
1.  **Zero Overhead in Background:** Uses `ImageReader` (raw buffer queue). No UI rendering. Best battery life.
2.  **60fps Hardware Acceleration in Foreground:** Switches to `Display.setSurface(activitySurface)` when watching. Best UX.

**How it works in `VirtualDisplayPlatform.kt`:**
1.  **Default:** `VirtualDisplay` points to `imageReader.surface`.
2.  **On User Watch (Live View):**
    *   Call `virtualDisplay.setSurface(viewerActivity.surface)`.
    *   This instantly enables hardware rendering on the viewer.
3.  **On User Exit:**
    *   Call `virtualDisplay.setSurface(imageReader.surface)`.
    *   This instantly reverts to low-power headless mode.

**Screenshot Logic:**
*   `captureScreen()` checks a flag (e.g., `isLiveMode`).
*   If `false` (Background): Reads from `ImageReader.acquireLatestImage()`.
*   If `true` (Live View): Calls `PixelCopy.request(viewerActivity.surface, ...)` to snapshot the live stream.

**Conclusion:**
This architecture is robust, smooth, and power-efficient without hacks. It is the recommended path for a high-quality product.

# Q: How does this "Hybrid Mode" discussion affect UI evaluation vs System evaluation?

**Answer: It is largely orthogonal, affecting System Feasibility only.**

1.  **UI Design (`ui_design_*.md`):** Focus on the **user journey**.
    *   *Example:* Does the "Pill" metaphor work? Is "Swipe Up to Dismiss" intuitive?
    *   *Overlap:* If a UI design promises "silky smooth 60fps interaction", you now know the **System** can deliver it via Hardware Path. But the *UI concept itself* is independent of `PixelCopy` vs `ImageReader`.

2.  **System Design (`system_design_*.md`):** Focus on the **architecture**.
    *   *Example:* Does the design create a `VirtualDisplayViewerActivity`? (Yes). Does it use a `TextureView/SurfaceView`? (Yes).
    *   *Verification:* As long as the System Design sets up the basic components correctly (Activity + Surface), you can swap in the "Hybrid Mode" logic later without breaking the architecture.

**Verdict:**
You do **not** need to worry about `PixelCopy` implementation details when judging the UI designs. Evaluate the UI based on clarity, aesthetics, and flow. The System architecture is flexible enough to support whatever performance level you choose later.
