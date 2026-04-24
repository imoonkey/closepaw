# UX Feedback — 2026-04-22 (rev 2, codex-reviewed)

Three UX issues raised against chat + capsule UI. Rev 2 incorporates the
`/eng-plan-review` pass: blocker resolved, scope expanded to upstream
truncation + Track A doc/test contracts, and three open product decisions
have been resolved by the user.

Files cited:
- `app/src/main/kotlin/ai/closepaw/protocol/TextUtils.kt`
- `app/src/main/kotlin/ai/closepaw/agent/TurnPlanningPhaseRunner.kt`
- `app/src/main/kotlin/ai/closepaw/agent/Turn.kt`
- `app/src/main/kotlin/ai/closepaw/ui/chat/ChatEventReducer.kt`
- `app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt`
- `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/StatusIslandCompose.kt`
- `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/IslandOverlayHost.kt`
- `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt`
- `app/src/main/kotlin/ai/closepaw/ui/theme/Motion.kt`
- `doc/main/ui/style.md`

---

## 0. Decisions (locked before /implement)

| # | Decision | Implication |
|---|---|---|
| **D1** | **Thought is preserved end-to-end at full length.** Capsule shows compact slice via marquee, chat + history keep full text. | `sanitizeThought()` stops truncating; ThoughtUpdate event carries `full` and `compact` fields; reducer/history store `full`. |
| **D2** | **Collapse disclosure lives on a dedicated CollapsePill.** Row root is no longer clickable; only the pill toggles trace visibility. Final answer area is a normal selectable text surface. | New `CollapsePill` composable; remove root-level `clickable` from `AgentRow`; semantics + `Role.Button` move to the pill. |
| **D3** | **"Final answer" is defined by the agent loop's stop criteria** (`Turn.kt:205-209`): either `complete_task.answer`, or the *last* text block that carries no tool calls. Earlier text blocks are narrative trace. | Introduce `ContentBlock.FinalText` (or an `isFinal: Boolean` flag) populated by the reducer when it sees the completion event. |

---

## 1. Thought truncation — fix the data path, not just the renderer

### Why §1 of rev 1 was wrong (codex blocker)
Marquee on `StatusIslandCompose.kt:60` and `SmartCapsuleSurface.kt:255` would
only scroll *already-truncated* text. Thoughts are clipped multiple times
upstream:

- `protocol/TextUtils.kt:10` — `sanitizeThought()` trims to 40 chars and
  appends `"..."`.
- `agent/TurnPlanningPhaseRunner.kt:235` — emits the *sanitized* string as
  `ThoughtUpdate`.
- `ui/chat/ChatEventReducer.kt:87-95` — stores that sanitized string verbatim
  into `ContentBlock.Thought`. Chat row + history both inherit the loss.
- `ui/overlay/compose/IslandOverlayHost.kt:85-86` — *re-truncates* to 24
  chars before reaching the island composable.

So the truncated `…` the user sees is structural data loss, not a render
choice.

### Fix (per D1: full preservation)

**Protocol layer**

  - `ThoughtUpdate` carries two fields: `full: String` (untouched, only
    whitespace-trimmed) and `compact: String` (~80 chars, used for any
    surface that explicitly opts into a compact form). Both are produced
    once at emission time in `TurnPlanningPhaseRunner.emitAgentThought()`.
  - `sanitizeThought()` stays in `TextUtils.kt` but is renamed
    `compactThought()` and called only by callers that want the compact
    form. `AgentTurnRunner`'s direct use of `sanitizeThought` (per
    TextUtils.kt's own header comment) updates accordingly.
  - Bump compact ceiling to ~80 chars (current 40 was set for a single-line
    capsule with no marquee; with marquee the displayed window can be
    wider, and 80 chars is a meaningful preview when marquee is disabled by
    reduced motion).

**Reducer + history**

  - `ChatEventReducer.kt:87-95` stores `event.full` in `ContentBlock.Thought`.
  - History `MessageRecord` / converter (`MessageConverter.kt`) round-trips
    the full text; add a coverage case in `MessageConverterTest`.

**Capsule renderers**

  - `IslandOverlayHost.modeText` (line 85-86): drop the `.take(24)` —
    forward the full text and let the composable handle overflow.
  - `StatusIslandCompose.kt:60` and `SmartCapsuleSurface.kt:255` add
    `Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 30.dp,
    delayMillis = 1500)` to the thought `Text`.
  - Reduced-motion fallback: `Motion.kt:37` exposes the platform reduce-
    motion preference. Wrap the marquee in `if
    (ClosePawMotion.reducedMotion()) compactThought() else basicMarquee
    + fullText`. With reduced motion, surface the compact form so the
    user still sees something meaningful in one frame.

