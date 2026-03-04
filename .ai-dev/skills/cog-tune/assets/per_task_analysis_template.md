# Per-Task Analysis Template

Use this template when writing per-task cog-tune analysis files (`*_<agent>.md`).

Principle: **compact by default**. Only include optional sections when they add signal.

---

## Template

```markdown
# {TaskName} — Cog-Tune Analysis

**Score**: {0.0|1.0} | **Turns**: {N}/{max} | **Reason**: {GoalAchieved|MaxTurnsReached|Error} | **Duration**: {N}s

**Goal**: {1-2 sentence goal from task definition}

## Root Cause

**Category**: {Success | PerceptionGap | ActionFailure | FalseCompletion | NavigationFailure | TurnExhaustion | InfraError}

{2-3 sentence summary of what happened and why.}

## Turn-by-Turn Analysis

<!-- Include for failures or when turns > 20. For clean passes, skip this section. -->

### Turn {N}
- **Action**: `{tool_call with key args}`
- **Result**: {outcome}
- **Assessment**: {Good|Neutral|Bad} — {why}

<!-- Group uneventful turns: "Turns 5-8: navigated to target screen without issues." -->

## What Worked

- {bullet}
- {bullet}

## What Didn't Work

<!-- Skip for clean passes. -->

- {bullet}
- {bullet}

## Recommendations

<!-- Skip for clean passes. Include for failures and messy passes. -->

- {actionable fix, reference issue ID if exists}
```

---

## Section Rules

| Section | When to include |
|---------|----------------|
| Header + Goal + Root Cause | Always |
| Turn-by-Turn | Failures, or passes with turns > 20 |
| What Worked | Always (even failures have something) |
| What Didn't Work | Failures and messy passes only |
| Recommendations | Failures and messy passes only |

## Root Cause Categories

| Category | Meaning |
|----------|---------|
| Success | Task passed cleanly |
| PerceptionGap | Agent couldn't see/parse a UI element |
| ActionFailure | Action executed but had wrong effect |
| FalseCompletion | Agent claimed success but scorer disagreed |
| NavigationFailure | Agent couldn't reach the right screen |
| TurnExhaustion | Correct approach but ran out of turns |
| InfraError | Emulator/runner/LLM infra issue, not agent fault |

## Length Guidelines

- Clean pass: ~20-30 lines
- Failure: ~60-120 lines (proportional to complexity)
- Group trivial turns to avoid bloat
