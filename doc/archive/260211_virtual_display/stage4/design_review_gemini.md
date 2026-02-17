# Virtual Display Stage 4 Design Review

**Reviewer**: Gemini
**Date**: 2026-02-11
**Scope**: UI & System Designs for Virtual Display (Stage 4)

---

## Executive Summary

The collected designs present a unified vision: **The "Stage & Balcony" Model**. All designs agree on the core UX: the main screen shows a minimal status indicator ("Island"), while the Virtual Display (VD) content is viewed via a dedicated full-screen Activity ("Viewer").

*   **Best UI Design**: **UI Design 3** (The Metaphor) + **UI Design 1** (The Spec).
*   **Best System Design**: **System Design 1** (The Pragmatist) validated by **System Design 3** (The Purist).

---

## UI Design Review

All three UI designs converge on the same interaction model, which is excellent. They differ primarily in fidelity and explanatory power.

### Comparison

| Feature | UI Design 1 | UI Design 2 | UI Design 3 |
| :--- | :--- | :--- | :--- |
| **Metaphor** | "Back Room" | "Background Run" | **"Stage & Balcony"** |
| **Entry Point** | Status Island (Pill) | Dynamic Island | Mini Island (Pill) |
| **Viewer** | Live View Activity | Viewer Activity | Front Row (Activity) |
| **Exit** | Swipe Up | Swipe Up | Swipe Up |
| **Details** | **High** (renders inside Activity) | Med (Verification criteria) | High (Interaction philosophy) |

### Strengths & Weaknesses

*   **UI Design 1**: **Strongest Technical Spec.** It explicitly defines *how* the UI is rendered (Compose inside Activity vs System Window), which is the key to solving the "Leaking Overlay" bug. It clearly lists what is suppressed on the real screen vs what is shown in the viewer.
*   **UI Design 2**: Good functional requirements and verification criteria ("DoD").
*   **UI Design 3**: **Strongest Conceptual Model.** The "Stage/Audience" and "Balcony/Front Row" metaphors provide a powerful shared vocabulary for the team. It clarifies *why* we are doing this.

### Recommendation

Adopt the **"Stage & Balcony" metaphor from Design 3**, but implement the **technical specification of Design 1**. 
*   **Crucial Decision**: The "Smart Capsule" and "Edge Glow" must NOT be system overlays in VD mode. They must be standard Views/Composables inside the `VirtualDisplayViewerActivity`. This naturally solves the leakage problem.

---

## System Design Review

The system designs tackle the same problems but offer different architectural complexities.

### Problem 1: Frame Pipeline (How to show the VD?)

*   **Design 1**: `VirtualDisplayFrameRelay`. A simple bridge. Uses a `Bitmap` cache or `TextureView`. Recommended: **Bitmap polling (cached)**.
*   **Design 2**: `VirtualDisplayFrameHub`. Reference counting, shared frame packets.
*   **Design 3**: `VirtualDisplayManager` + "Continuous Pump" (15fps loop).

**Verdict**: **Design 1 is the KISS winner.** We don't need a real-time, low-latency gaming stream. We need a monitoring window. Polling a cached Bitmap (or the simplest `TextureView` attachment) reduces complexity significantly compared to a full "Hub" or "Pump".

### Problem 2: The Leaking Overlay (Capsule showing on Main Screen)

*   **Design 1**: `platformMode` check in `ServiceOverlayController`. Simple `if/else`.
*   **Design 2**: `VirtualDisplayUiCoordinator`. A new central brain to dispatch state.
*   **Design 3**: "Ruthless Refactor". Deprecate main screen logic in `ServiceOverlayController` for VD mode.

**Verdict**: **Design 1 & 3 are aligned.** Design 2's Coordinator is elegant but potentially over-engineered for this stage. Design 1's approach of "just check the mode" is the most direct fix. Design 3's insight to move the capsule *into* the Activity layout (standard View hierarchy) effectively kills the bug by definition.

### Problem 3: The Ghost Keyboard (IME popping up on Main Screen)

*   **Design 1**: **Pragmatic Band-aid**. `clearFocus` + Shell command (`input keyevent 111` / `hideSoftInput`) to dismiss it if it appears.
*   **Design 2**: `TextInputPolicy`. Structured architectural fix. `NODE_ONLY` policy for VD to prevent focus requests.
*   **Design 3**: **"Silent Mode"**. Strict `ACTION_SET_TEXT` only. No clicking edit text.

**Verdict**: **Combine Design 1 and 3.**
*   **Primary Defense**: Adopt Design 3/2's policy: **Do not tap-to-focus** on VD. Use `ACTION_SET_TEXT`.
*   **Secondary Defense**: Keep Design 1's "Shell Command Dismissal" as a safety net. Android IMEs are notorious; a pure code fix might not catch every edge case (e.g., some fields auto-focus on window attach).

### Problem 4: Task Handoff (The "Magic Move")

*   **Design 1**: Relaunch app with same package. "Honest relaunch".
*   **Design 2**: "Task Continuity Handoff" (Best effort).
*   **Design 3**: `ActivityOptions.setLaunchDisplayId(DEFAULT_DISPLAY)`.

**Verdict**: **Design 3 is the correct Android implementation.** Moving the task via `ActivityOptions` is the system-intended way to migrate an activity stack between displays.

---

## Final Recommendations & Action Plan

1.  **Architecture**: Use **Design 1** as the base. It is the most actionable and "plumbing-focused".
2.  **UI Implementation**: Implement **`VirtualDisplayViewerActivity`** as a standard Android Activity. **Embed** the Capsule and Glow as standard Composable views within it. **Do not** use `WindowManager` for the VD overlay.
3.  **Input Fix**: Modify `TypeExecutor` to skip the "Tap-to-Focus" step when in VD mode. Add the "Dismiss Keyboard" shell command as a fallback.
4.  **Handoff**: Use `ActivityOptions` to move the task to Display 0 on success.

### Scoring (for fun)
*   **System Design 1**: 9/10 (Pragmatic, manageable scope)
*   **System Design 2**: 7/10 (A bit heavy on new abstractions)
*   **System Design 3**: 8.5/10 (Great insights, bold, maybe slightly aggressive on refactoring)
*   **UI Design 1**: 9/10 (Clear technical path)
*   **UI Design 3**: 9/10 (Great conceptual clarity)

---
