# Review of `design_claude.md`

## Findings

### High

1. Claude's budget model is wrong about what counts toward prompt cost. In the structural template, Claude keeps `# {App Name}` and implicitly keeps frontmatter, then says those do not count against the 20-line cap ([design_claude.md](./design_claude.md) lines 95-143). That is not correct for this codebase. `AppSkillRepository` reads the raw file body as plain text, and `TurnPlanningPhaseRunner` wraps that text with `## App Skill` and `Package: ...`; nothing parses or strips frontmatter or headings. So frontmatter and decorative titles do consume tokens every turn. My design calls this out explicitly and removes them from the rewrite base ([design_codex.md](./design_codex.md) lines 28-33, 72-75, 222-228). This is the biggest correctness gap because it directly affects the token-budget policy and the file template.

### Medium

2. Claude's suggestion to omit a skill file entirely for apps with few quirks is a scope and maintenance mismatch ([design_claude.md](./design_claude.md) line 104). The task is to rewrite all 17 app skills, not redesign the coverage model. Deleting some files may be reasonable later, but it is a separate product decision with different implications for discoverability, future edits, and app coverage conventions. For the first aligned draft, a minimal 2-3 line skill is the safer canonical case.

3. Claude's Audio Recorder example keeps "Type the exact filename..." as if it were app-local guidance ([design_claude.md](./design_claude.md) lines 271-279). In this system, exact filename matching is already core-prompt behavior, not Audio Recorder knowledge. Using it as a positive example weakens the ownership boundary that the rest of the design is trying to enforce. My design is stricter here: generic filename/date/counting discipline should disappear from app skills unless the app has a genuine local twist ([design_codex.md](./design_codex.md) lines 137-145, 150-171).

### Low

4. Claude's "concise user manual" litmus is useful, but slightly too narrow as stated ([design_claude.md](./design_claude.md) lines 9-11). Some of the best app-skill content would not appear in a normal user manual: `element_index`-driven targeting, silent shell failure from the agent process, or a11y-tree-specific gaps. Claude mostly compensates for this elsewhere, so this is not a structural bug, but the phrasing should be softened in the aligned draft to "operator notes for a grounded assistant" rather than "user manual."

5. Claude introduces a total-budget target across all 17 skills ([design_claude.md](./design_claude.md) lines 149-157), then correctly notes that only one foreground app skill loads per turn. The per-skill budget is the real operational constraint. The total-budget target is not harmful, but it is secondary and can distract from the more important cap on each individual skill.

## What Claude Gets Right That My Design Missed

1. The decomposition rule is excellent. Claude does the best job of turning a vague anti-overfit principle into an editing operation: extract the app fact, discard the solver procedure ([design_claude.md](./design_claude.md) lines 41-51). My design has the keep/move/drop classifier, but Claude's before/after framing is easier for an editor to apply consistently.

2. The CRITICAL audit table is strong and practical ([design_claude.md](./design_claude.md) lines 79-87). My design gives criteria and examples, but Claude goes one step further by auditing the current CRITICALs and naming which ones should stay, demote, or be removed.

3. Claude's migration strategy is better developed ([design_claude.md](./design_claude.md) lines 283-330). My design has rollout guidance, but Claude provides a phased rewrite order, risk tiers, and a concrete eval loop. That is useful for actually executing the rewrite without letting fragile apps slip through.

4. Claude explains the per-turn cost model clearly ([design_claude.md](./design_claude.md) lines 155-157). My design focused on line budgets and runtime token waste from raw text injection, but Claude is better on the operational consequence: optimize the foreground skill first, not the corpus total.

## Where I Disagree

1. I disagree with preserving frontmatter and `# App Name` headers as part of the template. This is not just a style preference; it is incorrect for the current loader. The aligned draft should adopt my rule here: remove decorative text unless a future tool actually consumes it.

2. I disagree with "no skill file at all" as part of the framework. That is a policy change beyond the stated task. The aligned draft should standardize how to rewrite all 17 existing files first; later we can decide whether some should collapse to empty/no-file.

3. I disagree with the Audio Recorder example's ownership boundary. The aligned draft should avoid examples that accidentally bless generic core-prompt guidance as app-local content.

4. I partially disagree with the "user manual" framing. The aligned draft should keep the spirit, because it pushes against solver leakage, but broaden it so app skills can still include agent-relevant operational notes that ordinary manuals would never mention.

## Trade-Off Comparison

### Claude design advantages

- Better editor-facing heuristics.
- Better examples.
- Better rollout plan.
- Better concrete audit of current CRITICAL usage.

### Codex design advantages

- More correct about the actual runtime prompt path.
- Cleaner ownership split between core prompt, tool docs, and app skills.
- Stronger on deleting decorative prompt text that the runtime does not use.
- Safer as a rewrite baseline because it makes fewer assumptions that conflict with the current implementation.

## Better Base For The First Aligned Draft

The better base is **CODEX**.

Reason: the first aligned draft should start from the design that is most correct about the current runtime and token model. Claude's draft contributes several things the aligned version should absolutely steal: the decomposition rule, the CRITICAL audit table, and the phased migration plan. But the loader/token accounting mistake is too central to use Claude as the base document. The aligned draft should therefore use CODEX as the base and import Claude's stronger editing workflow and rollout sections.
