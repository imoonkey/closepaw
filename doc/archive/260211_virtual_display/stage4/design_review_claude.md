# Stage 4 Design Review

Date: 2026-02-11
Reviewer: Claude

---

## Overview

This review covers 6 design documents (3 UI + 3 system) for Virtual Display Phase 4. All designs respond to the same `qi_note.md` requirements:
1. Dynamic Island (灵动岛) as the background status indicator
2. Viewer activity for watching the agent on the VD
3. Swipe-up to exit viewer without interrupting the agent
4. Completion handoff — bring the result back to the main screen
5. Fix overlay leak bug (glow/capsule appearing on main screen)
6. Fix ghost keyboard bug (IME popping on main screen during VD typing)

All three teams converge on the **same 3-state model**: Background Pill → Viewer Activity → Handoff. This is a strong signal — the core UX model is sound. The differences lie in **architectural depth**, **bug fix rigor**, and **implementation pragmatism**.

---

## Part 1: UI Design Comparison

### UI Design 1 — "Three States, Nothing More"

**Strengths:**
- Clearest articulation of *why* each decision was made. The "back room assistant" metaphor is immediately understandable.
- Explicitly defines what is **removed** on the real screen (table in §"What We're Removing"). This is the most important UI decision and only Design 1 gives it a crisp, scannable answer.
- Concrete visual specs: `#1A1A2E` at 70% opacity, 18dp radius, 12sp text, `200ms` animation timings. Implementation-ready.
- Honest about the keyboard fix: acknowledges `ACTION_SET_TEXT` + dismiss is a band-aid, and names key-event injection as the real solution.

**Weaknesses:**
- The Status Island is **tap-only**. No long-press for quick stop. If the user wants to abort an errant task, they must tap → open viewer → find stop button. This is a UX gap for the "oh no, stop!" scenario.
- App icon in the pill is not mentioned. The user learns *what the agent is doing* but not *which app* it's doing it in.
- Handoff is described as a "simple relaunch" — honest, but doesn't address the case where the user is currently *in* the viewer when the task completes (no transition sequence defined).

**Score: 8.5/10** — Best UX rationale, most implementation-ready visual spec. Minor interaction gaps.

---

### UI Design 2 — "简单、可预期、无串扰"

**Strengths:**
- Best **information density** in the Dynamic Island: App icon + App name + status phrase + status dot. This is the richest pill of the three, and arguably the most useful — the user sees *which app* and *what's happening* without opening the viewer.
- **Long press** on the island exposes Pause/Resume/Stop controls. This solves the "emergency stop" problem that Design 1 misses.
- Professionally defines the handoff as **"Task Continuity Handoff"** with a clear best-effort framing. Also specifies the **viewer-open completion flow** (show success 1.2s → transition) vs **viewer-closed flow** (direct switch + island toast 2s). This is the most nuanced handoff UX.
- **Acceptance criteria** (§6) are concrete and testable: `<= 300ms` viewer open, `> 99%` swipe success rate. No other design gives quantitative UX targets.

**Weaknesses:**
- The pill information is rich but risks visual clutter at ~160dp. App icon + name + status phrase + dot is 4 elements in a tiny capsule. Needs careful visual design to avoid cramming.
- Does not address the keyboard bug at all in the UI doc (presumably deferred to system design). But the UI doc should at least acknowledge that VD typing should not affect the main screen, since it's a user-visible behavior.
- The "failure handback" (§4.3) mentions "用户点岛后仍可查看 virtual display 最后一帧" — this implies persisting the last VD frame after the VD is destroyed, which is a non-trivial implementation detail passed off as a simple UI spec.

**Score: 8/10** — Most feature-complete interaction model. Best handoff nuance. Slightly over-specified pill risks implementation friction.

---

### UI Design 3 — "The Stage & The Balcony"

