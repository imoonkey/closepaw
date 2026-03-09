# Review: `r8r9_next_steps_claude.md`

## Verdict

Changes requested.

The memo has a solid high-level read on the failure mix, and Fix 2 / Fix 4 / most of Fix 5 are directionally useful. But two of the highest-priority items are built on incorrect root-cause calls, and that distorts the whole R10 priority table.

## Critical

### 1. `Fix 1` is prioritized as the top blocker, but the cited evidence does not support an app-skill injection failure

The memo claims at [doc/autotune/round_9/r8r9_next_steps_claude.md](doc/autotune/round_9/r8r9_next_steps_claude.md) lines 13-31 and 260-268 that "app skill not injected" accounts for about 7 failures and should be the first R10 fix. I spot-checked the actual trace artifacts and found the opposite for the cited examples:

- `TasksHighPriorityTasks`: `eval/results/20260307_064200/artifacts/aw_20260307_064200_TasksHighPriorityTasks_17_0/trace/artifacts/llm_input_items/26_turn_2_llm_input_items.json`
- `TasksDueNextWeek`: `eval/results/20260307_080143/artifacts/aw_20260307_080143_TasksDueNextWeek_19_0/trace/artifacts/llm_input_items/26_turn_2_llm_input_items.json`
- `TasksCompletedTasksForDate`: `eval/results/20260307_080143/artifacts/aw_20260307_080143_TasksCompletedTasksForDate_18_0/trace/artifacts/llm_input_items/26_turn_2_llm_input_items.json`
- `VlcCreateTwoPlaylists`: `eval/results/20260307_071709/artifacts/aw_20260307_071709_VlcCreateTwoPlaylists_19_0/trace/artifacts/llm_input_items/39_turn_3_llm_input_items.json`

All of those contain an `## App Skill` message.

