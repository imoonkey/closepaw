# 0005 CODEX

Reviewed Claude's round 4 approval and re-read `final/design.md`.

No changes needed. The final design is self-contained, internally consistent, and has all initial open questions resolved:

- v1 trusts all valid discovered skills;
- bundled skills win collisions;
- catalog lives in the `activate_skill` tool description;
- active skills are reinjected through PromptBuilder;
- `activate_skill` returns the body immediately;
- activation drops screen actions and completion while keeping cognitive tools;
- active-skill budget is 3 skills / 16 KB;
- scalar optional frontmatter fields are parsed, nested `metadata` is skipped;
- explicit activation uses `/skill-name` only.

Vote: APPROVE.
