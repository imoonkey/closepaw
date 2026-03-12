# Prompt Optimization Loop

## Goal

Maximize prompt generalization and minimize prompt tokens while maintaining or improving task success rate.

Three prompt layers are in scope: **system prompt**, **tool descriptions**, and **app skills**. Changes must follow `tuning_principles.md` (anti-overfit, token minimalism, generalization).

## Metrics

Track these across rounds:

| Metric | Source |
|--------|--------|
| Task success rate | Eval results (scripted_score) |
| System prompt tokens | `StandaloneAgentDef.kt` prompt string |
| Tool description tokens | `tool/impl/*Tool.kt` description properties |
| App skill tokens (total + avg) | `app/src/main/assets/app_skills/*/SKILL.md` |

## Process

Iterative, not monotonic. Each round can cut, modify, or add — whatever moves closer to the optimum. Based on current assessment (less generalizable, more verbose), the first round starts with cuts.

```
CHANGE ──► eval ──► analyze ──► CHANGE ──► eval ──► ...
  (cut / rewrite / add)           (cut / rewrite / add)
```

- Each round: make targeted changes (cut, rewrite, or add), run eval, analyze.
- For regressions caused by cuts, add back minimal general guidance. Ask: "would a real user of this app benefit from this?" If only an eval runner benefits, accept the regression.
- Oscillate until the best balance is found.

## Scope

All three layers, all apps. Use `eval/config/aw_fullset.txt` minus `cannot_handle_group.txt` for regression sweeps.

## Stop Criteria

- No further cuts possible without regressions
- No regressions recoverable with general guidance
- Token counts are at or near minimum for current success rate
