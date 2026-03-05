# Swipe Redesign - Design Review

## Reviewer: Claude
## Status: APPROVED with notes

Overall assessment: the design is sound, well-prioritized, and properly grounded in eval
data. The P0/P1/P2 layering is reasonable. Below are specific findings grouped by severity.

---

## 1. Issues to Address Before Implementation

### 1.1 ACTION_SCROLL direction mapping needs explicit specification

Section 3.1 says "try `ACTION_SCROLL_FORWARD/BACKWARD` (or `ACTION_SCROLL_UP/DOWN/LEFT/RIGHT`
API 23+)" but doesn't specify the exact mapping table.

The mapping from `redesign_claude.md` is:
```
"up"    -> ACTION_SCROLL_FORWARD     (API 16+)
"down"  -> ACTION_SCROLL_BACKWARD    (API 16+)
"left"  -> ACTION_SCROLL_RIGHT.id    (API 23+)
"right" -> ACTION_SCROLL_LEFT.id     (API 23+)
```

This has a **cross-orientation edge case**: `ACTION_SCROLL_FORWARD` is orientation-dependent.
On a vertical container, FORWARD = scroll down (correct for direction="up"). On a horizontal
container in LTR, FORWARD = scroll right. So if the agent mistakenly says direction="up"
targeting a horizontal RecyclerView, FORWARD produces a horizontal scroll, not vertical.

**Recommendation**: Prefer the directional API 23+ actions (`ACTION_SCROLL_DOWN` /
`ACTION_SCROLL_UP` / `ACTION_SCROLL_LEFT` / `ACTION_SCROLL_RIGHT`) as primary, with
FORWARD/BACKWARD as fallback when directional actions aren't in the node's action list.
Since minSdk is likely >= 23, directional actions should be available. The mapping becomes:

```
"up"    -> try ACTION_SCROLL_DOWN first, fall back to ACTION_SCROLL_FORWARD
"down"  -> try ACTION_SCROLL_UP first, fall back to ACTION_SCROLL_BACKWARD
"left"  -> try ACTION_SCROLL_RIGHT first, fall back to ACTION_SCROLL_FORWARD
"right" -> try ACTION_SCROLL_LEFT first, fall back to ACTION_SCROLL_BACKWARD
```

Rationale: the directional actions bypass the orientation-guessing problem entirely. The
FORWARD/BACKWARD fallback still catches containers that only declare the legacy actions.

Note on semantics: `ACTION_SCROLL_DOWN` means "the viewport scrolls down, revealing content
below." This matches direction="up" (finger up → content below comes into view). Verified
against Android docs.

### 1.2 ScrollNode UIAction variant — node identification mechanism unclear

Section 3.1 proposes `UIAction.ScrollNode(nodeId, scrollAction)`. But current UIAction
uses coordinate-based patterns (`ClickNodeAt(x, y)`), not nodeId-based. The perceptor
exposes `element_index` (snapshot-derived, re-indexed each turn) — not a stable native
`AccessibilityNodeInfo` node id.

This is the same issue documented in the click design `impl_summary.md` Reason 4:
> "`element_index` is snapshot-derived, not native node id"

**Options**:
1. **Coordinate-based** (like ClickNodeAt): `ScrollNodeAt(x, y, scrollAction)` — find the
   scrollable node at or near (x,y). Consistent with existing patterns, but may hit the
   same "coordinate-to-node lookup" limitation the click design flagged.
2. **Snapshot-element-based**: Pass the `PerceptionElement` reference directly. Cleanest
   but requires holding live node references across the perception boundary.
3. **Attribute-based**: Find the scrollable node by className + bounds matching.

**Recommendation**: Use option 1 (`ScrollNodeAt(x, y, scrollAction)`) for consistency with
`ClickNodeAt`. The coordinate comes from the resolved target's center. Since we're looking
for a *scrollable ancestor* in a known region, the coordinate lookup is more tolerant than
for clicks (any scrollable node containing the point works). Document this choice explicitly.

### 1.3 Stall policy threshold: "2 for swipe" needs scoping clarification
（这个不改，太hacky了，不实现这个policy了）
Section 3.8 says to tighten `LoopDetectionPolicy` threshold from 5 to 2 "for consecutive
same-direction no-effect swipes". But the current implementation (NavigationState.kt:30-35)
counts ALL swipes toward `consecutiveScrollActions`, not just no-effect ones.

Three possible interpretations:
1. Change `maxConsecutiveScrollActions` from 5 to 2 (affects all swipes, including effective ones)
2. Add a NEW counter that only increments on no-effect swipes
3. Change the existing counter to only increment on no-effect swipes

Option 1 is too aggressive — 2 successful scrolls in a row would trigger the warning.
Option 2 is cleanest. Option 3 changes existing semantics.

**Recommendation**: Add a separate `consecutiveNoEffectScrolls` counter that resets when a
swipe produces a screen change. Keep the existing `maxConsecutiveScrollActions=5` unchanged
for total scroll budget. Threshold of 2 applies only to the new no-effect counter.

---

## 2. Design Gaps (Borrowing from Click Design Structure)

