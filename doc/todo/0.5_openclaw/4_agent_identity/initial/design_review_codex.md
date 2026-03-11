# Review of `design_claude.md` (Codex)

## Summary

The design is pragmatic and easy to implement. Reusing the asset-loading pattern from app skills and replacing Kotlin prompt literals with ordered markdown sections is a credible way to make prompt iteration faster.

The main issue is architectural: it externalizes too much of the role contract. The current repo explicitly treats system prompt content as the owner of cross-tool policy, so moving critical rules and completion doctrine into editable assets weakens the boundary between immutable execution policy and configurable identity.

## Findings

### 1. Safety-critical role policy becomes configuration

The proposal moves role definition, critical rules, execution loop, task modes, and completion doctrine into asset files under `assets/agent_prompts/`.

Relevant sections:
- proposed asset layout: lines 42-65
- `AgentDef` loses `systemPrompt` and becomes metadata only: lines 121-147

Why this is a problem:
- These sections are not just "prompt content"; they are the hard execution contract for standalone/planner/executor behavior.
- Once they live as editable assets, future overrides and prompt tuning can unintentionally mutate safety and delegation policy.
- That cuts against the repo's existing ownership model, where tool/app behavior has already been split carefully and the remaining system prompt content is the role contract.

This design improves editability, but at the cost of weakening the most important boundary.

### 2. Identity selection is still deferred rather than designed

The goal talks about presets and user customization, but the concrete design only changes prompt storage and role lookup. It does not define a session-scoped identity/profile selection path.

Relevant sections:
- goal includes eventual user customization: lines 5-8
- out of scope defers user-facing persona selection: lines 246-251
- runtime still loads by `promptRole`: lines 150-180

Result:
- All sessions still effectively use one built-in prompt set per role.
- Planner/executor inheritance is solved only for file-based prompt loading, not for configurable identity.

So this is a good extraction design, but not yet a full identity/personality design.

### 3. The section model mixes immutable role policy and soft persona too early

The numbered section approach is simple, but `_shared/10_identity.md` plus role-local `30_rules.md`/`40_execution.md` means identity, values, and operational policy all become peers in the same content system.

Relevant sections:
- numbered section model: lines 42-73
- OpenClaw mapping: lines 75-85

Risk:
- The next step toward presets or overrides will likely copy whole role folders instead of cleanly varying only identity/persona.
- That increases duplication and makes it harder to reason about what is safe to tune.

I would prefer a harder split: keep role contract distinct, and only make persona/profile sections user-configurable.

## Strengths

- Strong KISS implementation path.
- Good reuse of the existing `AppSkillRepository` asset pattern.
- Minimal runtime impact: `TurnPlanningPhaseRunner`, `PromptBuilder`, and `Turn` stay unchanged.
- The ordered section convention is easy to diff and maintain.

## Recommendation

Keep the asset-based authoring idea, but narrow it:
- role contract should remain a distinct, protected layer
- identity/persona should be the configurable asset layer
- planner/executor should inherit one selected session identity rather than just one shared prompt source

That would preserve the operational simplicity of this design without giving up prompt ownership discipline.
