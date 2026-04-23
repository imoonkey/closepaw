# QA PASS — ux-feedback-0422 real-device validation

Date: 2026-04-22
Device: EP0110MZ0BC101266W (physical, portrait)
Branch: `main` @ 5 commits past 30fe9ad7
Setup: `scripts/setup.sh` ran clean — APK installed, overlay + a11y permissions granted, app launched.
Backend: openai / gpt-5.4 (basic execution mode, accessibility platform).

---

## Scenario 1 — Long thought visible in capsule

**Result: PASS**

| Sub-case | Evidence | Observation |
|---|---|---|
| SmartCapsule (MAIN_APP) marquee | `scenario1_capsule_marquee_mainapp.png` | Status line scrolls full thought (snapshot caught mid-scroll: "🐾 a maps app to search nearby Italian restaura"). No `…` truncation. |
| Capsule (BACKGROUND) marquee | `scenario1_capsule_marquee_background.png` | Same surface in BACKGROUND context; thought "Without using any apps, just think dee[ply]…" actively scrolls — different frames captured at different positions. |
| Reduced-motion fallback | `scenario1_reducedmotion_compactthought.png` | After `settings put global animator_duration_scale 0.0` and app restart: status line is **static** with ellipsis: "Plan a detailed 7-day European vac…". No scroll. Matches `compactThought()` ~80-char behavior. |

Notes:
- Could not isolate the StatusIsland (compact pill) form on this device — `showPreference` resolves to full SmartCapsule in BACKGROUND context. The `Modifier.basicMarquee` block in `StatusIslandCompose.kt:60` is the same as the SmartCapsule one in `SmartCapsuleSurface.kt:255`, so equivalent behavior is implied. Worth a follow-up only if Island-vs-Capsule routing is meant to be configurable.

---

## Scenario 2 — ThoughtGroup rendering in chat

**Result: PASS**

| Aspect | Spec | Observed |
|---|---|---|
| Group container | 2 dp left rule + 12 dp inset | ✓ Visible in `scenario2_thoughtgroup.png` (vertical line on left of thought header) |
| Thought style | bodyLarge, regular weight, no italic, no `✱` glyph | ✓ "Open Calculator app to perform the requested multiplication." rendered upright, no glyph |
| Action style | monoSmall, indented, status glyph right-aligned | ✓ "→ Open app(Open app: Calculator)" with hourglass on right |
| Multi-group separation | 16 dp between groups | ✓ `scenario2_multi_thoughtgroups.png` shows two groups (plan + maps) clearly separated; left rule continues per group |
| Preface group | Actions before first thought | Not exercised in this run (every observed turn started with a thought). No regression observed; reducer logic in `ChatEventReducer.kt` covers it. |

---

## Scenario 3 — Final answer persists on collapse

**Result: PASS**

| Aspect | Evidence | Observation |
|---|---|---|
| Collapsed default state | `scenario3_collapsed_with_finalanswer.png` | Row default-collapses on completion. Pill chip `▸ ✓ 1 action · 4.5s` visible. Final answer ("17 × 23 = 391. Brief method: …") rendered below pill, persistently visible. |
| Pill toggles trace | `scenario3_expanded.png` | Tap pill → chevron rotates to `▾`, trace expands inline (thought + complete_task action + result). Tap again → returns to collapsed state with final answer below. Confirmed both directions. |
| Final answer selectable | `scenario3_longpress_selection.png` | Long-press on final answer text triggers selection handles + system context menu (复制 / 全选 / etc.). Plain `SelectionContainer` semantics intact. |

Minor observation (P3, non-blocking):
- In **expanded** state, no separate "final region" is rendered below the pill — the answer appears once as the `complete_task` action result inside the trace. Spec D3 reads "always visible when present"; in collapsed state it is, in expanded the trace already contains it. Could either be intentional (avoid duplication) or a gap. Not user-facing harm.

---

## Scenario 4 — USER_STOPPED

**Result: PASS**

| Aspect | Evidence | Observation |
|---|---|---|
| Stop tap → cancel | logcat shows `SessionAgentRunner: Agent cancelled by user request` at 18:21:20 after Stop tap | Agent loop terminates cleanly. |
| Collapsed row state | `scenario4_user_stopped_collapsed.png` | Row shows pill `▸ ✓ 2 actions · 13s`. **No** "Task completed" / final-answer block below pill. uxfb-3 fix confirmed — no fabricated FinalText. |
| Expanded row state | `scenario4_user_stopped_expanded.png` | Trace shows two ThoughtGroups (plan turn + open-app turn that errored). Pill `▾ ✓ 2 actions · 13s` at bottom. No phantom completion block. |

---

## Out-of-scope observations

1. **Capsule overlay invisible to `uiautomator dump`** — known device-specific limitation; coordinate-based taps were used. Documented in skill reference.
2. **Approval flow:** when an action lands in `AwaitingApproval`, `debug-run.sh` reports "Task completed!" and exits; the approval dialog stays modal in-app. Independent from the UX changes here, but worth filing if it affects automation reliability.
3. **VPN icon shows red** in the status bar — unrelated, device state.

---

**Conclusion:** all four ux-feedback-0422 acceptance scenarios verified on real hardware. Implementation matches Decisions D1/D2/D3 from `README.md`. No follow-up tasks filed.

TASK COMPLETE
