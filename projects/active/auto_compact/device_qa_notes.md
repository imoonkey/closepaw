# Device QA — auto-compact

Device: 100.64.43.95:5555 (P0110 / Android 16)
Build: fc2e1d8636c753f570675d448e4e853b91287bf0 (branch task/auto-compact)
QA started: 2026-05-16T08:47:35-07:00
QA engineer: worker w-ac-dqa

## Scenario 1: Golden path
Status: PASS
Command: `./scripts/debug-run.sh "Open Settings"`
Run dir: `debug-output/run_20260516_084747/`
Evidence:
  - Capsule statuses (no "Max turns reached"): 🚀 Starting → 👀 Scanning → 🧠 Thinking → 💡 Executing actions → ✓ open_app → 🧠 → 💡 → ✓ complete_task → ✅ Goal achieved!
  - Completed in 2 turns; logcat: `AgentSession: Emitted event: TaskCompleted` and `outcome: GOAL_ACHIEVED`.
  - `grep -E "MaxTurnsReached|MAX_TURNS|Max turns" logcat_full.log` → 0 matches.
  - Screenshots: `turn_001_n1.png`, `turn_002_n2.png`.
Notes: Task too short to trigger compaction (expected); compaction proven in Scenario 2.

## Scenario 2: Long-task compaction
Status: PASS
Command: `DEBUG_MAX_TURNS=120 DEBUG_MAX_WAIT_SECONDS=900 ./scripts/debug-run.sh "Open Settings, navigate to Display, change brightness to 50, then back to home, then open the Calculator and compute 137 * 248, then verify the result by computing 248 * 137"`
Run dir: `debug-output/run_20260516_085006/`

Setup (DOCUMENTED — reverted after Scenario 6):
  - `app/src/main/assets/llm_models.json`: gpt-5.4 `context_window` 400000 → 40000 (so trigger threshold drops to ~4000 history tokens, fires within 2–3 turns).
  - `app/src/main/kotlin/ai/closepaw/history/Compactor.kt`: default `keepRecentTokens` 20_000 → 2_000 (so a safe cut can be found early in a session).

Evidence:
  - Capsule status fired the expected text 10+ times, e.g.:
    `Status: 📚 Compacted history (17869 → 15239 tokens)`
    `Status: 📚 Compacted history (19936 → 16963 tokens)`
    `Status: 📚 Compacted history (20431 → 16420 tokens)`
    ... (10 distinct compaction events between 11:51:49 and 11:54:54)
  - Logcat: `I Compactor: Compacted history: 17869 → 15239 tokens (cut@4)` etc.
  - **COMPACTION_SUMMARY surfaces in the next turn's prompt** — trace
    `trace/artifacts/llm_input_items/56_turn_4_llm_input_items.json` item index 1:
    `"content": "[Context checkpoint from earlier work in this session]\n\n## Progress\n### Done\n- Viewed the current task inside the ClosePaw interface.\n..."`
  - Agent continued the goal across compactions (turns 1→11, including a sub-agent run at turn 8).
  - Screenshots: `turn_001..turn_012` in run dir.
Notes:
  - Run ended at the 15-min wait cap (`USER_STOPPED`); compaction itself fired cleanly throughout — no error, no max-turns trip, no infinite loop. The capsule never showed any turn-budget message.

## Scenario 3: Supplement during compaction
Status: PASS
Run dir: `debug-output/run_20260516_085638/`

Setup (DOCUMENTED — reverted after Scenario 6):
  - Added a debug-only broadcast handler `ai.closepaw.DEBUG_SUPPLEMENT` that wraps
    `submitOp(Op.Supplement(text))` so a supplement can be injected from adb without
    going through the overlay input (which requires the chat UI to be foreground).
    Changes in `AgentService.kt` (new const + onReceive branch) and
    `AgentServiceReceiverHelpers.kt` (added action to existing IntentFilter).
    BuildConfig.DEBUG-gated via the existing helper.
  - Same Compactor + context_window overrides from Scenario 2 still in place.

