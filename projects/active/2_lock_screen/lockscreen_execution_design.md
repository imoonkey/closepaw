# Lockscreen Execution Design: Doubao Analysis vs. Shizuku Implementation

## 1. Executive Summary

**Doubao's "Lockscreen Execution"** is not magic; it is a combination of **privileged system capabilities** and **OEM-specific customization**. It does not merely run "on top" of the lockscreen; it runs on a completely separate **Virtual Display** that is invisible to the user but fully active to the system.

**Can Shizuku achieve this?**
**YES**, to a very high degree (`80-90%`). Shizuku grants access to the `shell` user's permissions, which include creating Virtual Displays, injecting input, and capturing screens. The main capability gap is not in *functionality* but in *power management* (OEMs whitelist their own scheduling) and *secure content* access.

---

## 2. Doubao's Implementation (Reverse Engineering Analysis)

Based on the analysis of `autoaction` and `tech_doc.md`, Doubao achieves "execution while locked" through the following architecture:

### 2.1 The "Parallel Universe" Strategy
Doubao doesn't unlock your phone. Instead, it creates a **Trusted Virtual Display** (headless, brightness 0) and moves the target application stack to this display.

*   **Physical Screen**: Shows Lockscreen / Always-on Display. (State: `OFF` or `DOZE`)
*   **Virtual Screen**: Runs the standard Android UI stack. (State: `ON`)

### 2.2 Key Mechanisms
1.  **Virtual Display Creation**:
    *   Uses `DisplayManager.createVirtualDisplay` with privileged flags.
    *   Flags: `VIRTUAL_DISPLAY_FLAG_TRUSTED`, `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`, `VIRTUAL_DISPLAY_FLAG_PUBLIC`.
    *   **Why Trusted?** Trusted displays allow system decorations and proper window focus management, which "untrusted" virtual displays (standard Android API) often restrict.

2.  **Task Migration (OEM Magic)**:
    *   Uses `ActivityManagerEx.moveTaskToDisplay(taskId, displayId, onTop)`.
    *   This is an OEM-extended API (Nubia specific) that allows forced migration of *any* app to the virtual display, ensuring it continues to render.

3.  **Input & Vision**:
    *   **Vision**: `READ_FRAME_BUFFER` allows it to read the GPU buffer of the *Virtual Display* directly, ignoring the fact that the physical screen is off.
    *   **Input**: `INJECT_EVENTS` allows it to send touch/swipe/key events targeting the specific `displayId` of the virtual display.

4.  **Keep-Alive**:
    *   Since it is a system app (`priv-app`), it is whitelisted from battery optimization. The Virtual Display keeps the rendering pipeline active.

---

## 3. Shizuku-Based Design (User Implementation)

We can mimic this architecture using **Shizuku** to access hidden Android APIs normally reserved for the system or `adb shell`.

### 3.1 Architecture Overview

```mermaid
graph TD
    User[User] -->|Locks Phone| Phone
    Phone[Physical Screen] -->|OFF/Locked| LockScreen
    
    Service[Your Agent Service] -->|1. Acquire| WakeLock[Partial Wake Lock]
    Service -->|2. Create| VD[Virtual Display (Shizuku)]
    Service -->|3. Launch| TargetApp[Target App]
    
    TargetApp -->|Renders on| VD
    Service -->|4. Read| VD_Buffer[Screen Buffer]
    Service -->|5. Inject| Input[Input Events]
    
    subgraph "Shizuku Privileges"
        VD
        VD_Buffer
        Input
    end
```

### 3.2 Detailed Implementation Plan

#### A. Keep CPU Alive (The Foundation)
You **MUST** keep the CPU running when the screen is off.
*   **Approach**: Foreground Service + Partial Wake Lock.
*   **Code**:
    ```kotlin
    val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Agent:Working")
    wakeLock.acquire()
    // Run as Foreground Service with Notification
    startForeground(ID, notification)
    ```
*   **Risk**: OEM Battery Savers (Samsung/Xiaomi) might still kill it.
*   **Fix**: Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

#### B. Create the Virtual Display (The "Shell" Trick)
Standard `createVirtualDisplay` creates a "Private" display that might pause when the owner pauses. We want a robust one.
*   **Shizuku Strategy**: Use `IWindowManager` or `IDisplayManager` hidden APIs (via Shizuku binder) to create the display with the best possible flags allowed for `shell` (UID 2000).
*   **Flags**: Try to set `VIRTUAL_DISPLAY_FLAG_PUBLIC` and `VIRTUAL_DISPLAY_FLAG_PRESENTATION`.
*   **Note**: `shell` cannot create `TRUSTED` displays on stock Android 10+, but usually `PUBLIC` + `OWN_CONTENT_ONLY` is sufficient for automation.

#### C. Launching Apps "Away"
How to launch an app specifically on that display?
*   **Command**:
    ```kotlin
    val options = ActivityOptions.makeBasic()
    options.setLaunchDisplayId(virtualDisplayId)
    context.startActivity(intent, options.toBundle())
    ```
*   **Challenge**: Some apps check `isScreenOn`. On a Virtual Display, `isScreenOn` might return true if configured correctly, even if the physical screen is off.

#### D. Interaction (Touch & See)
*   **See**: use `SurfaceControl.captureDisplay` (Android 10+) via Shizuku. This works even if the display is not "visible" to the user.
*   **Touch**: Use `InputManager.injectInputEvent` via Shizuku.
    *   *Crucial*: You MUST set `.setDisplayId(virtualDisplayId)` on the input events.

### 3.3 Critical Challenges & Solutions

| Challenge | Doubao (OEM) Solution | Shizuku (User) Solution |
| :--- | :--- | :--- |
| **Doze Mode** | System Whitelist | Partial Wake Lock + Ignore Battery Opt |
| **Secure Apps** | `CAPTURE_SECURE_VIDEO_OUTPUT` | **Cannot Bypass** (DRM content will be black) |
| **Task Management** | `moveTaskToDisplay` (Force) | `setLaunchDisplayId` (Launch new) |
| **App Pausing** | ActivityManagerEx (Force Resume) | Keep VD "ON". Some apps might still pause if they detect physical screen off. |

---

## 4. Feasibility Conclusion & Roadmap

**Feasibility: HIGH**
You can achieve 90% of Doubao's functionality. The "Lockscreen Execution" effect is fully achievable because Android treats the Virtual Display as a valid render target as long as the CPU is awake.

### Next Steps
1.  **Prototype Virtual Display**: Use the code from `virtual_display_design.md` to run a simple `displayManager.createVirtualDisplay` test via Shizuku.
2.  **Test "Screen Off" Behavior**:
    *   start the VD.
    *   launch a timer app on it.
    *   turn off physical screen.
    *   wait 5 mins.
    *   check if timer continued.
3.  **Implement Input Injection**: Ensure you can click buttons on that invisible display.

### Why Shizuku is "Good Enough"?
Doubao needs to be "perfect" because it's a commercial product. It needs to handle *every* app, including secure ones, and *never* be killed by the system.
For a personal/prosumer tool, **Shizuku + Wake Lock** is sufficient. If the specific app acts up, you can usually tweak the implementation (e.g., wake screen momentarily or keep a dummy activity on top) to fool it.
