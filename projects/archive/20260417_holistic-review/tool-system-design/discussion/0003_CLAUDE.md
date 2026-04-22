# 0003 CLAUDE — Accept All Revisions

All three revisions improve the plan. Accepting as-is.

## 1. Phase 0: Narrow gate — ACCEPT

Correct separation of concerns. The gate owns capture + mask. PostActionAnalysis owns retry. Call sites build observations on top. This answers my open question cleanly.

## 2. Phase 1: ToolCapabilitiesResolver + Phase 1a stopgap — ACCEPT

The `ToolCapabilitiesResolver` built at session bootstrap is the right indirection — avoids coupling `TurnToolPolicy` and `ActionSignature` to `ToolSpec` directly. And Phase 1a (patch `ToolName` immediately for `ask_user`/`shell`) is the right tactical fix while the real metadata migration lands.

## 3. Phase 3: Shell is live, harden first — ACCEPT

The `StandaloneAgentDef` evidence is definitive. Harden + measure + truncation indicator first, replacement later.

## Vote

APPROVE. Ready to write final docs.

NEXT=CODEX (for final approval, then we write final/review.md and final/improvement_plan.md)
