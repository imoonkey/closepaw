# R36 Generalization Plan

## Objective

Remove eval-task-specific language from 4 app skills. Keep only guidance that would help a real user of each app.

## Per-Skill Plan

### 1. `com.android.chrome/SKILL.md`

**Keep:**
- "Opening HTML Files from Files App" (lines 8-11) — general, real users do this
- "Chrome First-Run Dismissal" (lines 14-19) — general, always useful

**Remove entirely:**
- "Drawing Tasks (Canvas Pages)" (lines 21-29) — describes a specific eval page layout (target colors, palette, Submit button). No real user would need this.
- "Maze Tasks" (lines 31-37) — describes a specific eval page (grid maze, X, directional buttons, $). No real user would need this.

**Result:** Short skill with file-opening and first-run guidance only.

### 2. `code.name.monkey.retromusic/SKILL.md`

**Keep:**
- "Adding Songs to Playlists" (lines 8-11) — general, covers per-song and multi-select methods
- "Navigation" (lines 13-15) — general

**Remove entirely:**
- "Duration-Constrained Playlists" (lines 17-23) — describes a specific eval strategy ("add ALL songs", "remove one at a time"). This is a solver algorithm for RetroPlaylistDuration, not general playlist usage.

**Result:** Skill covers how to add songs and navigate, without prescribing a specific playlist-building algorithm.

### 3. `com.arduia.expense/SKILL.md`

**Keep:**
- "Adding Expenses" (lines 8-10) — general
- "Category Selection" (lines 12-15) — general, real users need to know about horizontal scrolling

**Remove entirely:**
- "Finding and Deleting Duplicate Expenses" (lines 17-25) — describes the exact eval task pattern (perturbed groups, decoys, same-name entries with different amounts). This is eval jargon. A real user would never encounter "perturbed groups".

**Optional general replacement:** Could add a brief "Deleting Expenses" section with just the delete mechanics (swipe left / long-press → Delete) without the duplicate-finding algorithm.

### 4. `com.flauschcode.broccoli/SKILL.md`

**Keep:**
- "Adding Multiple Recipes" (lines 8-11) — general
- "Filling Recipe Fields" (lines 13-15) — general
- "Scrollable UI Elements" (line 17-18) — general

**Remove entirely:**
- "Deleting Duplicate Recipes" (lines 20-31) — describes the exact eval task pattern (same-titled recipes with hidden field differences, 7-field comparison, scratchpad tracking). This is eval-specific.

**Optional general replacement:** Could add a brief "Deleting Recipes" section with just the delete mechanics (open → 3-dot menu → Delete) without the duplicate-detection algorithm.

## Principle

When removing, ask: "Would a real human user of this app benefit from this guidance?" If the answer is only "an eval task runner", remove it.

For delete mechanics (how to delete an item), keep a one-liner — that's genuinely useful. For detection algorithms (how to find duplicates among decoys), remove — that's eval-specific.
