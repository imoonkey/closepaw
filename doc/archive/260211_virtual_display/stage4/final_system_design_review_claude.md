# Final System Design — Comparative Review

Reviewer: Claude (Antigravity)
Date: 2026-02-11

---

## Overview

This review compares two "final" system designs for Virtual Display Phase 4:

- **Codex Design** (`final_system_design_codex.md`) — 252 lines, 10 sections, direct and terse
- **Claude Design** (`final_system_design_claude.md`) — 698 lines, 16 sections, detailed with code samples

Both designs were produced after synthesizing three earlier system designs, three design reviews, and the `qi_note.md` requirements. Both independently converge on the **Hybrid Model** (ImageReader for background + SurfaceView for live preview via `setSurface()` switching). This convergence is the strongest possible validation of the core architectural decision.

The review focuses on **design quality** — architectural soundness, bug fix rigor, completeness, risk management, and implementability — not writing style.

---

## 1. Core Architecture: Hybrid Model

Both designs agree on the fundamental mechanism:

| Aspect | Codex | Claude |
|---|---|---|
| Background capture | `ImageReader.acquireLatestImage()` | Same |
| Live preview | `setSurface()` to Viewer's `SurfaceView` | Same |
| Screenshot in live mode | `PixelCopy.request(viewerSurface, ...)` | Same |
| API path | Shizuku → `IDisplayManager.setVirtualDisplaySurface()` | Same |

**Verdict: Tie.** The core mechanism is identical. Both correctly identify that this eliminates the need for `FrameRelay`, `FrameHub`, or bitmap polling from earlier designs.

### Score: Codex 9/10, Claude 9/10

---

## 2. Overlay Leak Fix

| Aspect | Codex | Claude |
|---|---|---|
| Root cause identified? | ✅ `ServiceOverlayController` doesn't distinguish platform mode | ✅ Same diagnosis |
| Solution | New `VirtualDisplayUiController` (separate controller) | `when(platformMode)` branching in existing `ServiceOverlayController` |
| New abstractions | `VirtualDisplayUiController` + `VirtualDisplayUiStore` + `StatusIslandManager` | `StatusIslandManager` only; mode check added to existing controller |
| A11y regression | Implicit — separate controller means old path untouched | Explicit — `PlatformMode.ACCESSIBILITY` branches are "EXISTING CODE, UNTOUCHED" |

**Analysis:**

Codex introduces a **separate controller** (`VirtualDisplayUiController`) for VD mode. This is cleaner separation of concerns: the VD UI logic never touches `ServiceOverlayController`. But it also means:
- Two controllers with partially overlapping responsibilities (both react to agent events)
- `AgentService` must choose which controller to delegate to — the selection logic itself becomes a new concern
- The `VirtualDisplayUiStore` is an additional abstraction (event bus / shared state) that adds another layer

Claude keeps **one controller** with `when(platformMode)` branches. This is more verbose per-method but has a critical advantage: **local readability**. Every method's behavior for both modes is visible in one place, no file-hopping needed. And the a11y code is literally the identical lines that run today, just wrapped in a `when` branch.

Claude also explicitly addresses the "why not a Coordinator?" question (§5, final paragraph), showing awareness of the Design 2 alternative and consciously rejecting it with stated reasoning.

**Verdict: Claude is slightly better.** The flat branching approach is more pragmatic for 2 modes. Codex's extra abstractions (`VirtualDisplayUiStore` as event bus) add complexity without proportional benefit at this stage. However, Codex's approach would scale better if a third mode were ever added (unlikely per non-goals).

### Score: Codex 7.5/10, Claude 8.5/10

---

## 3. Ghost Keyboard Fix

| Aspect | Codex | Claude |
|---|---|---|
| Root cause | ✅ `TypeExecutor` Attempt 2: `TapAt → SetTextOnFocused` triggers IME on display 0 | ✅ Same |
| Solution | `TextInputPolicy` enum (`NODE_ONLY` / `TAP_TO_FOCUS_ALLOWED`) on platform | `allowTapToFocus(): Boolean` method on `AndroidPlatform` interface |
| Executor change | Skip `TapAt → SetTextOnFocused` fallback under `NODE_ONLY` | Skip same fallback when `allowTapToFocus()` returns `false` |
| Defense-in-depth | ❌ None | ✅ `dismissMainDisplayKeyboard()` after text actions in VD mode |
| Error reporting | ✅ "Lost failure就返回明确原因给模型重选节点" | ✅ Returns `ActionOutcome.Failed` with explicit reason |

