# Mobile Action Redesign: Design Review

Reviewer: Claude (Opus)
Date: 2026-02-08

Reviewed documents:
- **Doc A**: `overall_redesign_codex.md` — "Mobile Action Overall Redesign (Codex)"
- **Doc B**: `mobile_action_architecture_v2.md` — "Mobile Action Architecture V2: Cross-Module Redesign"

Both documents propose a 3-layer redesign of the mobile_action code path. They share the same root diagnosis (职责散落、multi-selector 复杂度、UIAction 接口错位) and agree on key principles (single targeting, KISS, atomic platform). The interesting differences lie in **where they draw boundaries** and **how far they go**.

---

## Scoring (1–10, higher = better)

| Dimension | Doc A (Codex) | Doc B (V2) | Notes |
|-----------|:---:|:---:|-------|
| 1. Problem Diagnosis | 7 | 9 | |
| 2. Architecture Layering | 7 | 8 | |
| 3. Responsibility Placement | 6 | 8 | |
| 4. API / Interface Design | 7 | 8 | |
| 5. Fallback Strategy Design | 8 | 7 | |
| 6. Complexity Management (KISS) | 5 | 8 | |
| 7. Implementation Feasibility | 5 | 9 | |
| 8. Testability | 6 | 8 | |
| 9. Novel Insights | 8 | 6 | |
| 10. Overall Design Coherence | 6 | 8 | |
| **Weighted Total** | **~6.5** | **~7.9** | |

---

## Dimension-by-dimension Analysis

### 1. Problem Diagnosis (Doc A: 7, Doc B: 9)

Doc B opens with the "8-class click path" — a concrete call trace from LLM JSON all the way down to `performNodeClickAt`. This is the single most useful artifact in either document. It makes the problem visceral: you can count the indirections and feel the pain. The "19 files" inventory with per-directory counts gives a quantitative baseline.

Doc A lists 5 problems accurately but abstractly ("职责散落", "接口错位"). These are correct characterizations, but they read more like a summary of the user's own `qi_note.md` rather than independent diagnosis. Missing: no concrete call trace, no file count, no "how many lines does a click actually touch today?"

### 2. Architecture Layering (Doc A: 7, Doc B: 8)

Both propose 3 layers. The conceptual structures are:

```
Doc A:  Tool Contract  →  MobileAction Engine  →  Atomic Platform
Doc B:  Tool Definition →  Execution Glue      →  Platform Execution
```

Doc B is more honest about Layer 2. It admits the glue layer is ~40 lines and "could arguably be inlined" but justifies its existence with the ToolInvocation contract for approval UI / cancellation. This is good design reasoning — acknowledging the layer is thin but explaining why it must exist.

Doc A's Layer B ("MobileAction Engine") is much heavier. It contains attempt planning, attempt execution, UI outcome evaluation, and target resolution. This is where the two designs fundamentally diverge (see Responsibility Placement below). Doc A's engine layer is doing real work, not just bridging.

The problem: Doc A's "3 layers" is somewhat misleading. The engine layer is internally complex enough to be 2 sub-layers (planning + execution), and the file structure shows 6+ files in that one "layer". Real complexity is closer to 4 layers.

### 3. Responsibility Placement (Doc A: 6, Doc B: 8)

**This is the core design disagreement.** Where does fallback logic live?

- Doc A: Fallback in the Engine layer (tool side). Platform is purely atomic.
- Doc B: Fallback in Platform Executors. Tool just says "Click element 3".

Doc A's approach means the Engine must know about `NodeAction` vs `GestureTap` — these are platform-level concepts. The Engine is making decisions like "try node click first, then gesture tap", which requires knowledge of Android accessibility API semantics. This leaks platform concerns upward.

Doc B's approach keeps the tool layer purely intent-based: `UIAction.Click(Target.ElementIndex(3))`. The platform decides how to execute. This is a cleaner separation because:

1. The retry strategy (ACTION_CLICK → gesture tap) is inherently about **what the accessibility service can do**, not about what the LLM intended.
2. If the platform changes (e.g., new API level adds a third fallback method), only platform code changes. In Doc A, the engine's `AttemptPlanner` would also need updating.
3. Testing the fallback chain is easier when it's inside an executor with injected dependencies than when it's in an engine that calls through an `AtomicUiExecutor` interface.

Doc A's counter-argument is "把复杂度集中在可单测的 MobileActionEngine，platform 保持薄且稳定". The testability argument is valid in isolation, but the price is that the engine carries platform-level knowledge, violating the boundary it claims to enforce.

Score gap reflects: Doc B's placement is more principled even though Doc A's is defensible.

### 4. API / Interface Design (Doc A: 7, Doc B: 8)

