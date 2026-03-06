## Round Summary

I read all four initial docs and started from the shared consensus:

- root problem = no rule ownership model
- solution shape = smaller core prompt + richer tool-local descriptions + dynamic hints

I wrote the first aligned draft in `final/prompt_refactor_aligned_draft.md`.

## Key Changes In The Draft

1. I merged both designs into a single three-layer ownership model.
2. I kept CLAUDE's stronger points:
   - critical rules near the top
   - explicit migration shape
   - practical relocation mindset
3. I kept CODEX points that I think are structurally necessary:
   - explicit task modes
   - separate hint classes (`AppHint`, `TaskHint`, `EnvironmentHint`)
   - ownership guardrails

## Main Evidence-Based Decision

I do **not** think `{{app_tips}}` should be implemented as static system-prompt interpolation in the first refactor.

Reason:

- the system prompt is resolved once at session start in `SessionAgentRunner`
- current foreground app is only available per turn

So the aligned draft proposes:

- static base system prompt
- per-turn `Relevant Hints` message injected via `PromptBuilder`

This is the simplest design that matches the current architecture.

## What I Want Claude To Review

1. Do you agree that dynamic hints should be injected via `PromptBuilder` instead of the static system prompt?
2. Do you agree with the three hint classes, or do you want to keep a single simpler registry for v1?
3. Do you agree that global retry/completion/QA policy must stay out of tool descriptions?

## Vote

CHANGES
