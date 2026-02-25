# Rollout Plan

## Implementation Order

### Phase 1: P0 Foundations (Unblock Infrastructure Failures)

| Item | Description | Expected Impact |
|---|---|---|
| P0-1 | Add eval app aliases to `PACKAGE_MAP` | Saves 12-13 turns per calendar task |
| P0-2 | System prompt ask_user guidance + `excluded_tools` config mechanism | Unblocks 6 ASK_USER_BLOCKED tasks |
| P0-3 | Preserve AccessibilityForwarder in eval bridge | Fixes false negatives from validator blindness |

**Eval checkpoint A**: Run full eval. Expect major reduction in zero-progress failures. ASK_USER_BLOCKED category should drop to 0. Calendar tasks should reach productive turns.

### Phase 2: P1 Cognition and Budget (Fix False Successes, Save Turns)

| Item | Description | Expected Impact |
|---|---|---|
| P1-4 | Fix implicit completion in Turn.processResponse + verification prompt | Fixes AudioRecorder/CameraTakeVideo false successes |
| P1-5 | Add `write_todos` to `excluded_tools` + comment out prompt | Saves 2-5 turns per task |
| P1-6 | Per-task `max_turns` override + stall detection | Gives ExpenseAddMultiple 45 turns; catches loops |

**Eval checkpoint B**: Run full eval. Expect fewer premature completions and more turns available for productive work. Stall detection should terminate looping tasks early.

### Phase 3: P3 Diagnostics (Faster Debugging)

| Item | Description | Expected Impact |
|---|---|---|
| P3-9 | Write `scoring_context.json` per task | Enables quick false-negative triage |

**Eval checkpoint C**: Run full eval. Verify `scoring_context.json` is present for all tasks. Validate that any remaining score=0.0 can be attributed quickly using the diagnostics.

### Phase 4: P2 Capability Expansion (New Modalities)

| Item | Description | Expected Impact |
|---|---|---|
| P2-7 | Wire `perception_mode` from eval config to PerceptionConfig | Unblocks BrowserDraw/BrowserMaze (with vision model) |
| P2-8 | Add shell tool with direct execution | Enables 1-turn file reading for Markor tasks |

**Eval checkpoint D**: Run targeted tasks (BrowserDraw with vision model, ExpenseAddMultipleFromMarkor with shell tool). Measure turn reduction and success delta.

## Why This Order

- **P0 first**: Unblocks the most tasks (8/20) and is the simplest to implement
- **P1 second**: Fixes bugs and saves turns, building on the newly-unblocked tasks from P0
- **P3 before P2**: Diagnostics help debug any remaining failures before adding new capabilities
- **P2 last**: Adds structural capability after baseline stability is restored; also depends on model upgrade (vision) for screenshot impact

## KPI Tracking

- Primary: unique-task pass rate (currently 1/20 = 5.0%)
- Secondary: infrastructure health (`a11y_service_enabled_at_scoring == true` for all tasks)
- Tertiary: average productive turns per task (turns minus wasted app-resolution + ask_user + write_todos turns)

## Open Questions

1. **ClockStopWatchPausedVerify task init**: Check AndroidWorld's `initialize_task()` to determine if the stopwatch should have been running when the agent started. This affects whether P1-4 prompt guidance needs task-specific additions.
