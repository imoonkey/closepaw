# Codex Review — ui-refresh-0422 "Bound Edition"

Reviewed against:
- `projects/active/ui-refresh-0422/{README.md,direction.md,changes.md,critique.md,non-regressions.md,align-with-uxfb-0422.md,plan.md}`
- current code in `app/src/main/kotlin/...`
- current tests in `app/src/test/...` and `app/src/androidTest/...`

Verification sampled:
- `:app:testDebugUnitTest --tests ai.closepaw.ui.chat.components.ThoughtGroupTest --tests ai.closepaw.ui.chat.components.ThinkingIndicatorCadenceTest --tests ai.closepaw.ui.chat.ChatDoneBridgeTest --tests ai.closepaw.ui.overlay.model.CapsuleRenderSpecTest` — pass

## 1. N2 — AgentTrace 2dp left-rule color bump (`outlineVariant` -> `outline`)

Verdict: **PASS**

Evidence:
- `app/src/main/kotlin/ai/closepaw/ui/chat/components/AgentTrace.kt:100-109` — the left rule is a static `Box.background(...)`; this component has no `ClosePawMotion.reducedMotion()` branch, so the swap changes pigment only, not motion behavior.
- `app/src/main/kotlin/ai/closepaw/ui/theme/Theme.kt:46-48` and `app/src/main/kotlin/ai/closepaw/ui/theme/Color.kt:17-18` — light theme `outline = Hairline = Ink @ 12%`; current `outlineVariant = InkGhost = Ink @ 8%`.
- `app/src/main/kotlin/ai/closepaw/ui/theme/Theme.kt:78-79` and `app/src/main/kotlin/ai/closepaw/ui/theme/Color.kt:37-38` — dark theme keeps the same relationship (`HairlineDark` 12% vs `InkGhostDark` 8%), so dark-theme contrast improves rather than regresses.

Position:
- Ship the one-line bump as written.

## 2. N3 — CollapsePill summary `labelLarge` -> `monoSmall`

Verdict: **PASS**

Evidence:
- `app/src/main/kotlin/ai/closepaw/ui/chat/components/CollapsePill.kt:46-74` — the proposed change is typography-only; `testTag`, `Role.Button`, `stateDescription`, click handling, and layout container all stay intact.
- `app/src/androidTest/kotlin/ai/closepaw/qa/ChatAgentRowDisclosureTest.kt:109-142` — current coverage asserts toggle behavior and visibility only; it does not pin text style.
- `projects/active/ui-refresh-0422/plan.md:43-47` claims `CollapsePillTest`, but there is no such test in the repo; `rg` over `app/src/test` and `app/src/androidTest` finds none.
- `app/build.gradle.kts:128-140` includes JUnit + Compose UI test deps only; there is no configured Paparazzi/Roborazzi/Shot-style snapshot harness to break.

Position:
- The style swap should not break existing tests because there is no direct typography pin today.
- The verify gate should stop naming a nonexistent `CollapsePillTest`.

## 3. N1 — `[t+12s]` ledger counter outside `basicMarquee`

Verdict: **CONCERN**

Evidence:
- `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt:243-286` — the marquee contract itself is clean: only the thought `Text` owns `Modifier.basicMarquee(...)`, so a fixed ledger `Text` can sit before it without breaking the scrolling layout.
- `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleMode.kt:12-14` — `CapsuleMode.Running` carries only `thought`; there is no start timestamp in the mode.
- `app/src/main/kotlin/ai/closepaw/ui/overlay/CapsuleStateHolder.kt:111-116` and `:162-165` — task start and every thought update both create a new `CapsuleMode.Running(...)`. A literal `produceElapsedLabel(mode)` / `LaunchedEffect(mode)` implementation will reset the counter whenever the thought text changes.
- `projects/active/ui-refresh-0422/changes.md:351-359` says "remember of the start timestamp from mode", but the mode does not contain one.
- `app/src/androidTest/kotlin/ai/closepaw/qa/CapsuleRenderingTest.kt:53-70` covers running thought/buttons only. Nothing currently pins "paw + ledger fixed, thought scrolls" or "elapsed does not reset across thought updates."

Position:
- The layout is implementable.
- The current state sketch is not implementable as written without either:
  1. clarifying that the timer is UI-local and anchored on entry into `Running`, or
  2. introducing a stable elapsed-time source outside `CapsuleMode`.
- As written, this is the round's main unresolved correctness risk.

## 4. Conscious scope expansions from `non-regressions.md`

Verdict: **PASS**

Evidence:
- `projects/active/ui-refresh-0422/non-regressions.md:31-52` — extending Fraunces italic to `PageMasthead` and section subheads replaces already-synthesized Fraunces usage with a loaded, disciplined identity surface.
- `projects/active/ui-refresh-0422/changes.md:99-125` and `:450-489` — the Fraunces footprint stays narrow and centralized around `PageMasthead` / small section subheads, not row-body prose.
- `projects/active/ui-refresh-0422/non-regressions.md:54-69` — the mono expansion is limited to `CollapsePill` summary text, which is already ledger-shaped.
- `app/src/main/kotlin/ai/closepaw/ui/chat/components/CollapsePill.kt:64-72`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/AgentSummary.kt:13-20`, and `app/src/main/kotlin/ai/closepaw/ui/chat/components/AgentTrace.kt:171-220` — the pill summary sits in an already machine-ledger neighborhood (mono chevron, mono action rows, elapsed/action-count string).

Position:
- **Fraunces italic surfaces:** accept as scoped.
- **Mono summary register:** accept as scoped.
- Neither of these is where I would trim the round.

## Recommended deltas to `changes.md`

1. In `changes.md` under `ui/capsule/surface/SmartCapsuleSurface.kt`, replace the current ticker sketch at `changes.md:351-359` with an explicit note that the elapsed clock must **not** key off the full `mode` value. The current `CapsuleStateHolder` recreates `CapsuleMode.Running(...)` on every thought update (`CapsuleStateHolder.kt:162-165`).
2. In that same section, choose the time source explicitly:
   - preferred within current round bounds: latch a `runningStartedAtMs` in `SmartCapsuleSurface` when entering `Running` from a non-`Running` mode, using the already-available `previousMode` seam (`SmartCapsuleSurface.kt:72-83`), and keep it stable across thought updates; or
   - if real task elapsed across surface recreation is required, state plainly that this round must add a stable non-UI time source and is therefore touching capsule state beyond pure ornament.
3. Add one verification note for Phase 5: prove the ledger node is separate from the marqueed thought and prove the elapsed label does not reset across `onThoughtUpdate`.

No `changes.md` delta is needed for the AgentTrace color bump or the CollapsePill type swap.

## Final

**GO-WITH-CHANGES**

Reason:
- I would open **Phase 1** after tightening the round docs on the capsule ticker and correcting the claimed verify coverage.
- I would not let a later Phase 5 PR land against the current N1 sketch, because the proposed `LaunchedEffect(mode)` shape is set up to reset on ordinary thought updates.
