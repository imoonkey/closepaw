# R41+ Prompt Optimization Goal

## Goals (ordered by priority)

1. **Maximize success rate** — every task that has historically passed at least once should pass again. The only acceptable reason to leave a task failing is if recovering it would require non-generalizable prompts (violating tuning_principles.md).
2. **Maintain generalization** — every prompt change must pass tuning_principles.md gates. No eval-specific solvers. No backward-compatibility hacks.
3. **Token budget awareness** — do not bloat. Small targeted additions (~50 tokens) are fine if they recover multiple tasks. Continue cutting where possible.

## Metrics

| Metric | Source |
|--------|--------|
| Task success rate | Eval results (scripted_score) |
| System prompt tokens | `StandaloneAgentDef.kt` |
| Tool description tokens | `tool/impl/*Tool.kt` |
| App skill tokens | `app/src/main/assets/app_skills/*/SKILL.md` |
| Measure: `python3 scripts/token_counts.py` | |

## Process

Add-back phase (opposite of R39 cut phase). Each round: add targeted general guidance, eval, analyze. If a change doesn't help, revert it. If it helps some tasks but hurts others, narrow it.

```
ADD / RESTORE ──► eval ──► analyze ──► ADD / RESTORE ──► eval ──► ...
```

- Add only what's justified by trace-verified root causes (see `r39_failure_analysis.md` and per-task files).
- Every addition must answer: "Would a real user of this app benefit from this guidance?"
- If an addition only helps eval tasks, reject it — but see "Eval Tips" below.
- After additions stabilize, do a second cut pass to tighten any verbose new text.

## Eval Tips (human-approved only)

Some failures are caused by systemic eval/scorer design issues, not agent capability. For these, **do NOT modify the scorer or the in-prompt skills during the loop.** Instead:

1. Collect confirmed eval-specific issues in `doc/autotune/round_41/eval_tips_proposal.md`.
2. Propose an `# Android World Eval Tips` app skill with workarounds for known scorer quirks.
3. This skill is **NOT auto-applied**. It requires human approval after all rounds complete.

Example: ExpenseAddMultipleFromMarkor scorer expects note without "Reimbursable." suffix, but Markor file embeds it in the note column with no indication to strip it.

## Scope

All three prompt layers. Use `eval/config/autotune_round_41.txt` (23 tasks: R39+R40 failures minus cannot_handle).

## Stop Criteria

- All historically-passing tasks pass again, OR remaining failures require non-generalizable fixes (documented with justification)
- No further general additions are justified
- Token counts remain within ~5% of current baseline (2,506 always-on)
- Eval tips proposal is complete for any systemic scorer issues found
