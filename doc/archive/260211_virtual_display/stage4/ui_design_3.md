# Virtual Display UI — The Stage & The Balcony

> "Simplicity is the ultimate sophistication." — Da Vinci
> 
> "Design is not just what it looks like and feels like. Design is how it works." — Steve Jobs

## The Philosophy

The user has a phone. They want to use it.
The agent needs a phone. It needs to work.
They cannot share the same screen at the same time without friction.

We are not building a "virtual display manager". We are building a **Stage** for the agent.
When the agent is working, it steps onto its private **Stage** (Virtual Display).
The user remains in the **Audience** (Real Display), undisturbed.

Sometimes, the user wants to see the performance. They can peek from the **Balcony** (Mini Island) or walk down to the **Front Row** (Virtual Display Activity).

And when the show is over, the Agent hands the result back to the User. **Magic.**

---

## 1. The Balcony (Mini Island)

When the Agent is performing on the Stage (Virtual Display), the User's Real Screen must remain pristine. No clutter. No complex overlays.

**The Dynamic Pill.**

*   **Appearance**: A small, sleek, black capsule centered at the top of the screen (mimicking the Dynamic Island or a minimal status bar indicator).
*   **Content**: A subtle, pulsing dot (Agent Color) and a brief status: "Agent working..."
*   **Behavior**:
    *   **Idle**: Visible but unobtrusive.
    *   **Processing**: Gentle pulse.
    *   **Action**: A quick flash of the tool icon being used, then back to pulse.
*   **Interaction**:
    *   **Tap**: Opens the **Front Row** (Virtual Display Activity).
    *   **Long Press**: Shows a "Stop" button immediately (Emergency Brake).

**Why?** The user trusts the agent. They don't need to see every click. They just need to know it's alive.

**Critique of Current Bug**: The current "Overlay" (Glow + Smart Capsule) appearing on the Real Screen is a leakage of the Stage into the Audience. It breaks the fourth wall. **Kill it.** The Real Screen gets *only* the Pill.

---

## 2. The Front Row (Virtual Display Activity)

When the user taps the Pill, they enter the **Front Row**. They are now watching the Agent's screen.

*   **Immersive**: Full screen. No status bars, no navigation bars from the host OS. Just the Virtual Display content.
*   **The Smart Capsule (Relocated)**:
    *   The "Smart Capsule" (User Input / Status / Pause / Stop) that used to float on the main screen? **It lives here now.**
    *   It floats over the bottom-center of the Virtual Display content.
    *   This is the control center for the Agent.
*   **The Glow**:
    *   The "AI Thinking" glow effect renders *inside* this activity, around the edges of the Virtual Display content.
    *   It reinforces that *this specific screen* is where the intelligence lives.

**Gestures**:
*   **Swipe Up (from bottom)**: "Leave Front Row".
    *   The activity slides down.
    *   The User returns to their Real Screen.
    *   The Agent *keeps working* on the Stage (Virtual Display).
    *   The Pill reappears on the Real Screen.
    *   **Analogy**: Walking out of the theater while the movie keeps playing.

---

## 3. The Grand Finale (Magic Move)

The most critical moment is when the Agent finishes.

*   **Current (Bad)**: "Task Done." User has to manually navigate to find what the Agent did.
*   **The Design (Good)**: The Agent **hands the app to the user**.

**The Interaction**:
1.  Agent finishes task (e.g., "Open YouTube and play song").
2.  Agent on Virtual Display is at the target state (YouTube playing).
3.  **The Drop**: The Agent's app *dissolves* from the Virtual Display and *materializes* on the Real Display.
4.  The Agent bows (Status: "Here you go").

**Technical Realization**:
*   Use `ActivityOptions.setLaunchDisplayId(DEFAULT_DISPLAY)` to launch the *same* intent the Agent ended with.
*   Because the app instance is the same (same process), Android will likely move the *entire task stack* from the Virtual Display to the Default Display.
*   It feels like teleportation.

---

## 4. The Bugs (The Friction)

### 4.1 The Leaking Overlay
*   **Symptom**: Overlay shows on Main Screen.
*   **Root Cause**: `ServiceOverlayController` thinks "Agent is running, show Overlay". It ignores *where* the user is looking.
*   **Fix**:
    *   `ServiceOverlayController` must know the **Mode**.
    *   **Mode = Virtual Display**:
        *   Main Screen = **Pill Only**.
        *   Virtual Display Activity = **Full Overlay + Glow**.
    *   The implementation must be state-aware.

### 4.2 The Ghost Keyboard
*   **Symptom**: Keyboard pops up on Main Screen while Agent types on Virtual Display.
*   **Diagnosis**: The Input Method (IME) is context-aware. If the Virtual Display doesn't have a dedicated Input Method setup, or if the Agent "clicks" an EditText, the System Default IME (on Main Screen) wakes up.
*   **The Solution**:
    *   **Don't Type.** The Agent is a superuser. It shouldn't need a soft keyboard.
    *   Use `AccessibilityNodeInfo.performAction(ACTION_SET_TEXT)`. This bypasses the keyboard entirely. It writes directly to the memory of the edit field.
    *   **The "Enter" Key**: Sometimes we need to press Enter.
        *   Inject `KEYCODE_ENTER` to the Virtual Display ID.
        *   *Crucially*: Ensure the Virtual Display is marked `trusted` and `own_focus`.
    *   **Aggressive Suppression**: If the keyboard *dares* to appear on the Main Screen, the Agent should detect it (via WindowStateChange) and ruthlessly `hideSoftInput`. But ideally, `ACTION_SET_TEXT` prevents it from ever spawning.

---

## 5. Summary

*   **Real Screen**: Silence. Just a Pill.
*   **Virtual Screen**: The Show. Full Overlay. Glow.
*   **Transition**: Seamless. Swipe up to leave. Tap to watch.
*   **Ending**: Teleport the result to the user.

This is not just a feature. It is a **Spatial Operating Model** for AI.
