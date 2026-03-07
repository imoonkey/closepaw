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

<!-- MANDATORY for failures and turns > 20. For clean passes under 20 turns, may skip. -->
<!-- CRITICAL: Do NOT rely solely on tool success/failure status. Compare pre-action and post-action
     screen state for EVERY turn to verify whether the action actually took effect. A click can report
     "success" while the screen remains unchanged (false success). -->

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| {N} | `{tool_call}` | {Y/N — verify via pre/post screen diff} | {G/B} | {G/B} | {G/B} | {short note or —} |
| ... | ... | ... | ... | ... | ... | ... |

<!-- G = Good, B = Bad. Group uneventful turns: "3-7 | navigated to target | Y | G | G | G | —" -->

### Key Turns

<!-- Expand every turn with a Bad rating or surprising behavior. -->

**Turn {N}** — {Detailed explanation:}
- **What was on screen**: {describe key elements visible in screen state / screenshot}
- **What agent decided**: {reasoning from LLM output}
- **What action was taken**: {exact tool call and params}
- **What actually happened**: {compare pre/post screen — did UI actually change?}
- **What should have happened**: {correct action and why}
- **Perception check**: {if behavior is weird, compare screenshot vs a11y tree — note any elements missing from a11y tree that are visible in screenshot}

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

## Analysis Quality Requirements

1. **Every turn must be verified**: For each turn, compare the screen state before and after the action. Do NOT trust the tool's success/failure status alone.
2. **False success detection**: Flag any turn where the tool reports success but the screen state is unchanged or doesn't match expectations. This is a common failure mode for click actions.
3. **Screenshot review**: When agent behavior is unexpected, open the screenshot image and compare it against the a11y tree. Note any UI elements visible in the screenshot that are missing from the a11y tree (perception gaps).
4. **Reasoning audit**: For each turn, evaluate whether the LLM's reasoning (in its thought/content) correctly interprets the current screen and selects the right action.
5. **Parameter audit**: Verify that action parameters (element targets, text values, coordinates) match the agent's stated intent.

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
