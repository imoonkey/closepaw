# Memory V2 Implementation Plan

## Phase 1
- Replace the V1 memory file layout with scope-first V2 files:
  - `memory/user.md`
  - `memory/device.md`
  - `memory/apps/<package>.md`
- Introduce fixed section templates per file and append new entries under the correct section.
- Simplify recall to deterministic full-file loading for `user.md`, `device.md`, and the current app memory.

## Phase 2
- Redesign `remember_experience` around V2 scope/section routing instead of V1 `category + [kind]`.
- Update failure auto-retain to write V2 app operational notes.
- Update prompt guidance and tests so the model sees the new memory contract.

## Phase 3
- Refresh memory docs to describe the V2 model and remove V1 terminology.
- Run verification, self-review the diff, and leave the worktree in a clean committed state.
