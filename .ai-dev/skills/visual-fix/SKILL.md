---
name: visual-fix
description: Debug Android Agent using visual inspection and log analysis. Use when agent is stuck, choosing wrong actions, or actions fail.
---

# Visual Fix

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
# With OpenAI backend (default)
./scripts/debug-run.sh "Goal here"

# With Local LLM backend (on-device)
./scripts/debug-run.sh --local "Goal here"
```

Creates `debug-output/` with `turn_N.png`, `turn_N_log.txt`, logs.

**Local model debugging tips:**
- First model run downloads ~800MB (lfm2-350m) or ~730MB (LFM2.5-1.2B Q4_K_M)
- Check `adb logcat -s LFMLLMClient` for model loading status
- Local models may reason differently than cloud models

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

### 4. Apply Targeted Fix

| Issue | Fix Location |
|-------|--------------|
| Missing perception | `perception/Perceptor.kt` |
| Bad reasoning | `agent/Agent.kt` system prompt |
| Tool failure | `tool/impl/*.kt` |
| Timing issue | Action delay, wait tool |

### 5. Verify

```bash
# OpenAI backend
./scripts/setup.sh && ./scripts/debug-run.sh "Goal here"

# Local LLM backend
LLM_BACKEND=local ./scripts/setup.sh && ./scripts/debug-run.sh --local "Goal here"
```

## Quick Diagnostics

```bash
grep -E "click|type|scroll|back" debug-output/agent.log          # Actions
grep "ActionResult" debug-output/agent.log                       # Results
grep "ERROR\|Exception" debug-output/agent.log                   # Errors

# Local LLM specific
adb logcat -d | grep -E "LFMLLMClient|Leap|Model"                # Model loading
adb logcat -d | grep "Tool call"                                 # Tool calls from local model
```

## Output Format

```
VISUAL DEBUG SESSION

Goal: [user goal]
Turn N: [screenshot] → [perception] → [action] → [result]
Issue: [category]
Root cause: [explanation]
Fix: [file:change]
Verification: [PASS/FAIL]
```

## Rules

- Capture screenshots before fixing
- One issue at a time
- Verify fix doesn't break other flows
