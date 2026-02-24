# Eval 20260223_154150 — Unified Problem Analysis & Fix Proposals

**Status**: ALIGNED — Both sides approved
**Eval**: 20260223_154150 | qwen3.5 | 14 tasks | 71.4% success (10/14)
**Sources**: Claude analysis (`*_claude.md`), Codex analysis (`*_codex.md`)

---

## 1. Agreed Facts

Both analyses independently confirm:

- **4 failures**: BrowserMultiply, ClockTimerEntry, FilesMoveFile, SimpleSmsSend — all MaxTurnsReached at 30 turns.
- **10 successes**: All clean or near-optimal. CameraTakePhoto (4T), SystemWifiTurnOn (5T), SystemBrightnessMax (6T), SystemBluetoothTurnOn (6T) are all efficient. Only SystemWifiTurnOff (8T, 3 wasted turns) shows slight inefficiency.
- **100% goal claim precision**: Agent never falsely claims completion.
- **0 hard tool crashes** (1 soft failure: SimpleSmsSend T1 app name mismatch).
- **Tool execution has a critical blind spot**: Actions physically dispatch (no crashes), but the tool conflates "action dispatched" with "action succeeded." In 3/4 failures, the tool reports `success=true` when the UI didn't change — misleading the agent into thinking its actions worked. This is an **execution/observation layer deficiency**, not just a reasoning problem.
- **Reasoning compounds the execution problem**: Even when the agent could infer failure from unchanged screen state, it lacks loop-detection and strategy-switching discipline. Both layers need fixes.

---

## 2. Unified Problem Taxonomy

### P0: No-Progress Detection / Loop Breaking (a.k.a. "Circuit Breaker")

**Both agree this is the #1 issue.**

| Task | Loop Pattern | Turns Wasted |
|------|-------------|--------------|
| ClockTimerEntry | press "1" → backspace, 8 cycles | 16 (T15-T30) |
| FilesMoveFile | long_press same file, 12 times | 12 (T19-T30) |
| SimpleSmsSend | compose → type → fail confirm → back, 3 cycles | ~24 (T5-T30) |
| BrowserMultiply | Re-click buttons after form already appeared | 6 (T23-T28) |

**Proposed fix (agreed)**:
- Compare pre-action and post-action a11y tree. If identical for N consecutive turns (N=2-3), force strategy change.
- Prompt policy: "If the same action has failed to change UI 2+ times, stop and try completely different approach."
- Turn-budget awareness: agent should know remaining turns and escalate urgency.

### P0: False Success — Tool Reports Success, No UI Change

**Both agree this is critical; differ on exact scope.**

Codex counting methodology: any action where `success=true` but `UI变化=否` → 假成功可疑.
Claude counting methodology: focused on actions whose **intent** implies UI change (clicks on interactive elements).

| Task | Codex count | Claude count | Notes |
|------|------------|-------------|-------|
| BrowserMultiply | 8 | 8 | Agreement |
| ClockTimerEntry | 1 (T11) | 0 | Minor — Claude classified T11 as reasoning |
| FilesMoveFile | 14 | 12 | Codex adds T2, T10 (Show roots clicks) |
| SimpleSmsSend | 18 | 8 | **Biggest gap** — see Open Question 1 |
| ContactsAddContact | 2 (T2-T3) | 0 | See Open Question 2 |
| SystemBrightnessMin | 1 (T5) | 0 | See Open Question 3 |

**Proposed fix (agreed in principle)**:
- Add `screen_changed: true/false` to tool result observation by diffing pre/post a11y trees.
- When `screen_changed=false` after click/long_press/swipe, observation should flag this to the LLM.
- This is **observation-layer enrichment**, not a prompt-only change.

### P1: Context Loss / Scratchpad Overwrite

**Both agree.**

| Task | Evidence |
|------|----------|
| BrowserMultiply | Scratchpad overwritten from "9,10,9,5" to "9,10,9" in T24-26. After form appeared (T22-23), agent went back to click buttons again. |
| ClockTimerEntry | After entering 1-6-3 correctly, cleared and couldn't recover. |

**Proposed fix (agreed)**:
- Better scratchpad discipline: append-only for multi-step data collection.
- History summarization after N turns to compress older context.
- Prompt: "Before each action, check what you've already accomplished."

### P1: Sub-goal Completion Conditions (Codex) / Unfamiliar UI Patterns (Claude)

**Partially overlapping concepts, different framing.**

Codex frames it as: the agent doesn't have clear completion criteria for sub-goals (e.g., "am I at 00h 16m 35s?", "did I enter the message body field?").

Claude frames it as: the agent fails on unfamiliar UI interaction patterns (timer keypad digit-push, move dialog, SMS contact picker).

