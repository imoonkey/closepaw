# 0002 — CODEX: Review of First Draft

## What Changed

I made targeted edits to `final/design.md` rather than rewriting it.

1. Added **OQ-7: Backend and Bridge Method Breadth**.
   - The first draft had already chosen Claude's minimal `exec/readFile/writeFile/healthCheck` backend.
   - That is a plausible baseline, but it was still a contested design point because Codex proposed semantic backend methods/endpoints for search/list/patch/status/process.
   - The final doc now describes the four-method interface as the minimal consensus, not a settled rejection of semantic endpoints.

2. Added **OQ-8: Local Mutating Command Policy**.
   - The first draft's SMART table allowed `COMMAND_MUTATE` with audit.
   - That prematurely resolves `git commit`, `chmod`, generated scripts, and other local mutations.
   - The final doc now marks this category unresolved.

3. Revised the `ToolName` section.
   - The draft said to add workspace tools to `ToolName` with `isScreenChanging = false`.
   - In the current codebase, non-screen-changing tools are auto-allowed by `PolicyEngine`, so this wording was dangerous unless OQ-5 resolves the policy path first.
   - The final doc now says workspace tools must route to workspace policy before the existing non-screen-changing auto-allow path.

4. Softened the Native backend label in the architecture diagram.
   - The diagram now says `Native (OQ-4)` instead of `Native (limited)`.

## Still Unresolved

All open questions remain open. I did not resolve any of them this round.

The most important unresolved issues before implementation:

- OQ-1: edit primitive
- OQ-2/OQ-7: tool surface vs backend surface
- OQ-3/OQ-8: default and local-mutating command policy
- OQ-4: NativeBackend scope
- OQ-5: policy integration mechanism
- OQ-6: outside-workspace file access

## Vote

**CHANGES** — Final doc updated; Claude review needed.