**Strengths:**
- The **Stage / Audience / Balcony / Front Row** metaphor is vivid and internally consistent. It creates a strong mental model for the entire team.
- Long press = "Emergency Brake" (immediate Stop). Clean, decisive.
- Bug analysis is embedded in the UI doc (§4), which is unusual but effective — it connects the *symptom the user sees* to the *root cause*, making the UI doc self-sufficient.
- "Don't Type" (§4.2) — the boldest keyboard fix: argues the agent should **never trigger a soft keyboard at all**, using `ACTION_SET_TEXT` exclusively. This is the most principled stance.

**Weaknesses:**
- The pill content ("Agent working..." + pulsing dot) is the **least informative** of the three. No app name, no app icon. The user can't tell *what* the agent is doing without opening the viewer. This contradicts the qi_note requirement: "在执行的时候，会在灵动岛显示virtual display当前ai agent在操作的app".
- "The Drop" (§3, dissolve/materialize animation) is poetic but technically undefined. What does "dissolves from VD and materializes on Real Display" actually mean in Android? It's hand-waved as `setLaunchDisplayId(DEFAULT_DISPLAY)` which is just a relaunch — the dissolve animation is pure fiction without custom transition code.
- "Tool icon flash" in the pill (§1) is a nice micro-interaction but adds implementation complexity for minimal user value — the tool icon is visible for a fraction of a second.
- Claims "app instance is the same (same process), Android will likely move the entire task stack" — this is **incorrect** for most cases. `startActivity` with a different `launchDisplayId` typically creates a new task on the target display rather than reparenting the existing one. This is a factual error that could lead to a broken handoff if taken at face value.

**Score: 6.5/10** — Strongest narrative, but weakest on specifics. The pill violates a qi_note requirement, and the handoff contains a factual error about Android task reparenting.

---

## Part 2: System Design Comparison

### System Design 1 — "Plumbing, Not Framework"

**Strengths:**
- **Self-correcting reasoning** on the frame relay is remarkably honest: proposes a Surface pipeline → recognizes ImageReader contention → considers shared surface → rejects as overcomplicated → lands on a trivial bitmap cache polled at 5fps. This is what real engineering looks like — showing the thought process and arriving at the simplest solution.
- Component inventory is tight: 3 new files, 4 modifications, ~325 new lines + ~75 modified. This is the smallest footprint of all three designs.
- `when(platformMode)` branching in `ServiceOverlayController` is the simplest possible approach — flat, explicit, no new abstractions. Every method reads independently.
- **Explicit non-goals** (§"What This Design Does NOT Do"): no multi-touch passthrough, no PiP, no recording. This prevents scope creep.
- Implementation order is dependency-aware: standalone components first, wiring last.

**Weaknesses:**
- `AgentService.instance?.frameRelay` — the viewer reads state from a static service reference. This is a well-known anti-pattern (static singleton leaking into Activity lifecycle). If the service is restarted while the viewer is open, NPE. A real solution should use a bound service or a shared ViewModel/StateFlow.
- Keyboard fix (§6b) uses `KEYCODE_ESCAPE (111)` via shell to dismiss the IME on display 0. This is fragile: ESCAPE does different things in different ROM contexts (some dismiss IME, some go back, some do nothing). `KEYCODE_BACK (4)` or the `InputMethodManager.hideSoftInputFromWindow` API would be more reliable.
- No `VirtualDisplayUiCoordinator` or equivalent — the overlay controller directly manages both modes. This means overlay state logic for two completely different UI paradigms lives in the same class, growing its complexity.
- No mention of `a11y mode` regression prevention strategy. The qi_note explicitly asks for no regression in a11y mode.

**Score: 8/10** — Most pragmatic implementation plan. Best self-correcting reasoning. Clean scope. Minor issues with static references and the keyboard fix approach.

---

### System Design 2 — "最小改动版"

