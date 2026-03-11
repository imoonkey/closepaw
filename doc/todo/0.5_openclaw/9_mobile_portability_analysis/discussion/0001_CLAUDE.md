# 0001 — CLAUDE (Initial Aligned Draft)

## What I Did

Created `final/mobile_portability_analysis.md` — an initial aligned draft synthesizing both designs and both reviews.

## Key Decisions in the Draft

1. **Core verdict:** Absorb, don't port. (Full consensus — both designs agreed.)

2. **Architectural invariants codified:**
   - One control plane (all inputs as `Op` variants)
   - Ownership boundaries preserved (`HistoryManager` vs `SessionRecordingService` vs `ScratchpadState` vs `app_skills/`)
   - Tool contracts are session-scoped (no hot-swap mid-session)

3. **Accepted Codex review feedback on Claude's design:**
   - Session persistence: Removed the proposal to add JSONL persistence to `HistoryManager`. Acknowledged that persistence already exists in `SessionRecordingService`/`SessionStorage`. The existing split is correct.
   - Memory injection: Changed from "extend `buildAppSkillMessage()`" to a dedicated "retrieved memory" prompt block, separate from app skills. This preserves prompt ownership boundaries.
   - Dynamic tool registration: Changed to "tool sets frozen at session/task start" instead of hot-swapping. Capability changes take effect at next session.
   - Shell tool boundary: Removed the "package manager intents" suggestion. `ShellTool` stays file-inspection-only. New capabilities get their own tools.
   - `ScratchpadState` scope: Corrected to "session-scoped" (not task-scoped).

4. **Accepted Claude review feedback on Codex's design:**
   - Added priority matrix with project cross-references and effort sizing
   - Added explicit compression comparison (Android Agent's pipeline is superior — no absorption needed)
   - Added `ToolCapabilities` integration sketch showing how capability metadata connects to `ToolSpec` and `PolicyEngine`

## Open Questions for Codex

1. The Codex design proposed a relay client state machine (DISABLED→CONNECTING→READY→DELIVERING→BACKOFF). I included it in the final doc. Is this the right level of detail for a portability analysis, or should it be deferred entirely to Project 3?

2. The Codex design mentioned "explicit memory write/read tools instead of implicit hidden persistence." I agree with explicit tools for reads, but writes feel like they should be automatic (end-of-session extraction). Does Codex prefer user-invoked memory writes?

## Vote

**CHANGES** — I created the final doc, so Codex needs to review.
