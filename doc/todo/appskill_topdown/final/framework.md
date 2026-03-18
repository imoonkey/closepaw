# Unified App Skill Rewrite Framework

## Goal

Rewrite all 17 Android agent app skills into a unified, top-down format that:

- keeps only app-local guidance,
- removes solver logic and eval overfit,
- stays compact enough to load every foreground turn,
- preserves the app truths that prevent wrong navigation, false reads, and destructive mistakes.

The target is better generalization without silent regressions.

## Scope

This framework covers the content and shape of `app/src/main/assets/app_skills/<package>/SKILL.md`.

It does not directly change:

- the core prompt,
- tool descriptions,
- the app-skill injection mechanism,
- eval definitions.

Those may be touched later if the rewrite exposes repeated cross-app patterns, but this framework is for rewriting the app skills themselves.

## Runtime Facts That Constrain The Design

The current runtime matters here.

- `AppSkillRepository` loads each app skill as raw text.
- `TurnPlanningPhaseRunner` already wraps that text with `## App Skill` and the package name.
- The loader does not parse YAML frontmatter or strip markdown headings.
- Only the current foreground app skill is injected on a given turn.

Consequences:

- Decorative frontmatter and decorative app-name headings consume prompt tokens and should be removed.
- The real prompt-cost constraint is per-skill cost, not the sum of all 17 skills on one turn.
- Every remaining line in the skill body must earn repeated use.

## Core Principle

App skills should encode the app's data model and interaction model: the app-local "what", "where", and "what goes wrong here". They should not encode the agent's general reasoning strategy for solving a task.

A useful litmus test:

- Keep lines that read like compact operator notes for a grounded assistant working in this app.
- Remove lines that read like instructions for how to solve a benchmark task shape.

## Content Ownership

Every candidate line should be classified before rewriting.

### Keep in the app skill

- Hidden data locations:
  where the real source of truth lives when the obvious screen is misleading or incomplete.
- Canonical navigation mechanics:
  the non-obvious route to common actions or authoritative screens.
- Accessibility gaps:
  missing cells, hidden scroll axes, misleading rows, unlabeled controls.
- Silent failure modes:
  flows that look correct but fail without obvious feedback.
- Interaction pitfalls:
  controls that do something different from what they appear to do.
- Platform quirks that matter in this app:
  first-run prompts, extension fields, picker behavior, shell restrictions from the agent process.
- App terminology mapping:
  control order, label semantics, field meaning.
- App-specific verification anchors:
  only when generic verification is not enough because this app hides the decisive state somewhere special.

### Remove from the app skill

- Solver algorithms:
  counting procedures, dedupe strategies, comparison workflows, batching strategies.
- Scratchpad format prescriptions:
  `unchecked:`, `checked:`, `unique:` and similar note schemas.
- Hardcoded counts, thresholds, and retry budgets.
- Eval fixture assumptions:
  decoys, perturbed groups, special data distributions, benchmark-only layouts.
- Turn-optimization language:
  "save turns", "do this quickly", "without visiting X", unless the statement is really an app fact.
- Generic rules already covered in the core prompt:
  exact filename discipline, generic scan-before-count behavior, generic date-range computation, generic verification rules.

### Move out of the app skill when needed

- Generic agent behavior belongs in the core prompt.
- Tool-usage mechanics belong in tool docs when the line is really about tool semantics rather than app semantics.

The rewrite should default to delete duplicated generic lines first. Only promote something upward if the same missing rule clearly reappears across multiple rewritten skills.

## Decomposition Rule

When a current skill mixes app knowledge with solver procedure, extract the app fact and discard the procedure.

| Before (mixed) | After (fact only) |
|---|---|
| "For counting by activity type: scroll list, write all tracks to scratchpad using compact format, open Edit for every track..." | "Track names do not indicate activity type. Activity type is only in Edit -> Activity type." |
| "To check completion: open detail, scroll down. If Completion is present, the task is completed..." | Keep the hidden-state fact: "Completion status is only visible in task detail metadata." |
| "Add 8-10 songs, then check total, then remove the last-added song if needed..." | "Playlist detail shows total duration. Add songs from Songs tab, not from the playlist screen." |

