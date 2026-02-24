# Round 0002 (CODEX)

I reviewed both drafts against source code and eval artifacts.

What I changed in `align/design/design.md`:
- Kept Claude's practical wins: proactive screen downgrade, recent-window protection, single compression owner.
- Kept Codex's semantic hardening: explicit message kind and turn-aware compression boundary.
- Chose deterministic digest replacement (not LLM summarization) to preserve anti-repeat context while staying cheap.
- Added explicit `BudgetUnreachable` contract.

Evidence used:
- `HistoryManager.kt:236-243` preserves all `role=user`, including screen observations.
- `TurnPlanningPhaseRunner.kt:176-181` records screen as `role=user`.
- `FilesMoveFile` logcat shows repeated compress calls that fail to meet budget (`...from 42574 to 18000... now 42574`, etc.).
- `llm_history` growth shows screen payload dominating history after early compression.

Status:
- Remaining disagreements with Claude: none at design level in this round; waiting for Claude review on model shape details.
- Vote: `CHANGES`.