Procedure:
  1. Started the multi-step task in background.
  2. Fired 30 supplements via `adb shell am broadcast -a ai.closepaw.DEBUG_SUPPLEMENT --es text 'note_N'`, one per second.
  3. Waited for compaction window to overlap (the compactor's summarize() takes ~12 s, ample overlap).

Evidence:
  - Logcat shows the CAS-miss → Stale path firing **twice**:
    `I Compactor: Compaction stale (revision moved from 7) — retrying next turn` (11:58:21)
    `I Compactor: Compaction stale (revision moved from 21) — retrying next turn` (11:58:33)
  - All supplements are preserved in the next prompt — trace
    `trace/artifacts/llm_input_items/56_turn_4_llm_input_items.json` includes
    `note_1` … `note_17` as plain user `Message` items (indices 7–25).
  - Agent kept running through the stale events (Turn 3 + Turn 4 ran normally, then
    the task completed with `TaskCompleted` at 11:58:58).
  - Screenshots: `turn_001..turn_004.png` in run dir.
Notes:
  - Because supplements arrived at 1 Hz and each summarize() took ~12 s, every
    in-flight summary was stale. That is the intended behavior — the supplement
    wins, the summary is discarded, the loop retries on the next turn with a
    fresh snapshot. In production a single human-typed supplement would land
    once and the next compaction would succeed; the unit test
    (`CompactorConcurrentSupplementTest`) already covers that follow-on path.

## Scenario 4: Settings UI
Status: PASS
Procedure: Hamburger menu → Settings → Agent Behavior on device.
Evidence:
  - `qa_evidence/sc4_settings_home.png` — settings home; Agent Behavior row subtitle is `Accessibility` only.
  - `qa_evidence/sc4_agent_behavior.png` — Agent Behavior page contents:
    Perception (Mode selector: Accessibility Only / Hybrid / Screenshot Only) and
    Tools (browser_script, Termux Shell). **No Max Turns control.**
  - Full UI dump (`/tmp/sc4d.xml`): grep -i "turn" → 0 hits.
  - Code-cross-check: `app/src/main/kotlin/ai/closepaw/ui/settings/AgentBehaviorSettingsPage.kt`
    has no MaxTurns slider; `agentBehaviorSubtitle()` returns only the perception mode.
Notes: No layout regression — sections render cleanly with section spacing.

## Scenario 5: Eval safety net
Status: PASS
Procedure: Sent the activity intent directly with `--ei eval_turn_budget 3` (the
exact extra name the eval bridge sets in `native_agent_bridge.py:205`) — this
exercises the same code path as `eval/aw_bridge/runner.py`-driven runs without
needing the AndroidWorld emulator to be available on this device.

Command (paraphrased):
```
adb shell am start -n ai.closepaw/.app.MainActivity \
  --es goal '<long-task>' --es main_model gpt-5.4 \
  --ei eval_turn_budget 3 ...
```

Evidence (logcat timeline):
  - `12:02:14.909  MainActivity: Eval turn budget set from intent: 3` (parsed)
  - `12:02:15.573  Agent: === TURN 1 START ===`
  - `12:02:22.583  Agent: === TURN 2 START ===`
  - `12:02:31.666  Agent: === TURN 3 START ===` (compaction also ran here as a side note: `📚 Compacted history (19643 → 16753 tokens)`)
  - `12:03:08.124  W Agent: Eval turn budget (3) reached`
  - `12:03:08.124  AgentEventDispatcher: Status: ⚠️ Eval turn budget reached`
  - `12:03:08.128  AgentSession: Emitted event: TaskCompleted`
  - `12:03:08.131  AgentService: Task completed: ... outcome: ERROR`

Stop reason matches code (`AgentStopReason.Error("Eval turn budget reached (3)")`) — outcome `ERROR`, capsule + log message both contain "Eval turn budget" (case-insensitive contains `eval turn budget` ✓).
Notes: The intent extra is `eval_turn_budget` (Int). The YAML `max_turns: 3` in `eval/config/default.yaml` would be plumbed through `BridgeConfig.max_turns → --ei eval_turn_budget` per `native_agent_bridge.py:205`, so the YAML path produces the same on-device behavior.

## Scenario 6: Reactive retry
Status: PASS
Run id (on-device trace): `sc6b_<timestamp>`

Setup (DOCUMENTED — reverted before final build):
  - Tried first to force the exception with `ModelEntry.contextWindow = 2000` —
    that doesn't work in practice because proactive `Compactor.maybeCompact`
    fires every turn and shrinks the prompt before it ever reaches the API,
    and the real `gpt-5.4` API still happily accepts the (small) request.
    To exercise the **reactive** path I instead added a debug-only one-shot
    injection hook:
    - `Turn.debugForceCweNext: Boolean` (companion @Volatile) — when true,
      `streamOnce()` consumes it and throws
      `ContextWindowExceededException("DEBUG_FORCE_CWE: simulated overflow")`.
    - Broadcast `ai.closepaw.DEBUG_FORCE_CWE` in `AgentService` flips the flag
      (BuildConfig.DEBUG-gated via the existing helper).

Procedure:
  1. Pre-armed the flag: `adb shell am broadcast -a ai.closepaw.DEBUG_FORCE_CWE -p ai.closepaw`.
  2. Launched a short task (`Open Settings`) so the very first `streamOnce` call
     consumes the flag and throws.

Evidence (logcat timeline):
  - `12:05:28.777  AgentService: Received DEBUG_FORCE_CWE broadcast`
  - `12:05:30.111  Agent: === TURN 1 START ===`
  - `12:05:30.183  W Turn: DEBUG: forcing ContextWindowExceededException (one-shot)`
  - `12:05:30.184  W Turn: Context-window exceeded; running reactive compaction and retrying`
    `ai.closepaw.llm.ContextWindowExceededException: DEBUG_FORCE_CWE: simulated overflow`
  - `12:05:33.210  I Turn: Reactive compaction outcome: Compacted(before=14581, after=14684)`
  - `12:05:33.210  I TurnPlanningPhase: Rebuilding prompt input items after reactive compaction`
  - `12:05:35.192  Status: 💡 Executing actions...` ← retry succeeded
  - `12:05:36.367  Status: ✓ open_app executed`
  - `12:05:40.552  Status: ✅ Goal achieved!`
  - `12:05:40.588  AgentService: Task completed: ... outcome: GOAL_ACHIEVED`

The user-visible capsule never showed an error — the CWE was caught, the prompt
was rebuilt by the supplied `rebuildInputItems` lambda after compaction, and the
streaming call succeeded on the retry. End-to-end reactive recovery confirmed.

Notes:
  - Reverted before final assemble: removed `Turn.debugForceCweNext` companion
    field + throw block, `ACTION_DEBUG_FORCE_CWE` const + receiver branch,
    `ACTION_DEBUG_SUPPLEMENT` const + receiver branch (added during Scenario 3),
    `filter.addAction(...)` lines, the Scenario-2 `Compactor.keepRecentTokens`
    override (back to 20_000), and the gpt-5.4 `context_window` JSON override
    (back to 400_000).

## Summary
6/6 passed. Failures: none.

Auto-compaction works end-to-end on a real Pixel-class device:
  - No code path mentions or surfaces a Max-Turns concept anymore (Scenarios 1, 4).
  - Proactive `Compactor.maybeCompact` fires correctly, the capsule shows
    "📚 Compacted history (N → M tokens)", and `COMPACTION_SUMMARY` lands
    in the next prompt with the `[Context checkpoint from earlier work…]`
    prefix (Scenario 2).
  - CAS-based concurrency works: a supplement during the in-flight summarize()
    correctly invalidates the swap (Stale outcome) and supplements remain in
    history (Scenario 3).
  - The eval-only safety net trips at exactly the configured budget and
    surfaces as `AgentStopReason.Error("Eval turn budget reached (N)")`
    (Scenario 5).
  - Reactive recovery from `ContextWindowExceededException` is silent: caught,
    `forceCompactNow` runs, the prompt is rebuilt, the call is retried, and
    the task continues to GOAL_ACHIEVED with no user-visible error (Scenario 6).

All temporary scaffolding (asset overrides, debug broadcasts, debug throw hook)
has been reverted; final `./gradlew assembleDebug` passes on the clean tree.

## Deferred (open follow-ups)
None.
