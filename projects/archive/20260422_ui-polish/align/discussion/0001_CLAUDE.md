# 0001 — Claude (initial alignment draft)

## What this turn did

Built the initial consolidated fix list at `final/aligned.md` from both source reviews, after verifying every finding against source.

## Verification done

- `AgentServiceEventHandler.kt:76-86,127-131` — confirmed `recordTaskOutcome` only stashes the enum (`SessionRecordingService.kt:226-228`); `event.result` and `SessionError.message` never reach the recorder. **Codex C1 stands.**
- `SessionRecordMessageMerger.kt:14-21` — confirmed `MessageRecord.Agent` is built without `rowState`. **Codex C2 stands.**
- `CapsuleStateHolder.kt:243-251` — confirmed `message` arg is dropped on `ERROR`. **Codex I3 stands.**
- `MainActivity.kt:212-222,253-254` — confirmed `isAccessibilityEnabled` / `isOverlayEnabled` are inline, not state, won't recompose on resume. **Claude N1 stands.**
- `Motion.kt:37-56` + `ThinkingIndicator.kt` — confirmed `reducedMotion()` defined but unused at thinking indicator. **Codex I5 stands.**
- `MessageBubble.kt:304,311,320` + `ChatEventReducer.kt:104,116-118` — reducer never emits `ActionState.Executing`. **Claude N5 stands.**
- `SessionRecordingService.kt:165-171` — `recordThought` has no blank guard. **Claude N6 stands.**
- `roadmap.md:29` — `ic_paw` on `StatusIslandCompose` listed as unchecked TODO; design_aligned.md confirms it as a core identity rule. **Codex I4 stands.**

## Cross-reviewer overlap

The two reviews looked at **different layers** — Codex stayed at recorder/state/protocol; Claude stayed at compose/UI/cosmetic. **Almost nothing was caught by both.** The closest overlap: Claude praised the `951c82f5` rowState round-trip helper as a good-call; Codex flagged that the production writer bypasses it (C2). Both are right — the helper is correct, but the writer doesn't use it.

So the "HIGH confidence (both caught)" tier is empty. Most findings start at MEDIUM confidence pending the other side weighing in.

## Items requiring Codex weigh-in

- **I1** (resume stale flags) — Claude only. Real Compose state-read bug, but Codex didn't touch this file in their review.
- **N1 (live pill)** — Codex; please confirm Claude shouldn't have flagged this.
- **N2-N7** — Claude cosmetic/consistency nits; please weigh agree/disagree/no-opinion.
- **N8 (FivePawProgress)** — borderline. Per global rule "don't design for hypothetical futures" the current code may be intentional; please weigh in on whether to keep, drop, or apply the simpler `require + stepIndex.coerceIn` form.

## Items dropped at this stage

None. Every finding from both reviews is either (a) carried into the aligned list or (b) preserved as context (Good-calls). User said: don't skip real bugs even if small.

## My vote

CHANGES — created the draft. Awaiting Codex review.

## Open questions for Codex

1. Do you concur with **I1**? Claude's analysis: the booleans are inline expressions in `MainActivityContent(...)` — Compose binds at first composition because no state is read inside them. Promoting to `mutableStateOf` next to `repairModel` and recomputing all three on `ON_RESUME` is the minimal fix.
2. **N5** (Executing dead branch): delete vs wire? Claude leans delete — KISS, the protocol can't reach it.
3. **N8** (FivePawProgress): keep complex formula, hard-require 5, or simplify? Claude leans `require(totalSteps == pawCount)` + `filled = stepIndex.coerceIn(0, pawCount)`.
4. Anything either of us missed in scope `f4a699b9..HEAD`?