**Strengths:**
- **Best architecture** of the three: introduces `VirtualDisplayUiCoordinator` as a single UI state owner with `StateFlow<VirtualDisplayUiState>`. This cleanly separates the state computation from the rendering targets (Island vs Viewer), and makes the system testable.
- **Root cause diagnosis** is the most rigorous: traces the overlay bug to specific code paths (`AgentService.kt` event handling → `ServiceOverlayController` lacking platformMode), and traces the keyboard bug to `TypeExecutor`'s `TapAt → SetTextOnFocused` fallback in Attempt 2.
- The `TextInputPolicy` enum (`NODE_ONLY` vs `TAP_TO_FOCUS_ALLOWED`) is the **best keyboard fix** across all designs. It doesn't just suppress symptoms — it prevents the root cause at the executor level. And it's a clean platform capability abstraction that doesn't pollute the tool layer.
- `VirtualDisplayFrameHub` with `StateFlow<FramePacket?>` as a single-consumer reader of `ImageReader` is architecturally cleaner than Design 1's bitmap cache — it eliminates the contention problem rather than working around it.
- **Testing strategy** (§7) is the only design that includes unit test targets, integration test scenarios, AND regression checks. This is production-grade thinking.
- **Risk matrix** (§8) with mitigations — ROM differences, frame overhead, lifecycle jitter. No other design acknowledges risks.
- **Completion Definition** (§9) — clearcut acceptance criteria tied to code outcomes, not vague descriptions.

**Weaknesses:**
- Higher architectural complexity: 6 new files vs Design 1's 3. The `VirtualDisplayUiCoordinator` + `VirtualIslandManager` + `CompletionHandoffManager` + `VirtualDisplayFrameHub` are all new abstractions. Whether this is "over-engineering" or "proper separation" depends on team velocity — for a solo dev, this might be too many moving parts.
- `FramePacket` stores `jpeg: ByteArray` — re-encoding JPEG on every frame at 15fps is CPU-expensive and unnecessary for the viewer (which just needs a Bitmap). The agent's `captureScreenshot()` needs JPEG, but the viewer doesn't.
- The `VirtualDisplayUiCoordinator` receives events, computes state, and drives two renderers — this risks becoming a god-object if not carefully scoped. The state model has 9 fields already.
- Handoff uses `am start -n <component> --display 0 -W` (shell) as primary, with `ShizukuClient.launchOnDisplay` as fallback. But the primary path already goes through Shizuku (shell execution), so these aren't truly independent fallback paths.

**Score: 8.5/10** — Best engineering rigor. Best keyboard fix. Best testability. Slightly over-architected for the problem size, but every abstraction is justified.

---

### System Design 3 — "Show Me the Code"

**Strengths:**
- **`VirtualDisplayManager` singleton** as a shared bridge between the agent (Platform) and the UI (Activity/Overlay) is a sound architectural choice. It names the problem clearly: "The VD Platform is an Agent component; the UI is a User component; they need a bridge."
- "Crash Fast" philosophy (§6) — if VD creation fails, throw, don't retry silently. This is correct for a pre-release product.
- "No Interfaces for Mockability" and "No BaseOverlayManager" — these anti-over-engineering stances are refreshing and aligned with the KISS mandate.
- Code Guidelines (§6) are compact and actionable. Good engineering culture signals.

**Weaknesses:**
- **15fps continuous pump** is wasteful. The agent captures screenshots on-demand (once per turn, roughly every 2-5 seconds). Running a continuous 15fps bitmap conversion loop burns CPU and battery even when nobody is watching. Design 1's on-demand cached bitmap and Design 2's single-consumer StateFlow are both more efficient.
- The continuous pump also **breaks the existing screenshot pipeline**: "captureScreen() simply reads screenFlow.value. Instant. Non-blocking." — but this means the agent's screenshot may be stale by up to 67ms (1/15fps). If the agent just performed an action and immediately captures, it might get a pre-action frame. The current on-demand `acquireLatestImage()` is actually more correct for the agent's needs.
- `MiniIslandService` — the name implies it's a `Service`, but the description suggests it's just a manager for a floating view. Naming confusion.
- "Delete Old Code. The old AccessibilityPlatform logic that tried to handle overlays? Gone." — this contradicts the qi_note requirement that a11y mode must have **no regression**. You can't delete the old overlay logic; it's still needed for `PlatformMode.ACCESSIBILITY`.
- The `handOverTaskToUser()` code uses `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` — this flag clears the task's activity stack to the root activity, potentially losing the agent's navigation state. For example, if the agent navigated YouTube → Search → Play, `RESET_TASK_IF_NEEDED` would reset to YouTube's main page. This defeats the purpose of handoff.
- `singleTop` launch mode for the viewer — if the viewer is already in the back stack when the user taps the island, `onNewIntent` will be called. But Design 3 doesn't implement `onNewIntent`, so the viewer might show stale state.
- No testing strategy mentioned at all.