**Chat thought rendering**

  - `ThoughtItem` (MessageBubble.kt:275-292) and the new ThoughtGroup
    header in §2 render the **full** text. No truncation, no marquee — the
    chat is vertically scrollable so wrap is fine.

### Acceptance

  - Long thoughts (>200 chars) survive intact through:
    capsule surface (marqueed), chat row (wrapped), session history record.
  - Reduced-motion users see ~80-char compact form in capsule.
  - `MessageConverterTest` round-trips a long thought without loss.
  - `CapsuleRenderingTest` updated: a long-thought fixture asserts the full
    text is the source of truth, not a 24/40-char prefix.

---

## 2. Chat trace hierarchy — group by thought, restyle, and split the file

### Restated problem
`ExpandedTrace` (MessageBubble.kt:215-259) flattens Thought + Action +
Action + Thought + ... at one indent level. Thought is styled as the
*least* prominent item (italic + onSurfaceVariant) while mono-styled
actions look most prominent. Inverted information hierarchy.

### Cardinality (per user clarification)
A turn emits **one thought** (the first selected tool call's `agent_thought`
— `TurnPlanningPhaseRunner.kt:217-238`) and **one or more actions**
(`selectedToolCalls`). A row spans many turns. So:

```
Turn 1: Thought₁ + Action₁ₐ + Action₁ᵦ + …
Turn 2: Thought₂ + Action₂ₐ + …
…
Final
```

Grouping rule: each `ContentBlock.Thought` opens a group; subsequent
`ContentBlock.Action`s belong to it until the next Thought. Actions before
the first Thought form a header-less preface group. Thoughts with no
actions (rare, e.g. all selected tools dropped by arbitration) render as
their own group with no action rows.

### Fix

Restructure `ExpandedTrace` into ThoughtGroups:

```
│ Thought (bodyLarge, onSurface, regular weight, no glyph prefix)
│
│      → action(args)                              ✓
│      ↳ result summary (bodySmall, onSurfaceVariant)
│      → action(args)                              ✓
│
│ Next thought
│      → action(args)                              ✓
```

Concrete:

  - **Group container**: `Row` with a 2dp `Box(background =
    colorScheme.outlineVariant)` left rule + `Column(start padding 12dp)`.
  - **Thought**: `bodyLarge`, `onSurface`, no italic, no `✱` glyph (the
    rule is the marker).
  - **Action row**: stays mono but drops to `monoSmall`,
    `onSurfaceVariant`, additional 16dp `start padding` inside the group.
    Status glyph stays right-aligned.
  - **Spacing**: `spacedBy(spacing.md)` (16dp) between groups,
    `spacedBy(spacing.xs)` (4dp) inside a group.
  - **Preface group** (actions before first thought): same left rule, no
    header.

### Scope items the rev 1 doc missed (codex)

  - **Track A doc contract** (`doc/main/ui/style.md:96`) pins the current
    italic-thought / mono-action typography. Update style.md alongside
    the code change.
  - **File length**: `MessageBubble.kt` is already 463 lines (over the
    400-line repo cap). §2 + §3 must extract:
      - `AgentRow.kt` — disclosure + final-answer rendering
      - `AgentTrace.kt` — ThoughtGroup, ThoughtHeader, ActionRow
      - `AgentSummary.kt` — `outcomeFooter`, `collapsedSummary` (and the
        retired `collapsedHeadline` ladder, see §3)
    leave `MessageBubble.kt` as the entry-point dispatcher only.

### Tests

  - Add a `ThoughtGroupTest` (compose UI) covering: (a) one-thought-many-
    actions, (b) preface (actions-before-first-thought), (c) thought-only
    group, (d) multi-turn ordering preserved.
  - Update `ChatAgentRowDisclosureTest` for new structure (it asserts
    expanded-state shape).
  - Update `style.md`'s typography table — landed in same PR.

---

## 3. Final answer survives collapse — define "final" structurally

### Restated problem
Collapsed rows show `collapsedSummary` (MessageBubble.kt:381-397), whose
headline ladder picks the *first* in-row signal (user prompt → first
thought → first action → first text). The agent's actual concluding
answer disappears.

### Why "use the last Text block" isn't enough
`ExpandedTrace` (MessageBubble.kt:233-235) treats *every*
`ContentBlock.Text` as final prose. Mid-turn narrative text (e.g. a stream
that gets interrupted by a tool call) currently becomes a "Final" block
too. Without a real definition, collapsing-to-conclusion would still leak
narrative.

### Definition (per D3, aligned with `Turn.kt:205-209`)

A row is `Complete` when one of:

  1. `complete_task` was executed → its `answer` argument is the final.
  2. A turn returned no tool calls and a non-empty text → that text is the
     final.

