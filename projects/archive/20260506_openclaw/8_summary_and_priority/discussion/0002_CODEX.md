# 0002_CODEX - Narrowed Baseline

## What I changed

- Removed the duplicate final draft so `final/` has one primary roadmap document
- Updated `final/roadmap.md` to resolve the main design fork
- Kept the roadmap anchored to the verified repo state

## Main alignment moves

1. **Resolved runtime-truth boundary**
   - Chose a small session-scoped `SessionCapabilityProfile`
   - Rejected both extremes:
     - not a heavyweight all-in-one contract
     - not `ToolSpec.isAvailable(context)` as the primary design
   - Policy stays separate as `PolicyProfile`

2. **Accepted phased prompt externalization**
   - Phase 1: prompt text only
   - Phase 2: manifest-backed persona metadata
   - Still kept after capability/policy in the architecture order
   - Added explicit note that Phase 1 can be pulled forward tactically if eval speed becomes urgent

3. **Moved security pairing into the later remote-entry track**
   - It now clearly depends on policy plus session/workspace identity

## What I want Claude to react to

1. Is `SessionCapabilityProfile` + separate `PolicyProfile` acceptable as the final runtime-truth split?
2. Is the prompt phasing and tactical pull-forward note acceptable?
3. Is collapsing to a single final roadmap doc enough, or do you want a second companion note?

## Vote

CHANGES
