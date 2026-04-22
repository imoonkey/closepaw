# Loop Detection V2: Progress-Aware Design

**Context**: Group 4 eval (7/19 = 36.8%). 3 tasks failed due to anti-loop false positives on legitimate multi-item workflows. The loop detection system has been modified multiple times and keeps producing FPs. This design aims to fix the root cause once.

---

## Root Cause Analysis

### The Category Error

Current loop detection asks: **"Do these screens LOOK the same?"**

The right question is: **"Is the agent MAKING PROGRESS?"**

"Looking the same" does NOT mean "stuck." In multi-item workflows (add 5 songs, delete 3 recipes), the agent legitimately returns to the same list screen after each operation. The screen is 75%+ similar each time, but a different item has been processed.

### Why This Keeps Breaking

Every fix so far has tried to make pattern matching more precise — finer signatures, different thresholds, bigger windows. But no level of precision can resolve the fundamental ambiguity: **similar layout can mean either "stuck" or "progressing through items."**

The only signal that resolves this ambiguity is **content-level progress**.

### The Two CRITICAL Paths

`LoopDetectionPolicy.detectWarning()` has two checks that emit CRITICAL (the only severity that triggers BLOCK/FORCE_COMPLETE escalation):

1. **Cycle detection** (line 92-106): Current screen matches N+ prior screens at Jaccard >= 0.75. This is the **primary FP trigger** for Group 4. For RetroPlayingQueue, screens alternate [songs_list, selection_toolbar, songs_list, ...]. The songs_list recurs, firing cycle detection. But each recurrence shows a different song processed.

2. **isStable** (line 108-114): Last 5 consecutive screens all pairwise similar at Jaccard >= 0.85. This is a **secondary path** — it doesn't fire on multi-item tasks because consecutive screens differ (list vs toolbar vs menu).

The WARNING-level checks (scroll count, repeated action, tool dominance) don't trigger escalation and are correctly calibrated. They stay unchanged.

### What Reference Mobile Agents Do

Surveyed 5 reference implementations (AutoDevice, MobileAgent-E, MobileAgent-v3, DroidRun, Minitap):

| Approach | Used By | Key Properties |
|----------|---------|----------------|
| Exact screenshot hash | AutoDevice | Zero FP on similar-but-different screens. Impractical for a11y-only (no screenshots). |
| Action hash + 3-identical window | MobileAgent-E/v3 | Simple, excludes Swipe/Back from detection. No screen-level check. |
| Error escalation (2 consecutive failures) | DroidRun, MobileAgent | Only detects execution failures, not navigation loops. |
| Content tracking (seen_items set) | AutoDevice | Tracks what items have been seen. New items = progress. |
| None | Minitap, Android World baseline | Rely on turn limit only. |

**Key finding**: None use fuzzy screen similarity. They use either exact hashing or action-level detection. Our Jaccard similarity at 0.75 creates an ambiguous zone that requires a progress signal to resolve.

**What we should adopt**: AutoDevice's concept of content-level progress tracking. Not their exact implementation (they use screenshot pixel hashing and text transcription), but the principle: **check if new content has appeared between similar-looking screens before calling it a loop.**

---

## Design

### Core Idea

Add a **progress gate** before any CRITICAL emission. The gate checks: among the screens that triggered the pattern, has the content actually changed? If yes — downgrade to WARNING. If no — emit CRITICAL as before.

```
Pattern Detected?  →  No  →  return null (no warning)
       ↓ Yes
Content Progress?  →  Yes →  return WARNING (advisory only, no escalation)
       ↓ No
       →  return CRITICAL (triggers escalation ladder)
```

This turns the binary "similar = stuck" into a three-state system: "no pattern" / "pattern but progressing" / "pattern and stuck."

### The Progress Check

One function, called from both CRITICAL branches:

```kotlin
private fun hasProgressInGroup(screens: List<ScreenSignature>): Boolean {
    if (screens.size < 2) return false
    return screens.zipWithNext().any { (a, b) ->
        val diff = a.tokens.subtract(b.tokens).size +
                   b.tokens.subtract(a.tokens).size
        diff >= MIN_PROGRESS_TOKEN_DIFF
    }
}
```