### 2.1 Missing unified "Swipe Execution Contract"

The click design had a clean "Click Execution Contract (Phase 1)" with explicit pipeline
steps and a "No:" list. The swipe design distributes its logic across 6+ separate sections.

**Recommendation**: Add a consolidated contract block, e.g.:

```
## Directional Swipe Execution Contract (P0)

Pipeline:
1. Parse target (element_index / text / coordinate / none).
2. If targeted: resolve target to (x,y) via TargetResolver. Fail if unresolved.
3. If untargeted: use screen center as origin.
4. Find nearest scrollable ancestor node (isScrollable=true) containing origin.
5. If scrollable node found: try performAction(scrollAction) on it.
   - If screen changed: settle, capture, return success.
   - If unchanged: fall through to gesture.
6. Compute asymmetric gesture endpoints from origin.
7. Dispatch UIAction.Swipe once.
8. Dynamic settle delay: max(200, durationMs * 0.75).coerceAtMost(800).
9. Capture screen + detectScrollBoundary.
10. Return success with observation (+ no-change warning if boundary detected).

No:
- No retry loop in executor
- No center-fallback for targeted swipes that fail resolution
- No per-attempt UiChangeDetector success gating (warning only)
```

This makes the full pipeline visible in one place.

### 2.2 Missing explicit "File-Level Change Plan" with unchanged files

The click design listed files as "Phase 1 implement now", "Phase 1 unchanged", and "Phase 1
optional cleanup". The swipe design only mentions files inline with each change.

**Recommendation**: Add a section listing:
- Files to modify (already partially in Section 4 tables)
- Files explicitly unchanged (e.g., `MobileActionTool.kt` schema in P0, `UIAction.kt` not
  changed until P1 adds `swipe_intent`, etc.)
- Files with optional cleanup

### 2.3 Missing "Explicitly Disallowed" list

The click design had entries like "No `ClickNodeAt` fallback, no jitter, no re-resolve."
The swipe design would benefit from:
- No automatic retry loop in SwipeExecutor
- No silent center-fallback for targeted swipes
- No gesture-only path without trying a11y scroll action first (for directional swipes)
- No `ActionOutcome.Failed` for executed-but-unchanged swipes in P0

---

## 3. API Behavior Verification (Researched)

### 3.1 performAction(ACTION_SCROLL_*) return value

**Finding**: `performAction()` returns `true` if the action was **dispatched**, NOT if
scrolling actually occurred. A container at scroll boundary will still return `true` from
`performAction(ACTION_SCROLL_FORWARD)` even though no scrolling happens.

**Impact on design**: Section 3.1 says "If screen changes -> success; if unchanged ->
fall through to gesture." This is the correct approach — the design already checks screen
change (via UiChangeDetector) rather than relying on the return value. Good.

However, this means every directional swipe attempt will need TWO screen captures when
the scroll action path fires and fails (once after scroll action, once after gesture
fallback). Consider whether the latency cost is acceptable or whether we should skip the
post-scroll-action capture and just try gesture immediately if `performAction` returned
`false` (which at least indicates dispatch failure).

**Recommendation**: If `performAction` returns `false`, skip the screen capture and go
directly to gesture fallback (dispatch failure is definitive). If `performAction` returns
`true`, then capture + compare (dispatch succeeded but scrolling may not have happened).

### 3.2 isScrollable reliability

**Finding**: `isScrollable` is generally reliable for RecyclerView, ScrollView, ListView,
HorizontalScrollView, NestedScrollView. It can be unreliable for custom views and WebView.

The property indicates scroll *capability*, not whether content currently overflows. A
ScrollView with content shorter than its viewport will still report `isScrollable=true`.

**Impact on design**: When the a11y scroll action is tried on such a container, it will
return true (dispatched) but produce no change. The fall-through to gesture will fire.
This is acceptable behavior — no special handling needed.

### 3.3 ACTION_SCROLL_FORWARD on horizontal containers

**Finding**: `ACTION_SCROLL_FORWARD` on a horizontal RecyclerView scrolls RIGHT in LTR
layouts (reveals content further right). On a vertical RecyclerView, it scrolls DOWN.
The action is orientation-dependent.

**Impact on design**: Reinforces recommendation 1.1 — prefer explicit directional actions
(API 23+) over FORWARD/BACKWARD to avoid orientation ambiguity.

### 3.4 API 23+ directional actions availability

**Finding**: `ACTION_SCROLL_UP/DOWN/LEFT/RIGHT` were added in API 23 (Android 6.0). Views
*should* expose both FORWARD/BACKWARD and directional actions, but not all do. The standard
RecyclerView and ScrollView implementations do expose directional actions on API 23+.

**Impact on design**: If minSdk >= 23 (very likely), directional actions are available. The
fallback to FORWARD/BACKWARD handles the edge case of views that don't declare directional
actions.

---

## 4. Specific Section Notes

### Section 3.2 — Geometry fix looks correct

The asymmetric endpoint calculation (start 1/3 from origin, end at full distance from start)
is well-motivated by eval data. The increased distance factors (15%→25%, 40%→50%, 70%→80%)
are reasonable given that autodevice uses 100% screen sweep for directional swipes.

