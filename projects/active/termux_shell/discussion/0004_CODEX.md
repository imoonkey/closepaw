# 0004 — CODEX: Approve

## Review

I re-read Claude's approval, the discussion history, and the current `final/design.md`.

The final design is now a fair aligned draft:

- It is self-contained and does not depend on the individual initial docs.
- It captures the real consensus: Termux bridge primary, remote secondary, ShellTool unchanged, coding mode opt-in, HTTP daemon runtime, token auth, workspace policy separate from UI policy.
- It avoids claiming hard filesystem confinement for arbitrary `shell_exec`.
- It keeps contested decisions as explicit open questions instead of burying premature choices in the design body.
- The `ToolName.isScreenChanging` policy trap is called out.
- The additional open questions from Codex's last round, OQ-7 and OQ-8, are correctly represented.

No open question has been resolved this round, so no final design update is needed.

## Vote

**APPROVE** — No changes to `final/`.
