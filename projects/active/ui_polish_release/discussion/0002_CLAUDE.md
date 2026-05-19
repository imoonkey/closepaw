# 0002 CLAUDE

## Read

- `discussion/0001_CODEX.md`
- `final/design.md` (Codex's first draft)
- All four files under `initial/`

## Review of Codex's First Draft

Strong base. Conservative as advertised, picks land on the right consensus
items (P0-1 and P0-2 are the two genuine release blockers), and Open
Questions correctly absorbs the still-disputed choices instead of silently
resolving them. No findings I disagree with on substance.

Three classes of issue I want to address in this round:

1. **Premature opinionated phrasing** in a couple of P1 items (P1-3's effort
   estimate, P1-1's implementation guidance).
2. **Open questions that already have cross-doc consensus** and should be
   resolved into the body rather than left dangling. Five candidates: touch
   target implementation, chat header structure, inset policy, overlay
   lifecycle scope, local-tab warning tone.
3. **Two missing open questions** that neither of the initial designs
   answered: voice feedback channel choice (P1-7 says "transient UI path"
   without picking toast vs banner vs snackbar), and P1-1 PR slicing (the
   sweep touches 10 file:line refs in 7 files — needs explicit split for
   review tractability).

## Changes to `final/design.md`

Incremental edits only — no restructure.

### Resolutions (these are now in the body, not in Open Questions)

- **Touch-target convention** → added to **Principles**:
  default `Modifier.minimumInteractiveComponentSize()` on click modifiers;
  reserve `Box(48.dp)` wrappers for `TextField` icon slots; do not add a
  new shared button helper unless 3+ call sites need identical chrome.
  Removed the corresponding open question. Cross-referenced from P1-1.
- **Inset convention** → added to **Principles**: `AppWindowInsets` at
  page-level containers; feature code must not reference
  `WindowInsets.statusBars`/`statusBarsPadding()`/`systemBarsPadding()`
  directly; migrate file-by-file as P0/P1 fixes touch each file. Removed
  the corresponding open question. P0-1's fix wording updated to honor
  this convention (no bespoke padding inside `CompleteStepContent`).
- **Chat header** → removed the open question. Already covered by Not In
  Scope.
- **Overlay lifecycle / `OverlayComposeHost` cleanup** → moved from open
  question to Not In Scope. It's robustness/implementation work, not a
  visual polish surface.
- **Local tab warning tone** → captured as new **P2-13** with the
  `LOCAL_TAB_ENABLED = false` gate noted; removed from open questions.

### Tightening

- **P1-3 effort**: S-M → **S**. Both fixes are one-line literals
  (default strength + behavior swap inside `Modifier.paperGrain`). The
  open question (Q2 in the new numbering) is about which of the two
  implementation options to take, not whether either is hard.
- **P1-3 description**: expanded to spell out the actual ordering bug
  inside `Modifier.paperGrain` (`drawContent()` then `drawRect()` at
  lines 49-53) so future readers don't have to re-derive it. Corrected
  the "Settings does the right thing" misreading from Claude's initial
  doc — Settings has the same bug.
- **P1-1 fix wording**: replaced the generic "min-size wrappers …" with
  a direct pointer to the touch-target convention in Principles.
- **P1-1 effort**: M → **M (split across 3-4 PRs by area)** — explicit
  note that this is multi-PR for review tractability.
- **P1-1 acceptance**: added `at 360dp / font scale 1.5` so the bar
  matches P0-1 / P0-2.
- **P0-1 fix wording**: now references the inset convention and
  explicitly bans bespoke inset modifiers in `CompleteStepContent`.
- **QA matrix — Onboarding row**: `large font` → `font scale 1.5`;
  added `(gesture nav + three-button nav)` for the completion screen
  cell.

### Additions

- **P2-13 Local tab warning tone (deferred)** — see above.
- **Not In Scope: overlay lifecycle / token-cleanup work** — see above.
- **Not In Scope: broad refactor sweeps purely for the conventions in
  Principles** — the conventions migrate file-by-file; nobody should
  start a stand-alone refactor PR for them.
- **Open Questions Q4 (user bubble material)** — kept open but
  rewritten to spell out the three concrete options (1dp outline,
  `primaryContainer` token swap, accept current flatness) so it's
  decidable from screenshots without re-deriving.
- **Open Questions Q12 (voice feedback channel)** — new. Required for
  P1-7 to land.
- A "**Resolved during this round**" sub-section under Open Questions
  records the five resolutions inline so the next reviewer can see what
  moved and why.

## What I Deliberately Did NOT Change

- **P0/P1/P2 boundaries** — the triage is right. I considered promoting
  P1-3 (paper grain) closer to a blocker, but the legibility hit is
  measurable, not catastrophic; P1 is correct.
- **P1-1 as a single row** — the sweep is one logical task even though
  it ships as 3-4 PRs; splitting the row obscures that they share an
  acceptance criterion.
- **The QA matrix structure** — already comprehensive; tightening the
  Onboarding row was enough.
- **The Implementation Tasks list** — task 3 (`accessibility-targets`)
  could be split to mirror the P1-1 PR slicing, but Codex's grouping
  reads as a release-track, not a PR list, and that's the right grain
  for this doc.

## Unresolved / For Next Round

Twelve open questions remain. The ones most useful for Codex (or the
master user) to decide before implementation starts:

- **Q2 (paper grain implementation)** — modifier-level fix vs call-site
  background sibling. Affects whether `Modifier.paperGrain` semantics
  change.
- **Q4 (user bubble material)** — needs screenshot comparison; can be
  punted to the QA pass but blocks any "definitely ship as-is" claim.
- **Q5 + Q7 (App Access memory failure surface)** — both are about the
  same UI choice; resolving one resolves the other.
- **Q12 (voice feedback channel)** — blocks P1-7 from being a single
  PR.

The rest are validation-dependent (screenshots, contrast snapshots) and
naturally resolve during QA.

## Vote

CLAUDE=CHANGES. Handoff to CODEX. The changes are conservative
refinements within Codex's structure; no restructure, no scope change.
Codex should be able to either APPROVE or push back on the specific
resolutions I made (touch-target convention, inset convention, chat
header, overlay lifecycle, local tab) plus the new P2-13 and the two
new open questions.
