# Stage4 Design Review (Codex)

## Scope
Reviewed files:
- `doc/todo/0.01_virtual_display/stage4/ui_design_1.md`
- `doc/todo/0.01_virtual_display/stage4/ui_design_2.md`
- `doc/todo/0.01_virtual_display/stage4/ui_design_3.md`
- `doc/todo/0.01_virtual_display/stage4/system_design_1.md`
- `doc/todo/0.01_virtual_display/stage4/system_design_2.md`
- `doc/todo/0.01_virtual_display/stage4/system_design_3.md`

Baseline requirements are taken from `doc/todo/0.01_virtual_display/stage4/qi_note.md`.

Evaluation focus: design quality (not writing style), with emphasis on:
1. Requirement coverage
2. Implementability on current codebase
3. KISS/readability
4. Regression risk (especially ACCESSIBILITY mode)
5. Testability / operability

---

## Critical Findings (Must Fix)

1. `system_design_3.md` has explicit regression risk against your hard requirement.
- Problem: it proposes removing old accessibility overlay logic (“Delete Old Code… old AccessibilityPlatform logic… Gone”, lines 129-132).
- Why critical: your requirement explicitly says a11y mode should remain basically unchanged with no regression (`qi_note.md`).
- Code alignment: current a11y overlay flow is deeply integrated via `AgentService` -> `ServiceOverlayController` (`app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:239-296`, `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:108-202`).

2. `system_design_3.md` handoff assumption is overly optimistic and may break final UX promise.
- Problem: it assumes launching on default display will “likely move the entire task stack” (line 83).
- Why critical: handoff is a core product moment. A “likely” behavior is not acceptable as primary mechanism.
- Better direction: keep best-effort with explicit fallback path (as in `system_design_2.md:197-200`).

---

## High Findings (Should Fix)

1. `system_design_1.md` has internal inconsistency in frame pipeline design.
- Problem: section 3 proposes real-time `ImageReader` relay (lines 170-203), then否定并切换到 polling cache (lines 224-262), while file header still frames it as one concrete component.
- Impact: implementation ambiguity; teams can build different versions and diverge.

2. `system_design_1.md` IME fix is mostly reactive suppression, not root-cause control.
- Problem: proposes shell keyevent dismissal after text set (lines 410-427).
- Impact: may flicker and is ROM-dependent; does not prevent trigger path.
- Current code root cause is clear: `TypeExecutor` always has tap-to-focus fallback (`app/src/main/kotlin/com/moonkey/androidagent/tool/action/TypeExecutor.kt:71-84`).

3. `system_design_3.md` introduces heavy always-on frame pump (15fps) without lifecycle throttling policy.
- Problem: continuous bitmap conversion loop (lines 25-30).
- Impact: unnecessary CPU/memory pressure when user is not watching; violates practical KISS for mobile constraints.

4. `system_design_3.md` asks for broad architectural move to singleton manager without migration detail.
- Problem: `VirtualDisplayManager` absorbs display, image, and activity launch concerns (lines 14-20).
- Impact: coupling and lifecycle complexity increase; current architecture already has `PlatformFactory` + `VirtualDisplayPlatform` separation (`app/src/main/kotlin/com/moonkey/androidagent/platform/PlatformFactory.kt:37-83`).

5. `ui_design_3.md` depends on “teleportation/magic move” framing without concrete fallback UX.
- Problem: strong narrative, weak failure-mode design for handoff.
- Impact: when app relaunch fails, user expectation gap is large.

---

## Medium Findings (Consider)

1. `system_design_2.md` is the most complete, but introduces more components than strictly minimal.
- New coordinator + frame hub + handoff manager is clean, but should avoid over-abstracting phase-1 implementation.
- Suggested tightening: deliver in two increments (overlay/IME first, viewer/handoff second).

2. `system_design_2.md` `NODE_ONLY` text policy is correct direction, but may reduce success in edge apps.
- It should define fallback prompt strategy when `SetTextOnNodeAt` fails repeatedly (already hinted at line 180, needs explicit policy).

3. `ui_design_1.md` and `ui_design_2.md` are both solid; `ui_design_1.md` has better visual specificity, `ui_design_2.md` has better acceptance criteria.
- Ideal merge should combine both strengths.

---

## Comparative Assessment

### UI designs
1. **Best: `ui_design_2.md`**
- Strongest on product completeness: explicit interaction model, handoff behavior, and measurable acceptance criteria (`<=300ms`, `>99%`).
- Most implementation-ready for QA.

2. **Second: `ui_design_1.md`**
- Very clear 3-state model and good “only one overlay on real screen” rule.
- Slightly weaker than #2 on measurable acceptance and operational fallback detail.

3. **Third: `ui_design_3.md`**
- Great concept framing, but comparatively weaker on deterministic behavior specs and failure handling.

### System designs
1. **Best: `system_design_2.md`**
- Best alignment with existing code and bug roots.
- Correctly targets mode split, IME root cause (`TypeExecutor` policy), and adds test/DoD closure.

2. **Second: `system_design_1.md`**
- Practical and relatively small diff, but frame-pipeline inconsistency and IME strategy are weaker.

3. **Third: `system_design_3.md`**
- Contains high-risk assumptions and explicit a11y regression risk.

---

## Recommended Direction

Use a hybrid:
1. **UI base**: `ui_design_2.md`
2. **System base**: `system_design_2.md`
3. **Borrow from `ui_design_1.md`**: status-island simplicity and “real screen has only one overlay” hard rule

And enforce these implementation constraints:
1. ACCESSIBILITY mode path remains unchanged by default (no regression).
2. IME fix is policy-first (`NODE_ONLY` in VD) rather than hide-keyboard-first.
3. Handoff is best-effort with explicit fallback UX (never rely on implicit task migration).
4. Frame pipeline must have viewer visibility gating (no unnecessary continuous work).

---

## Final Verdict

- `方案2` (UI2 + System2) is the strongest primary candidate.
- `方案1` is a valid backup if you want minimum diff quickly, but must resolve frame/IME weaknesses first.
- `方案3` should not be used as primary implementation blueprint in current form.
