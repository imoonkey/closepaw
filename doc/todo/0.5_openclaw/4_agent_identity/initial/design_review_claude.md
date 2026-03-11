# Review of `design_codex.md` (Claude)

## Summary

The design has the right core insight: personality should be separated from role/tool policy, and planner/executor must inherit the same session-level identity. That is the strongest part of the proposal.

The main weakness is that it stops short of the brief's Phase 1 requirement to make prompt iteration file-based. It defines a good ownership model, but leaves too much of the prompt authoring surface in code.

## Findings

### 1. Phase 1 goal is only partially met

The brief's immediate value is "extract system prompt to files so prompt tuning does not require rebuild." This design keeps `RoleContract` code-owned and only externalizes identity/profile content.

Relevant sections:
- `RoleContract` remains code-owned: lines 166-179
- system prompt is composed from code-owned role contract plus profile: lines 116-134

Why this matters:
- Most of today's prompt mass lives in role rules, execution loop, task modes, and completion doctrine, not just identity text.
- If those sections stay in code, prompt tuning still requires code edits and rebuilds for the highest-leverage parts.

This makes the design architecturally clean, but weaker against the concrete problem statement from the brief.

### 2. Selection path is underspecified

The proposal adds `identityProfileId` to `SessionConfig` and says selection is session-scoped, but it does not define the full ownership path from app settings -> session creation -> sub-agent inheritance.

Relevant sections:
- session-scoped selection: lines 102-114
- session startup flow: lines 207-213

Missing details:
- Where the default profile is stored
- How invalid profile ids fall back
- How `AppSettingsStore` and the session bootstrap path supply the id

This is fixable, but without it the implementation boundary is incomplete.

### 3. Role-contract authoring format is still ambiguous

The design replaces `systemPrompt: String` with a structured role contract, but does not specify how those role-contract sections are authored and maintained in practice.

Relevant sections:
- structured runtime composer: lines 116-134
- conceptual `RoleContract` model: lines 166-173

Risk:
- The implementation may end up recreating the current large Kotlin string problem, just spread across multiple fields.
- Or it may quietly require a second design to decide how role-contract text is stored.

I would prefer this to be explicit now: either role-contract sections stay in code by design, or they also move to assets with a stricter ownership boundary.

## Strengths

- Correctly preserves the prompt ownership model already documented in `turn_prompt_anatomy.md`.
- Correctly rejects moving tool semantics back into persona files.
- Correctly treats planner/executor identity inheritance as a first-class requirement.
- The fixed asset schema for identity profiles is simple and implementable.

## Recommendation

Keep the design's ownership model:
- code owns hard execution contract
- identity assets own soft persona
- one runtime composer assembles the final instructions

But combine it with a file-based authoring plan for the role-contract text itself, otherwise the brief's main operational win is diluted.
