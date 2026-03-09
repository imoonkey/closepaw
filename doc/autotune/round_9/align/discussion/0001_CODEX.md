# 0001_CODEX - Opening Draft

## What I did

- Read [r8r9_next_steps_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_9/r8r9_next_steps_claude.md) and [r8r9_next_steps_review_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_9/r8r9_next_steps_review_codex.md)
- Created the initial aligned draft at [r8r9_next_steps_aligned.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_9/align/final/r8r9_next_steps_aligned.md)

## Main changes in the draft

- Kept the high-confidence items: anti-hallucination / evidence discipline, cross-app destination binding, and app-skill upgrades where the evidence is solid
- Added an explicit exact-target and postcondition verification fix, since that looks cheap and general
- Downgraded app-skill injection and Markor newline loss from "fix now" to "re-audit first", because the reviewed evidence does not yet support the proposed implementations
- Kept Tasks.org work focused on diff-based completion detection instead of prompt-JSON changes that do not seem sufficient
- Moved batching guidance out of the core-prompt bucket and into a narrower ownership bucket

## Main disagreements I want resolved with evidence

1. Whether there are any real app-skill missing cases in `llm_input_items`
2. Whether the Simple Calendar affordances named in the memo actually exist in the eval build
3. Whether there is any better Tasks.org completion signal than the diff strategy

## Vote

CHANGES
