# Align with uxfb-0422 — changelog

> **The substance of this file has been merged back into the source docs.**
> This page now stands as the changelog of *what was reconciled* when
> `uxfb-1 → uxfb-followup` landed mid-round. Read the source docs (`README`,
> `critique`, `direction`, `changes`, `plan`, `non-regressions`) — they are
> already correct standalone.

## Context

While this round was being written, six commits landed on `main`:

| commit | what it shipped |
|---|---|
| `uxfb-1` | `ThoughtUpdate { full, compact }` — full thought preserved end-to-end. `sanitizeThought` → `compactThought`. |
| `uxfb-2` | Capsule thought scrolls via `Modifier.basicMarquee`; reduced motion → `compactThought()` (~80 chars). |
| `uxfb-3` | `ContentBlock.FinalText` + `CollapsePill.kt` + `AgentRow.kt` split. Row root no longer clickable. FinalAnswer always visible below pill. |
| `uxfb-4` | `AgentTrace.kt` extracted. ThoughtGroup hierarchy with **2dp `outlineVariant` left rule** as marker (replaces `✱` glyph). ThoughtHeader = `bodyLarge / onSurface`, no italic. `MessageBubble.kt` becomes a thin dispatcher. |
| `uxfb-followup` | `complete_task` no longer truncated; `MainActivity` uses `adjustNothing`; `ChatScreen` bottomBar gets `imePadding`. |

## What was withdrawn

| Move | Why |
|---|---|
| **W1.** `MessageBubble::FinalSeparator → Fleuron()` | `uxfb-3` made the boundary structural: `[trace] [CollapsePill] [FinalAnswer]`. The pill *is* the boundary on Complete rows; on Live/Waiting/Error there's no Final block to separate. The fleuron primitive itself stays — Settings home still uses it. |
| **W2.** `CollapsedHeader / OutcomeFooter → [+]/[−]` mono brackets | `uxfb-3` shipped `CollapsePill.kt` with a rotating `▸` chevron (codex-reviewed). Don't fight the new component. |

## What was already-fixed-by-uxfb (dropped from `critique.md`)

| Critique | Fixed by |
|---|---|
| `[H] FinalSeparator below the JND threshold` | `uxfb-3` (structural boundary) |
| `[M] AgentRow ✱ glyph + italic+onSurfaceVariant Thought = inverted hierarchy` | `uxfb-4` (`AgentTrace.kt:105-110` — bodyLarge/onSurface, 2dp left rule replaces `✱`) |
| `[M] Track A row voice: italic Thought clashes with mono Action` | Same — Thought is no longer italic |

## What was added (new findings born from the new shape)

| Finding | Where it landed in the source docs |
|---|---|
| **N1.** Capsule ledger counter must sit *outside* the new `basicMarquee` (paw + ledger fixed; thought scrolls) | `direction.md` §7 + `changes.md` §SmartCapsuleSurface |
| **N2.** `AgentTrace.kt:109` — bump 2dp left rule color from `outlineVariant` (8% Ink, JND-borderline at 2dp on Paper) to `outline` (Hairline, 12%). The rule is now the *primary* turn marker — deserves the weight. | `critique.md` (new [H]), `direction.md` §6a, `changes.md` §AgentTrace, `plan.md` Phase 3 |
| **N3.** `CollapsePill.kt:72` — swap summary text from `labelLarge` (Geist) to `monoSmall`, so the chevron + summary read as one ledger object. | `critique.md` (new [M]), `direction.md` §6b, `changes.md` §CollapsePill, `plan.md` Phase 3, `non-regressions.md` §2 |

## Held moves (no change)

13 moves stand exactly as written: PageMasthead, Fraunces SemiBold synthesis
fix, paperGrain modifier, Stop button full Rust border, capsule ledger counter
(N1 layout note added), send-button breath, StartupErrorBanner Rust rule + ⚠,
EmptyState asymmetric rebuild, user bubble letter-to-the-editor, ThinkingIndicator
naked, five-paw progress contrast, Settings sectioning + fleuron + mono version,
`live` pill mono restyle.

## Phase plan delta

`plan.md` Phase 3 went from "5 chat ornament edits, 3-4h" to "3 chat edits +
2 one-liners, 2h." Total round estimate dropped from 12-16h to **10-14h**.

## Bound Edition direction is unaffected

The aesthetic vision and the two new theme primitives (`Ornaments.kt`,
`PaperGrain.kt`) are exactly as proposed. This alignment trimmed the round
to fit the new MessageBubble shape; it didn't retreat from the direction.
