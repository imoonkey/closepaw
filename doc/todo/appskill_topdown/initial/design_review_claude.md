# Design Review: Codex App Skill Framework

Reviewer: Claude
Reviewed: `design_codex.md`
Compared against: `design_claude.md`

---

## Overall Assessment

Both designs converge on the same core insight: app skills should contain app-local facts (where data lives, what UI traps exist), not solver algorithms or generic rules. The content inclusion/exclusion criteria are nearly identical. The meaningful differences are in operational detail, structural choices, and what each design missed.

Codex's design is more operational — it provides a decision flow, per-skill rewrite procedure, and review checklist that someone can follow mechanically. Claude's design is more concrete — it provides before/after examples, a CRITICAL audit, and a phased migration plan that ground the framework in reality.

---

## 1. Things Codex Gets Right That Claude Missed

### 1a. Frontmatter and header removal (significant)

Codex observes that `AppSkillRepository.kt` reads raw file text and `TurnPlanningPhaseRunner.kt` already wraps it with `## App Skill` and the package name. Therefore YAML frontmatter and `# App Name Skill` headers are pure token waste.

**Verified**: `AssetAppSkillRepository.load()` does `readText().trim()` — no YAML parsing. `buildAppSkillMessage()` prepends `## App Skill\nPackage: $packageName\n\n`. The runtime already provides the context the headers duplicate.

Claude's templates all include `# {App Name}` and implicitly assume frontmatter stays. This is ~2-3 lines of wasted tokens per skill (34-51 tokens across all 17 skills every turn they're active). Codex is right — remove them.

### 1b. Tool docs as a third ownership layer (genuine insight)

Codex identifies guidance that's really about tool mechanics (e.g., `element_index` targeting, shell limitations) rather than app knowledge. Claude lumps "use `element_index`" under "interaction pitfalls" in the app skill, but "prefer semantic targets over coordinates" is already in the core prompt. The app-specific part is "hamburger menu overlaps left edge" — the tool instruction ("use `element_index`") could live in tool docs.

This is a genuine third layer that Claude doesn't acknowledge. In practice, few lines cross this boundary, so the impact is small — but the conceptual clarity is valuable for edge cases.

### 1c. Explicit per-skill rewrite procedure (operationally superior)

Codex's 7-step procedure (delete frontmatter → mark lines as core/tool/app/overfit → delete duplicates → rewrite → order → add CRITICAL → cut to budget) is mechanically executable. Claude describes what to include/exclude but not the step-by-step process for transforming an existing skill. Someone doing the rewrite would need to invent their own procedure from Claude's principles.

### 1d. Review checklist (practical)

Codex's 5-question checklist provides a concrete validation gate for each rewritten skill:
- Would this help a real user on a different task?
- Does any line describe how to solve a benchmark?
- Could any line move to core prompt without mentioning the app?
- Is the first line the most important app truth?
- Is the skill as short as possible without losing a real constraint?

Claude has no equivalent standalone checklist.

### 1e. "Turn-optimization language" as an exclusion category

Codex explicitly calls out phrases like "save turns" or "do this quickly" as leaked eval-meta-language. Claude's exclusion list covers solver algorithms and batch-size tuning but doesn't name this specific pattern. It's a real pattern in current skills (RetroMusic's "Playlist Efficiency" section is framed around turn optimization).

### 1f. "Cross-task" as a CRITICAL criterion

Codex requires CRITICALs to "apply across multiple tasks in the app." Claude requires "silent failure" + "non-obvious alternative" + "app-specific" but doesn't explicitly require cross-task applicability. Codex's criterion is a useful additional filter — a CRITICAL that only matters for one task shape is probably overfit.

### 1g. Blank lines count against budget

Codex notes blank lines cost tokens and should be used sparingly. Claude's budget counts "content lines" without addressing whitespace overhead. This is a minor but correct observation about token accounting.

### 1h. Ordering by failure cost (sharper principle)

Codex orders skill content by "failure cost" — the most damaging mistake goes first. Claude orders by content type (CRITICAL → core guidance → navigation → operations). Codex's principle is sharper: within any section, the bullet that prevents the worst failure comes first. Claude's content-type ordering could bury a high-impact pitfall in a "Common Operations" section because of its category.

---

## 2. Things I Disagree With

### 2a. No before/after examples (significant gap)

Codex provides abstract tier shapes (3 generic bullets as Tier 1 example, 4 bullets as Tier 2). These show format but not the actual editorial judgment required. The hardest part of this rewrite is deciding what stays and what goes for each real skill — where the line falls between "app fact embedded in a solver" and "pure solver."

Claude's 3 before/after examples (OpenTracks, RetroMusic, Audio Recorder) demonstrate the decomposition rule on real content. They show that removing OpenTracks' counting algorithm keeps the "activity type is hidden" fact. They show that RetroMusic's CRITICAL is actually a solver in disguise. These are essential for anyone executing the rewrite.

