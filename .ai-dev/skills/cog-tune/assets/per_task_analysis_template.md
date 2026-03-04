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

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| {N} | `{tool_call}` | {Y/N} | {G/B} | {G/B} | {G/B} | {short note or —} |
| ... | ... | ... | ... | ... | ... | ... |

<!-- G = Good, B = Bad. Group uneventful turns: "3-7 | navigated to target | Y | G | G | G | —" -->

### Key Turns

<!-- Expand only turns with a Bad rating or surprising behavior. Skip if table is self-explanatory. -->

**Turn {N}** — {1-2 sentence explanation: what went wrong in reasoning/grounding/execution, what was on screen, what should have happened instead.}

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
| ReasoningError | Agent saw the screen correctly but chose the wrong action |
| GroundingError | Agent reasoned correctly but selected wrong action type/params (wrong element, wrong text, wrong coordinates) |
| ActionFailure | Action executed but had wrong effect (tool reported success but screen didn't change as expected) |
| FalseCompletion | Agent claimed success but scorer disagreed |
| NavigationFailure | Agent couldn't reach the right screen |
| TurnExhaustion | Correct approach but ran out of turns |
| InfraError | Emulator/runner/LLM infra issue, not agent fault |

## Length Guidelines

- Clean pass: ~20-30 lines
- Failure: ~60-120 lines (proportional to complexity)
- Group trivial turns to avoid bloat