One clarification: the new formula computes `startY = min(originY + distancePx / 3, safeBottom)`,
then `endY = max(startY - distancePx, safeTop)`. This means the *total travel distance* is
`distancePx` (not `distancePx * 4/3`). The 1/3 offset is just positioning, not adding extra
distance. This is fine — the increased base percentages compensate.

### Section 3.3 — Target resolve failure semantics are clear

Matching the click design's principle: valid target → always produce a result; missing
target → fail. Center-only for untargeted swipes. Clean.

### Section 3.4 — Dynamic settle delay formula

```
settleMs = max(200, (durationMs * 0.75).toLong()).coerceAtMost(800)
```

This produces:
- 400ms swipe → 300ms settle (matches current behavior)
- 200ms swipe → 200ms settle (floor)
- 1000ms swipe → 750ms settle
- 1200ms swipe → 800ms settle (cap)

The formula is reasonable. The floor prevents too-short settles; the cap prevents blocking
on very long drags. Consider whether the 800ms cap is sufficient for heavy RecyclerView
animations — but this can be tuned empirically.

### Section 3.5 — Prompt guidance is critical

Direction semantics clarification is the highest-impact, lowest-risk change. Agree that
it belongs in P0 alongside the executor changes. The phrasing "direction=up means finger
moves up, content scrolls DOWN" is clear.

The "after 2 consecutive unchanged swipes, change strategy" guidance pairs well with the
stall policy threshold. Both prompt hint and code enforcement work together.

### Section 3.6 — NoChange result semantics compromise is pragmatic

Keeping transport-level success with enhanced warnings in P0, adding structured marker
in P1, is the right phasing. Changing result semantics across the board is high-risk;
adding a flag is low-risk and incrementally testable.

The `warning_unchanged=true` flag should be defined concretely in the design:
- Where does it live? Suggested: `ActionOutcome.Success` gets a new `noEffect: Boolean = false`
  field (like `verified`).
- How does it propagate? Suggested: SwipeExecutor sets it; MobileActionInvocation reads it
  for cognition context injection.

### Section 3.7 — swipe_intent inference rules

The default inference (`start/end` → drag, `direction` → scroll) is clean. One edge case
to specify: what if the agent provides `direction` + `swipe_intent: "drag"`? This likely
means "drag in this direction" which is contradictory with the drag model (explicit coords).
**Recommendation**: treat `swipe_intent` as override only when it adds information, not
when it contradicts the parameter shape. If `direction` is present and `swipe_intent: "drag"`,
either error or ignore the intent field.

### Section 3.9 — TYPE_VIEW_SCROLLED deferral is correct

Service-level event buffering is a distinct architectural scope. The current approach
(pre/post snapshot comparison) is sufficient for P0. Agree with deferral.

---

## 5. Comparison with Click Design — Lessons Applied

| Aspect | Click Design | Swipe Design | Assessment |
|--------|-------------|--------------|------------|
| Clear final decisions list | Yes (9 items) | No (distributed) | Gap — see 2.1 |
| Execution contract | Explicit pipeline | Distributed | Gap — see 2.1 |
| Phase separation | Phase 1 / Phase 2 | P0 / P1 / P2 | Good — more granular |
| File change plan | Explicit changed/unchanged/cleanup | Inline only | Gap — see 2.2 |
| Data-driven justification | Minimal | Strong (29 swipes analyzed) | Better than click |
| Explicitly disallowed list | Yes | No | Gap — see 2.3 |
| Verification gate | Numbered unit tests | Less structured | Minor gap |
| Discussion process | Clean conflict resolution | Clean conflict resolution | Good |
| Current code reality | Well-grounded | Well-grounded | Good |
| External contract stability | Explicitly preserved | Implicitly preserved | Minor gap |

---

## 6. Risks to Monitor

1. **Double screen capture latency**: Scroll-action-then-gesture path captures screen twice.
   Measure actual latency impact in first impl pass.
2. **isScrollable false positives**: Containers that report `isScrollable=true` but don't
   actually scroll will always trigger the (wasted) scroll action attempt before gesture.
   The fallback handles correctness; monitor whether latency matters.
3. **Stall threshold 2 may be too tight**: If the no-effect counter isn't scoped correctly
   (see 1.3), legitimate 2-swipe sequences trigger false warnings.
4. **Prompt direction semantics**: Even with better prompts, LLMs can still confuse
   direction mapping. The a11y scroll action fallback partially mitigates this because the
   scroll action is container-native and direction-aware.

---

## 7. Verdict

The design is **ready for implementation** with the clarifications noted in Section 1.
The P0 changes are well-scoped, data-justified, and follow the proven pattern from the
click redesign (node action → gesture fallback). The structural gaps (Sections 2.1-2.3)
are organizational, not architectural — they can be addressed in the design doc or during
implementation without changing the approach.

Priority edits before starting code:
1. Add direction → scroll action mapping table (1.1)
2. Specify ScrollNode identification mechanism (1.2)
3. Clarify stall counter scoping (1.3)
4. Optionally: add unified execution contract (2.1)
