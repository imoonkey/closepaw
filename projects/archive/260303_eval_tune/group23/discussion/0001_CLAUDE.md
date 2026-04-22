# 0001_CLAUDE — Initial Consensus Draft

## What I Did

Read both individual designs (Claude's `group23_design_claude.md` and Codex's `group23_common_problems_design_codex.md`), plus the relevant codebase files:
- `LoopDetectionPolicy.kt`, `TurnToolPolicy.kt`, `NavigationState.kt`
- `AgentTurnRunner.kt`, `TurnExecutionPhaseRunner.kt`, `ExecutorStepPolicy.kt`
- `StandaloneAgentDef.kt`, `PromptBuilder.kt`, `ShellTool.kt`
- `eval/config/default.yaml`

Created initial consensus document at `final/group23_improvement_design.md`.

## Where We Agree

Both designs converge on the same diagnosis and same general approach:

1. **Loop detection is advisory-only and must become enforceable** — both propose 3-tier escalation with forced action blocking and eventual auto-completion. Both cite Mobile-Agent-v3 and MiniTap as evidence.

2. **QA tasks need a data collection protocol** — both identify missing scratchpad usage and missing `complete_task` calls. Both note the SportsTracker field mismatch (name vs category).

3. **Shell is overused** — both propose prompt tightening + runtime guardrails.

4. **MarkorTranscribeReceipt needs hybrid override** — agree on the specific config change.

## Where We Differ (Open Questions in Final Doc)

### Q1: Shell Budget

- **Codex**: Hard cap (3 per task) + domain whitelist (read-only commands).
- **Claude**: Pattern-based blocking (block after 2 same-pattern failures).
- **My position**: Pattern-based is more aligned with the general anti-loop mechanism. Hard cap risks false positives on legitimate 3-shell workflows. Proposed pattern-based in final doc. Open to discussion.

### Q2: History Compression

- **Codex**: Compress failed action sequences into 3-5 line summaries to reduce inertia.
- **Claude**: Not addressed.
- **My position**: If Tier 2/3 loop escalation works, the loop breaks early and history never grows to 80+ items. Proposed deferring this. But I acknowledge it could be valuable if escalation alone isn't enough — Codex, do your trace observations show history length correlating with failure even after early loop detection fires?

### Q3: Action Outcome Classification

- **Codex**: Proposes PROGRESS/NO_CHANGE/WRONG_PAGE classification (lightweight reflector à la Mobile-Agent-v3).
- **Claude**: Uses existing ScreenSignature similarity instead of a new classification layer.
- **My position**: In the final doc, I used `ScreenSignature.similarityTo() >= 0.85` as the NO_CHANGE detector, which already exists. WRONG_PAGE is harder to detect without knowing the "right" page. KISS principle says: use what we have. But I'm open to hearing if Codex thinks the ScreenSignature approach misses cases where the screen changes but no progress is made.

### Q4: Observability

- **Codex**: Proposes trace fields for loop detector hits, shell budget, etc.
- **Claude**: Not addressed.
- **My position**: Good idea, deferred as independent follow-up in final doc.

## What I Changed vs Individual Designs

1. **Removed Claude's P7 (dialog tips)** — too tactical, and loop escalation should catch dialog cycling generically.
2. **Simplified escalation thresholds** — Claude had 2/5, Codex had 2/4/6. Final doc uses 2/5 (fewer tiers is simpler).
3. **Grounded all code examples in actual codebase** — signatures match real types in NavigationState, TurnToolPolicy, etc.
4. **Removed Claude's "turn count in every observation"** — reframed as Section 6 (Turn Budget Visibility) with simpler implementation.

## Vote

**CHANGES** — I created the initial `final/` document with substantive design content.
