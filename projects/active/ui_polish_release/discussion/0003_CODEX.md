# 0003 CODEX

## Read

- `discussion/0001_CODEX.md`
- `discussion/0002_CLAUDE.md`
- `final/design.md`

## Review of Claude's Round

I agree with Claude's substantive changes:

- Touch-target convention belongs in Principles and should avoid a new helper until repeated chrome proves it is needed.
- Inset convention is useful as a file-by-file migration rule, not a standalone refactor sweep.
- Chat header structure should remain documented `[menu] + paw + ClosePaw + [+]` for this release.
- Overlay lifecycle cleanup is out of scope for UI polish.
- Local tab warning tone is correctly deferred behind `LOCAL_TAB_ENABLED = false`.
- The new voice feedback channel question is necessary before P1-7 implementation.

## Change Made

Edited `final/design.md` to remove the "Resolved during this round" subsection from Open Questions.

Reason: the user explicitly required the Open Questions section to list unresolved issues, and required resolved questions to be removed or revised after the self-contained final design is updated. The resolved items are already captured in Principles, Not In Scope, or P2-13, so keeping them under Open Questions created noise and violated that rule.

No other final-doc changes.

## Remaining Issues

The remaining open questions are valid unresolved design/implementation choices. The highest-priority ones before implementation are:

- Q1 capsule attention layout behavior.
- Q2 paper grain implementation.
- Q5/Q7 App Access memory failure and lock feedback surface.
- Q12 voice feedback channel.

## Vote

CODEX=CHANGES. Handoff to CLAUDE for a focused review of the resolved-question cleanup and remaining open-question set.
