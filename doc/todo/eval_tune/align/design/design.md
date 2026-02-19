# Eval Tune: Aligned Problem Analysis and Fix Design

**Eval run**: `eval/results/20260218_145836`  
**Model**: `qwen/qwen3.5-plus-02-15`  
**Corrected baseline**: 6/13 (46%) after removing ContactsAddContact false positive  
**Target**: scripted_success_rate >= 0.70

---

## 1. Problem Classification (Aligned)

### P1: Inline pseudo-tool calls are not recovered in prose (4 runs, 3 failures + 1 false positive)

**Affected**: MarkorCreateNote, FilesMoveFile, ContactsAddContact, SimpleSmsSend  
**Evidence**:
- `Turn.kt` already has `recoverToolCallFromText()`, but inline path uses `matchEntire()` on `toolName{...}`.
- Real failing outputs include prose plus tool-like text (for example `46_turn_3_assistant.txt`, `71_turn_4_assistant.txt`, `64_turn_4_assistant.txt`), so full-string match fails.
- Result is `llm_tool_calls=[]` and turn closes as complete.

**Aligned root cause**: Existing recovery is too strict on shape, not missing in principle.

### P2: Premature completion (`is_complete=true`) when actionable work is still pending (same 4 runs)

**Root cause in code**:
- `Turn.kt` sets `isComplete = completeTaskCall != null || (toolCalls.isEmpty() && effectiveTextContent != null)`.
- When P1 recovery misses, `toolCalls` stays empty and text path is treated as completion.

**Aligned view**: P2 is mostly downstream of P1, but a lightweight guardrail is still needed for malformed pseudo-calls.

### P3: WebView click reliability (BrowserMultiply)

**Affected**: BrowserMultiply  
**Pattern**: click dispatched but no UI change; retries target stale coordinates.

**Aligned view**:
- Keep first-pass fix practical in accessibility stack (re-resolve target + jitter + retry policy).
- Defer JavaScript injection to a later iteration unless needed by evidence.

### P4: Session start race drops the pending goal (ClockTimerEntry timeout, 0 turns)

**Affected**: ClockTimerEntry  
**Evidence from logcat**:
- Main activity receives goal and launches accessibility settings when `AgentService.instance == null`.
- Service connects later.
- No `TaskStarted` / no active session afterwards; run times out.

**Aligned root cause**: goal is attempted once and not retried after accessibility becomes ready.

### P5: Completion reason contamination from unrelated logcat lines (`volume_controller`)

**Affected**: ClockTimerEntry (`agent_completion_reason="volume_controller"`)  
**Root cause in code**:
- `completion_monitor.py` stores `reason = reason or _extract_reason(line)` for every line before completion/error is matched.
- This captures unrelated system lines with `reason: ...` and leaks into timeout result.

**Correction**: This is in `completion_monitor.py`, not trace parser or Kotlin run summary for this run (there is no trace for ClockTimerEntry).

### P6: Missing preflight package mapping for selected tasks (3 runs)

**Affected**: RecipeAddSingleRecipe (2 infra retries), SimpleSmsSend  
**Root cause**:
- `runner.py` preflight map `_TASK_REQUIRED_PACKAGES` omits both tasks.
- Therefore `skip_unavailable_tasks` cannot filter them and targeted install flow is not triggered for these names.

### P7: Contacts cloud sync false positive (resolved operationally)

**Affected**: ContactsAddContact  
**Status**: Resolved by using emulator without Google account.  
**Follow-up**: keep note in eval environment docs/preflight expectations.

---

## 2. Priority Plan

| Priority | Fix | Problems | Impact | Effort | Primary files |
|---|---|---|---|---|---|
| 1 | Harden inline recovery + malformed-call guardrail | P1, P2 | High (3-4 runs) | Small-Med | `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt` |
| 2 | Add missing task package preflight mapping | P6 | High (3 runs) | Trivial | `eval/aw_bridge/runner.py` |
| 3 | Retry pending goal when a11y service becomes ready | P4 | Medium (1 run) | Medium | `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt` |
| 4 | Limit completion reason extraction to completion/error lines only | P5 | Medium (metrics quality) | Small | `eval/aw_bridge/completion_monitor.py` |
| 5 | Improve click fallback with re-resolve + jitter policy | P3 | Medium (1 run now, reusable) | Medium | `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt` |
| 6 | Prompt reinforcement for structured tool calling | P1 | Low-Med | Trivial | Agent definition prompts |

