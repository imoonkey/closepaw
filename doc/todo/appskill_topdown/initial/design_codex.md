# Unified Top-Down App Skill Framework

## Goal

Rewrite all 17 app skills into short, app-local operating notes that help with real user tasks across the app, not benchmark-shaped procedures. The framework should reduce token cost, remove solver logic, and preserve the app truths that prevent wrong navigation, false reads, and destructive mistakes.

## Problem

Current skills mix three different layers:

1. Core agent behavior already covered by the system prompt.
2. Real app-specific knowledge that belongs in the foreground app skill.
3. Eval-specific solver procedures that do not generalize.

That mixing causes bloat, inconsistent shape, and fragile gains. The rewrite should separate those layers cleanly.

## Design Principles

- App skill = app-local delta from the core prompt. If a line makes sense in many apps, it probably does not belong in the skill.
- Prefer stable app truths over task procedures. Keep where to go, what field is authoritative, what control lies, what gesture works, what UI trap exists.
- Order top-down. Put the single most failure-preventing app fact first, then the next-most important, then lower-risk mechanics.
- Do not force structure. A simple app can be 3 lines. A complex app can use a short `CRITICAL` block plus a few sections.
- Token budget is a product constraint, not a suggestion. Every line must earn repeated use across many tasks in that app.
- Generalization wins over benchmark rescue. If the only way to save an eval is app-skill overfit, accept the regression.

## Observations That Shape This Design

- The current 17 skills range from 11 to 31 lines, with the longest files carrying the most solver logic.
- `StandaloneAgentDef.kt` already covers generic rules such as exact outcome verification, date-range computation for relative dates, scanning lists before counting, scratchpad use, and exact filename checks.
- `AppSkillRepository.kt` injects raw file text. It does not parse YAML frontmatter or markdown headings.
- `TurnPlanningPhaseRunner.kt` already wraps the loaded file with `## App Skill` and the package name.

This means decorative frontmatter and `# App Name Skill` titles cost prompt tokens without adding runtime structure.

## Framework

### 1. Content Ownership

Use this decision flow for every candidate line:

1. If the line is already in the core prompt, remove it from the app skill.
2. If the line is generic tool usage rather than app knowledge, move it to tool docs or drop it.
3. If the line encodes a solver algorithm, scratchpad schema, hardcoded threshold, or eval fixture pattern, remove it.
4. If the line describes an app-local UI truth, navigation rule, source of truth, accessibility gap, or interaction trap, keep it.
5. If the kept line is both app-local and high-risk if missed, place it in `CRITICAL`.

### 2. Top-Down Structure

The body should be ordered by failure cost, not by feature taxonomy.

Preferred section order, only when earned:

1. `CRITICAL`:
One app-specific rule or cluster of rules that prevents the most common serious failure.

2. Canonical route or source of truth:
Where to navigate, or which screen/field is authoritative.

3. Pitfalls and fallbacks:
Accessibility gaps, misleading controls, accidental side drawers, unusable search paths, shell restrictions, gesture fallbacks.

4. Common operation notes:
Only for stable cross-task workflows in that app, such as create, delete, edit, or search.

5. App-specific verification anchor:
Only when the app needs a special verification step beyond the generic core prompt.

Do not add a section if it has only one weak sentence that can fit naturally elsewhere.

### 3. Format Rules

- Prefer plain body text with short bullets.
- Do not keep YAML frontmatter in rewritten app skills unless some future tooling actually parses it.
- Do not keep decorative `# App Skill` headings. The runtime prompt already adds the app-skill wrapper and package.
- Use headers only when they compress understanding more than they cost in tokens.
- Prefer one-line bullets over numbered procedures unless sequence is itself the app truth.

## Structural Tiers

### Tier 1: Minimal

Use for apps with one or two important quirks. Target 3 to 6 lines. No headers unless one `CRITICAL` line is truly necessary.

Example shape:

```md
- Open images full-screen before reading details.
- Verify the filename in the title bar after opening.
- Scroll by filename, not thumbnail appearance.
```

### Tier 2: Standard

Use for most apps. Target 7 to 12 lines. Up to 2 short headers.

Example shape:

```md
## Search
- Use Address tab for structured lookup.
- Use coordinate search if address lookup fails.

## Marker
- Tap the map pin to open the action sheet.
- Swipe the action row to reach Marker.
```

### Tier 3: Complex

Use only for apps with a genuine hidden-state problem or multiple strong traps. Target 13 to 18 lines. Hard cap 20 lines. One `CRITICAL` block max, then 2 to 3 short sections.

Example shape:

```md
## CRITICAL — Completion state is only visible in task detail
- Open the task detail and scroll to the metadata section.
- Use the Completion field as the source of truth for completed vs incomplete.

## Dates
- The right-side label is the due date.
- Relative labels in the list are ambiguous across weeks.
```

## What Belongs In App Skills

Keep lines like these:

- Canonical navigation paths that a user would need repeatedly.
- Authoritative fields when the obvious screen lies or omits state.
- App-specific accessibility gaps, such as unlabeled month cells or hidden scrollable rows.
- Interaction traps, such as a playlist action that does something different from its label, or a left-edge click opening a drawer.
- Stable fallback mechanics for that app, such as a gesture that works when the visible button often fails.
- App-specific verification when generic verification is insufficient.

## What Must Not Belong In App Skills

Remove lines like these:

- Solver algorithms for counting, deduping, batching, or comparing.
- Scratchpad format prescriptions such as `unchecked:` or `unique:`.
- Hardcoded counts, thresholds, ranges, or retry budgets.
- Eval fixture assumptions, decoy patterns, or benchmark-specific data descriptions.
- Turn-optimization language such as "save turns" or "do this quickly".
- Generic guidance already covered in the core prompt, such as exact filename matching, relative date-range computation, generic scan-before-count behavior, or generic completion verification.

## Shared Patterns: Core Prompt vs App Skill

The practical rule is simple: delete duplicates from app skills first, then move guidance upward only if it is truly cross-app and missing from the core layer.

### Keep in the core prompt

- General grounding and verification rules.
- Generic file-operation checks.
- Generic list-scanning and date-resolution rules.
- Generic scratchpad usage.

The current core prompt already covers much of this, so many repeated app-skill lines should simply disappear instead of moving.

### Keep in app skills

- Anything that depends on this app's UI semantics.
- Anything that names the authoritative screen, field, tab, or control for this app.
- Anything that exists because this app's accessibility tree is misleading or incomplete.

### Consider tool docs instead

- Guidance that is really about how to use `mobile_action`, `shell`, `open_app`, or other tools, rather than about the app.

### Escalation rule

If the same non-app-specific rule still appears necessary in 3 or more rewritten skills, review whether it belongs in the core prompt or tool docs. Do not preemptively centralize semi-generic advice.

## `CRITICAL` Usage

`CRITICAL` is the strongest prompt lever in this layer, so it should be scarce and high-signal.

Use a top `CRITICAL` block only when all of these are true:

1. Missing the rule commonly causes a wrong branch, destructive action, or false answer.
2. The rule is specific to this app, not generic agent discipline.
3. The rule applies across multiple tasks in the app, not one eval scenario.
4. The rule can be stated in 1 to 3 bullets or a very short numbered flow.

Do not use `CRITICAL` for:

- Generic reminders to verify or scroll.
- Solver strategies such as "open every candidate".
- Benchmark tuning like batch sizes or count heuristics.
- Multiple unrelated warnings in the same skill.

One skill should have at most one `CRITICAL` block.

Good uses:

- "Use Address tab, not general search" in OsmAnd.
- "Completion state is only in detail view" in Tasks.org.
- "Use library tabs, not Browse" in VLC playlist work.

Bad uses:

- "Add 8 to 10 songs before checking total" in RetroMusic.
- "Scroll the entire file list before selecting" when that is just generic scan discipline.

## Token Budget

Use these budgets for the skill body itself.

- Target default: 8 to 12 lines.
- Simple apps: 3 to 6 lines.
- Complex apps: 13 to 18 lines.
- Absolute max: 20 lines, including headers.

Budget rules:

- Blank lines count against prompt size. Use them sparingly.
- Prefer merging verification into the relevant bullet instead of creating a dedicated section.
- Each extra header must save more reading effort than the tokens it costs.
- Any skill above 12 lines should justify why the extra lines are app-local and cross-task useful.

## Rewrite Procedure For Each Skill

1. Start from the current skill and delete frontmatter and decorative title.
2. Mark each remaining line as `core`, `tool`, `app`, or `overfit`.
3. Delete `core` duplicates and all `overfit` content.
4. Rewrite surviving `app` lines into the shortest general form.
5. Order the lines top-down by failure cost.
6. Add `CRITICAL` only if the kept content meets the bar above.
7. Cut again until the skill fits its tier budget.

## Review Checklist

Before accepting a rewritten skill, ask:

- Would this still help a real user on a different task in the same app?
- Does any line describe how to solve a benchmark instead of how the app works?
- Could any line be moved to the core prompt without mentioning the app? If yes, it should not stay here.
- Is the first line the most important app truth?
- Is the skill as short as it can be without losing a real app constraint?

## Rollout Guidance

Use the scoreboard as validation pressure, not as a content source. Fragile apps such as Tasks, OpenTracks, RetroMusic, and some cross-app file flows should get stricter review during rewrite, but they should not receive benchmark-shaped logic as compensation.

The intended outcome is a cleaner three-layer system:

- Core prompt handles generic agent behavior.
- Tool docs handle tool mechanics.
- App skills contain only compact, high-signal app deltas.

## Trade-Offs

- Some benchmark-specific wins will disappear. That is intentional if they depended on overfit procedures.
- Shorter skills shift more responsibility onto the core prompt, which is the right place for generic reasoning rules.
- Removing frontmatter and decorative headings slightly reduces raw-file ceremony, but the token savings are worth it because the runtime already supplies the app-skill wrapper.