**JSON schema**: Doc A proposes nested `target` with `kind` discriminator:
```json
{ "target": { "kind": "element", "element_index": 12 } }
```
Doc B keeps flat params:
```json
{ "element_index": 12 }
```

Doc A's nested approach is structurally safer (one-of is enforced by shape), but it changes the LLM interface. This is a non-trivial cost — the LLM has learned the flat format, and switching adds a prompt engineering burden. Doc A doesn't discuss this migration cost at all.

Doc B's flat approach is pragmatic. One-of enforcement in validation code (10 lines) achieves the same constraint without touching the LLM contract.

**Kotlin types**: Doc A introduces `MobileActionRequest` as an intermediate type between JSON and platform execution. This means the system has three type representations: JSON → `MobileActionRequest` → `AtomicUiAction`. Doc B goes JSON → `UIAction` (two representations). The extra intermediate type in Doc A doesn't carry enough unique semantics to justify its existence — `MobileActionRequest.Click(target)` and `UIAction.Click(target)` are almost isomorphic.

**Platform interface**: Doc A's `AtomicUiExecutor` with `AtomicUiAction`, `NodeSelector`, `NodeOp` is a well-typed abstraction, but it's a lot of new surface area. Doc B keeps `AndroidPlatform.performAction(UIAction)` unchanged — same signature, new semantics. Less disruption.

### 5. Fallback Strategy Design (Doc A: 8, Doc B: 7)

Doc A wins here. Its fallback tables (§5.2–5.5) are the clearest specification of what happens for each action × target combination. The explicit attempt ordering:

```
element/text → resolve →
  Attempt 1: NodeAction(locator, CLICK)
  Attempt 2: NodeAction(ByPoint(center), CLICK)
  Attempt 3: GestureTap(center)
```

...is a complete decision table. You could implement directly from it. The rule "不在 target 类型之间切换" (no cross-target-type fallback) is an important constraint stated explicitly.

Doc B describes the same logic but embedded in executor code examples. The information is there, but you have to read Kotlin to extract it. For a design document, the table format is better.

Doc A also has a 3-attempt sequence for element clicks (node-by-locator → node-by-point → gesture), while Doc B only has 2 (ACTION_CLICK → gesture tap). Doc A's middle step (find node at coordinates) captures a real scenario: the element's locator might be stale but a node still exists at those coordinates.

### 6. Complexity Management / KISS (Doc A: 5, Doc B: 8)

Doc A claims KISS repeatedly but introduces: `MobileActionRequest`, `TargetSpec`, `SwipeSpec`, `AtomicUiAction`, `AtomicUiExecutor`, `AtomicUiResult`, `NodeSelector`, `NodeOp`, `NodeLocator`, `NodeFingerprint`, `MobileActionValidator`, `AttemptPlanner`, `AttemptRunner`, `UiOutcomeEvaluator`, `TargetResolver`, `MobileActionInvocation`, `MobileActionExecutor`... The file structure shows 9+ new files across 3 sub-packages.

Doc B introduces: `UIAction.Target`, `ClickExecutor`, `LongPressExecutor`, `TypeExecutor`, `SwipeExecutor`, `UiChangeDetector`, `MobileActionInvocation` — 6 new files, flat structure under `platform/action/`.

The type counts tell the story. Doc A has roughly 2× the new type surface area of Doc B for equivalent capability. Several of Doc A's types are thin wrappers that don't carry independent semantics (e.g., `TargetSpec` is isomorphic to `UIAction.Target`).

A red flag: Doc A's file structure has `contract/`, `validation/`, `engine/`, `targeting/` sub-packages. For a system where the engine is ~300–400 lines total, this is premature structuring. Compare: Doc B's ClickExecutor is ~60 lines. The whole execution layer might be 400 lines. That doesn't need 4 sub-packages.

### 7. Implementation Feasibility (Doc A: 5, Doc B: 9)

Doc B is dramatically more implementable. It provides:
- Full Kotlin code for `ClickExecutor` (~60 lines, compilable)
- Full Kotlin code for `MobileActionTool` (validation + invocation creation)
- Full Kotlin code for `MobileActionInvocation` (~40 lines)
- Full Kotlin code for `UiChangeDetector`
- `AccessibilityPlatform.performAction()` rewrite
- 25-step phased migration plan with verification gates
- File-level inventory: 13 to delete, 5 to modify, 6 to create, with line estimates

Doc A provides:
- Type definitions (sealed interfaces, data classes)
- Fallback tables
- A 6-step implementation sequence
- File structure diagram

You could hand Doc B to a developer and get a working implementation within a day. Doc A requires significant design-to-code translation.

