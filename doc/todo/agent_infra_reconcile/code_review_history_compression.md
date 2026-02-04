# Code Review: History Compression Implementation

> **Commit**: `77fb43c feat: compress LLM history and persist replay screen states`  
> **Reviewer**: Claude  
> **Date**: 2026-02-04  
> **Scope**: Phase 1 context hygiene - LLM history compression + replay persistence

---

## Summary

This commit implements Phase 1 of the multi-agent infrastructure roadmap:
1. **LLM history compression**: Tool results now store text summaries instead of full JSON a11y trees
2. **Replay history persistence**: `ScreenStateRecord` captures artifact paths for replay/debug
3. **Screen summary heuristic**: `ScreenSummary.kt` generates compact text summaries
4. **Event enhancement**: `AgentEvent.ScreenCaptured` now carries replay metadata
5. **Prompt improvements**: Agent nudged to use scratchpad for visited items

**Files changed**: 17 (301 insertions, 163 deletions)

---

## Critical

None found.

---

## High

### 1. Missing Unit Tests for ScreenSummary

**Location**: `perception/ScreenSummary.kt`

**Issue**: The new `toSummary()` extension function contains heuristic logic (stopword filtering, label extraction, truncation) but has no unit tests.

**Risk**: Regressions in summary quality could go unnoticed. The Gmail-specific stopword filtering is particularly risky to change without tests.

**Recommendation**: Add tests covering:
- Basic element counting (total, clickable, editable)
- Label extraction with various element configurations
- Gmail stopword filtering
- Edge cases (empty elements, all stopwords, very long labels)

```kotlin
// Example test structure
class ScreenSummaryTest {
    @Test
    fun `toSummary returns correct element counts`() { ... }
    
    @Test
    fun `toSummary filters Gmail stopwords`() { ... }
    
    @Test
    fun `toSummary truncates long labels`() { ... }
}
```

---

## Medium

### 2. Hardcoded Gmail-Specific Stopwords

**Location**: `perception/ScreenSummary.kt:10-26`

```kotlin
private val GMAIL_STOPWORDS =
    setOf(
        "Search in mail",
        "Open navigation drawer",
        // ...
    )
```

**Issue**: Stopword filtering is hardcoded for Gmail only, using package name matching. This won't scale as more apps need custom filtering.

**Recommendation**: Remove them for now, as it is too ugly and hacky. We will find better ways to resolve these issues later.

**Severity justification**: This is design debt, not a bug. Current implementation works for the primary use case (Gmail).

---

### 3. Observation.ScreenState Constructor Coupling

**Location**: `agent/AgentObservation.kt:6`

```kotlin
data class ScreenState(val accessibilityTree: String, val summary: String) : Observation()
```

**Issue**: Adding `summary` as a required positional parameter breaks any future extension. The conversion function at line 18 assumes `ToolObservation.ScreenState` always has a summary.

**Recommendation**: Either:
- Make `summary` have a default value: `val summary: String = ""`
- Or document that this is intentionally required

---

### 4. POST_ACTION Event Not Emitted for All Success Paths

**Location**: `agent/AgentTurnRunner.kt:235-246`

The `ScreenStatePhase.POST_ACTION` event is emitted only when `observedSnapshot != null`. However, the `complete_task` tool path explicitly skips observation capture:

```kotlin
if (toolCall.name == "complete_task") {
    observation = Observation.TextOutput("Completion acknowledged; no screen captured.")
}
```

**Issue**: Replay trace will have gaps for turns that end with `complete_task`.

**Recommendation**: This may be intentional (no screen needed for completion), but consider documenting this gap or emitting a "terminal" phase event.

---

### 5. No Validation of ScreenStateRecord Paths

**Location**: `history/model/ScreenStateRecord.kt`, `history/SessionRecordingService.kt:359-386`

**Issue**: `ScreenStateRecord` stores paths (`rawA11yTreePath`, `sanitizedA11yTreePath`, `screenshotPath`) but there's no validation that these files exist when recorded. If trace artifacts fail to write, the record will contain invalid paths.

**Recommendation**: Consider adding a validation step or marking paths as "pending verification" until confirmed.

---

## Low

### 6. Magic Numbers Without Documentation

**Location**: `perception/ScreenSummary.kt:6-8`

```kotlin
private const val MAX_LABELS = 4
private const val MAX_LABEL_LENGTH = 40
private const val MIN_LABEL_LENGTH = 3
```

**Issue**: These constants lack documentation explaining the rationale.

**Recommendation**: Add KDoc:

```kotlin
/** Maximum labels to include in summary (balance context vs. token usage) */
private const val MAX_LABELS = 4

/** Truncate labels longer than this to reduce noise */
private const val MAX_LABEL_LENGTH = 40

/** Skip labels shorter than this (likely icons/punctuation) */
private const val MIN_LABEL_LENGTH = 3
```

---

### 7. Duplicate capturePostActionObservation Logic

**Location**: 
- `tool/BaseTool.kt:220-238`
- `tool/handlers/TargetingInvocationUtils.kt:25-44`

**Issue**: Both have nearly identical `capturePostActionObservation` methods with slight differences (log tag, delay parameter).

**Recommendation**: Consolidate into `TargetingInvocationUtils` and have `BaseTool` delegate to it.

---

## Android-Specific Checks

- [x] Coroutines scoped correctly? — Yes, all `delay()` calls are in suspend functions
- [x] No Context leaks? — Yes, no static Context references
- [x] Main thread safe? — Yes, screen capture uses platform APIs appropriately
- [x] Permissions checked? — N/A (accessibility service already active)
- [x] A11y service best practices? — Yes, no changes to service lifecycle

---

## Build Verification

```
✅ ./gradlew assembleDebug — PASS
✅ ./gradlew lint — PASS (no new warnings)
✅ ./gradlew test — PASS (all tests green)
```

---

## Recommendation

**APPROVE with minor changes**

The implementation correctly achieves the Phase 1 goals:
- LLM history is now text-first (major context reduction)
- Replay artifacts are preserved out-of-band
- Summary heuristic provides reasonable screen descriptions

**Required before next phase**:
1. Add unit tests for `ScreenSummary.kt` (High priority)

**Nice to have**:
2. Document magic numbers
3. Consolidate duplicate observation capture code

---

## References

- [Final Design](./final_design_claude.md)
- [History Compression Design](./history_compression_claude.md)