Mapping to UI:

  - Reducer recognises completion (`AgentMessageState.Done` /
    `RowState.Complete`) and tags the corresponding text block with
    `isFinal = true`. Easiest implementation: introduce
    `ContentBlock.FinalText(text)` (parallel to `Text`) and have the
    reducer convert the matching block at completion time.
  - For (1): when `complete_task` action lands, reducer pulls
    `arguments.answer` and emits a `FinalText` block (the current
    `OutcomeFooter` of `✓ N actions · 12s` survives separately).
  - For (2): when the row transitions to Done with no `complete_task`,
    the reducer marks the most recent `Text` block as `FinalText`.
  - All other `ContentBlock.Text` instances stay `Text` and render *inside*
    the trace area, **not** in the always-visible final region.

### Fix

`AgentRow` (post-extraction → `AgentRow.kt`):

```
[trace region]              <- collapsible
[CollapsePill (optional)]   <- the toggle
[final region]              <- always visible when present
```

Rules:

  - **Live / Waiting / Error** rows: trace open, no pill (locked open),
    final region empty (no FinalText yet) — same as today.
  - **Complete** rows with traceBlocks AND finalText: render trace
    (collapsed by default), CollapsePill below trace as the toggle, then
    the final region.
  - **Complete** rows with finalText but no trace (single-turn one-shot
    answers): no pill, no trace region, only final.
  - **Complete** rows with trace but no finalText (failed completion or
    missing answer field): pill collapses trace, footer stays as today
    (`✓ N actions · 12s`). No final region.

Per codex (Major #2): the row root is **no longer** clickable. The pill
owns the click + `Role.Button` + `stateDescription`. The final-text
surface stays a plain `SelectionContainer + Text` so copy/select works
without fighting the toggle.

CollapsePill visual:

  - Compact chip: `[ ▸  ✓ 4 actions · 12s ]`
  - Chevron rotates 90° when expanded.
  - Width: hug content, left-aligned (matches the trace's left rule
    column).
  - Color: `surfaceVariant` background, `onSurfaceVariant` text + chevron.
  - Min touch target 48dp via `defaultMinSize`.

### Discarded subtlety

Codex Moderate #5 was right: error rows are already `RowState.Error`
(`ChatEventReducer.kt:148`) and stay locked-open with the inline `⚠️`
text — no extra "warning pill" logic needed. Drop that part of rev 1.

### Tests

  - Replace `CollapsedHeadlineTest` (the headline ladder is no longer the
    contract). Add `FinalTextSelectionTest` that exercises:
      - `complete_task` answer path
      - last-text-without-tools path
      - completion with no answer (no final region)
  - `ChatAgentRowDisclosureTest`: collapsed Complete row still shows
    final text; only pill toggles trace; root row not clickable.

---

## 4. Implementation order (revised)

PRs are independent enough to land separately.

  1. **Protocol/data: full thought preservation** (§1, D1)
       - rename `sanitizeThought` → `compactThought`, stop truncating in
         the canonical pipeline.
       - `ThoughtUpdate { full, compact }`, reducer stores `full`,
         history round-trips full.
       - drop `IslandOverlayHost.modeText`'s `.take(24)`.
       - acceptance: `MessageConverterTest` long-thought round trip green.
  2. **Capsule marquee + reduced-motion fallback** (§1, renderer half)
       - lands after #1; ~10 lines per surface plus a `Motion.kt` check.
  3. **Final-text concept + CollapsePill** (§3, D2 + D3)
       - introduce `ContentBlock.FinalText` (or `isFinal` flag).
       - reducer marks final on completion.
       - extract `AgentRow.kt` (file-split) and add `CollapsePill`.
       - update `ChatAgentRowDisclosureTest`, replace `CollapsedHeadlineTest`.
  4. **Trace hierarchy: ThoughtGroup + restyle + style.md** (§2)
       - extract `AgentTrace.kt` / `AgentSummary.kt`.
       - update `doc/main/ui/style.md` typography table in same PR.
       - add `ThoughtGroupTest`.

After all four land, the chat reads as: user prompt → grouped trace
(collapsible behind a pill) → final answer (always visible). Capsule
keeps its single-line silhouette but no longer drops information.

---

## 5. Out of scope (codex Not-In-Scope, kept)

  - No capsule state-machine changes.
  - No action execution / arbitration changes.
  - No new per-action model beyond preserving current `expandedContent`.
  - The protocol/history thought-schema change (D1) IS in scope here
    because full-thought recovery is now an explicit acceptance criterion
    — this is the deliberate exception to codex's "don't mix protocol
    change with chat restyle" advice, justified by D1.