**Score: 5.5/10** — Good high-level instincts (KISS, crash fast, no over-abstraction), but multiple technical errors that would cause real bugs if implemented as-is. The continuous pump, the a11y regression risk, and the handoff flag are all implementation landmines.

---

## Part 3: Cross-Design Comparative Summary

| Dimension | Design 1 | Design 2 | Design 3 |
|---|---|---|---|
| **UI: Pill informativeness** | Status text + dot | App icon + name + status + dot | "Agent working..." + dot |
| **UI: Emergency stop** | ❌ Tap → Viewer → Stop | ✅ Long press on pill | ✅ Long press on pill |
| **UI: Handoff nuance** | Simple relaunch | Viewer-open vs viewer-closed flows | "Magic Move" (poetic, incorrect) |
| **UI: Visual spec completeness** | ✅ Full (colors, sizes, timings) | Partial (structure only) | ❌ Minimal |
| **System: Keyboard fix** | Shell dismiss (band-aid) | `TextInputPolicy` enum (root cause) | ACTION_SET_TEXT only (principled but incomplete) |
| **System: Frame pipeline** | Bitmap cache @ 5fps poll | `FrameHub` + StateFlow | 15fps continuous pump |
| **System: Overlay mode switch** | `when(platformMode)` in each method | `VirtualDisplayUiCoordinator` | Strip/delegate to `MiniIslandService` |
| **System: New file count** | 3 new | 6 new | 3 new |
| **System: A11y regression safety** | Implicit (when-branches) | ✅ Explicit regression tests | ❌ "Delete old code" |
| **System: Test strategy** | ❌ None | ✅ Unit + integration + regression | ❌ None |
| **System: Risk analysis** | ❌ None | ✅ 3 risks with mitigations | ❌ None |

---

## Part 4: Recommendations

### Best UI Design: **UI Design 1**, enhanced with Design 2's additions
- Take Design 1's clarity and visual spec as the base.
- Add Design 2's **app icon + name in the pill** (fulfills qi_note requirement).
- Add Design 2's **long-press for quick controls** (emergency stop).
- Add Design 2's **viewer-open vs viewer-closed completion flows**.

### Best System Design: **System Design 2**, simplified with Design 1's pragmatism
- Take Design 2's `VirtualDisplayUiCoordinator` + `TextInputPolicy` as the architectural core.
- Replace `VirtualDisplayFrameHub` with Design 1's **bitmap cache approach** — simpler, sufficient for monitoring, avoids the JPEG re-encoding overhead.
- Keep Design 2's testing strategy and risk analysis.
- Use Design 1's implementation order (standalone → wiring → manifest).

### Critical Fixes Needed Across All Designs
1. **Handoff**: None of the designs correctly handle Android's cross-display task reparenting. The realistic approach is a simple `startActivity` with `getLaunchIntentForPackage` — accepting that it's a relaunch, not a migration. Design 1 is the most honest about this.
2. **A11y regression**: Must be explicitly tested. Only Design 2 acknowledges this.
3. **Static service references**: All designs that use `AgentService.instance` for the viewer should migrate to a bound service or scoped state holder.
