# Loop Detection Redesign — Round 6 Post-Analysis

**Date**: 2026-03-07 | **Trigger**: Round 6 eval showed loop detection warnings causing premature task abandonment

## Problem

Round 6 (8 tasks, GPT-5.4) revealed that the loop detection system's WARNING-level advisories are actively harmful. The system injects warning text into the LLM prompt, and the LLM treats it as an instruction to pivot or quit — exactly the opposite of what's needed for form-filling and multi-item workflows.

### Evidence

| Task | System Warnings | Effect |
|------|----------------|--------|
| RecipeAddMultipleRecipesFromMarkor | 17 (tool dominance) | **Direct cause of failure.** Agent received "mobile_action used 7 times without progress" at turn 22, combined with system prompt rule 6 ("after loop warning, pivot immediately"), gave up with 28 turns remaining. Previously misdiagnosed as "hallucinated loop warning" — it was a real system-injected warning. |
| RecipeAddMultipleRecipesFromImage | 27 (tool dominance + cycle) | Warnings accumulated throughout form-filling. May have pushed agent toward overwriting recipe 1 instead of navigating back to create recipe 2. |
| VlcCreateTwoPlaylists | 17 (tool dominance + cycle) | Agent used inefficient one-at-a-time strategy instead of multi-select. Persistent warnings may have discouraged the consecutive mobile_action sequences that multi-select requires. |
| VlcCreatePlaylist | 9 (tool dominance + cycle) | **Succeeded despite warnings.** Model ignored the advisories and continued. This is the control case — success required ignoring the system's own guidance. |
| TasksCompletedTasksForDate | 7 (tool dominance + cycle) | Unclear impact; task failed due to reasoning error. |

5 of 8 tasks received WARNING advisories. The only multi-step success (VlcCreatePlaylist) required the model to **ignore** the warnings. The system is rewarding disobedience and punishing compliance.

### Root Cause: Tool Dominance Rule

The primary offender is `LoopDetectionPolicy`'s tool dominance check:

```kotlin
val toolRepetitionThreshold: Int = 3  // mobile_action used 3+ times → warning
```

For any form-filling task, consecutive `mobile_action` calls are the **only correct behavior**: type title → type description → scroll → type time → save. Each call has different parameters, targets different elements, and produces different screen states. But the detector only counts tool name occurrences, ignoring all of this.

The warning message says: "This approach is not working — try a fundamentally different strategy." This is factually wrong for form-filling. The approach IS working.

### Deeper Issue: Advisory Warnings Are Harmful

The V2 design doc (loop_detection_v2_design.md) added a progress gate for CRITICAL warnings, but left WARNING warnings unprotected because "they don't trigger escalation and are correctly calibrated." This assumption was wrong.

WARNING advisories don't drive hard interventions (BLOCK / FORCE_COMPLETE), but they drive **soft interventions** by changing LLM behavior. The LLM treats injected warning text with the same weight as system prompt instructions. An ADVISORY warning that says "try a fundamentally different strategy" IS an instruction to pivot, regardless of its severity label.

System prompt rule 6 amplifies this:
> "Do not repeat failed actions blindly. After a failure or **loop warning**, pivot immediately to a meaningfully different strategy."

This rule makes the LLM obey every ADVISORY as if it were a command.

## Reference Analysis

From the V2 design doc's own survey of 5 reference implementations:

| Agent | Loop Detection Approach |
|-------|------------------------|
| AutoDevice | Exact screenshot hash (no fuzzy matching) |
| MobileAgent-E/v3 | Action hash + 3-identical window (excludes swipe/back) |
| DroidRun | Error escalation only (2 consecutive failures) |
| MiniTap | **None** — turn limit only |
| Android World baseline | **None** — turn limit only |

Key observations:
- No reference agent uses "advisory warning injected into prompt"
- The simplest agents (MiniTap, AW baseline) rely solely on turn limits
- Those that detect loops use exact matching or hard stops, not soft guidance

## Design Decision

**Delete all loop detection WARNING advisories. Keep only facts-based stable-screen detection and final-turn warning.**

### Principle

> System warnings should state **facts** (what happened), not **opinions** (what to do about it).

The LLM is the reasoning engine. Give it facts and let it decide strategy. When the system injects opinions ("try a fundamentally different strategy"), it overrides the LLM's own judgment — which may be correct.

### What to Keep

| Warning | Keep? | Rationale |
|---------|-------|-----------|
| Stable screen (unchanged N turns) | **Yes, simplified** | Factual: "Screen has not changed for N turns." No strategy suggestion. |
| Final turn | **Yes** | Factual: "This is your final turn." |

### What to Delete

| Warning | Delete? | Rationale |
|---------|---------|-----------|
| Cycle detection (CRITICAL) | **Delete** | The progress gate already downgrades most cycles to WARNING. The remaining true cycles (no content change) are covered by stable screen. Cycle detection adds complexity for marginal value. |
| Cycle detection (WARNING, progress-downgraded) | **Delete** | "Screen layout recurring but content changing" — this is normal multi-item work. The warning is noise. |
| Scroll spam | **Delete** | Legitimate scroll-heavy tasks (long lists) get punished. Turn limit is sufficient. |
| Action repetition | **Delete** | Same action 3 times might be retrying a flaky click, which is correct behavior. |
| Tool dominance | **Delete** | Primary harmful rule. Form-filling requires consecutive mobile_action. |
| BLOCK escalation (Tier 2) | **Delete** | Without CRITICAL warnings, no escalation path exists. |
| FORCE_COMPLETE (Tier 3) | **Delete** | Turn limit serves the same purpose. |

