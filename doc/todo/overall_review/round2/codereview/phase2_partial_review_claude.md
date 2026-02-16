# Code Review: Phase 2 Partial — LLM Streaming Consolidation + Tool Observation DRY (uncommitted)

**Reviewer**: Claude
**Date**: 2026-02-16
**Scope**: 4 changed files + 1 new file, uncommitted working tree
**Covers**: J1 (streaming retry extraction), J5 (tool observation consolidation)

---

## Summary

Clean extraction of the shared streaming retry scaffold into `CloudStreamRetryRunner.kt` and consolidation of inline observation construction to use `buildObservation()`. Both changes are well-executed with no correctness issues.

---

## New File: CloudStreamRetryRunner.kt (92 lines)

**Purpose**: Shared retry scaffold for cloud streaming calls. Eliminates ~100 lines of identical retry/backoff/fail-fast logic duplicated between `OpenAIResponseClient` and `ChatCompletionClient`.

**Design**:
- `StreamAttemptEmitter` fun interface — emitter passed to the attempt block
- `StreamRetryRunResult` data class — `completed`, `failureEmitted`, `lastError`
- `streamWithRetry()` top-level internal function — owns retry loop, backoff, CloudStreamRetryPolicy decisions

**Assessment**: ✓ Clean. The abstraction boundary is well-chosen:
- Shared: retry counter, backoff, emittedEvent flag, CloudStreamRetryPolicy.decide, delay, result packaging
- Per-client: stream creation, event parsing
- No over-abstraction — both clients call `streamWithRetry` with a lambda that does their specific parsing

---

## Changed Files

### OpenAIResponseClient.kt (-89 lines)
- Replaced inline retry loop with `streamWithRetry()` call
- All `emit()` calls changed to `emitter.emit()`
- Post-loop logic now reads `retryResult.completed` / `retryResult.failureEmitted` / `retryResult.lastError`
- ✓ Behavior-preserving refactor

### ChatCompletionClient.kt (-66 lines)
- Same `streamWithRetry()` migration
- Removed local `INITIAL_BACKOFF_MS` usage (now handled by the helper's defaults = `LLMClient.INITIAL_BACKOFF_MS`)
- ✓ Behavior-preserving refactor

### UIActionInvocation.kt (-11 lines)
- Replaced inline `Perceptor.toPromptJson()` + `ToolObservation.ScreenState(...)` with `buildObservation(snapshot, context.platform)`
- ✓ Correct — `buildObservation` handles screenshot-only mode (no a11y data) which the inline code did not

### OpenAppTool.kt (-10 lines)
- Same `buildObservation()` migration
- Removed direct imports of `Perceptor` and `toSummary`
- ✓ Correct — now consistent with UIActionInvocation

---

## Critical

None.

## High

None.

## Medium

### 1. `OpenAIErrorClassifier` used for both clients

**File**: `CloudStreamRetryRunner.kt:51`

The retry runner always classifies exceptions via `OpenAIErrorClassifier.classify(e)`. This is correct today since both cloud clients use the OpenAI SDK. But the function name `OpenAIErrorClassifier` creates a misleading coupling — if a non-OpenAI cloud client is added, this would silently misclassify errors.

**Action**: Rename to `CloudErrorClassifier` or pass the classifier as a parameter. Low priority since no third client is planned.

---

## Low

### 2. ChatCompletionClient: `MAX_RETRIES` constant cleanup incomplete

The local constant `MAX_RETRIES` was removed from the retry loop (now handled by `streamWithRetry` defaults), but `ChatCompletionClient` still had `INITIAL_BACKOFF_MS` defined locally before this change. The `streamWithRetry` helper now uses `LLMClient.INITIAL_BACKOFF_MS` by default, which is correct. However, if `ChatCompletionClient` previously used a DIFFERENT value for `INITIAL_BACKOFF_MS`, the behavior has silently changed.

**Checked**: Both previously used `1000L` as the initial backoff. No behavioral change. ✓

---

## Recommendation

**APPROVE** — Clean, well-scoped extraction. No correctness issues. The `OpenAIErrorClassifier` naming is a minor smell that can be addressed later.