The boundary is simple:

- Navigation to find authoritative data is app knowledge.
- Strategy for processing many items is solver logic.

## Top-Down Structure

Do not force a template onto every app. Structure should scale with complexity.

Order the skill by failure cost:

1. the rule that prevents the worst mistake,
2. the next-most important hidden truth,
3. lower-risk mechanics and fallbacks.

Preferred section order, only when earned:

1. `CRITICAL`
2. Canonical route or source of truth
3. Pitfalls and fallbacks
4. Stable operation notes
5. App-specific verification anchor

If a section would contain only a weak single line, merge it into surrounding bullets instead of adding a header.

## Format Rules

- Use plain text with short bullets.
- Remove YAML frontmatter.
- Remove decorative app-name headings.
- Use headers only when they save more confusion than they cost in tokens.
- Prefer one-line bullets.
- Use numbered steps only when the sequence itself is the app truth.
- Count every line in the final raw skill body as budget-relevant, including headers and blank lines.
- Keep blank lines sparse.

## Structural Tiers

These tiers are targets, not identity labels. A skill can move tiers during rewrite if its content shrinks or expands.

### Tier 1: Minimal

- Target: 2 to 5 lines
- Use for apps with one or two strong quirks
- Usually no headers

Example:

```md
- Open images full-screen before reading text or details.
- Verify the filename in the title bar after opening.
- Find the target file by filename, not by thumbnail appearance.
```

### Tier 2: Standard

- Target: 6 to 12 lines
- Use for most apps
- Up to 2 short headers if they clearly compress understanding

Example:

```md
## Search
- Use Address tab for structured lookup.
- If Address fails, use coordinate search.

## Marker
- Tap the map pin to open the action sheet.
- Swipe the action row to reach Marker.
```

### Tier 3: Complex

- Target: 13 to 18 lines
- Hard cap: 20 lines
- Use only for apps with genuine hidden state, silent failure modes, or multiple strong traps
- At most one `CRITICAL` block

Example:

```md
## CRITICAL — Completion state is hidden
- Completion status is only visible in task detail metadata.
- Open the detail view and inspect the Completion field before relying on list impressions.

## Dates
- The right-side standalone label is the due date.
- Relative labels in the list are ambiguous across weeks.
```

## CRITICAL Protocol

`CRITICAL` is the strongest emphasis mechanism in this layer. Overuse will dilute it.

Use a top `CRITICAL` block only when all of these are true:

1. The obvious/default path commonly causes a wrong branch, destructive action, or false answer.
2. The correct alternative is not obvious from the current screen alone.
3. The rule is app-specific, not generic agent discipline.
4. The rule applies across multiple tasks in that app, not just one eval scenario.
5. The rule can be stated in 1 to 3 short bullets or an equivalently short note.

Do not use `CRITICAL` for:

- generic reminders to scroll or verify,
- solver procedures such as "open every candidate",
- benchmark tuning such as batch sizes,
- multiple unrelated warnings in one skill.

One skill should have at most one `CRITICAL` block.

### Initial Audit Of Current CRITICAL Blocks

This is a starting audit for the rewrite, not an irrevocable ruling.

| Skill | Current CRITICAL | Initial verdict | Reason |
|---|---|---|---|
| OsmAnd | Use Address tab for search | Keep | General search can silently geocode wrong; Address is the non-obvious safe path. |
| VLC | Use library tabs, not Browse | Keep | Browse silently breaks playlist-selection workflows. |
| Tasks.org | Completion-status procedure | Keep, but trim to the hidden-state fact | The important app fact is where completion status lives. The counting procedure should be removed. |
| Markor | Scroll full list before selecting | Demote to regular bullet | This reads as a generic scan rule, not a true app-specific CRITICAL. |
| RetroMusic | Playlist efficiency procedure | Remove | This is solver tuning, not app-local truth. |

