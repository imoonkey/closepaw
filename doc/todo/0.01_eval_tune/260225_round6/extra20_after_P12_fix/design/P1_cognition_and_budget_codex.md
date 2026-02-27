# P1 Cognition and Turn Budget Design (Codex)

## Scope
- Recommendation 4: pre-completion verification (deep fix, not prompt-only)
- Recommendation 5: remove `write_todos` overhead (first phase)
- Recommendation 6: dynamic max-turn policy (clean, avoid blanket 40)

## Updated Root Cause Notes (from traces)

### CameraTakeVideo
- Agent clicked `Shutter` directly without switching to video mode.
- Agent attempted `mobile_action(action="wait")` (invalid for mobile_action; should be `wait` tool).
- After invalid action and more clicks, state shows photo artifacts (`Photo taken ...`), then agent still completed success.
- Root cause is not just "verification missing"; it is combined action-model mismatch + weak completion gate.

### AudioRecorderRecordAudioWithFileName
- Final turn did not call `complete_task`.
- Model output plain text looked like inline action (`long_press{...}`) and run ended with `GoalAchieved`.
- This is a runtime completion policy bug:
  - In `Turn.processResponse`, tool_calls empty + non-empty text => `isComplete = true`.

### ClockStopWatchPausedVerify
- Trace shows stopwatch paused state with `Start` visible before completion.
- Task definition precondition is paused-at-zero verify style.
- This one looks primarily evaluator-visibility related, not clear cognitive miss.

## Design 4: Completion Gate Rework

### Goals
- Prevent false-success completion from malformed plain text.
- Keep completion robust across models with imperfect tool-call formatting.

### Proposed changes
1. Explicit completion only in eval profile:
- Success/failure completion requires `complete_task` tool call.
- Plain text without `complete_task` does not end task.

2. Harden `Turn.processResponse`:
- Remove/feature-flag implicit completion rule:
  - Current: `toolCalls.isEmpty() && text != null` => complete
  - New eval behavior: never complete implicitly.

3. Add completion preflight checks in `TurnToolPolicy`:
- If last screen/tool action failed in recent window and no new positive evidence, block completion for this turn.
- Emit warning event: "Completion blocked: recent action failure without recovery."

4. Add goal-slot coverage check (lightweight, generic):
- Extract required literals from goal (quoted strings, obvious filenames, numbers).
- On `complete_task(status=success)`, require each required literal to appear in at least one evidence source:
  - current a11y text/content-desc,
  - recent typed inputs,
  - scratchpad values.
- If missing, block success completion and continue.

### Why this addresses your concern
- It fixes runtime completion semantics, not only prompt wording.
- It specifically covers the observed Audio failure mode.
- It reduces false success even when model "thinks it verified" incorrectly.

## Design 5: Remove write_todos Overhead (Phase 1)

### Requirement interpretation
- "先去掉" and keep re-enable path easy.

### Proposed implementation
- In eval profile (`EVAL_CLEAN`): disable `write_todos` tool and remove related prompt instruction lines.
- Keep tool implementation in codebase, not deleted.

### Optional phase 2
- Reintroduce with strict usage policy (only for long-horizon tasks) after core eval stability is restored.

## Design 6: Dynamic Turn Budget (Config-driven)

### Goals
- Avoid fixed 40 for all tasks.
- Give higher budget only to high-structure tasks.

### Proposed policy
1. Keep global base at 30.
2. Add config-driven resolver in runner:
- Inputs:
  - task name
  - task complexity (`task.complexity` when available)
  - goal text heuristics (multi-item patterns)
- Outputs:
  - per-task `max_turns` injected via intent extra.

3. Policy order
- Task explicit override (highest priority).
- Complexity threshold mapping.
- Goal heuristic bonus.
- Clamp in `[min_turns, max_turns_cap]`.

### Example config
```yaml
bridge:
  max_turns: 30
  turn_budget:
    enabled: true
    min_turns: 24
    max_turns_cap: 50
    task_overrides:
      ExpenseAddMultiple: 42
      SimpleCalendarAddOneEvent: 42
      SimpleCalendarDeleteOneEvent: 42
    complexity_rules:
      - min_complexity: 1.6
        turns: 38
      - min_complexity: 2.0
        turns: 44
    goal_bonus_rules:
      - regex: "\\b(three|3|multiple|repeating|each)\\b"
        bonus: 6
```

### Anti-token-waste companion
- Add stagnation early-stop guard:
  - if same action signature repeats N times with no UI delta, force stop with failure summary.
- This keeps simple tasks cheap while protecting from infinite loops.

## Validation Plan
1. Replay critical tasks:
- `CameraTakeVideo`, `AudioRecorderRecordAudioWithFileName`, `ClockStopWatchPausedVerify`.
2. Check no implicit completion in trace:
- Completion only when `complete_task` is called.
3. Evaluate budget behavior:
- simple tasks remain near 30,
- targeted long tasks receive uplift,
- no blanket increase.

## Risks and mitigations
- Risk: strict completion gate may increase MaxTurns on ambiguous tasks.
  - Mitigation: pair with dynamic budget + stagnation stop.
- Risk: slot-coverage false blocks in some apps.
  - Mitigation: make slot checker conservative (only high-confidence literals).