Without before/after examples, Codex's framework is untested theory — you don't know if the rules produce good results until someone applies them to a real skill and discovers ambiguities.

### 2b. No CRITICAL audit of current skills (gap)

Codex lists good and bad CRITICAL examples in prose, but doesn't systematically audit all 5 current CRITICALs with keep/demote/remove verdicts. Claude's audit table makes the framework's judgment concrete and testable — you can disagree with a specific verdict (e.g., "Markor DEMOTE" vs "Markor KEEP") in a way you can't disagree with an abstract principle.

### 2c. No phased migration strategy (operational gap)

Codex's rollout guidance is one paragraph: "use the scoreboard as validation pressure, not content source." It doesn't answer: which apps do we rewrite first? How do we detect regressions? What do we do when a regression appears?

Claude's 3-phase approach (low-risk simple apps → moderate-risk overfit apps → high-risk fragile apps) with a per-phase eval process is operationally necessary. Rewriting all 17 at once is risky — if eval drops, you can't attribute regressions. Phasing lets you learn from early rounds.

### 2d. No app-to-tier assignment (minor gap)

Codex defines tier criteria but doesn't assign the 17 apps to tiers. Claude maps every app. The mapping is guidance, not binding — a skill might end up in a different tier after rewrite — but starting expectations help the rewriter calibrate scope.

### 2e. "Observations That Shape This Design" — partially wrong

Codex states: "The current 17 skills range from 11 to 31 lines." The actual range is ~4 lines (Google Calendar) to ~25+ lines (Tasks.org). Several skills are well under 11 lines. This suggests Codex may have counted including frontmatter/headers as content, or miscounted. Minor factual error, but it slightly undermines the "read the codebase" credibility.

### 2f. Missing the "non-obvious alternative" CRITICAL criterion

Codex requires that missing a CRITICAL rule "commonly causes a wrong branch, destructive action, or false answer." But it doesn't require that the correct alternative be non-obvious from the screen. Claude's "non-obvious alternative" criterion captures an important case: sometimes the failure IS visible but the fix isn't inferrable from what's on screen. OsmAnd's Address tab is a good example — you'd need to know it exists and that it does structured lookup, which isn't apparent from the search UI.

### 2g. The "verification anchor" section feels over-specified

Codex's 5-level section hierarchy (CRITICAL → canonical route → pitfalls → operations → verification anchor) creates a taxonomy that may not map cleanly to real skills. The "verification anchor" section is particularly thin — most apps don't need app-specific verification beyond what the core prompt covers. Making it a named section type risks encouraging people to add unnecessary verification bullets to fill the structure.

---

## 3. Minor Observations

- Both designs agree on max 1 CRITICAL per skill, hard cap 20 lines. No conflict.
- Both exclude the same content categories (solver algorithms, scratchpad formats, batch tuning, eval patterns). No conflict.
- Codex's "consider tool docs instead" layer is conceptually clean but affects very few lines in practice. Worth noting in the aligned draft but not worth heavy process.
- Codex's observation about `TurnPlanningPhaseRunner.kt` wrapping is accurate and actionable — confirmed by reading the code. Claude missed this entirely.
- Both designs note the 3+ skill escalation rule for core prompt promotion. Agreement.
- Claude's "total budget target" (all 17 < core prompt tokens) is a useful cross-check metric that Codex doesn't have. Worth keeping.

---

## 4. Verdict: Which Design Is the Better Base?

**CODEX is the better base for the aligned draft.**

Rationale:

1. **More operational structure.** The decision flow (Section 3.1), rewrite procedure (Section "Rewrite Procedure"), and review checklist are the skeleton of a usable style guide. Claude's design reads more like a design rationale document — it explains *why* but doesn't give you the *how-to* as cleanly.

2. **Correct on frontmatter/header removal.** This is a concrete, code-verified insight that affects every skill. Claude's templates assume headers stay, which would need to be undone.

3. **Sharper ordering principle.** "Order by failure cost" is a better single rule than organizing by content type.

4. **Cleaner layer model.** Core prompt → tool docs → app skills is a tighter ownership model than Claude's two-layer (core prompt → app skills) approach.

**However, the aligned draft must incorporate from Claude:**

- **Before/after examples** (Section 6) — non-negotiable. The framework is untested without them.
- **CRITICAL audit table** (Section 2) — grounds the CRITICAL protocol in real verdicts.
- **Phased migration strategy** (Section 7) — operationally necessary for safe rollout.
- **Decomposition rule with table** (Section 1) — the sharpest articulation of how to split mixed content.
- **App-to-tier mapping** (Section 4) — starting guidance for the rewriter.
- **"Non-obvious alternative" CRITICAL criterion** — Codex's criteria are necessary but not sufficient without this.
- **Per-turn cost analysis** — Codex doesn't note that only 1 skill loads per turn, which changes how you think about the budget.

The aligned draft should use Codex's skeleton (decision flow → structure → format rules → rewrite procedure → review checklist) and fill it with Claude's concrete artifacts.

**Better base: CODEX.**
