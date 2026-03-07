# Common Problems Summary Template

Use this template when writing `common_problems_<agent>.md` for an autotune round.

Mandatory sections are marked with **(required)**. Others: include when they add signal.

---

## Template

```markdown
# Round N Eval Analysis — Common Problems

**Run ID**: `<run_id>` | **Date**: YYYY-MM-DD | **Model**: <model>
**Tasks**: X evaluated | **Pass Rate**: Y/X = Z%

## Scorecard (required)

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| TaskA | 1.0 | 12 | Success |
| TaskB | 0.0 | 30 | ActionFailure |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | Y | — |
| ActionFailure | Z | TaskB, TaskC |

## Previous Round Comparison

<!-- Include from round 2 onward. Skip for round 0. -->

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| P1: type clear=false | Working | MarkorEditNote now passes |
| P4: OsmAnd hybrid mode | Ineffective | Still 0/2 |

## Common Problems (required)

### P1: <Name> — <HIGH|MED|LOW> — N tasks

**Affected**: TaskA, TaskB, TaskC

**Root Cause**: 2-3 sentences. What goes wrong and why.

**Proposed Fix**:
- [Concrete action with implementation target: code/prompt/config]

---

### P2: <Name> — <HIGH|MED|LOW> — N tasks

[Same structure...]

---

## Next Steps (required)

### Must Fix
1. **<Fix name>**: Brief description
   - Targets: TaskA, TaskB
   - Implementation: [code path / prompt section / config change]
   - Owner layer: [core prompt | tool desc: <tool> | app skill: <package> | infra]  <!-- use /prompt-tune ownership model -->

### Should Fix
2. **<Fix name>**: Brief description

### Accept as Capability Gap
- <TaskX>: reason (candidate for cannot_handle)
```

---

## Section Rules

| Section | When to include |
|---------|----------------|
| Header + Scorecard | Always |
| Previous Round Comparison | Round 1+ (skip round 0) |
| Common Problems | Always |
| Next Steps | Always — this is the interface contract with /autotune |

## Priority Levels

| Level | Meaning |
|-------|---------|
| HIGH | Directly blocks task passes. Fix first. |
| MED | Improves behavior but tasks may pass without it. |
| LOW | Nice to have, or speculative fix. |

## Next Steps Categories

| Category | Meaning |
|----------|---------|
| Must Fix | Directly unblocks passes. Agent applies these in Step 1 of the next /autotune. |
| Should Fix | Improves quality but not blocking. Apply if budget allows. |
| Accept as Capability Gap | Fundamentally can't handle with current architecture. Move to cannot_handle. |

## Length Guidelines

- Small round (5-10 tasks): ~80-120 lines
- Large round (15-25 tasks): ~150-250 lines
- Don't pad. If there's one problem pattern, write one pattern.
