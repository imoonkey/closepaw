# Tuning Principles

Every change during autotune must pass these gates:

## 1. Anti-overfit

Does this help real users, not just eval tasks?

- Core prompt changes must be generally applicable across apps and tasks.
- App skill changes should cover more than the single eval task that triggered them.
- If a proposal only helps one narrow eval case, do not add it.

**App skill overfit indicators** — remove or rewrite if a skill contains:
- Solver algorithms for specific task shapes (e.g., "add all then prune", "compare 7 fields")
- Eval-specific data patterns (e.g., "perturbed groups", "decoy entries", target colors)
- Hardcoded counts, budgets, or thresholds tuned to eval data (e.g., "scroll exactly 40 items")
- Eval page layouts or element descriptions that only match test fixtures

**What to keep** in app skills:
- App behavior and UI patterns a real user would encounter
- Interaction mechanics (how to navigate, delete, where to find things)
- Platform quirks (accessibility tree gaps, hidden scrollable areas, first-run dialogs)
- One-liner action mechanics (e.g., "swipe left to delete", "3-dot menu → Delete")

## 2. Token Minimalism

Is every token earning its keep? You should maximize usefulness per token.

- Core prompt target: about 80-100 lines.
- App skills target: under 20 lines when possible because they load every turn in the foreground app.
- Say the same thing in fewer words whenever clarity stays intact.


## 3. Generalization

Eval tasks are training data, not the final target.

- Ask whether the change would also help an unseen real-user task in the same app or workflow family.
- Prefer stable workflow rules and grounding rules over task-specific patches.
- If the proposal improves the benchmark but weakens real-user behavior, reject it.

**The generalization test**: A well-generalized app skill should let a user say "delete duplicate recipes" or "add expenses from a note" and get useful guidance — without the skill assuming specific data shapes, counts, or comparison algorithms.

**Acceptable regressions**: If the only fix for a failing eval task would reintroduce eval-specific content (solver algorithms, task-specific patterns), accept the regression rather than compromise generalization. Document in `doc/autotune/round_N/issues.md`.
