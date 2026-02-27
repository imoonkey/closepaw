# Android Agent Visual Debugging Guide

> **Prerequisites:** Understand the agent architecture in [doc/main/agent_infra.md](../doc/main/agent_infra.md)

Visual debugging approach for the Android Agent's ReAct loop using screenshots + logs.

## Issue Categories

| Category | Symptoms |
|----------|----------|
| **Perception** | Agent doesn't see visible elements, wrong element indices |
| **Reasoning** | LLM chooses wrong action despite correct perception |
| **Execution** | Action fails or targets wrong element |
| **Observation** | Post-action state not captured correctly |

## Debugging Workflow

### Step 1: Run with Debug Script

```bash
./scripts/debug-run.sh "Open Chrome"
```

Output in `debug-output/`:
```
debug-output/
├── turn_N.png           # Screenshot at each turn
├── turn_N_log.txt       # Log excerpt for that turn
├── orchestration.log    # Full agent log
└── agent.log            # Full app log
```

### Step 2: Turn-by-Turn Analysis

For each turn, compare:

| Check | Source | Look For |
|-------|--------|----------|
| Actual screen | `turn_N.png` | What's visible, is target there? |
| Perceived elements | `turn_N_log.txt` | Element indices, missing elements |
| Action chosen | Log: `ACTION` | Does action match goal? |
| Result | Log: `ActionResult` | Success/failure, observation |

### Step 3: Common Issues

#### A. Agent Stuck in Loop

**Symptoms:** Same action repeating, no progress

**Debug:**
1. Compare consecutive screenshots - is screen actually changing?
2. Check observation after action - did agent see the change?
3. Check LLM reasoning - is history providing correct context?

#### B. Wrong Action Chosen

**Symptoms:** Target visible but agent does something else

**Debug:**
1. Check `Perceptor` output - is target element in the list?
2. Check element index - does it match what agent referenced?
3. Check LLM prompt - does system prompt have enough guidance?

**Example:**
```
Screen shows Chrome at element_index=10
Agent action: {"tool": "back"}  ← WRONG
Should be: {"tool": "click", "element_index": 10}
```

#### C. Action Fails

**Symptoms:** Tool returns error or no effect

**Debug:**
1. Check element bounds - is target actually clickable?
2. Check timing - did UI change during action?
3. Check `ActionResult` - what error was returned?

### Step 4: Add Targeted Logging

In `Agent.kt` or `Turn.kt`:

```kotlin
// Log perception before LLM call
private fun logPerception(snapshot: ScreenSnapshot) {
    Log.d(TAG, "=== PERCEPTION ===")
    Log.d(TAG, Perceptor.toPromptJson(snapshot))
}

// Log LLM input/output
private fun logTurn(input: String, result: TurnResult) {
    Log.d(TAG, "=== LLM INPUT ===\n$input")
    Log.d(TAG, "=== LLM OUTPUT ===\n${result.content}")
    Log.d(TAG, "=== TOOL CALLS ===\n${result.toolCalls}")
}
```

### Step 5: Manual Verification

```bash
# Capture current screen
adb exec-out screencap -p > /tmp/check.png
open /tmp/check.png

# Compare with what Perceptor saw
```

## Quick Diagnostics

```bash
# Actions taken
grep -E "click|type|scroll|swipe|back|home" debug-output/orchestration.log

# Tool results
grep "ActionResult\|ToolCallResult" debug-output/orchestration.log

# Errors
grep "ERROR\|Exception" debug-output/agent.log

# Turn markers
grep "Turn\|TURN" debug-output/orchestration.log
```

## Example Session

**Problem:** Agent keeps pressing back instead of clicking Chrome

```bash
# 1. Run debug
./scripts/debug-run.sh "Open Chrome"

# 2. Check turn_2.png - Chrome icon visible

# 3. Check turn_2_log.txt
# PERCEPTION: element_index=10, text="Chrome", clickable=true
# ACTION: {"tool": "back"}  ← BUG

# 4. Issue: LLM reasoning wrong despite correct perception
# Fix: Update system prompt in Agent.kt

# 5. Verify
./scripts/setup.sh && ./scripts/debug-run.sh "Open Chrome"
```

## Key Files

> See [doc/main/agent_infra.md](../doc/main/agent_infra.md) for full architecture

| File | Purpose |
|------|---------|
| `agent/Agent.kt` | ReAct loop (Perceive → Think → Act → Observe) |
| `agent/Turn.kt` | Single LLM call with streaming |
| `perception/Perceptor.kt` | Accessibility tree → ScreenSnapshot |
| `tool/ToolRouter.kt` | Tool execution state machine |
| `platform/AccessibilityPlatform.kt` | Screen capture and actions |