### Stable Screen: Simplified

Current: Jaccard similarity >= 0.85 for 5 consecutive screens, with progress gate, emitting CRITICAL or WARNING.

Proposed: Jaccard similarity >= 0.95 for 5 consecutive screens. No progress gate needed (at 0.95, screens are nearly identical — if content were changing, similarity would drop). Emit a single factual WARNING:

```
"Screen has not changed for 5 turns."
```

No "Try a different strategy." No "Switch approach." Just the fact. The LLM can decide what to do with this information.

Why 0.95 instead of exact match: Exact match (1.0) is too brittle — a single `isFocused` change or cursor blink breaks it. 0.95 allows minor noise while still requiring near-identical screens.

Why not reimplement: The existing `ScreenSignature.similarityTo()` already computes Jaccard similarity. Changing the threshold from 0.85 to 0.95 is a one-line edit.

### System Prompt Rule 6: Remove Loop Warning Reference

Current:
> "Do not repeat failed actions blindly. After a failure or loop warning, pivot immediately to a meaningfully different strategy."

Proposed:
> "Do not repeat failed actions blindly; if the same action fails twice, try a different approach."

Remove "loop warning" from the rule. The rule should be about action failures (observable facts), not about system-generated advisories.

## Implementation

### Files Changed

| File | Change |
|------|--------|
| `LoopDetectionPolicy.kt` | Remove cycle detection, scroll spam, action repetition, tool dominance. Keep only stable screen at 0.95 threshold. Remove progress gate (unnecessary at 0.95). Remove CRITICAL severity, BLOCK, FORCE_COMPLETE. |
| `LoopDetectionConfig` | Remove `cycleMatchThreshold`, `cycleMinOccurrences`, `toolRepetitionThreshold`, `maxConsecutiveScrollActions`, `repeatedActionWindow`, `blockEscalationThreshold`, `forceCompleteEscalationThreshold`. Keep `similarityThreshold` (change to 0.95), `repeatedScreenWindow` (keep at 5). |
| `EscalationLevel` | Remove `BLOCK`, `FORCE_COMPLETE`. Keep `NONE`, `ADVISORY`. |
| `AgentTurnRunner.kt` | Remove Tier 2/3 handling (blocked actions, force complete). Simplify `buildWarnings()`. Remove `consecutiveLoopTurns` tracking. |
| `NavigationState.kt` | Remove `consecutiveLoopTurns`, `blockedActions` fields. |
| `TurnToolPolicy.kt` | Remove `blockedActions` parameter from arbitration. |
| `StandaloneAgentDef.kt` | Rewrite rule 6 to remove "loop warning" reference. |
| `doc/main/agent/loop.md` | Update loop guard section to reflect simplified design. |

### What the Simplified LoopDetectionPolicy Looks Like

```kotlin
internal data class LoopDetectionConfig(
    val similarityThreshold: Double = 0.95,
    val stableScreenWindow: Int = 5
)

internal class LoopDetectionPolicy(
    private val config: LoopDetectionConfig = LoopDetectionConfig()
) {
    fun detect(state: NavigationState): LoopDetectionResult {
        val recent = state.recentSignatures.takeLast(config.stableScreenWindow)
        if (recent.size == config.stableScreenWindow && recent.isStable(config.similarityThreshold)) {
            return LoopDetectionResult(
                warning = LoopWarning(
                    message = "Screen has not changed for ${config.stableScreenWindow} turns.",
                    severity = LoopWarningSeverity.WARNING
                ),
                escalation = EscalationLevel.ADVISORY
            )
        }
        return LoopDetectionResult(null, EscalationLevel.NONE)
    }
}
```

~10 lines of detection logic, down from ~100.

### Ownership (per /prompt-tune)

| Change | Owner Layer |
|--------|-------------|
| System prompt rule 6 rewrite | Core prompt |
| Stable screen warning message | Infra (LoopDetectionPolicy) |
| Final turn warning message | Infra (ExecutorStepPolicy) |

## Risk Assessment

**Risk**: Removing loop detection could allow agents to waste turns on genuinely stuck loops.

**Mitigation**: Turn limit (30 or 50) already caps wasted turns. The stable screen check (5 consecutive identical screens) catches the most egregious case. Reference agents (MiniTap, AW baseline) operate without any loop detection and their turn limit is the only safeguard.

**Expected benefit**: Multi-item tasks (RecipeAdd*, VlcCreateTwoPlaylists) should no longer be poisoned by false tool dominance warnings. Form-filling tasks should no longer trigger premature pivots.

## Validation Plan

Re-run the same 8 round-6 tasks after implementing changes. Key metrics:
- RecipeAddMultipleRecipesFromMarkor: should no longer self-terminate at turn 22
- VlcCreateTwoPlaylists: should attempt multi-select without warning interference
- VlcCreatePlaylist: should remain passing (was already ignoring warnings)
- Tasks app tasks: unaffected (no loop warnings in those runs)