**Analysis:**

Both designs fix the root cause identically — prevent `TapAt → SetTextOnFocused` in VD mode. The difference is in **API shape** and **defense-in-depth**.

Codex uses an enum (`TextInputPolicy`), which is more extensible if you ever need more than two policies. Claude uses a boolean method, which is simpler and sufficient for the current binary choice.

The critical differentiator: Claude adds a **safety net** (`dismissMainDisplayKeyboard()`) that proactively dismisses the IME on display 0 after any text action. This is defense-in-depth thinking — even if `allowTapToFocus()` prevents the primary cause, some apps auto-focus fields on window attach, which could still trigger the IME. Codex's design has no such fallback.

Codex's statement "这才是根因修复，不是靠 shell keyevent 擦屁股" dismisses the defense-in-depth pattern. While philosophically appealing, Android IME behavior is notoriously inconsistent across ROMs. The root cause fix alone may not cover all edge cases.

**Verdict: Claude is better.** Root cause fix (+) defense-in-depth safety net is the right engineering approach for Android's fragmented IME landscape.

### Score: Codex 7.5/10, Claude 9/10

---

## 4. Completion Handoff

| Aspect | Codex | Claude |
|---|---|---|
| Trigger | `TaskCompleted.reason == GOAL_ACHIEVED` | `SessionCompleted` with `CompletionReason.GOAL_ACHIEVED` |
| Implementation | `CompletionHandoffManager` (new class) + `lastActivePackage` → start on display 0 | Inline in `handleEvent()` — `getLaunchIntentForPackage()` → `startActivity()` |
| Event contract | New `CompletionReason` enum, mapped from `AgentStopReason` | Assumes `CompletionReason` already exists (or will exist) |
| Cross-display migration | Not attempted — just relaunch | ✅ Explicitly explains why task reparenting doesn't work on Android |

**Analysis:**

Codex introduces a **new `CompletionHandoffManager`** class and a **new `CompletionReason` enum** with mapping from `AgentStopReason`. This is more architectural investment:
- The `CompletionHandoffManager` is a separate file for what amounts to ~5-10 lines of logic
- The `AgentStopReason → CompletionReason` mapping adds a data transformation layer
- But the event contract cleanup (`TaskCompleted` gaining a `reason` field) is genuinely needed — this is a real gap in the current protocol

Claude does the handoff inline in `handleEvent()`, arguing "5 lines of code doesn't need a manager." This is correct for the current scope. Claude also provides the **most honest technical explanation** of why cross-display task reparenting doesn't work:
- `setLaunchDisplayId(DEFAULT_DISPLAY)` creates a new task, doesn't reparent
- `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` clears the back stack
- Simple relaunch works because most apps restore state via Android's built-in activity restoration

This Android platform knowledge is a significant strength — it prevents implementers from wasting time on "correct" approaches that simply don't work.

**Verdict: Mixed.** Codex is better on event contract design (exposing `CompletionReason` is genuinely needed). Claude is better on implementation pragmatism and Android platform knowledge. The `CompletionHandoffManager` class is over-engineering.

### Score: Codex 7/10, Claude 8.5/10

---

## 5. PixelCopy Failure Handling

| Aspect | Codex | Claude |
|---|---|---|
| Strategy | Consecutive failure count → force fallback to ImageReader after N fails (suggest 2) | `captureFromImageReaderFallback()` — switch to ImageReader, capture, stay on ImageReader |
| Recovery | Implied: UI reports degraded state | Graceful: falls back permanently on PixelCopy failure |
| Viewer UX on failure | Reports to UI to show warning | Not addressed — viewer stays open but gets no frames |

**Analysis:**

Codex's approach is slightly better here — it introduces a failure count threshold before switching, which tolerates transient failures. It also mentions reporting the degraded state to the UI layer.

Claude's fallback is simpler (just switch back to ImageReader on first failure) but doesn't consider what happens to the Viewer. If we switch to ImageReader while the Viewer is open, the Viewer's SurfaceView goes black — yet the Viewer is still displayed. Neither design fully addresses this scenario.

**Verdict: Codex is slightly better** on failure thresholds. Both miss the Viewer UX degradation scenario.

### Score: Codex 7/10, Claude 6/10

---

## 6. Scope & File Plan

