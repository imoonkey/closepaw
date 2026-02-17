# Design Review: Shizuku Virtual Display

**Date:** 2026-02-10
**Reviewer:** Gemini
**Documents Reviewed:**
- [Design 1 (shizuku_design_1.md)](shizuku_design_1.md)
- [Design 2 (shizuku_design_2.md)](shizuku_design_2.md)
- [Requirements (qi_note.md)](qi_note.md)

---

## Executive Summary

Both designs offer valuable components, but neither is complete on its own.
- **Design 1** excels at the **Platform Layer**, correctly identifying that the Accessibility Tree *can* and *should* serve the virtual display.
- **Design 2** excels at the **Application/UI Layer**, correctly addressing the "Mini Island" and "Virtual Display Activity" requirements, but incorrectly simplifies the platform layer by dropping Accessibility Tree support.

**Verdict:** Adopting **Design 2** as the architectural skeleton but critically integrating the **Platform logic of Design 1** (specifically A11y filtering) is the correct path forward to meet all requirements in `qi_note.md`.

---

## Design 1 Evaluation

**Focus:** Low-level Platform Implementation
**Score:** 7.5/10

### Strengths
1.  **Correct A11y Implementation**: It correctly identifies that `AccessibilityService` works across all displays and that filtering windows by `displayId` is the standard way to support multi-display A11y. This directly addresses the requirement in `qi_note.md` to share code and use the A11y tree.
2.  **Clean Abstraction**: The decision to keep `ShizukuClient` as a focused wrapper and `VirtualDisplayPlatform` as the logic holder adhering to `AndroidPlatform` is sound.
3.  **Technical Depth**: Detailed verification of Shizuku APIs and InputManager injection reflects strong technical due diligence.

### Weaknesses
1.  **Missed UI Requirements**: It explicitly deems the "Smart Capsule" (on VD) and "Virtual Display Preview" as "future work" (Section 10), whereas `qi_note.md` lists these as core deliverables for this task.
2.  **Missed UX Flow**: It treats the virtual display as a headless implementation detail, failing to address the user-facing "Mini Island" entry point and the specific "swipe up to exit" interaction.

---

## Design 2 Evaluation

**Focus:** Product & Architecture Implementation
**Score:** 8/10

### Strengths
1.  **Product Alignment**: It accurately captures the UI requirements: Mini Island (Real Screen) vs. Full Capsule (Virtual Display), and the specific navigation flows (Swipe to exit).
2.  **Architecture Completeness**: It introduces the necessary `PlatformFactory` and `VirtualDisplayActivity` components that are missing in Design 1 but are essential for the app to function as a product.
3.  **Lifecycle Management**: It identifies the gap in `SessionServices.cleanup()` and explicitly designs `platform.stop()`, which is critical for releasing the virtual display resource.

### Weaknesses
1.  **Regression on A11y**: It explicitly decides to return `elements = emptyList()` for V1 (Section 5, 9). This contradicts the constraint in `qi_note.md` ("...still use a11y tree api... avoid large duplications"). Dropping A11y support makes the agent significantly less robust (blind to nodes, relying only on screenshots/OCR) just for the sake of "minimalism," which is an incorrect tradeoff given the API is available.
2.  **Action Degradation**: By mapping `ClickNodeAt` to `TapAt` (Screen coordinates), it loses the precision of node-based operations that Design 1 preserves.

---

## Synthesis & Recommendation

The final implementation should merge the two designs. It should use **Design 2** as the architectural frame and populate the `VirtualDisplayPlatform` logic with **Design 1**'s technical strategy.

### The Hybrid Plan

1.  **Architecture (From Design 2)**:
    - Use `PlatformFactory` to select platform at session start.
    - Implement `VirtualDisplayActivity` for the specific UI requirements (Island, Swipe exit logic).
    - Lifecycle management via `start()` / `stop()` interfaces on `AndroidPlatform`.

2.  **Platform Implementation (From Design 1)**:
    - **A11y Tree**: Do NOT return empty list. Implement `captureA11yTree` using `service.windows.filter { it.displayId == displayId }` as described in Design 1. This is low-cost and high-value, preserving agent intelligence.
    - **Actions**: Do NOT map everything to coordinates. Support `ClickNodeAt` natively using existing `AccessibilityNodeInfo` APIs which work across displays.
    - **Input**: Use the Shizuku injection logic for coordinate-based actions where A11y actions are not applicable.

### Final Scoring

| Aspect | Design 1 | Design 2 |
| :--- | :--- | :--- |
| **Shizuku API Mastery** | High | High |
| **A11y Tree Strategy** | **Excellent** (Correctly filters ID) | **Poor** (Drops support) |
| **UI/Product Req** | **Poor** (Ignored) | **Excellent** (Detailed) |
| **Architecture** | Good (Platform only) | Excellent (Full App) |
| **"Simple" (KISS)** | Yes | Yes |

**Winner**: **Design 2**, but **MUST** adopt Design 1's A11y strategy to meet the `qi_note.md` addendum.
