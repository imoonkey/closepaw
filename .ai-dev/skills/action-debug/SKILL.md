---
name: action-debug
description: Debug failed tool executions from eval runs or debug-runs by isolating the action execution layer. Use when /cog-tune classifies the root cause as "Execution" (action failed or hit wrong target), when actions report success but UI doesn't change (false success), or when you need to compare adb baseline vs. accessibility service execution paths. Designed to complement /cog-tune — this skill focuses on action reliability, not LLM reasoning.
---

# Action Debug

Debug action execution failures by isolating and testing individual actions directly on the device.

## When to Use

- `/cog-tune` classified root cause as **Execution** (tool call failure or wrong target)
- Action reports `success` but UI didn't change (false success pattern)
- Need to determine whether failure is in L0 (device/adb), L1 (a11y platform), or L2 (executor logic)
- Comparing node-based vs. gesture-based execution for the same target
- Debugging scroll/swipe that moves wrong distance or direction

## Relationship to /cog-tune

These skills are **separate but complementary**:

| Aspect | /cog-tune | /action-debug |
|--------|-----------|---------------|
| Focus | LLM reasoning, prompt, context | Action execution reliability |
| Question answered | "Did the agent make the right decision?" | "Did the action physically work?" |
| Evidence | LLM prompts, tool call args, history | Before/after screenshots, a11y trees, result.json |
| Root causes | Perception, Context, Reasoning | Platform bug, wrong coords, untargetable element, timing |

**Typical combined workflow**: Run `/cog-tune` first to classify the root cause. If it's Execution, switch to `/action-debug` to drill into the action layer.

## Workflow

### 1. Identify the failed action from traces

Start from a debug-run or eval run trace. Find the step where execution failed.

**From debug-run:**
```bash
# Find tool call failures
grep '"success": false' debug-output/run_*/trace/trace.jsonl

# Or find mobile_action calls and check results
python3 -c "
import json, sys, glob
for f in sorted(glob.glob('debug-output/run_*/trace/trace.jsonl')):
    for line in open(f):
        ev = json.loads(line)
        if ev['type'] == 'tool_result' and ev['data'].get('name') == 'mobile_action':
            print(f\"{f}  seq={ev['seq']}  turn={ev.get('turnNumber')}  success={ev['data']['success']}\")
"
```

**From eval results:**
```bash
# Find tasks with tool failures
python3 -c "
import json
for line in open('eval/results/<run_dir>/per_task.jsonl'):
    t = json.loads(line)
    if t.get('tool_failures', 0) > 0 or not t.get('scripted_success', True):
        print(f\"{t['task_id']}  success={t.get('scripted_success')}  tool_failures={t.get('tool_failures', 0)}\")
"
```

### 2. Extract action parameters from the trace

Read the tool_call_args artifact for the failed step:

```bash
# List tool call args for a specific run
ls debug-output/run_*/trace/artifacts/tool_call_args/

# Read the mobile_action arguments (example)
cat debug-output/run_<ID>/trace/artifacts/tool_call_args/<seq>_turn_<N>_mobile_action_<call_id>.json
```

**Expected format (mobile_action):**
```json
{
  "action": "click",
  "element_index": 13,
  "agent_thought": "Clicking the submit button"
}
```

To get the **coordinates** for an element_index, read the corresponding sanitized a11y tree:

```bash
# Find the sanitized tree captured BEFORE the action (same turn)
cat debug-output/run_<ID>/trace/artifacts/sanitized_a11y_tree/<seq>_*.json | \
  python3 -c "import json,sys; tree=json.load(sys.stdin); elem=[e for e in tree if e.get('index')==13]; print(json.dumps(elem, indent=2))"
```

This gives `center` (x, y) and `bounds` for the target element.

### 3. Navigate to the error state

Get the device screen to the same state as just before the failed action. Options:

**Option A: Manual navigation via adb**
```bash
# Use the screenshot from the trace as reference
# Open it to see what app/screen was visible
open debug-output/run_<ID>/trace/artifacts/screenshot/<seq>_*.jpg

# Navigate using adb
adb shell am start -n com.example.app/.MainActivity
adb shell input tap <x> <y>  # Navigate step by step
```

