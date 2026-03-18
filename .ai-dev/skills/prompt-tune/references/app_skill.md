# App Skill Writing Guide

## Core Principle

App skills encode the app's data model and interaction model: the app-local "what", "where", and "what goes wrong." They do NOT encode the agent's reasoning strategy for solving a task.

- Keep lines that read like compact operator notes for a grounded assistant.
- Remove lines that read like instructions for solving a benchmark task.

## What Belongs in an App Skill

- **Hidden data locations**: where the real source of truth lives when the obvious screen is misleading.
- **Canonical navigation**: the non-obvious route to common actions or authoritative screens.
- **Accessibility gaps**: missing cells, hidden scroll axes, misleading rows, unlabeled controls.
- **Silent failure modes**: flows that look correct but fail without feedback.
- **Interaction pitfalls**: controls that behave differently from what they appear to do.
- **Platform quirks**: first-run prompts, extension fields, picker behavior, shell restrictions.
- **App terminology**: control order, label semantics, field meaning.

## What Does NOT Belong

- **Solver algorithms**: counting procedures, dedup strategies, comparison workflows, batching strategies.
- **Scratchpad format prescriptions**: `unchecked:`, `checked:`, `unique:` schemas.
- **Hardcoded counts/thresholds**: batch sizes, retry budgets tuned to eval data.
- **Eval-specific patterns**: decoys, perturbed groups, benchmark-only layouts.
- **Turn-optimization language**: "save turns", "do this quickly" (unless it's a real app fact).
- **Generic agent rules**: already in the core prompt (scroll-before-count, date-range computation, verification).

## Decomposition Rule

When a skill mixes app knowledge with solver procedure, extract the app fact and discard the procedure:

| Mixed | Fact only |
|---|---|
| "Scroll list, write tracks to scratchpad, open Edit for every track..." | "Track names do not indicate activity type. Type is only in Edit → Activity type." |
| "Add 8-10 songs, check total, remove if over..." | "Playlist detail shows total duration." |

## Structure

Order by failure cost: the rule preventing the worst mistake comes first.

- **CRITICAL** (at top, max 1 per skill): only when the obvious path commonly causes a wrong result AND the correct alternative is not obvious from the screen.
- Then: canonical routes, pitfalls, operation notes.
- No headers for sections with only one line — just use a bullet.

## Token Budget

| Tier | Lines | When |
|---|---|---|
| Minimal | 2-5 | One or two strong quirks |
| Standard | 6-12 | Most apps |
| Complex | 13-18 | Genuine hidden state, multiple strong traps |
| Hard cap | 20 | Never exceed |

## Format

- Plain text with short bullets. No YAML frontmatter in the prompt-facing body (the loader strips it).
- One-line bullets preferred. Numbered steps only when the sequence itself is the app truth.
- Headers only when they save more confusion than they cost in tokens.

## CRITICAL Protocol

Use only when ALL of these are true:
1. The obvious/default path commonly causes a wrong result.
2. The correct alternative is not obvious from the screen alone.
3. The rule is app-specific, not generic agent discipline.
4. The rule applies across multiple tasks in that app.
5. The rule fits in 1-3 short bullets.
