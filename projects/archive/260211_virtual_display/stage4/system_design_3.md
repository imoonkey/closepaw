# Virtual Display System Design (Stage 4)

> "Talk is cheap. Show me the code." — Linus Torvalds
> 
> "Perfection is achieved, not when there is nothing more to add, but when there is nothing left to take away." — Antoine de Saint-Exupéry

## 1. Core Architecture

We are not patching the old system. We are defining the new reality.

### 1.1 The Source of Truth: `VirtualDisplayManager`
Previously, `VirtualDisplayPlatform` was a silo. Now, it must expose its state to the UI.

New Component: **`VirtualDisplayManager`** (Singleton)
*   Holds the `VirtualDisplay` instance.
*   Holds the `ImageReader`.
*   Exposes `screenFlow: StateFlow<Bitmap?>`.
*   Exposes `displayId: Int`.
*   Manage `VirtualDisplayActivity` launching.

**Why?** The `VirtualDisplayPlatform` is an *Agent* component (implements `AndroidPlatform`). The UI (Activity/Overlay) is a *User* component. They need a shared bridge. `VirtualDisplayManager` is that bridge.

### 1.2 The Image Pipeline (KISS)
*   **Old**: `captureScreen()` calls `imageReader.acquireLatestImage()`. Blocking. Single consumer.
*   **New**: **Continuous Pump**.
    *   A coroutine in `VirtualDisplayManager` runs `@ 15fps` (sufficient for UI, overkill for Agent).
    *   It acquires the image, converts to `Bitmap`, updates `screenFlow.value`.
    *   `VirtualDisplayPlatform.captureScreen()` simply reads `screenFlow.value`. Instant. Non-blocking.
    *   `VirtualDisplayActivity` collects `screenFlow`. Renders to `ImageView`.
    *   **Logic**: simpler, decoupled, predictable.

---

## 2. UI Components Implementation

### 2.1 `MiniIslandService` (The Balcony)
*   **Replaces**: The complex logic inside `ServiceOverlayController` for the main screen.
*   **Responsibility**: Show the floating pill on Default Display.
*   **Logic**:
    *   If `VirtualDisplay` is active AND `VirtualDisplayActivity` is NOT visible: **Show**.
    *   Else: **Hide**.
*   **Interaction**: Click -> `startActivity(VirtualDisplayActivity)`.

### 2.2 `VirtualDisplayActivity` (The Front Row)
*   **Manifest**: `android:launchMode="singleTop"`, `android:theme="@style/Theme.NoDisplay"` (initially) -> dynamically switches to Fullscreen.
*   **Layout**:
    *   `ImageView` (for the stream).
    *   `SmartCapsuleView` (reused from existing overlay logic).
    *   `EdgeGlowView` (reused).
*   **Logic**:
    *   `onStart()`: Bind to `VirtualDisplayManager.screenFlow`.
    *   `onSwipeUp()`: `finish()`.

### 2.3 `ServiceOverlayController` Deprecation
*   **Ruthless Refactor**:
    *   Strip out all "main screen overlay" logic if `isVirtualDisplay` mode is active.
    *   If `PlatformMode == VIRTUAL_DISPLAY`, `ServiceOverlayController` effectively does nothing on the main screen (delegates to `MiniIslandService`).
    *   It *only* manages the capsule *inside* `VirtualDisplayActivity` (via callback/injection).

---

## 3. Bug Hunting & Elimination

### 3.1 The Ghost Keyboard (IME) Fix
**Root Cause**: Input injection + Focus requests trigger the global `InputMethodManager`. Android associates the IME with the "interactive" user.

**The Fix: "Silent Mode" Input**
Use a custom `InputStrategy` for Virtual Display.

1.  **Text**: **ONLY** use `AccessibilityNodeInfo.performAction(ACTION_SET_TEXT)`.
    *   Determine target node.
    *   `Bundle arguments = new Bundle(); arguments.putCharSequence(..., text);`
    *   `node.performAction(ACTION_SET_TEXT, arguments);`
    *   **Do not click first.** Clicking sets focus, invoking IME. Setting text via A11y does not require focus in the WindowManager sense.

2.  **Enter Key**:
    *   If we *must* press enter:
    *   Check if keyboard is visible on Main Display (`InputMethodManager.isActive()`).
    *   If yes -> `hideSoftInputFromWindow`.
    *   Then inject `KEYCODE_ENTER` to VD.

3.  **App Launch**:
    *   Always use `ActivityOptions.setLaunchDisplayId(vdId)`.
    *   Add `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK` to ensure separation.

### 3.2 The Leaking Overlay Fix
*   **Logic**: The Overlay must be parented to the correct Context.
*   **Current**: `WindowManager.addView(view, params)`. Uses `ApplicationContext` or `Service` context. Defaults to Main Display.
*   **New**:
    *   **Main Screen Pill**: Uses `Service` context (correct).
    *   **VD Overlay**:
        *   Do **NOT** use `WindowManager`.
        *   Embed standard Android Views (`SmartCapsuleView`) directly into the XML layout of `VirtualDisplayActivity`.
        *   Why? Because `VirtualDisplayActivity` is a normal app activity. It's easiest to just build a layout. No system permissions needed. No "draw over other apps" needed *within* the app itself.
        *   **KISS**: An overlay inside an Activity is just a `FrameLayout`.

---

## 4. The Magic Move (Task Handover)

When `SessionState.Success`:

```kotlin
fun handOverTaskToUser(finalPackage: String) {
    val intent = packageManager.getLaunchIntentForPackage(finalPackage)
    // The magic flags to move the task
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    
    // Crucial: Create options for the DEFAULT display
    val options = ActivityOptions.makeBasic()
    options.launchDisplayId = Display.DEFAULT_DISPLAY
    
    // Launch. Android OS handles the process migration.
    context.startActivity(intent, options.toBundle())
}
```

## 5. Implementation Roadmap (Dev)

1.  **`VirtualDisplayManager`**: Create the singleton. Move `ImageReader` logic here. (Status: New)
2.  **`MiniIslandService`**: Implement the floating pill logic. (Status: New)
3.  **`VirtualDisplayActivity`**: Create the viewer activity. (Status: New)
4.  **`VirtualDisplayPlatform` Refactor**: Gut the internal `ImageReader`. Point it to `VirtualDisplayManager`. (Status: Modify)
5.  **`ServiceOverlayController` Refactor**: Add checks for `PlatformMode`. Disable existing overlays in VD mode. (Status: Modify)

## 6. Code Guidelines (Linus Mode)

*   **No Interfaces for "Mockability"**. If there's only one implementation, write a class.
*   **No "BaseOverlayManager"**. The Pill and the Capsule are different. Don't force them to share a hierarchy.
*   **Crash Fast**. If `VirtualDisplay` fails to create, throw an exception. Don't retry silently.
*   **Delete Old Code**. The old `AccessibilityPlatform` logic that tried to handle overlays? Gone.

---

**Summary**:
*   **VD Manager** owns the pixels.
*   **VD Activity** shows the pixels + controls.
*   **Mini Island** shows the status.
*   **Main Screen** stays clean.

This is the way.