**Option B: Replay earlier actions**
```bash
# Replay the preceding actions from the trace to reach the target state
# Read steps.jsonl to see the sequence
python3 -c "
import json
for line in open('debug-output/run_<ID>/trace/derived/steps.jsonl'):
    s = json.loads(line)
    calls = s.get('tool', {}).get('calls', [])
    for c in calls:
        if c.get('name') == 'mobile_action':
            print(f\"turn={s['turn_number']}  action={c.get('arguments', {}).get('action')}  args={c.get('arguments')}\")
"
```

### 4. Test with action-test.sh

Run the action in isolation using different execution paths to identify the failure layer.

**L0 baseline (adb input — does the action work at all?):**
```bash
./scripts/action-test.sh click --x 540 --y 1200 --adb
```

**L1 node action (accessibility node click — default):**
```bash
./scripts/action-test.sh click --x 540 --y 1200
```

**L1 gesture (accessibility gesture injection):**
```bash
./scripts/action-test.sh click --x 540 --y 1200 --use-node false
```

**Side-by-side comparison (runs adb then a11y):**
```bash
./scripts/action-test.sh click --x 540 --y 1200 --compare
```

**Scroll/swipe:**
```bash
./scripts/action-test.sh scroll --direction down
./scripts/action-test.sh swipe --start-x 540 --start-y 1400 --end-x 540 --end-y 600
```

**With tag for organized output:**
```bash
./scripts/action-test.sh click --x 540 --y 1200 --tag "issue_42_click_submit"
```

### 5. Analyze results

Results are in `debug-output/action-test/[tag or latest]/`:

```bash
# Read the result verdict
cat debug-output/action-test/latest/result.json | python3 -m json.tool
```

**Key fields in result.json:**
```json
{
  "action": "click",
  "action_accepted": {
    "status": "success",      // Transport-level: did the a11y API accept it?
    "message": "..."
  },
  "ui_changed": {
    "verdict": "unchanged",   // Effect-level: did the screen actually change?
    "element_count_before": 42,
    "element_count_after": 42
  },
  "elapsed_ms": 150,
  "settle_ms": 350
}
```

**Interpret the result:**

| action_accepted | ui_changed | Diagnosis |
|-----------------|------------|-----------|
| success | changed | Action worked correctly |
| success | unchanged | **FALSE SUCCESS** — a11y reported success but nothing changed |
| failure | unchanged | Expected failure — element not found/not actionable |
| failure | changed | Rare — action "failed" but something coincidentally changed |

**Compare screenshots:**
```bash
# View before/after screenshots
open debug-output/action-test/latest/before_a11y.png debug-output/action-test/latest/after_a11y.png
```

**Compare a11y trees:**
```bash
# Diff the pre/post trees to see what changed (or didn't)
diff <(python3 -m json.tool debug-output/action-test/latest/pre_tree.json) \
     <(python3 -m json.tool debug-output/action-test/latest/post_tree.json) | head -50
```

### 6. Classify the execution root cause

Based on the comparison across execution paths:

| Pattern | Root Cause | Fix Location |
|---------|------------|--------------|
| ADB works, a11y node fails | Node targeting issue | `NodeActionPerformer.kt` or element bounds |
| ADB works, a11y gesture fails | Gesture injection issue | `AccessibilityGestureInjector.kt` |
| Both a11y paths fail, adb works | A11y service limitation | Consider gesture fallback in executor |
| All three fail | Element not interactable at those coords | Perception or coordinate calculation |
| Node succeeds, gesture succeeds, but executor fails | Executor logic bug (target resolution, fallback) | `tool/action/*.kt` executors |
| Action works but wrong element clicked | Coordinate mismatch | `Perceptor.kt` bounds/center calculation |
| Action works on first run, fails on repeat | Timing/settling issue | Increase `--settle` or add wait |

### 7. Document findings and propose fix

Write a brief report:

```
## Action Debug Report

**Source**: debug-run `run_20260219_172029`, turn 3, mobile_action call_abc123
**Action**: click element_index=13 (Submit button) at (540, 1200)

### Test Results
| Path | action_accepted | ui_changed |
|------|----------------|------------|
| L0 adb | n/a (manual verify) | changed |
| L1 node | success | **unchanged** |
| L1 gesture | success | changed |

### Root Cause
Node-based click on this element is a false success. The element has
`clickable=true` but the click handler is on a parent FrameLayout that
doesn't receive the accessibility click event.

### Proposed Fix
- Option A: Add gesture fallback in ClickExecutor when node click produces no UI change
- Option B: Fix target resolution to click the parent container instead

### Verification
Re-run with: `./scripts/action-test.sh click --x 540 --y 1200 --tag verify_fix`
```

## action-test.sh Quick Reference

```
Usage: ./scripts/action-test.sh <action> [options]

Actions:
  click       Click at coordinates (node action or gesture)
  tap         Gesture tap at coordinates
  long_press  Long press at coordinates
  scroll      Scroll in direction
  swipe       Swipe between coordinates

Execution path options:
  (default)              L1 node action via a11y
  --use-node false       L1 gesture injection via a11y
  --adb                  L0 adb input baseline
  --compare              Run L0 then L1, compare

Coordinate options:
  --x N, --y N           Coords for click/tap/long_press/scroll
  --start-x/y, --end-x/y  Coords for swipe
  --direction DIR        Scroll direction (up/down/left/right)
  --duration N           Duration in ms (long_press/swipe)

Output options:
  --tag NAME             Name output subdirectory
  --open                 Auto-open screenshots
  --no-tree              Skip a11y tree capture
  --settle N             Post-action settle delay ms (default: 350)

Output: debug-output/action-test/[tag or latest]/
  result.json            Action result with verdict
  before_*.png           Screenshot before action
  after_*.png            Screenshot after action
  pre_tree.json          A11y tree before (unless --no-tree)
  post_tree.json         A11y tree after (unless --no-tree)
```

## Extracting Action Parameters from Traces

### From mobile_action tool calls

The agent's `mobile_action` tool uses `element_index` (not raw coordinates). To get coordinates for `action-test.sh`:

1. **Find the a11y tree for that turn**:
   ```bash
   ls debug-output/run_*/trace/artifacts/sanitized_a11y_tree/
   ```

2. **Look up element by index**:
   ```bash
   python3 -c "
   import json, sys
   tree = json.load(open('path/to/sanitized_a11y_tree.json'))
   idx = 13  # target element_index
   elem = next((e for e in tree if e.get('index') == idx), None)
   if elem:
       cx, cy = elem.get('center', [0,0])
       print(f'Coordinates: --x {cx} --y {cy}')
       print(f'Bounds: {elem.get(\"bounds\")}')
       print(f'Text: {elem.get(\"text\", \"\")}')
       print(f'Clickable: {elem.get(\"clickable\")}')
   else:
       print(f'Element {idx} not found in tree')
   "
   ```

3. **Run action-test.sh with extracted coordinates**:
   ```bash
   ./scripts/action-test.sh click --x <cx> --y <cy> --tag "trace_turn3_elem13"
   ```

### For scroll actions

Scroll typically uses screen center. Check the trace for any custom coordinates:
```bash
cat debug-output/run_*/trace/artifacts/tool_call_args/*scroll*.json
# Look for: {"action": "scroll", "direction": "down", "element_index": 5}
# element_index gives the scroll container - look up its center coords
```

### For swipe actions

Extract start/end coordinates from the tool call args:
```bash
cat debug-output/run_*/trace/artifacts/tool_call_args/*swipe*.json
```

## Project References

- Action test harness: `scripts/action-test.sh`
- Debug action receiver: `app/.../debug/ActionDebugReceiver.kt`
- Debug action executor: `app/.../debug/DebugActionExecutor.kt`
- Node action performer: `app/.../platform/NodeActionPerformer.kt`
- Gesture injector: `app/.../platform/AccessibilityGestureInjector.kt`
- Tool executors: `app/.../tool/action/` (ClickExecutor, ScrollExecutor, etc.)
- UI change detector: `app/.../tool/action/UiChangeDetector.kt`
- Perceptor: `app/.../perception/Perceptor.kt`
- Cognition tuning: `/cog-tune` skill (for LLM reasoning issues)
- Debug runs: `scripts/debug-run.sh`
- Trace replay: `inspection_tool/replay_compiler.py`
