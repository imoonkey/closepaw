# Android Agent Visual Debugging Guide

This document describes a systematic approach to debugging the Android Agent's orchestration using visual inspection combined with log analysis.

## Overview

The Android Agent uses a multi-agent orchestration system (Manager, Executor, Reflector). When issues occur, they can stem from:

1. **Perception Issues** - ScreenSnapshot doesn't capture correct elements
2. **Planning Issues** - Manager creates incomplete or wrong plans
3. **Execution Issues** - Executor picks wrong actions
4. **Reflection Issues** - Reflector incorrectly validates action outcomes
5. **State Management Issues** - Before/after snapshots are compared incorrectly

## Debugging Workflow

### Step 1: Run with Debug Script

Use `debug-run.sh` to capture screenshots at each turn:

```bash
./scripts/debug-run.sh "Open Chrome"
```

This creates `debug-output/` with:
- `turn_N.png` - Screenshot at each turn
- `turn_N_log.txt` - Relevant log excerpt for that turn
- `orchestration.log` - Full orchestration log
- `agent.log` - Full agent log

### Step 2: Examine Turn-by-Turn Flow

For each turn, compare:

1. **Actual Screenshot** (`turn_N.png`)
   - What is actually visible on screen?
   - Is the target element visible?

2. **ScreenSnapshot Log** (in `turn_N_log.txt`)
   - What elements did the agent perceive?
   - Are element IDs correct?
   - Is the target element in the list?

3. **Action Decided**
   - What action did Executor choose?
   - Does it make sense given the visible elements?

4. **Reflection Result**
   - Did Reflector correctly assess if action succeeded?
   - Are Before/After snapshots different?

### Step 3: Common Issues to Look For

#### A. Agent Stuck in Loop

**Symptoms:**
- Same action repeated multiple times
- Reflection keeps saying "FailedNoChange"

**Debug Steps:**
1. Check screenshots - is the screen actually changing?
2. Check ScreenSnapshot timestamps - are Before/After different?
3. Check element differences - did elements actually change?

**Example from logs:**
```
>>> REFLECTION (Turn 2)
Before snapshot timestamp: 1234567890
After snapshot timestamp: 1234567895
Elements ADDED: [10:Chrome]
Elements REMOVED: [4:Start Agent]
REFLECTION RESULT: Success(...)
```

If timestamps are close but content differs, reflection should succeed.

#### B. Wrong Action Chosen

**Symptoms:**
- Target is visible but agent does something else
- Agent keeps pressing "home" when already on home screen

**Debug Steps:**
1. Check ScreenSnapshot - is target element listed?
2. Check element_id - does it match what Executor referenced?
3. Check Executor prompt - does it have enough guidance?

**Example:**
```
Screen shows Chrome at element_id=10
Agent action: {"action": "system", "button": "home"}  ← WRONG
Should be: {"action": "click", "element_id": 10}
```

#### C. Incomplete Plan

**Symptoms:**
- Task partially completes then agent thinks it's done
- Plan doesn't cover all steps needed

**Debug Steps:**
1. Check Manager's initial plan - is it complete?
2. Check when replanning triggers - are there enough failures?
3. Verify plan matches the full user goal

**Example:**
```
User goal: "Open Chrome"
Manager plan: "1. Go to home screen"  ← INCOMPLETE
Should be: "1. Go to home screen\n2. Click Chrome icon"
```

#### D. Reflection Mismatch

**Symptoms:**
- Action succeeded visually but Reflector says failed
- Action failed but Reflector says succeeded

**Debug Steps:**
1. Compare Before/After snapshot JSONs sent to Reflector
2. Check timestamp difference (should be > actionDelayMs)
3. Look for element count changes

### Step 4: Add Targeted Logging

If the issue isn't clear, add logging to `MobileV3Orchestration.kt`:

```kotlin
// Log snapshot JSON for LLM debugging
private fun logSnapshotJson(label: String, snapshot: ScreenSnapshot?) {
    if (!DEBUG || snapshot == null) return
    val json = Perceptor.toPromptJson(snapshot)
    debugLog("=== SNAPSHOT JSON [$label] ===\n$json\n=== END JSON ===")
}
```

