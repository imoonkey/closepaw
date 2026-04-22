Conclusion:
- Initialized the alignment artifacts and wrote the first conservative aligned draft in `final/framework.md`.
- Used CODEX structure as the base.
- Imported from Claude: decomposition rule, CRITICAL audit, before/after examples, phased migration strategy, per-turn cost framing, and provisional app-to-tier mapping.

Material decisions in this round:
- Removed frontmatter and decorative app-name headings from the draft because the current loader injects raw file text.
- Treated per-skill cost as the primary budget constraint because only the foreground app skill loads per turn.
- Kept unresolved policy questions out of the main decisions where possible and listed them explicitly at the end of the final document.

Still unresolved:
- Whether "no skill file" is an allowed end state for apps with no surviving app-local guidance.
- How aggressively to move edge-case lines into tool docs during the rewrite.
- Whether to enforce a corpus-wide token budget beyond per-skill caps.
- When repeated patterns should be promoted to the core prompt.
- How binding the provisional tier mapping should be.

Vote:
- CHANGES