### 8. Testability (Doc A: 6, Doc B: 8)

Doc B's testing section is comprehensive: per-component mock boundaries, 7 specific test cases, analysis of which existing tests to delete/rewrite, and the observation that executors are testable with mocked service/gesture dispatcher ("much simpler setup: no ToolExecutionContext, no JSONObject param construction").

Doc A lists test categories (one-of validation, fallback order, ui-change contract, attempt trail readability) but doesn't specify mock boundaries or migration impact on existing tests.

More importantly, Doc B's design is inherently more testable because the executor pattern has clean constructor injection:
```kotlin
class ClickExecutor(
    private val service: AccessibilityService,
    private val gestureDispatcher: GestureDispatcher,
    private val uiChangeDetector: UiChangeDetector,
    ...
)
```

Doc A's `AtomicUiExecutor` interface achieves something similar, but the engine layer that calls it also needs its own test infrastructure, adding test complexity.

### 9. Novel Insights (Doc A: 8, Doc B: 6)

Doc A's strongest contribution is the `NodeLocator` concept (§7):

```kotlin
data class NodeLocator(
    val windowId: Int?,
    val pathFromRoot: List<Int>,
    val fingerprint: NodeFingerprint
)
```

This is genuinely important. Currently, element_index immediately degrades to coordinates at execution time. With NodeLocator, the system can:
1. Re-walk the fresh accessibility tree via `pathFromRoot`
2. Verify identity via `fingerprint` (class/resourceId/text/bounds)
3. Only fall back to coordinates when the node is truly gone

This makes element targeting semantically correct rather than "element → coordinates → hope for the best". Doc B misses this entirely — its `resolveTarget` just extracts center coordinates from the snapshot.

Doc A's two-layer success model (`dispatchResult` + `interactionResult`) and explicit `unverifiable_success` concept are also more nuanced than Doc B's binary Success/Failure.

### 10. Overall Design Coherence (Doc A: 6, Doc B: 8)

Doc B tells a single consistent story: simplify by pushing complexity down, keep the tool layer thin, make executors the unit of understanding ("time to understand 'how does click work?' → read 1 file"). Every design choice reinforces this narrative.

Doc A has internal tensions. It claims KISS but introduces significant type complexity. It claims "唯一智能层" (single smart layer) but that layer has 6+ files across 4 sub-packages. It claims clean boundaries but the engine knows about `NodeAction` vs `GestureTap`. The NodeLocator section, while insightful, is somewhat orthogonal to the rest of the redesign and could be introduced independently.

---

## Cross-cutting Observations

### What Doc A does better
1. **NodeLocator** — The standout idea. Should be adopted regardless of which architecture wins.
2. **Fallback tables** — Clearer specification format. Worth incorporating into Doc B.
3. **Two-layer success model** — dispatchResult + interactionResult is more precise.
4. **"不在 target 类型之间切换"** — This constraint is stated explicitly and prominently.

### What Doc B does better
1. **Responsibility placement** — Fallback in platform is the right call.
2. **Simplicity in practice** — Fewer types, flatter structure, less indirection.
3. **Implementation readiness** — Compilable code, phased migration, file inventory.
4. **Honest self-assessment** — "~40 lines, could arguably be inlined" shows good judgment.
5. **Backward compatibility** — Keeps flat JSON params, reducing LLM migration cost.

### What both miss
1. **Cancellation during fallback** — Neither document clearly specifies what happens if cancellation is requested between attempt 1 and attempt 2 of a fallback chain. Doc B's executor loop doesn't check cancellation between attempts.
2. **Concurrent action safety** — What prevents two actions from overlapping if the engine/executor is still in a fallback chain? Neither document addresses thread/coroutine safety.
3. **Observability / logging** — Both mention attempt trails in output, but neither designs structured logging for debugging fallback decisions in production.
4. **Snapshot cost model** — Doc B's open question #4 touches this, but neither quantifies the performance impact of multiple `captureScreen()` calls per action.

---

## Recommendation

**Use Doc B as the implementation blueprint, cherry-pick Doc A's key insights:**

1. Adopt Doc B's architecture (fallback in platform executors, thin tool + glue layers).
2. Adopt Doc A's `NodeLocator` concept — add it as Phase 0 or integrate into Phase 1.
3. Adopt Doc A's fallback tables as the specification — put them in executor KDoc or a companion doc.
4. Adopt Doc A's two-layer success model (`dispatchResult` + `interactionResult`) inside executors — the external `ActionResult` can stay simple, but internal executor logic should distinguish API success from UI change.
5. Keep flat JSON params (Doc B). The nested `target.kind` format is cleaner in theory but not worth the LLM interface migration cost.
