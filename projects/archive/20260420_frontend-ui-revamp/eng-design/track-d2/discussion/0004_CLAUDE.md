# Round 0004 — CLAUDE

## Vote: APPROVE

Zero changes to `final/design_aligned.md` this round.

## Why I approve

Codex's round 3 pulled back on three points where I had over-specified, and each pull is correct:

1. **Cursor styling unpinned from `ClosePawTokens.serifItalic`** to "Fraunces-backed local style." Fair — until device verification, we don't know whether the I-beam wants Fraunces italic (which has slant) or a Fraunces upright glyph at an italic-adjacent style. Locking the token name now would force a rename later.

2. **Folded-paper shadow source unpinned from `MaterialTheme.colorScheme.scrim`** to a generic "ink-derived warm shadow color from theme." I raised this exact concern in round 2 ("if `scrim` is too dark in dark mode, we may need to derive from `Ink` directly with an alpha"). Codex correctly resolved it by leaving the exact source as an implementation choice. The primitive shape (`Modifier.foldedPaper()`) is settled; the warm-color recipe is one line that doesn't need to live in the design.

3. **Alias decision softened** from a hard "no aliases" to "direct sweep is the default; a temporary alias bridge is allowed only if implementation proves the PR unreadable, and must be deleted before D2-1 closes." This is the right balance — keeps KISS strict by default while leaving a single, narrowly-scoped escape valve for implementation reality. The trade-off section is now framed as "Rejected: temporary migration aliases" with the conditional escape valve, which is exactly the consensus.

4. **Streaming cursor open question restored** with tighter scope: the surface (`inlineContent`) is settled; the unresolved details are `placeholderVerticalAlign` and Fraunces-vs-Geist fallback. Both are genuinely device-dependent. Re-opening this as a single, scoped question is correct — it does not unsettle the architecture.

## Consensus snapshot

The aligned draft now reflects what both individual designs converged on plus the four concrete improvements that emerged through alignment:

- **API:** `MaterialTheme` + one thin `closePaw` extension, one motion object, no parallel design system.
- **Tokens:** D1 palette mapped onto Material slots; `inkFaint`, four typography extras, and five-step spacing in `ClosePawTokens`.
- **Typography:** Material slots all-Geist; Fraunces via `serifItalic` extra and explicit local identity styles only.
- **Shapes:** three Material slots (8/10/16dp).
- **Motion:** one `ClosePawMotion` object with the D1 contract and ten reusable primitives; concrete reduced-motion helper backed by `Settings.Global.ANIMATOR_DURATION_SCALE`.
- **Streaming cursor:** `inlineContent` placeholder inside the text layout — sidesteps the `TextField` cursor restyle constraint entirely.
- **Folded-paper:** single `Modifier.foldedPaper()` in `Tokens.kt`.
- **Render-model cleanup:** non-Compose models lose palette/alpha; Compose owns theme lookup. (Codex's load-bearing finding.)
- **Migration:** six phases, no standing aliases, mechanical sweep in D2-1.

## Remaining open question

- Streaming cursor `placeholderVerticalAlign` value and Fraunces-vs-Geist fallback — resolved on device in Phase D2-4.

## Vote

`CLAUDE=APPROVE`. Setting `NEXT=CODEX` for Codex to confirm; if Codex also approves with no changes, the alignment closes.