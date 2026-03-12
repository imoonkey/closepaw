# R36 App Skill Generalization

## Problem

App skills were tuned across R10-R35 to maximize eval success rate. This produced task-specific language that overfits to eval patterns — e.g., describing exact page layouts, solver algorithms, or eval-specific data patterns (like "perturbed groups" or "decoy entries"). These skills help the agent pass benchmarks but would confuse or mislead a real user's agent.

A well-generalized app skill should:
- Describe **app behavior and UI patterns** a real user would encounter
- Teach **interaction mechanics** (how to navigate, how to delete, where to find things)
- Warn about **platform quirks** (accessibility tree gaps, hidden scrollable areas, first-run dialogs)
- NOT prescribe **solver algorithms** for specific task shapes
- NOT reference **eval-specific data patterns** (decoys, perturbed groups, target colors)

## Approach

1. Audit all app skills for task-specific language (not just the 4 below — the loop should check others too if issues surface)
2. Remove or rewrite overfitted sections into general guidance
3. Keep delete/navigation mechanics as one-liners where useful
4. Run impacted tasks to measure regression
5. For failures, add only **general** guidance — "what would help a real user doing something similar?"

## Starting Point: 4 Known Overfitted Skills

### 1. `com.android.chrome/SKILL.md`

**Keep:**
- "Opening HTML Files from Files App" — general, real users do this
- "Chrome First-Run Dismissal" — general, always useful

**Remove:**
- "Drawing Tasks (Canvas Pages)" — describes a specific eval page layout (target colors, palette, Submit button)
- "Maze Tasks" — describes a specific eval page (grid maze, X, directional buttons, $)

### 2. `code.name.monkey.retromusic/SKILL.md`

**Keep:**
- "Adding Songs to Playlists" — general per-song and multi-select methods
- "Navigation" — general tab layout

**Remove:**
- "Duration-Constrained Playlists" — prescribes a solver algorithm (add all, check total, prune) for RetroPlaylistDuration

### 3. `com.arduia.expense/SKILL.md`

**Keep:**
- "Adding Expenses" — general field-filling guidance
- "Category Selection" — general horizontal scroll awareness

**Remove:**
- "Finding and Deleting Duplicate Expenses" — eval jargon ("perturbed group", "decoys", 40+ item scroll)

**Consider adding:** one-liner on delete mechanics (swipe left or long-press → Delete)

### 4. `com.flauschcode.broccoli/SKILL.md`

**Keep:**
- "Adding Multiple Recipes" — general FAB + navigate-back workflow
- "Filling Recipe Fields" — general field mapping guidance
- "Scrollable UI Elements" — general

**Remove:**
- "Deleting Duplicate Recipes" — eval-specific 7-field comparison algorithm, "same title but different hidden fields" pattern

**Consider adding:** one-liner on delete mechanics (open → 3-dot → Delete)

## Beyond These 4

The loop should also watch for:
- Other skills that become newly overfitted if general fixes inadvertently add task-specific workarounds
- Skills that seem general but contain implicitly task-specific phrasing (e.g., very specific scroll counts, field enumeration that only matches eval data)
- Opportunities to consolidate cross-app patterns (e.g., "scroll before concluding X is missing" appears in multiple skills)
