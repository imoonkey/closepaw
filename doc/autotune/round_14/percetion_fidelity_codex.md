# Perception Fidelity Summary

## Decision

Adopt high-fidelity capture in perception.

- `Perceptor` should preserve accessibility text exactly as exposed by the platform.
- Do not normalize whitespace during capture.
- If normalization is needed later, do it in a derived layer such as prompt shaping, matching, or deduplication, not in the capture layer.

Core principle:

> Perception is source-of-truth and should be lossless by default. Any normalization must happen in a downstream purpose-specific layer, never in capture.

## Implementation Plan

### Phase 1: Make prompt text semantics fidelity-aligned

Affected files:

- `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/perception/PerceptorInternals.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TargetResolver.kt`

Key decisions:

- Keep capture raw.
- Restrict prompt `text` to visible/accessibility text semantics rather than resource-id fallback.
- Introduce matching-only canonicalization for indexing and resolver lookup.

### Phase 2: Lock behavior with tests and docs

Affected files:

- `app/src/test/kotlin/com/moonkey/androidagent/perception/PerceptorTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/perception/PerceptorInternalsTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/tool/action/TargetResolverTest.kt`
- `doc/main/README.md`
- `doc/main/infra/platform.md`
- `doc/main/infra/perception.md`

Key decisions:

- Add tests for raw prompt text preservation, visible-text fallback, and normalized matching behavior.
- Keep the design record in this perception-specific doc instead of burying it in a generic changelog entry.

## Why

The previous approach normalized whitespace too early. That is risky because it is irreversible and can destroy intentional structure.

- Multiple blank lines may be intentional.
- Multiple horizontal spaces or tabs may be intentional.
- Leading/trailing whitespace may be intentional.
- This matters for notes, Markdown, code, YAML/JSON, aligned text, and text fields in general.

The expected principle is:

- What the user sees or enters should be preserved as-is whenever possible.
- Capture should be lossless.
- Token savings from whitespace normalization were not measured, so doing it in capture looked like over-optimization without evidence.

## Final Design Direction

Use raw values in `PerceptionElement` for:

- `text`
- `description`
- `hintText`

This is intentionally conservative. Even though `description` and `hintText` are not always user-entered text, they are still real accessibility signals and can carry meaningful formatting.

## Code Changes

### 1. Preserve raw captured text

Updated [Perceptor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt):

- `text = text`
- `description = desc`
- `hintText = hintText`

Instead of normalizing those fields at capture time.

### 2. Remove capture-time normalization helper

Updated [PerceptorInternals.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/perception/PerceptorInternals.kt):

- Removed `normalizeWhitespace()`
- Removed the related regex helpers

This makes the code reflect the new rule directly: capture does not rewrite content.

### 3. Align prompt text with text targeting

Updated [Perceptor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt) and [TargetResolver.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/TargetResolver.kt):

- Prompt `text` now uses visible/accessibility text semantics only: `text -> description -> hintText`
- Prompt `text` no longer falls back to `resourceId` suffix
- Added `normalizeForMatching()` for downstream matching/indexing only
- `text_index` now uses that shared matching canonicalization
- `TargetResolver` now:
  - matches prompt-text semantics first
  - falls back to `description` / `hintText` only when no prompt-text match exists
  - no longer implicitly treats `resourceId` suffix as text

This keeps capture lossless while fixing the prompt/action contract drift.

### 4. Update tests

Updated [PerceptorTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/test/kotlin/com/moonkey/androidagent/perception/PerceptorTest.kt):

- Added a test that verifies `Perceptor.toPromptJson()` preserves raw whitespace in `text`, `desc`, and `hint_text`
- Added tests for visible-text fallback and matching-only `text_index` canonicalization

Updated [PerceptorInternalsTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/test/kotlin/com/moonkey/androidagent/perception/PerceptorInternalsTest.kt):

- Removed tests that validated the deleted capture-time normalization helper
- Added tests for `normalizeForMatching()`

Updated [TargetResolverTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/test/kotlin/com/moonkey/androidagent/tool/action/TargetResolverTest.kt):

- Added tests for normalized text matching
- Added tests for hint-text fallback
- Added a regression test that `resourceId` suffix alone is not text-targetable

### 5. Update docs

Updated [platform.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/infra/platform.md):

- Removed the outdated note that `PerceptorInternals.kt` performs whitespace normalization
- Added a short perception semantics note so `doc/main/` reflects the current lossless-capture rule

Added [perception.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/infra/perception.md):

- Split perception details out of the generic platform doc into a dedicated SOTA document
- Documented the capture pipeline, `ScreenSnapshot` boundary, prompt JSON contract, and text-targeting semantics

Updated [README.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/README.md):

- Added the new perception doc to `doc/main` navigation and reading order

## Expected Impact

Positive:

- The agent now sees text closer to the real accessibility output.
- Text fields and whitespace-sensitive content are safer.
- The perception layer is simpler and less opinionated.
- Prompt text and text targeting are now closer to the same semantic contract.

Tradeoff:

- Prompt text may become slightly noisier.
- Token usage may increase slightly in some cases.
- Unlabeled nodes that only had a `resourceId` suffix no longer masquerade as `text`; the agent should rely on `element_index` or `id` instead.

Current judgment: that tradeoff is acceptable because fidelity is more important than speculative token savings.

## Verification

Ran targeted unit tests successfully:

```bash
./gradlew testDebugUnitTest --tests com.moonkey.androidagent.perception.PerceptorTest --tests com.moonkey.androidagent.perception.PerceptorInternalsTest
```

## Follow-up Ideas

If more reduction or disambiguation is needed later, keep it out of capture and add it to a narrower layer with a clear purpose, for example:

- `normalizeForPrompt()`
- dedup-only canonicalization

Remaining thing worth reviewing: `desc_index` is still emitted in prompt JSON, but text targeting only has `text_index`. That is a separate targeting-contract cleanup, not a capture-fidelity issue.