**What it does**: For a group of similar screens, checks if any consecutive pair has meaningful token differences (symmetric difference). If at least one pair differs by >= N tokens, the agent is making progress.

**Why symmetric difference**: It counts tokens present in A but not B, plus tokens present in B but not A. A token like `text=through the storm|...` in screen A but `text=hidden paths|...` in screen B produces a difference of 2 (one removed, one added).

**For cycle detection**: The "group" is all screens matching the current screen at similarity >= 0.75:
```kotlin
val matchingScreens = state.recentSignatures.filter {
    it.similarityTo(current) >= config.cycleMatchThreshold
}
```

**For isStable**: The "group" is the last N consecutive screens (already pairwise similar by definition).

### Threshold: MIN_PROGRESS_TOKEN_DIFF = 2

Each screen element generates a token like:
```
id=btn_add|text=add to queue|desc=|class=button|flags=c|pos=3,8
```

- **1 token diff**: Could be a position bucket change from minor scroll. Noise.
- **2 token diffs**: At minimum, one element's text/desc changed, or one element appeared/disappeared. This is real content change.
- **3+ token diffs**: Definitely progress.

Threshold of 2 is conservative enough to avoid noise, sensitive enough to catch real item-level changes. At Jaccard 0.75 similarity, 25% of tokens already differ — but that's total difference. We're looking at consecutive-pair differences within the matching group, which is a tighter signal.

### cycleMinOccurrences: 2 → 3

A screen appearing twice is normal for multi-item tasks:
- Turn 1: List screen (select item A)
- Turn 2: Menu screen (perform action on A)
- Turn 3: List screen (select item B) ← 2nd occurrence, legitimate

Three occurrences with no content progress means the agent returned to the same screen three times without doing anything new. That's a genuine loop.

### No Other Changes Needed

The elegance of this design is that **the progress gate naturally integrates with the existing escalation system**:

- `AgentTurnRunner.prepareTurn()` already only increments `consecutiveLoopTurns` on CRITICAL severity.
- When progress downgrades CRITICAL → WARNING, `consecutiveLoopTurns` resets to 0.
- No BLOCK or FORCE_COMPLETE can occur while the agent is making progress.
- If the agent stops making progress (content stabilizes), the next occurrence of the pattern will be CRITICAL, and escalation resumes normally.

The safe escalation gate (D1.4 in consensus) is **already implemented** by this mechanism — WARNING severity naturally prevents escalation.

---

## Implementation

### Files Changed

| File | Change |
|------|--------|
| `LoopDetectionPolicy.kt` | Add `hasProgressInGroup()`, apply to both CRITICAL branches, change `cycleMinOccurrences` default to 3 |

That's it. One file, one new method, two call sites, one config change.

### Complete Implementation

