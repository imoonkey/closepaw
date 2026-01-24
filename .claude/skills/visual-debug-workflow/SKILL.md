# Visual Debug Workflow

Debug Android Agent using visual inspection + log analysis.

> **Full guide:** [scripts/agent_process_visual_debug.md](../../../scripts/agent_process_visual_debug.md)  
> **Architecture:** [doc/main/agent_infra.md](../../../doc/main/agent_infra.md)

## When to Use

- Agent stuck in loops
- Agent choosing wrong actions
- Actions failing or having no effect
- Any unexpected behavior in the ReAct loop

## Issue Categories

| Category | Component | Symptoms |
|----------|-----------|----------|
| **Perception** | `Perceptor.kt` | Missing elements, wrong indices |
| **Reasoning** | `Agent.kt`, `Turn.kt` | Wrong action despite correct perception |
| **Execution** | `ToolRouter.kt`, tools | Action fails, wrong target |
| **Observation** | `Agent.kt` | Post-action state not captured |

## Workflow

### 1. Capture Debug Data

```bash
./scripts/debug-run.sh "Goal here"
```

Creates `debug-output/` with `turn_N.png`, `turn_N_log.txt`, logs.

### 2. Turn-by-Turn Analysis

For each turn compare:
- **Screenshot** (`turn_N.png`) - What's actually visible
- **Perception** (log) - What elements agent saw
- **Action** (log) - What agent decided
- **Result** (log) - Success/failure

### 3. Pattern Recognition

#### Stuck in Loop
- Compare consecutive screenshots
- Check if observation captured screen change
- Verify history context is correct

#### Wrong Action
- Check Perceptor output for target element
- Verify element_index matches
- Check LLM system prompt guidance

#### Action Fails
- Check element bounds and clickable state
- Check timing (UI changed during action?)
- Check ActionResult error

### 4. Apply Fix

| Issue | Fix Location |
|-------|--------------|
| Missing perception | `perception/Perceptor.kt` |
| Bad reasoning | `agent/Agent.kt` system prompt |
| Tool failure | `tool/impl/*.kt` |
| Timing issue | Action delay, wait tool |

### 5. Verify

```bash
./scripts/setup.sh && ./scripts/debug-run.sh "Goal here"
```

## Quick Diagnostics

```bash
grep -E "click|type|scroll|back" debug-output/orchestration.log  # Actions
grep "ActionResult" debug-output/orchestration.log               # Results
grep "ERROR\|Exception" debug-output/agent.log                   # Errors
```

## AI-Assisted Debugging

When asking for help:
1. Share `turn_N.png` screenshots
2. Share relevant log excerpts
3. Describe symptom: "Agent keeps pressing back when Chrome is visible"
4. Ask specific: "Why does agent choose X when element Y is visible?"
