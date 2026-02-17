# Smart Capsule V2 — Review Summary (All Reviewers)

**Date**: 2026-02-12
**Sources**: stage123_review_claude.md, stage123_review_codex.md, stage123_review_gemini.md

---

## Consensus

All three reviews agree:
1. **Core collaboration features work.** Thought display, takeover/resume, supplement, ask_user tool — the ReAct loop integration is sound.
2. **Multi-Context Presence ([1][2][3] navigation) is the #1 gap.** Designed in ux_design_1.md §9 and qi_ui.md, zero implementation.
3. **State transition animations are missing.** Designed in ux_design_1.md §12, zero implementation.

---

## Issues by Priority

### Must Fix (local code fixes, no higher-level design needed)

| # | Issue | Source | Severity |
|---|-------|--------|----------|
| 1 | `ask_user` not in agent allowedTools — tool registered but never reachable at runtime | Codex | HIGH |
| 2 | `thoughtText.maxLines` not reset after WaitingFor* → Running transition | Claude | HIGH |
| 3 | VD mode: `ask_user` has no response mechanism — always times out | Claude, Codex, Gemini | HIGH |
| 4 | Takeover timing: `SessionTakeover` emits immediately after `pause()`, but agent may still be executing current turn's remaining tool calls | Codex | HIGH |
| 5 | `onTaskCompleted` doesn't pass `CompletionReason` — always shows "已完成" | Claude | MEDIUM |
| 6 | `ask_user` + supplement conflict: if user is typing supplement when ask_user fires, supplement input is overwritten | Codex | MEDIUM |
| 7 | `renderWaitingForAction` doesn't ensure overlay is non-focusable | Claude | MEDIUM |
| 8 | `SmartCapsuleManager` exceeds 400-line guideline (641 lines) | Claude | MEDIUM |
| 9 | Pill button `contentDescription` not updated when label changes (accessibility) | Claude | LOW |
| 10 | Row 1 not tappable to open main app (callback exists but not wired) | Codex, Claude | LOW |
| 11 | `supplementConfirmedRunnable` may overwrite an updated thought if ThoughtUpdate arrives during 1.5s delay | Codex | LOW |
| 12 | `updateStatus` emoji regex is fragile for multi-codepoint emoji | Claude | LOW |

### Needs Round 2 Design + Implementation

| # | Feature | Designed Where | Status |
|---|---------|---------------|--------|
| 1 | Multi-Context Presence: [1][2][3] navigation buttons | ux_design_1.md §9, qi_ui.md | NOT IMPLEMENTED |
| 2 | Main App: capsule replaces input dock during active task | ux_design_1.md §9.4 | NOT IMPLEMENTED |
| 3 | Status Island ↔ full capsule expansion | ux_design_1.md §9.3 | NOT IMPLEMENTED |
| 4 | WaitingFor* expanded layout (160dp/120dp with header/body/input) | ux_design_1.md §8.3-8.4 | Partial (reuses compact) |
| 5 | 4-minute nudge for ask_user timeout | ux_design_1.md §8.5, stage_3 design | NOT IMPLEMENTED |
| 6 | State transition animations (expand/collapse, crossfade, fade-out) | ux_design_1.md §12 | NOT IMPLEMENTED |
| 7 | "已收到，下一步生效" context-aware supplement confirmation | ux_design_1.md §10.2 | NOT IMPLEMENTED |

---

## Reviewer Divergence

| Topic | Claude | Codex | Gemini |
|-------|--------|-------|--------|
| ask_user allowedTools | Not flagged | **HIGH** — core blocker | Not flagged |
| Takeover timing gap | Not flagged | **HIGH** — agent continues executing | Not flagged |
| ask_user supplement conflict | Not flagged | **MEDIUM** — input overwritten | Not flagged |
| Stage 2 addUserMessage helper | Not flagged | Not flagged | Noted as minor deviation |
| Stage completion | All 3 complete | Partial (all 3) | Complete (all 3) |

**Codex found the most critical runtime blocker** (#1: ask_user not in allowedTools). This needs to be verified and fixed immediately — if the tool is registered but not in the agent's allowed tools list, the LLM will never receive it in the tool schema, making the entire Stage 3 UI unreachable at runtime.

---

## Action Plan

### Phase 1: Fix local issues (no design needed)
Fix issues #1-12 from the "Must Fix" table above. Priority:
1. Add `ask_user` to agent allowedTools (#1) — **runtime blocker**
2. Fix `thoughtText.maxLines` reset (#2)
3. Fix takeover timing (#4) — ensure SessionTakeover only emits after current action actually completes
4. Handle ask_user + supplement conflict (#6) — queue ask_user if supplement input is open
5. Pass CompletionReason to capsule (#5)
6. Fix remaining MEDIUM/LOW items

### Phase 2: Round 2 design + implementation
Design and implement the features from the "Needs Round 2" table. This is the bulk of remaining work and requires both UX refinement and new system design.