**These are complementary, not contradictory.** The UI pattern ignorance causes the sub-goal confusion.

| Task | Unknown Pattern |
|------|----------------|
| ClockTimerEntry | Timer digit keypad — digits push left. "01m 63s" is intermediate, not an error. |
| FilesMoveFile | Move destination picker — need to click "Move here" at bottom, not long-press files. |
| SimpleSmsSend | Contact number confirmation — need to press Enter on IME keyboard or tap the number itself to create recipient chip. |

**Proposed fix (agreed)**:
- Add app-specific interaction patterns to system prompt or knowledge base.
- General policy: "If you see an unfamiliar UI state, try scrolling to reveal hidden buttons, then try tapping content directly."

### P2: App Name Robustness

**Codex elevates this; Claude mentions it but doesn't prioritize.**

- SimpleSmsSend T1: `open_app("Simple SMS Messenger")` → failure. Recovered with `open_app("SMS Messenger")`.
- Only 1 turn wasted, successful recovery.

**Proposed fix (low priority)**:
- Fuzzy app name matching in `open_app` tool, or maintain app name aliases.

### P2: Chrome/WebView Interop

**Claude elevates this; Codex covers in BrowserMultiply analysis.**

- Chrome first-run dialogs waste 5 turns (T4-T8).
- WebView scroll via a11y dispatched gesture doesn't reliably change content.

**Proposed fix (low priority for agent, high for eval setup)**:
- Pre-dismiss Chrome first-run in eval device setup.
- WebView scroll fallback: coordinate-based scrolling.

---

## 3. Priority-Ordered Fix Roadmap

| Priority | Fix | Type | Impact |
|----------|-----|------|--------|
| P0-a | Post-action a11y tree diff → `screen_changed` flag | Observation layer | Addresses false success across all 4 failures |
| P0-b | Loop/no-progress circuit breaker (soft reflect at N=2, hard switch at N=4 consecutive no-progress turns) | Prompt + agent logic | Addresses infinite loops in 3/4 failures |
| P0-c | Add `semantic_progress` signal for key states (timer value change, message composer entered, move transaction state) | Observation layer | Reduces false positives and improves strategy switching quality |
| P1-a | Scratchpad append-only discipline + progress review prompt | Prompt | Addresses context loss in 2/4 failures |
| P1-b | App-specific UI pattern hints (timer keypad, file move, SMS compose) | Prompt / knowledge | Addresses 3/4 failures directly |
| P2-a | Fuzzy app name matching in open_app | Tool | 1 turn saved in 1 failure |
| P2-b | Chrome first-run pre-dismissal in eval setup | Eval infra | 5 turns saved in BrowserMultiply |

---

## 4. Decided Policies

### DP1: Two-Layer False Success Detection

**Resolved from OQ1.** Both sides agreed.

- **Layer 1 — Raw detector (observation layer, strict)**: `tool_success=true && screen_changed=false` → attach `no_visual_change=true` to tool result. Simple a11y tree diff, no intent classification needed. Always runs.
- **Layer 2 — Reasoning signal (prompt/agent logic)**: Escalate to `false_success_suspect=true` only when `no_visual_change` repeats for 2+ consecutive turns, or when an expected key state fails to appear.

This avoids both under-detection (ignoring dead loops) and over-reaction (penalizing benign focus clicks).

### DP2: Benign No-Change Exemption

**Resolved from OQ2.** Both sides agreed.

Rule: If `click(no_visual_change=true)` is immediately followed by a successful `type` action on the same field/context, treat the click as a benign focus-acquiring action. Do NOT increment the loop-breaker counter for this sequence.

Example: ContactsAddContact T2-T3 — click sets input focus, type succeeds. This is correct behavior, not a false success.

### DP3: Semantic Progress for Continuous Controls

**Resolved from OQ3.** Both sides agreed.

For sliders, seekbars, and other continuous-value controls where a11y tree exposes `rangeInfo` (min/max/current):
- If `current` value is unchanged after action → `no_semantic_progress=true`.
- If `current` moved toward goal but hasn't reached it → `partial_progress` (no warning).
- If `current` moved away from goal → flag as counter-productive.

This is part of P0-c implementation.

### DP4: Loop-Break Thresholds

**Resolved from alignment discussion.** Both sides agreed.

- **Soft reflect (N=2)**: After 2 consecutive turns with `no_visual_change=true && no_semantic_progress`, inject observation: "Warning: 2 consecutive actions produced no UI change. Reconsider your approach before proceeding."
- **Hard strategy switch (N=4)**: After 4 consecutive no-progress turns, inject: "CRITICAL: 4 consecutive actions with no progress. You MUST try a fundamentally different approach. Do NOT repeat the same action."

Thresholds are initial defaults; tune per eval round.
