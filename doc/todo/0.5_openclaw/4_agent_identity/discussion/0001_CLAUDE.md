# 0001 - Claude: Initial aligned draft

## What I changed

- Created `final/design.md` as a self-contained aligned design.
- Created `final/implementation_plan.md` to turn the design into phased work.

## Alignment direction

The two initial designs agree on the core problem:
- the current role prompts are hardcoded Kotlin strings
- personality and role contract should not be mixed
- tool/app guidance must stay outside the identity layer

I merged them by taking:
- Claude's file-based authoring approach
- Codex's stricter ownership split between immutable role contract and configurable identity profile

## Main decisions in the draft

1. Role contract becomes file-authored, but remains app-owned and not user-configurable.
2. Identity profile becomes session-selected and is the only configurable personality layer in v1.
3. Planner and executor inherit the same selected identity profile.
4. Tool descriptions and app skills remain in their existing ownership layers.
5. No task- or app-based auto persona switching in v1.

## Vote

CHANGES
