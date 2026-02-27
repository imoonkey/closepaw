---
name: orchestrate
description: Sequential agent workflow for complex tasks. Chains agents together with structured handoffs.
---

# Orchestrate

Sequential agent workflow for complex Android Agent tasks.

## Usage

`/orchestrate [workflow-type] [task-description]`

## Workflow Types

### feature
Full feature implementation:
```
planner -> tdd -> code-review -> verify
```

### bugfix
Bug investigation and fix:
```
cog-tune -> build-fix -> code-review -> verify
```

### refactor
Safe refactoring workflow:
```
planner -> code-review -> tdd -> verify
```

### ui
UI-focused development:
```
planner -> cog-tune -> code-review -> verify
```

## Execution Pattern

For each step in the workflow:

1. **Invoke skill/agent** with context from previous step
2. **Collect output** as structured handoff document
3. **Pass to next** in chain
4. **Aggregate results** into final report

## Handoff Document Format

Between steps, create handoff document:

```markdown
## HANDOFF: [previous] -> [next]

### Context
[Summary of what was done]

### Findings
[Key discoveries or decisions]

### Files Modified
[List of files touched]

### Open Questions
[Unresolved items for next step]

### Recommendations
[Suggested next steps]
```

## Example: Feature Workflow

```
/orchestrate feature "Add retry logic to LLM calls"
```

Executes:

1. **Planner** (`/plan`)
   - Analyzes requirements
   - Creates implementation plan
   - Identifies affected files
   - Output: `HANDOFF: planner -> tdd`

2. **TDD** (`/tdd`)
   - Writes tests first
   - Implements to pass tests
   - Output: `HANDOFF: tdd -> code-review`

3. **Code Review** (`/code-review`)
   - Reviews implementation
   - Checks Android-specific issues
   - Output: `HANDOFF: code-review -> verify`

4. **Verify** (`/verify`)
   - Runs build, lint, tests
   - Security scan
   - Output: Final Report

## Example: Bugfix Workflow

```
/orchestrate bugfix "Agent stuck in loop on Chrome"
```

Executes:

1. **Cog Tune** (`/cog-tune`)
   - Captures debug data
   - Turn-by-turn analysis
   - Identifies root cause
   - Output: `HANDOFF: cog-tune -> build-fix`

2. **Build Fix** (`/build-fix`)
   - Fixes any build errors from changes
   - Output: `HANDOFF: build-fix -> code-review`

3. **Code Review** (`/code-review`)
   - Reviews fix
   - Output: `HANDOFF: code-review -> verify`

4. **Verify** (`/verify`)
   - Final verification
   - Output: Final Report

## Final Report Format

```
ORCHESTRATION REPORT
====================
Workflow: [type]
Task: [description]
Steps: [step1] -> [step2] -> ...

SUMMARY
-------
[One paragraph summary]

STEP OUTPUTS
------------
Plan: [summary]
TDD: [tests written, coverage]
Code Review: [findings by severity]
Verify: [pass/fail status]

FILES CHANGED
-------------
[List all files modified]

TEST RESULTS
------------
[Test pass/fail summary]

RECOMMENDATION
--------------
[SHIP / NEEDS WORK / BLOCKED]
```

## Custom Workflow

```
/orchestrate custom "plan,tdd,verify" "Add new tool implementation"
```

Specify custom sequence of skills to chain together.

## Available Skills for Chaining

| Skill | Purpose |
|-------|---------|
| `plan` | Structured planning |
| `tdd` | Test-driven development |
| `code-review` | Systematic review |
| `verify` | Pre-commit quality gates |
| `build-fix` | Fix Gradle errors |
| `cog-tune` | Debug agent cognition + visual debug |
| `update-docs` | Sync documentation |

## Tips

1. **Start with plan** for complex features
2. **Always end with verify** before commit
3. **Use cog-tune** for agent behavior bugs
4. **Keep handoffs concise** - focus on what next step needs
5. **Run verify between steps** if needed for confidence
