# Round 0003 — CODEX

## Vote: CHANGES

Made a conservative cleanup pass on `final/design_aligned.md`. Resetting CLAUDE vote to PENDING.

## What I changed

1. **Re-opened the one issue that is still genuinely unresolved.**
   - Restored an `Open Questions` item for the streaming cursor verification.
   - Kept the agreed surface (`inlineContent` inside the text layout), but left the exact `placeholderVerticalAlign` and Fraunces-vs-Geist fallback as unresolved until Phase D2-4 device verification.

2. **Pulled back from over-specific cursor styling.**
   - Replaced "`streaming cursor uses ClosePawTokens.serifItalic`" with the more conservative "`Fraunces-backed local style`".
   - This keeps the architecture settled without pretending the exact style token is already locked.

3. **Pulled back from over-specific folded-paper shadow sourcing.**
   - Kept the agreed primitive form (`Modifier.foldedPaper()`).
   - Removed the hardcoded `colorScheme.scrim` choice from the sketch and replaced it with a generic ink-derived warm shadow color from theme.
   - The primitive is resolved; the exact theme source stays implementation-level.

4. **Softened the alias decision into the actual consensus.**
   - Direct sweep remains the default.
   - A very short-lived alias bridge is permitted only if implementation proves the Phase D2-1 PR unreadable without it, and it must be deleted before D2-1 closes.
   - This keeps KISS strict without baking in an unnecessarily rigid implementation tactic.

## Why I changed it

Claude's round improved the draft substantially, but it resolved a few points more aggressively than the evidence supported. The user explicitly asked for a conservative aligned draft that keeps unresolved issues visible. The current version now does that while preserving the consensus architecture.

## Current state

- Architecture remains settled:
  - `MaterialTheme` + one thin `closePaw` token accessor
  - one motion object
  - no shared component module
  - semantic model / renderer cleanup stays in scope
  - settings/drawer/onboarding rollout stays in scope
- Remaining open question:
  - streaming cursor real-device verification details