The likely methodology bug is that the supporting analyses inspected `llm_system_prompt`, but app skills are not inserted into the system prompt. They are appended as user-context input items by [PromptBuilder.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptBuilder.kt#L20) and [PromptBuilder.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptBuilder.kt#L43). That means the memo is spending its top priority on a likely phantom bug. Before R10 is planned around Fix 1, the injection claim needs to be re-audited from `llm_input_items`, not from `llm_system_prompt`.

### 2. `Fix 6` targets the wrong layer: the multiline loss is in perception/prompt serialization, not clearly in `ACTION_SET_TEXT`

The memo states at [doc/autotune/round_9/r8r9_next_steps_claude.md](doc/autotune/round_9/r8r9_next_steps_claude.md) lines 208-234 that `ACTION_SET_TEXT` or Markor collapses `\n\n` to `\n`. The artifacts and code do not support that:

- Raw tree after the type action still contains `\n\n`: `eval/results/20260307_061407/artifacts/aw_20260307_061407_MarkorAddNoteHeader_4_0/trace/artifacts/raw_a11y_tree/243_raw_1697384150500.json`
- The tool-observation screen shown back to the model contains only `\n`: `eval/results/20260307_061407/artifacts/aw_20260307_061407_MarkorAddNoteHeader_4_0/trace/artifacts/tool_observation_screen/102_turn_6_mobile_action_call_ZHoHV06SzG6hIjma36Xk9fc4_screen.json`
- The collapse exists in code: [PerceptorInternals.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/perception/PerceptorInternals.kt#L230) replaces `\\n{2,}` with `\n`

So the reviewed evidence points to prompt-facing normalization, not action execution.

The memo also says the current post-action verification could "pass because the base text is still present." That is not true for the shown case: [NodeActionPerformer.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/platform/NodeActionPerformer.kt#L235) already fails when refreshed text differs and does not contain the requested text verbatim. If `\n\n` had actually become `\n` at that point, this check should fail.

R10 should not spend time on clipboard/paste fallbacks until the perception-layer newline collapse is addressed or ruled out.

## High

### 3. `Fix 1`'s recommended session-sticky design can inject the wrong skill on later turns

Even if an injection race existed, the proposed code at [doc/autotune/round_9/r8r9_next_steps_claude.md](doc/autotune/round_9/r8r9_next_steps_claude.md) lines 35-49 is unsafe. It searches `currentPackage + detectedPackages` and returns the first skill that loads. In any cross-app task, a transient `null` or unknown package could cause an old app skill to be injected on the wrong screen.

Concrete failure mode: read data in Markor, switch to Expense, current package is briefly null, and the agent receives the stale Markor skill while looking at Expense. That is worse than no skill. If this idea is kept at all, it needs strict package scoping, not a session-wide fallback set.

### 4. `Fix 3` Option B is based on an outdated code assumption and will not address the actual Tasks row problem

The memo says at [doc/autotune/round_9/r8r9_next_steps_claude.md](doc/autotune/round_9/r8r9_next_steps_claude.md) lines 133-139 that `checkable` should be emitted. That already happens today in [Perceptor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt#L173).

More importantly, the task-row left icons in the raw Tasks trace are `ImageView` nodes with `checkable=false`, so emitting `checkable` more aggressively does not solve the row-state ambiguity. The useful part of Fix 3 is Option A's diff strategy. Option B is a no-op, and Option C is only a small general improvement.

### 5. `Fix 7` conflicts with the repo's prompt-tuning ownership model

The memo proposes adding a worked example directly to the core prompt at [doc/autotune/round_9/r8r9_next_steps_claude.md](doc/autotune/round_9/r8r9_next_steps_claude.md) lines 245-252. The repo's own tuning guidance says the opposite: [prompt-tune/SKILL.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/.ai-dev/skills/prompt-tune/SKILL.md#L51) says "No examples in the core prompt - those belong in tool descriptions."

This matters because the memo is not only proposing a prompt change, but proposing it in the wrong layer. If a batching example is needed, it belongs in the relevant tool description or another constrained layer, not the global prompt.

## Medium

### 6. `Fix 5a` mixes solid observations with unsupported app-specific lore

The Simple Calendar task evidence supports three things:

- month-grid cells are not individually exposed in a11y
- typing into the picker spinner is unreliable
- the agent should verify the visible date before answering

But the memo also adds "long-press the month/year header" and "find Go to date in overflow" at [doc/autotune/round_9/r8r9_next_steps_claude.md](doc/autotune/round_9/r8r9_next_steps_claude.md) lines 172-179. I did not see support for those exact affordances in the reviewed task analysis. That makes this part of the skill a phantom rule risk.

### 7. The projected R10 uplift is not grounded after the root-cause errors above

The table at [doc/autotune/round_9/r8r9_next_steps_claude.md](doc/autotune/round_9/r8r9_next_steps_claude.md) lines 260-290 assumes:

- Fix 1 unblocks 7+ tasks
- Fix 3 depends on Fix 1 and unblocks 6 more
- Together they move the run to roughly 72-75%

Because Fix 1 and part of Fix 6 are misdiagnosed, those lift estimates are not reliable. The impact model should be recomputed after the memo is corrected to distinguish:

- skill injected but ignored
- perception bug
- actual missing package/skill cases, if any

### 8. The memo misses a cheap cross-cutting fix from the analyzed failures: exact-target and postcondition verification for file operations

`FilesMoveFile` is a straightforward example: the agent moved `holiday_photos_ZYUM.jpg` instead of `holiday_photos.jpg`, then failed to verify that the real target still existed in the source folder. That is not a hallucination problem; it is an exact-match grounding and postcondition-check problem. The current memo never turns that into an explicit fix, even though it is likely low effort and generalizable to other file/edit tasks.

## Recommended Rewrite

The memo should be revised around this ordering:

1. Keep `Fix 2` as a real R10 candidate.
2. Keep `Fix 4` as a real R10 candidate.
3. Keep Retro / Calendar app-skill improvements, but remove unsupported Calendar steps.
4. Replace `Fix 1` with a narrower audit: verify actual missing-skill cases from `llm_input_items` before proposing code.
5. Replace `Fix 6` with a perception-layer multiline-preservation investigation.
6. Add an explicit exact-target/postcondition verification fix for file/edit tasks.

## Bottom Line

The memo is strongest when it addresses evidence-discipline failures in the model. It is weakest where it promotes implementation-specific fixes without revalidating the traces against the current codebase and prompt assembly path. Correcting those two root-cause mistakes would materially improve the R10 plan.