Key places to log:
- Before/After snapshots in reflection
- InfoPool context sent to Executor
- Manager's plan output

### Step 5: Manual Screenshot Verification

Take manual screenshots to compare with agent's perception:

```bash
# Capture current screen
adb exec-out screencap -p > /tmp/manual_check.png

# View it
open /tmp/manual_check.png  # macOS
```

Compare with what the agent logged in ScreenSnapshot to verify perception is accurate.

## Debug Script Details

### `debug-run.sh`

```bash
./scripts/debug-run.sh "Your Goal Here"
```

**What it does:**
1. Clears previous debug output
2. Starts agent with specified goal
3. Monitors logcat for turn markers
4. Captures screenshot at each turn start
5. Saves relevant logs per turn
6. Detects completion or errors
7. Outputs all files to `debug-output/`

**Output structure:**
```
debug-output/
├── turn_1.png           # Screenshot at turn 1
├── turn_1_log.txt       # Log excerpt for turn 1
├── turn_2.png
├── turn_2_log.txt
├── ...
├── orchestration.log    # Full MobileV3Orchestration log
└── agent.log            # Full agent-related log
```

## Example Debugging Session

### Problem: Agent keeps pressing "home" instead of clicking Chrome

**Step 1: Run debug script**
```bash
./scripts/debug-run.sh "Open Chrome"
```

**Step 2: Check turn_2.png**
- Screenshot shows home screen with Chrome icon visible

**Step 3: Check turn_2_log.txt**
```
SNAPSHOT [Turn2_Current]
  [10] TextView | text='Chrome' | clickable=true
ACTION DECIDED: AtomicAction(type=system, button=home)
```

**Step 4: Identify issue**
- Chrome is visible at element 10
- But Executor chose "home" instead of "click"
- Issue: Executor prompt doesn't emphasize clicking visible targets

**Step 5: Fix**
Update Executor prompt in `Executor.kt`:
```kotlin
// Add to SYSTEM_PROMPT:
// "If the target app/item is visible and clickable, CLICK IT - don't navigate away."
```

**Step 6: Verify fix**
```bash
./scripts/setup.sh
./scripts/debug-run.sh "Open Chrome"
# Check that agent now clicks Chrome
```

## Log Patterns to Search

```bash
# Actions taken
grep "ACTION DECIDED" debug-output/orchestration.log

# Plans created
grep "New plan\|Current plan" debug-output/orchestration.log

# Reflection results
grep "REFLECTION RESULT" debug-output/orchestration.log

# Snapshot comparisons
grep "Before elements\|After elements" debug-output/orchestration.log

# Errors
grep "ERROR\|Error\|Exception" debug-output/agent.log
```

## Tips for AI-Assisted Debugging

When using an AI coding agent to debug:

1. **Provide screenshots** - Share the turn_N.png files for visual context
2. **Share log excerpts** - Include relevant parts of orchestration.log
3. **Describe the symptom** - "Agent keeps pressing home" vs "Agent doesn't see Chrome"
4. **Ask specific questions**:
   - "Why is the agent choosing action X when element Y is visible?"
   - "What's different between the Before and After snapshots?"
   - "Is the Manager plan complete for this goal?"

5. **Request targeted fixes**:
   - "Update the Executor prompt to prioritize clicking visible targets"
   - "Add logging to track snapshot timestamps"
   - "Fix the reflection comparison logic"

## Related Files

- `MobileV3Orchestration.kt` - Main orchestration logic with debug logging
- `SessionExecutionState.kt` - State management (plan, actions, outcomes)
- `Manager.kt` - Planning agent
- `Executor.kt` - Action selection agent
- `Reflector.kt` - Action validation agent
- `AccessibilityPlatform.kt` - Screen capture and action execution
- `Perceptor.kt` - Screen to ScreenSnapshot conversion