Verdict definitions: "Keep" retains the `CRITICAL` block. "Demote" moves the content to a regular bullet in the skill body — the app fact is preserved, only the emphasis level changes. "Remove" deletes the content entirely.

## Shared Patterns Policy

### Keep in the core prompt

- general grounding and verification rules,
- generic scan-before-count behavior,
- generic date-range computation,
- generic file-operation verification,
- generic scratchpad usage.

### Keep in the app skill

- anything that depends on this app's UI semantics,
- anything that names the authoritative screen, field, tab, or control,
- anything caused by this app's accessibility tree or interaction model.

A line that echoes a generic core-prompt rule still earns its place when the app's specific behavior deviates from the norm: an unusual scroll axis (horizontal category row), a coordinate-based trap at a specific screen region (hamburger overlay at left edge), or a platform command that silently fails only in this app's context (shell `svc wifi` from the agent process). The test is whether removing the line leaves the agent unable to predict the app-specific deviation.

### Consider tool docs

- lines that are really about using a tool correctly rather than about the app itself.

### Escalation rule

If the rewrite reveals the same missing non-app-specific rule in 3 or more skills, review whether it belongs in the core prompt or tool docs instead of repeating it across app skills.

## Token Budget

Per-skill cost is the primary operational budget because only one app skill loads on a turn.

Recommended budgets:

- Tier 1: 2 to 5 lines
- Tier 2: 6 to 12 lines
- Tier 3: 13 to 18 lines
- Absolute max: 20 lines

Secondary cross-check:

- The total corpus should trend downward after rewrite.
- A total corpus target may be useful for sanity checking, but it is less important than keeping each foreground skill compact.

Budget rules:

- Prefer folding verification into an existing bullet rather than adding a section.
- Any skill above 12 lines should justify the extra lines as app-local and cross-task useful.
- If a skill cannot fit within 20 lines, first delete generic content and solver logic before asking for an exception.

## Provisional App-To-Tier Mapping

This is a starting expectation for rewrite planning, not a locked classification.

### Tier 1 candidates

- Audio Recorder
- Documents UI

### Tier 2 candidates

- Chrome
- Google Files
- Android Settings
- Google Photos
- Simple Gallery
- Google Calendar
- Pro Expense

### Tier 3 candidates

- Tasks.org
- OpenTracks
- RetroMusic
- VLC
- OsmAnd
- Markor
- Simple Calendar Pro
- Broccoli

## Before/After Examples

### Example A: OpenTracks

Before:

```md
## Counting/Summing by Activity Type
For any count or sum by activity type:
1. Scroll the entire track list end-to-end.
2. Write all tracks to scratchpad using unchecked/checked lists.
3. Open Edit for every track.
4. Match activity type exactly.
```

After:

```md
## CRITICAL — Activity type is hidden
- Track names do not indicate activity type.
- The authoritative type is only in track -> 3-dot menu -> Edit -> Activity type.

## Track data
- Stats view shows distance and time details.
- Relative date labels in the list must be resolved against the device date.
```

What changed:

- Removed the checklist algorithm and scratchpad schema.
- Kept the hidden source of truth and the navigation path to it.

### Example B: RetroMusic

Before:

```md
## CRITICAL — Playlist Efficiency
- Never open song Details to check durations.
- Add 8-10 songs before checking total.
- Remove the last-added song if total is too high.
- If multi-select fails, switch immediately.
```

After:

```md
- Playlist detail shows total duration.
- "Add to playlist" inside a playlist adds the playlist to another playlist, not songs into it.
- If multi-select does not respond, use the per-song 3-dot menu fallback.

## Navigation
- Create playlists from Playlists tab.
- Add songs from Songs tab.
```

What changed:

- Removed batch tuning and adjustment heuristics.
- Kept the hidden workflow trap and the fallback interaction mechanic.

### Example C: Audio Recorder

Before:

