# Round 0002 — CLAUDE

## Vote: CHANGES

Made substantive edits to `final/design_aligned.md`. Resetting CODEX vote to PENDING.

## What I changed and why

Codex's first draft was solid as a spine but left four open questions that already had cross-review consensus to draw on. I resolved all four into the body of the document and removed them from `Open Questions` per the alignment rule ("update the self-contained final design first, then remove or revise the corresponding open question").

### Resolved open questions

1. **Material typography mapping (OQ1)** → §1 Typography. All Material `Typography` slots (`display*`, `headline*`, `title*`, `body*`, `label*`) use **Geist**. Fraunces is **not** wired into any Material slot. Identity-facing text reaches Fraunces only via `ClosePawTokens.serifItalic` and explicit local `TextStyle` declarations (empty state, onboarding watermark). Both cross-reviews flagged Codex's original "headline → Fraunces" mapping as a violation of D1 §4.2 identity-scarcity.

2. **Streaming cursor verification (OQ2)** → new §2 Streaming cursor subsection. The Final block is read-only `Text`, so Compose's text-cursor restyle constraint does not apply at all — D1's worry is moot. The cursor is rendered via `inlineContent` with a `Placeholder` anchored at end-of-stream; this anchors correctly across reflow on the *last visual line*, which a trailing-sibling composable cannot do. Real-device verification of Fraunces baseline alignment becomes a Phase D2-4 acceptance criterion, not a blocking open question. Fallback (Geist `|`) is a one-line font swap inside the same surface — design does not branch on this.

3. **Folded-paper primitive (OQ3)** → new §2 Folded-paper primitive subsection. `Modifier.foldedPaper()` with `shadow(ambientColor=, spotColor=)` for the warm under-shadow and `drawWithContent` for the top hairline. Modifier wins over a `FoldedPaperSurface` composable because the capsule and modal drawer already use `Surface`-bearing composables — chaining a modifier costs less than nesting/replacing a Surface, and avoids reinventing content-slot/click/accessibility plumbing. Lives in `ui/theme/Tokens.kt` next to `ClosePawTokens`.

4. **Migration alias strategy (OQ4)** → §5 Phase D2-1 + §7 Trade-offs. **No aliases.** Reasoning: an alias still has to be reviewed twice, still has to be deleted, and still risks new code being written against it during its half-life. The mechanical sweep is a regex-replace operation, reviewable as one commit, bounded to one PR. The alias gain is illusory.

### Other changes

5. **Reduced-motion concrete API** → §2 Reduced motion. Replaced "exact detection source is an implementation detail" with a concrete `reducedMotion()` helper backed by `Settings.Global.ANIMATOR_DURATION_SCALE`. Added the per-primitive contract per D1 §8 (cursor blink continues — liveness, not decoration).

6. **Phase consolidation** → §5. Dropped the old Phase D2-3 ("motion primitives + reduced-motion policy") because (a) its centralization belongs in D2-1 (which already creates `Motion.kt`), and (b) its application halves overlap with D2-4 capsule/overlay restyle (overlay) and D2-6 settings rollout (settings transitions). Renumbered: old D2-4→D2-3, D2-5→D2-4, D2-6→D2-5, D2-7→D2-6. Now **6 phases**, no artificial split between "create the file" and "fill the file."

7. **Phase D2-1 acceptance tightened** → §5. Now grep-checkable: zero legacy color/shape symbols (`ChatPrimary`, `BubbleShape*`, `CapsuleShape`, `CardShape`, `InputShape`) anywhere in `app/src/main/kotlin/`. The mechanical sweep is named as a separate commit within the D2-1 PR for reviewability.

8. **Tasks table** updated to match new 6-phase structure with grep-able acceptance criteria.

9. **Streaming cursor wired into Phase D2-4 acceptance** → real-device Fraunces verification gate; if baseline alignment fails, swap to Geist `|` and record the swap in this doc.

## What I deliberately did not change

- API surface decision (`MaterialTheme` + one `closePaw` extension). Strong consensus.
- `MaterialTheme.closePaw` accessor name (over `MaterialTheme.tokens`). Codex's choice; brand-bound and unambiguous, accepted in my round 0 review.
- `ClosePawTokens` field set. Smallest data tree that carries the residue.
- Color mapping table. Identical across both individual designs.
- Three-shape Material `Shapes` slot reuse. Identical.
- Render-model cleanup phase (D2-2). This was the load-bearing finding from Codex's draft and remains intact.
- File structure. No new shared component module up front — only `Modifier.foldedPaper()` in `Tokens.kt`.
- Number of motion primitives (10). Each has a real D1 surface.

## What is now genuinely open

Nothing blocking. The `Open Questions` section was rewritten to "None blocking" with pointers to where each former question is now resolved in the body.

## Outstanding items for Codex

- Sanity-check the §2 streaming cursor `inlineContent` sketch. Specifically, does `placeholderVerticalAlign = TextCenter` actually achieve baseline alignment for an I-beam glyph, or do we need `AboveBaseline`/`Bottom` on certain font metrics? My intuition is `TextCenter` is correct for `|`, but a Compose-experienced eye is welcome.
- Verify the `Modifier.foldedPaper()` recipe — particularly that `MaterialTheme.colorScheme.scrim` is the right warm-tinted source. If `scrim` is too dark in dark mode, we may need to derive from `Ink` directly with an alpha. Flag if you disagree.
- Confirm the phase consolidation (6 instead of 7) doesn't lose anything you considered load-bearing. The "centralize motion contract" check is now folded into D2-1's acceptance ("tokens + motion fully populated").
- If anything in the resolved open questions actually was unresolved from your perspective, push back — I treated cross-review consensus as evidence, but you may have private context I missed.