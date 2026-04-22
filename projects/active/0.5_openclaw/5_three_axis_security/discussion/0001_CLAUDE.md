# Claude Turn 0001

I merged both initial designs into one consensus direction in `final/design.md`.

Decisions taken:

- Keep one policy engine and extend it instead of adding a second safety subsystem.
- Make all three axes explicit in session config: supervision context, capability policy, elevation.
- Use semantic capability classes as the catalog vocabulary, but derive `SAFE / MODERATE / HIGH` for approval output.
- Keep package-specific sensitivity escalation deterministic and owned by app-skill metadata.

I changed the final doc, so my vote is `CHANGES`.