```md
## Recording and Naming a File
1. Tap record, wait, tap stop.
2. Recording appears with an auto-generated name.
3. Rename from 3-dot menu or long-press.
4. Confirm and verify.
```

After:

```md
- Rename a recording from its 3-dot menu or by long-pressing it.
```

What changed:

- Removed obvious recording procedure.
- Kept the non-obvious rename mechanic.

## Rewrite Workflow

Apply the same workflow to every skill.

1. Start from the existing file.
2. Remove decorative text that the runtime does not use.
3. Label each remaining line as `core`, `tool`, `app`, or `overfit`.
4. Delete all `overfit` lines.
5. Delete `core` duplicates.
6. Move true tool-mechanics lines out only when the app-specific part is not carrying the line.
7. Rewrite the surviving `app` lines into the shortest general form.
8. Order the remaining lines top-down by failure cost.
9. Add `CRITICAL` only if the rule meets the protocol above.
10. Cut again until the skill fits its tier budget.

## Review Checklist

Before accepting a rewritten skill, ask:

- Would this help with a different real-user task in the same app?
- Does any line describe how to solve a benchmark instead of how the app works?
- Could this line be said without mentioning the app? If yes, it probably belongs elsewhere or should be deleted.
- Is the first line the most important app truth?
- Is the skill as short as it can be without losing a real app constraint?

## Migration Strategy

Use a phased rollout so regressions can be localized.

Phase assignment is based on the expected volume and risk of skill content changes, not on the general fragility of the app's eval tasks:

- Phase 1: Skills already close to framework-compliant. Minimal editing needed.
- Phase 2: Skills with clear solver content to remove but no load-bearing CRITICALs.
- Phase 3: Skills with load-bearing CRITICALs that need careful trimming, or where solver removal touches content adjacent to fragile app facts.

### Phase 1: Low risk

- Audio Recorder
- Documents UI
- Chrome
- Google Files
- Android Settings
- Google Photos
- Simple Gallery
- Google Calendar

Goal:

- establish the rewrite pattern,
- confirm that simpler skills can shrink without regressions.

### Phase 2: Moderate risk

- RetroMusic
- Broccoli
- Simple Calendar Pro
- Pro Expense

Goal:

- remove obvious solver leakage,
- recover only the app facts that were accidentally embedded inside procedures.

### Phase 3: High risk

- Tasks.org
- OpenTracks
- OsmAnd
- VLC
- Markor

Goal:

- protect the load-bearing hidden-state and silent-failure rules,
- verify that trimmed CRITICAL content still carries enough force.

### Per-phase process

1. Rewrite the selected skills.
2. Build and install the app.
3. Run evals for affected tasks.
4. Compare task-level results against the last stable round.
5. If regressions appear, separate "lost app fact" from "lost solver crutch".
6. Restore only lost app facts.
7. If the regression is purely from removing solver overfit, accept it unless evidence shows a real-user regression.

## Trade-Offs

- Some benchmark-specific wins may disappear. That is acceptable if they depended on solver overfit.
- A stricter ownership split shifts responsibility back to the core prompt for generic behavior, which is the right place for it.
- A hard 20-line cap forces prioritization and may make some formerly detailed skills feel sparse. That pressure is intentional.
- The migration plan adds process overhead, but it is cheaper than rewriting all 17 at once and losing attribution when regressions appear.

## Open Questions

1. Should an app with effectively zero surviving app-local guidance keep a minimal skill file, or is "no skill file" an allowed end state? The current task is to rewrite all 17 skills, so the conservative default is to keep files during the first rewrite wave.

2. How aggressively should the rewrite use the "tool docs" layer? There is agreement that some lines are really tool mechanics, but it is still unresolved whether this rewrite should actively move such lines or only note the boundary and defer those moves.

3. If a repeated pattern shows up across multiple rewritten skills, should promotion into the core prompt happen during the rewrite campaign or only after Phase 1 evidence confirms the pattern is stable? The need for promotion criteria is agreed; the timing is not yet settled.