| Aspect | Codex | Claude |
|---|---|---|
| New files | 7 new files listed | 2 new files + 6 modifications |
| Estimated LOC | Not quantified | ~355 lines total |
| Rollout order | 5 phases, each independently verifiable | 9 steps, dependency-aware |
| Non-goals | ✅ Explicit list (no PiP, no takeover, no recording, no cross-display task migration) | ✅ Explicit list (no coordinator, no frame relay, no CompletionHandoffManager, no PiP, no multi-touch) |

**Analysis:**

Codex's file plan lists **7 new files**, including `VirtualDisplayUiStore`, `CompletionHandoffManager`, and `TextInputPolicy` — significantly more architectural surface area. Claude's plan is **2 new files, 6 modifications, ~355 lines**. This is a major difference in implementation scope.

Codex's rollout order (5 phases) is more compact but less granular. Claude's (9 steps) is more detailed and each step has clearer test criteria.

Claude's explicit line count estimates per file (e.g., `StatusIslandManager ~130 lines`, `ViewerActivity ~100 lines`) demonstrate implementation familiarity and make the plan actionable for sprint planning.

**Verdict: Claude is significantly better** on scope control. Fewer new files with more precise estimates make for a lower-risk implementation plan.

### Score: Codex 6.5/10, Claude 9/10

---

## 7. API Feasibility & Risk

| Aspect | Codex | Claude |
|---|---|---|
| API verification | ✅ Lists 4 official API references (setSurface, PixelCopy, AOSP VirtualDisplay.java, IDisplayManager.aidl) | ✅ Explains the reflection path through Shizuku |
| ROM compatibility risk | Not mentioned | ✅ Risk table: "fails on some ROMs" → fallback to bitmap polling |
| Shizuku callback token | Mentioned ("只要保留创建时 token/callback") but no fallback | ✅ Risk table: "Store callback from createVirtualDisplay, pass to setSurface" |
| Risk matrix | ❌ None | ✅ 4 risks with impact + mitigation |

**Analysis:**

Codex's §1 is stronger on **proving API existence** — it lists 4 hyperlinked official references. This is valuable for an implementer who needs to verify the APIs before starting.

But Claude provides a formal **risk matrix** (§14) with 4 risks, each paired with impact assessment and mitigation strategy. This is production-grade engineering practice. The Shizuku callback token risk is particularly well-handled — Claude identifies the potential issue AND provides the specific mitigation (`store the callback from createVirtualDisplay`).

**Verdict: Claude is better overall.** Codex's API references are a nice touch, but Claude's risk matrix + mitigations provide more implementation safety.

### Score: Codex 7/10, Claude 8.5/10

---

## 8. Future Compatibility (Interactive Mode)

| Aspect | Codex | Claude |
|---|---|---|
| Addressed? | ❌ Listed as non-goal, no discussion | ✅ Dedicated section (§13) with 5-step path to user takeover |

**Analysis:**

The `qi_note.md` explicitly states: "在未来这个virtual display在user看它的时候, 它也可以选择接管...我希望我现在的设计和实现是兼容我以后的这个想法的。"

Claude directly addresses this requirement with a concrete 5-step explanation of how the Hybrid Model naturally supports user takeover (touch on SurfaceView → inject via ShizukuClient → pause agent). This demonstrates the design was made with the requirement in mind.

Codex lists "不做 user takeover（触摸接管）" as a non-goal. While correct that it shouldn't be implemented now, Codex fails to show that the current design is *compatible with* future implementation — which is the actual requirement.

**Verdict: Claude is significantly better.** Addressing compatibility without implementing is exactly what the qi_note asked for.

### Score: Codex 4/10, Claude 9/10

---

## 9. Code Specificity

| Aspect | Codex | Claude |
|---|---|---|
| Code samples | Pseudocode / API sketches (3 snippets) | Near-implementation Kotlin (10+ snippets) |
| StatusIsland spec | Class name + role description | Full class API + layout spec + WindowManager params |
| ViewerActivity spec | Mentioned in flow | Full Activity + Composable implementation (~70 lines) |
| ShizukuClient change | "新增能力" description | Actual method with reflection code |
| TypeExecutor change | Enum + policy description | Exact insertion point in existing code flow |

**Analysis:**

Claude provides code at a level where an implementer could **almost copy-paste** into the codebase. The `ShizukuClient.setVirtualDisplaySurface()` method includes the reflection API call. The `VirtualDisplayViewerScreen` composable is essentially complete. The `captureFromPixelCopy()` suspend function handles coroutine context, bitmap lifecycle, and failure paths.

Codex's descriptions are correct but require the implementer to translate prose into code. The `CaptureRoute` enum and switch strategy are well-described conceptually but lack the implementation detail that reduces ambiguity.