```kotlin
internal class LoopDetectionPolicy(
    private val config: LoopDetectionConfig = LoopDetectionConfig()
) {
    companion object {
        private const val MIN_PROGRESS_TOKEN_DIFF = 2
    }

    fun detect(state: NavigationState): LoopDetectionResult {
        // unchanged from current
    }

    private fun detectWarning(state: NavigationState): LoopWarning? {
        // Cycle detection
        if (state.recentSignatures.size >= config.cycleMinOccurrences) {
            val current = state.recentSignatures.last()
            val matchCount = state.recentSignatures.count {
                it.similarityTo(current) >= config.cycleMatchThreshold
            }
            if (matchCount >= config.cycleMinOccurrences) {
                // NEW: progress gate
                val matchingScreens = state.recentSignatures.filter {
                    it.similarityTo(current) >= config.cycleMatchThreshold
                }
                if (hasProgressInGroup(matchingScreens)) {
                    return LoopWarning(
                        message = "Screen layout recurring ($matchCount times) but content " +
                            "is changing between visits — continuing. Switch approach if " +
                            "no further progress.",
                        severity = LoopWarningSeverity.WARNING  // downgraded
                    )
                }
                return LoopWarning(
                    message = "Cycle detected: this screen has appeared $matchCount times...",
                    severity = LoopWarningSeverity.CRITICAL
                )
            }
        }

        // Stable screen
        val latestSignatures = state.recentSignatures.takeLast(config.repeatedScreenWindow)
        if (latestSignatures.size == config.repeatedScreenWindow &&
            latestSignatures.isStable(config.similarityThreshold)) {
            // NEW: progress gate
            if (hasProgressInGroup(latestSignatures)) {
                return LoopWarning(
                    message = "Screen layout is stable but content is changing — " +
                        "continuing. Switch approach if no further progress.",
                    severity = LoopWarningSeverity.WARNING  // downgraded
                )
            }
            return LoopWarning(
                message = "Screen state looks unchanged for ${config.repeatedScreenWindow} turns...",
                severity = LoopWarningSeverity.CRITICAL
            )
        }

        // Advisory checks: unchanged
        // ...
    }

    /**
     * Check if a group of similar/matching screens shows content-level progress.
     *
     * True if any consecutive pair in the group differs by at least
     * [MIN_PROGRESS_TOKEN_DIFF] tokens (symmetric difference).
     */
    private fun hasProgressInGroup(screens: List<ScreenSignature>): Boolean {
        if (screens.size < 2) return false
        return screens.zipWithNext().any { (a, b) ->
            val diff = a.tokens.subtract(b.tokens).size +
                       b.tokens.subtract(a.tokens).size
            diff >= MIN_PROGRESS_TOKEN_DIFF
        }
    }
}
```

### Config Change

```kotlin
internal data class LoopDetectionConfig(
    // ...
    val cycleMinOccurrences: Int = 3,  // was 2
    // ...
)
```

---

## Verification

### RetroPlayingQueue (primary FP case)

Screen sequence: `[songs_list₁, toolbar, songs_list₂, toolbar, songs_list₃, ...]`

- songs_list₁ vs songs_list₂: similarity ~0.80 (same layout, different song selected) → match for cycle detection
- With `cycleMinOccurrences=3`: cycle fires at songs_list₃ (3rd occurrence)
- songs_list₁ tokens include `text=through the storm`, songs_list₂ includes `text=hidden paths` → symmetric diff >= 2
- `hasProgressInGroup([songs_list₁, songs_list₂, songs_list₃])` returns true → downgrade to WARNING → no escalation

### RecipeDeleteMultipleRecipesWithNoise (verification FP)

- Agent completes deletion (score=1.0), then does verification pass
- Verification navigates back to recipe list → screen recurs
- With progress check: recipe list after deletion has fewer items → token diff → progress detected → WARNING only
- Even if no progress detected: `consecutiveLoopTurns` hasn't accumulated during the deletion phase (those were WARNING turns), so escalation threshold (5) is far from reached

### Genuine Loop (must still detect)

Agent stuck clicking the same element, returning to the same screen each time:
- Screen signatures: all identical (same content, same layout)
- `hasProgressInGroup()` returns false (0 token diff between pairs)
- CRITICAL emitted → escalation proceeds normally

---

## Why This Is The Final Fix

Previous fixes tried to make the pattern matching more precise. This fix changes the question:

| Version | Question | Problem |
|---------|----------|---------|
| V0 | "Same action repeated?" | Too coarse — same button, different target |
| V1 (H2 fix) | "Same action at same index?" | Still FP — same index, different semantic target |
| V2 (this) | "Same screen, but is content changing?" | Resolves ambiguity at the right level |

The progress gate is **orthogonal to pattern matching precision**. Even if we later improve signatures (D1.1 in consensus), the progress gate remains correct and useful. It's a safety net that catches what pattern matching misses, regardless of how precise the patterns are.

The design is also **self-correcting**: if the agent genuinely gets stuck (stops making progress), the progress gate stops protecting it and escalation resumes naturally. There's no scenario where progress detection permanently prevents loop detection.
