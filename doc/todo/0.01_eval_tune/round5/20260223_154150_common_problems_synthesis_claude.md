# Common Problems Synthesis: eval/results/20260223_154150

**Run**: 20260223_154150 | **Model**: qwen3.5 (qwen/qwen3.5-plus-02-15) | **Success Rate**: 10/14 (71.4%)

---

## Overall Assessment

The agent performs well on **structured form-filling tasks** (Contacts, Expense, Markor, Recipe, Settings toggles) — all near-optimal efficiency. It struggles critically with **complex multi-step tasks** requiring state tracking, unfamiliar UI patterns, or recovery from unexpected situations. All 4 failures share the same termination: MaxTurnsReached at 30 turns.

---

## Common Problem 1: Infinite Loops / No Escape Mechanism

**Severity**: CRITICAL | **Affected**: 3/4 failures (ClockTimerEntry, FilesMoveFile, SimpleSmsSend)

### Pattern
The agent gets stuck repeating the same action (or action sequence) when it doesn't produce the expected result, with no mechanism to detect the loop or break out.

### Evidence
| Task | Loop Pattern | Repetitions | Turns Wasted |
|------|-------------|-------------|--------------|
| ClockTimerEntry | press "1" → backspace | 8 cycles | 16 turns (16-30) |
| FilesMoveFile | long_press on file | 12 consecutive | 12 turns (19-30) |
| SimpleSmsSend | compose → type number → fail confirm → back | 3 full cycles | 27 turns (4-30) |

### Root Cause Classification
- **Reasoning**: The model doesn't compare pre-action and post-action states to detect "no progress."
- **Context**: No loop-detection policy in the prompt or agent framework.

### Recommended Fix
1. **Action-observation comparison**: After each action, compare the pre-action and post-action a11y trees. If they're identical (or near-identical) for N consecutive turns, force a strategy change or reflection step.
2. **Prompt policy**: Add a policy like "If the same action has been attempted 2+ times without visible change, stop and try a completely different approach."
3. **Turn-budget awareness**: Add remaining-turns-awareness so the agent prioritizes completion as budget depletes.

---

## Common Problem 2: False Success — Action Reports Success But No UI Change

**Severity**: HIGH | **Affected**: 3/4 failures (BrowserMultiply, FilesMoveFile, SimpleSmsSend)

### Pattern
The tool execution layer reports `success: true` but the screen state is unchanged after the action. The agent trusts the success report and doesn't independently verify via the a11y tree.

### Evidence
| Task | Action | Times | Reported | Actual |
|------|--------|-------|----------|--------|
| BrowserMultiply T5 | click("No thanks") | 1 | SUCCESS | Screen unchanged (still "Turn on sync?") |
| BrowserMultiply T20-21 | scroll/swipe on WebView | 2 | SUCCESS | Same elements visible |
| FilesMoveFile T19-30 | long_press(file) in move dialog | 12 | SUCCESS | No selection bar appeared |
| SimpleSmsSend T5-6,12-13,16,22-23,27 | click(confirm) on contact field | 8 | SUCCESS | Screen unchanged |

### Root Cause Classification
- **Execution**: The action physically executes (tap dispatched to coordinates, long press held) but the target element doesn't respond as expected in the current context.
- **Observation**: The agent doesn't validate success by checking post-action state changes.

### Recommended Fix
1. **Post-action state diff**: The observation layer should automatically compare the a11y tree before and after an action. If no elements changed and the action was supposed to be interactive (click, long_press), flag it as a **potential false success**.
2. **Observation enrichment**: Include a `screen_changed: true/false` field in the tool result observation.
3. **Agent prompt**: "If a click/tap action returned success but the screen elements are unchanged, consider the action may have missed its target. Try a different element or approach."

---

## Common Problem 3: Loss of Progress Context Over Long Histories

**Severity**: HIGH | **Affected**: 2/4 failures (BrowserMultiply, ClockTimerEntry)

### Pattern
As the conversation history grows (20+ turns), the agent loses track of what it has already accomplished and restarts completed steps.

### Evidence
| Task | What Happened |
|------|--------------|
| BrowserMultiply | After completing 5 button clicks and finding the form (turn 22), the agent scrolled back and started clicking the button AGAIN from scratch (turns 23-28). Scratchpad showed "9,10,9,5" but later read "9,10,9" — 4th number lost. |
| ClockTimerEntry | After entering 1-6-3 correctly and seeing the intermediate "01m 63s", the agent cleared everything and couldn't re-enter the sequence, entering a 2-step loop instead. |

