# 0001_CLAUDE — Initial Alignment Draft

## What I Did

1. Read all four initial files (both designs + both reviews).
2. Verified disputed claims against actual codebase:
   - **Risk levels already exist**: Confirmed. `RiskLevel` enum in `ApprovalTypes.kt`, `DEFAULT_RISK_LEVELS` in `PolicyEngine`, per-action risk in `MobileActionName.defaultRiskLevel`. Codex's review was correct; my original design was wrong to treat this as net-new.
   - **System prompt lives in AgentDef, not PromptBuilder**: Confirmed. `StandaloneAgentDef.systemPrompt` is a hardcoded string. `PromptBuilder` only assembles turn input items. Codex's review was correct; my original T1-1 targeted the wrong layer.
   - **Session persistence largely exists**: Confirmed. Full checkpoint/reload/hot-idle infrastructure is in place. The gap is product-level session identity, not storage.
3. Wrote initial `final/roadmap.md` incorporating corrections from both reviews.

## Key Decisions in This Draft

1. **Accepted Codex's corrections** on risk levels (existing, not new), prompt ownership (AgentDef layer), and session persistence (already built).
2. **Kept `isAvailable(context)` over `RuntimeCapabilityContract`** — marked as open question. My reasoning: the three concerns (availability, risk, config) change at different rates. A thin context parameter avoids coupling them into one rebuild cycle. But I acknowledge this may need revisiting.
3. **Phased prompt externalization**: Phase 1 extracts just the prompt text (half-day win for eval). Phase 2 moves full AgentDef into assets. This bridges Claude's "do it now" urgency with Codex's "do it at the right scope" concern.
4. **Accepted Codex's memory ordering argument**: Memory after tool/policy stabilization to avoid overfitting.
5. **Added onboarding** (missing from Codex design) as P7, independent track.

## Unresolved for Codex

1. Do you accept `isAvailable(context)` as the P1 approach, or do you insist on `RuntimeCapabilityContract`? If the latter, what's the minimal version that avoids over-coupling?
2. Is phased prompt externalization (Phase 1: text only, Phase 2: full manifest) acceptable, or must it be all-or-nothing?
3. The priority order puts dynamic tools (P1) before policy externalization (P2) before persona assets (P3). Does this match your dependency analysis?

## Vote

**CHANGES** — I wrote the initial `final/roadmap.md`.