**Verdict: Claude is clearly better.** More code specificity directly reduces implementation risk and ambiguity.

### Score: Codex 6/10, Claude 9/10

---

## 10. Testing & Verification

| Aspect | Codex | Claude |
|---|---|---|
| Unit tests | ✅ 3 specific test targets | ❌ No unit tests mentioned |
| Integration tests | ✅ 5 integration scenarios | ✅ 8 manual test scenarios |
| Regression tests | ✅ "A11y 模式 overlay 行为与当前一致" | ✅ "Run same task in A11y mode → verify existing overlay behavior unchanged" |
| DoD | ✅ 5 concrete criteria | ❌ No formal DoD |
| Edge cases | ❌ None | ✅ 4 edge cases (viewer open at completion, Shizuku dies, rapid tap, rotation) |
| Build verification | ❌ Not mentioned | ✅ `./gradlew clean assembleDebug lint` |

**Analysis:**

This is one area where Codex is stronger. Codex defines a clear **Definition of Done** (5 criteria) and names **specific unit test targets** (`TypeExecutor`, `VirtualDisplayUiController`, `AgentSession`). Unit test targets are particularly valuable — they force the design to be testable.

Claude has more **manual test scenarios** (8 vs 5) and adds **edge cases** that Codex doesn't consider (Shizuku death during viewer, rapid tap), plus a build command. But the lack of unit test targets is a gap.

**Verdict: Mixed.** Codex is better on formal DoD and unit test specification. Claude is better on edge case coverage and build verification. Overall roughly even.

### Score: Codex 8/10, Claude 7.5/10

---

## Overall Comparative Summary

| Dimension | Codex | Claude | Winner |
|---|---|---|---|
| **Core architecture** | 9 | 9 | Tie |
| **Overlay leak fix** | 7.5 | 8.5 | Claude |
| **Keyboard fix** | 7.5 | 9 | Claude |
| **Completion handoff** | 7 | 8.5 | Claude |
| **PixelCopy failure** | 7 | 6 | Codex |
| **Scope & file plan** | 6.5 | 9 | Claude |
| **API feasibility & risk** | 7 | 8.5 | Claude |
| **Future compatibility** | 4 | 9 | Claude |
| **Code specificity** | 6 | 9 | Claude |
| **Testing & verification** | 8 | 7.5 | Codex |
| **Weighted Average** | **~7.0** | **~8.5** | **Claude** |

---

## Final Assessment

### Codex Design: 7.0/10

**Strengths:**
- Correct on the core Hybrid Model — independently arrives at the same conclusion
- Event contract cleanup (`TaskCompleted.reason`) is a genuine improvement not fully addressed by Claude
- Formal DoD and unit test targets show testing discipline
- Concise — 252 lines covers the essential decisions

**Weaknesses:**
- Over-architects the overlay fix (3 new abstractions where mode branching suffices)
- No defense-in-depth for keyboard fix
- Dismisses the future interactive mode requirement instead of addressing compatibility
- Lacks code specificity — descriptions require significant translation to implementation
- No risk matrix — doesn't consider ROM fragmentation or API failure scenarios

### Claude Design: 8.5/10

**Strengths:**
- Near-implementation code quality — low ambiguity for implementers
- Defense-in-depth keyboard fix with root cause + safety net
- Smallest file footprint (2 new files) with clear line estimates
- Risk matrix with mitigations — production engineering mindset
- Explicitly addresses future interactive mode compatibility per qi_note requirement
- Provides detailed Android platform knowledge (why task reparenting doesn't work, why SurfaceView over TextureView, etc.)

**Weaknesses:**
- PixelCopy failure handling doesn't consider Viewer UX degradation
- No unit test targets specified
- No formal Definition of Done
- `AgentService.instance?.` static reference pattern for viewer communication (same anti-pattern flagged in prior reviews)

### Recommendation

**Use Claude's design as the implementation base**, supplemented with:

1. **From Codex**: Add `CompletionReason` enum and `TaskCompleted.reason` event contract cleanup
2. **From Codex**: Add the 3 unit test targets to Claude's verification plan
3. **From Codex**: Add a formal 5-point DoD
4. **Fix in both**: Replace `AgentService.instance` pattern with a scoped state holder or bound service for viewer communication
5. **Fix in Claude**: Handle PixelCopy failure with a retry threshold (Codex's approach) before permanent fallback, and address Viewer UX when degraded to ImageReader mode