### Root Cause Classification
- **Context**: Long history dilutes the signal about already-completed steps.
- **Reasoning**: The model doesn't maintain a clear mental model of task progress state.

### Recommended Fix
1. **Structured progress tracking**: Use scratchpad/todo more aggressively to track completed sub-steps.
2. **History summarization**: After N turns, compress older turns into a progress summary.
3. **Prompt engineering**: "Before each action, review your progress. What sub-steps have been completed? What remains?"

---

## Common Problem 4: Unfamiliar UI Interaction Patterns

**Severity**: MEDIUM | **Affected**: 3/4 failures (ClockTimerEntry, FilesMoveFile, SimpleSmsSend)

### Pattern
The agent fails when it encounters UI interaction patterns it hasn't seen before and can't figure out the correct sequence.

### Evidence
| Task | Unknown Pattern | What Agent Needed to Do | What Agent Did |
|------|----------------|------------------------|----------------|
| ClockTimerEntry | Timer digit keypad (digits push left) | Just type 1-6-3-5 sequentially | Panicked at "63s" intermediate state, cleared and looped |
| FilesMoveFile | "Move to..." dialog — confirm button | Scroll down to find "Move here" button in DCIM | Navigated away, then tried selecting files in destination picker |
| SimpleSmsSend | SMS contact number confirmation | Unknown — possibly tap the number, swipe, or different Enter mechanism | Tried every visible button but none worked |

### Root Cause Classification
- **Reasoning**: Model lacks familiarity with specific Android app UI patterns.
- **Execution**: Some UI patterns require specific interaction mechanisms not covered by the tool's simple click/type/swipe primitives.

### Recommended Fix
1. **App-specific knowledge in prompts**: Add common interaction patterns for frequently-tested apps.
2. **Interactive exploration policy**: "If you don't know how to proceed, try: (a) scrolling to reveal hidden buttons, (b) tapping on the content directly, (c) using scratchpad to reflect on what you see."
3. **Tool enhancement**: Consider adding specialized tools for specific UI patterns (e.g., `confirm_input` for text field submission).

---

## Common Problem 5: WebView / Chrome Interop Issues

**Severity**: LOW (affects 1 task) | **Affected**: BrowserMultiply

### Pattern
WebView content doesn't respond well to scroll/swipe via accessibility, and Chrome's first-run flow adds significant overhead.

### Evidence
- Turns 4-8: Chrome first-run (Accept, Sync, accidentally Google Sign-in) — 5 wasted turns
- Turns 20-21: Swipe on WebView reported success but content didn't scroll — a11y tree was identical

### Recommended Fix
1. **Chrome pre-configuration**: Pre-dismiss Chrome first-run dialogs in eval setup to avoid polluting agent turn budget.
2. **WebView scroll fallback**: If a11y-based scroll fails in a WebView, try coordinate-based scrolling with specific heuristics for web content areas.

---

## Priority-Ordered Improvement Recommendations

| Priority | Problem | Impact | Effort |
|----------|---------|--------|--------|
| P0 | Loop detection + break-out policy | Prevents 3/4 failures from wasting 12-27 turns | Low — prompt/policy change |
| P0 | Post-action state-change detection | Prevents false-success blindness in 3/4 failures | Medium — observation layer change |
| P1 | Progress tracking / context management | Prevents context-loss resets in 2/4 failures | Medium — prompt + scratchpad usage |
| P1 | UI pattern knowledge | Helps with 3/4 failures | Low — prompt additions |
| P2 | Chrome/WebView handling | Helps with 1 failure | Low — eval setup + scroll fallback |

---

## Metrics Summary

| Metric | Value |
|--------|-------|
| Scripted success rate | 71.4% (10/14) |
| Goal claim precision | 100% (no false completions) |
| Tool failure rate | 0.48% (1/208 total calls) |
| Avg turns (success) | 8.1 |
| Avg turns (failure) | 30.0 (all maxed) |
| Duration p50 | 74.6s |
| Duration p90 | 222.3s |

**Positive signals**: The agent never falsely claims task completion (100% precision). When it succeeds, it's efficient. The problem is entirely about handling failure modes and complex interaction patterns.
