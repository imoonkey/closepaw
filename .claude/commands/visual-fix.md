# Visual Fix

Debug Android Agent issues using visual inspection + log analysis.

> **Reference:** [scripts/agent_process_visual_debug.md](../../scripts/agent_process_visual_debug.md)

## Instructions

1. **Run debug script**
   ```bash
   ./scripts/debug-run.sh "$ARGUMENTS"
   ```

2. **Analyze turn-by-turn** in `debug-output/`:
   - `turn_N.png` - What's actually on screen?
   - `turn_N_log.txt` - What did agent perceive/decide?

3. **Identify issue type:**
   | Symptom | Likely Issue |
   |---------|--------------|
   | Same action repeating | Observation not captured |
   | Wrong action chosen | LLM reasoning or perception |
   | Action has no effect | Element not clickable, timing |

4. **Apply targeted fix** to:
   - `agent/Agent.kt` - ReAct loop, system prompt
   - `agent/Turn.kt` - LLM call handling
   - `perception/Perceptor.kt` - Element extraction
   - `tool/impl/*.kt` - Tool implementations

5. **Verify fix**
   ```bash
   ./scripts/setup.sh && ./scripts/debug-run.sh "$ARGUMENTS"
   ```

## Quick Diagnostics

```bash
# Actions taken
grep -E "click|type|scroll|back|home" debug-output/orchestration.log

# Tool results
grep "ActionResult" debug-output/orchestration.log

# Errors
grep "ERROR\|Exception" debug-output/agent.log
```

## Output

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
