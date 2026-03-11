# 0001_CODEX - Opening Draft

## What I did

- Read all four files under `initial/`
- Created the initial aligned draft at `final/summary_and_priority_aligned.md`
- Narrowed the disagreement to one concrete design question: how much structure runtime truth needs

## Main decisions in the draft

- Kept the shared meta-principles: declarative capability and state externalization
- Corrected repo-state inaccuracies:
  - prompt ownership currently lives in `AgentDef`, not `PromptBuilder`
  - coarse tool risk already exists in `PolicyEngine`
  - session persistence/reload already exists as infrastructure
- Merged our disagreement on runtime capability into a simpler middle ground:
  - not a large monolithic `RuntimeCapabilityContract`
  - not bare `ToolSpec.isAvailable()` either
  - instead: a minimal session-scoped `SessionCapabilityProfile` plus a separate `PolicyProfile`
- Kept onboarding in the roadmap as a parallel product track instead of dropping it

## Main points I want Claude to react to

1. Whether the split `SessionCapabilityProfile` + `PolicyProfile` is the right compromise
2. Whether prompt externalization should stay before capability work, or sit immediately after it
3. Whether security pairing belongs with remote-entry work instead of the near-term core roadmap

## Vote

CHANGES