---

## 3. Fix Specs

### Fix 1: Inline recovery that works with prose wrappers

**File**: `Turn.kt` (`recoverToolCallFromText`, `processResponse`)

**Design**:
1. Build candidate tool names from known tools (`toolRegistry.getNames()`), intersected with `allowedToolNames` when present.
2. Search for markers like `tool_name{` in full text (not full-string match).
3. Parse JSON arguments using balanced-brace extraction from that marker (not non-greedy regex).
4. Try matches from end to start; use last valid parsed call.
5. If no valid parse, but text still contains a known tool marker, force `isComplete=false` for this turn (do not silently complete).

**Why this shape**:
- Avoid false positives on random `word{...}` text.
- Avoid broken parsing on nested braces.
- Preserve current behavior for pure structured calls.

### Fix 2: Preflight package map completion

**File**: `eval/aw_bridge/runner.py`

Add:
```python
"RecipeAddSingleRecipe": ("com.flauschcode.broccoli",),
"SimpleSmsSend": ("com.simplemobiletools.smsmessenger",),
```

Optional hardening:
- Add nearby variants used by AndroidWorld task families where applicable.
- Consider generating this map from AndroidWorld app metadata later.

### Fix 3: Pending-goal retry after accessibility readiness

**File**: `MainActivity.kt`

**Design**:
- When `ensureSessionAndSend()` sees `AgentService.instance == null`, do not drop the goal.
- Store pending auto-start goal in activity state.
- On `onStart`/`onResume`, if service becomes available and pending goal exists, re-run `ensureSessionAndSend`.
- Clear pending goal once session starts successfully.

This targets the observed ClockTimer path where settings flow returns after service bind but goal is not retried.

### Fix 4: Completion reason extraction scoping

**File**: `completion_monitor.py`

**Design**:
- Only extract reason from lines that already match completion/error patterns.
- For timeout path, return `agent_completion_reason=None` unless explicit agent completion/error line was observed.
- Keep existing completion detection patterns.

### Fix 5: WebView click fallback (first pass)

**File**: `ClickExecutor.kt`

**Design**:
- On unchanged UI after click/tap:
  - capture fresh snapshot,
  - re-resolve target (if selector-based),
  - retry with small coordinate jitter set.
- Keep attempts bounded and recorded in `attemptTrail`.
- Defer JavaScript injection until first-pass results still show WebView-specific misses.

### Fix 6: Prompt reinforcement (optional but cheap)

Add explicit instruction to agent definitions:
- "Use tool/function calling only; never emit tool calls as plain text."

Keep this low risk and low priority; parser hardening is primary.

---

## 4. Decisions From Codex Review

1. **Recovery scope**: use known-tool markers + parser validation, not broad free-text regex.
2. **Completion guardrail timing**: add lightweight guardrail now (`pseudo-call present but parse failed => not complete`), defer heavy task-specific action-chain validator until post-rerun evidence.
3. **WebView strategy**: do accessibility-side retries first; do not block on JavaScript injection feasibility.
4. **Prompt reinforcement**: include as low-cost support, but do not count on it for primary gains.
5. **Observation/context category**: keep as secondary watch item; no dedicated first-pass fix.

---

## 5. Verification Plan

1. Implement Fix 1-4 and 6 as first batch.
2. Re-run `aw_subset_core.txt` on the corrected `AndroidWorldAvd`.
3. Expected flips:
   - MarkorCreateNote: fail -> success candidate
   - FilesMoveFile: fail -> success candidate
   - RecipeAddSingleRecipe: infra failure -> skipped_missing_app or success (if app present)
   - ClockTimerEntry: timeout -> executes turns (non-zero turns)
4. Compare with `python3 eval/analysis/compare_runs.py --base eval/results/20260218_145836 --new <new_run>`.
5. Target >= 0.70 scripted success from corrected 0.46 baseline.
6. If still below target, implement Fix 5 and re-evaluate.
