# Performance / Resource Improvement Plan

Ordering principle: highest impact per unit of engineering effort first. References below point back to finding IDs in `design_codex.md`.

## Priority 1

### 1. Fix trace batching and make `flush()` real
- Addresses: `IO-1`, `RES-2`
- Files: `app/src/main/kotlin/com/moonkey/androidagent/trace/FileTraceRecorder.kt`
- Why first: this is a localized change with immediate battery and IO payoff during traced runs, and it fixes a misleading API at the same time.
- Change:
  - Stop calling `writer.flush()` on every `AppendLine`.
  - Flush on explicit `WriteOp.Flush`, on close, and optionally on a simple batch threshold or time threshold.
  - Keep `flush()` as the durability boundary used by session completion.
- Verification:
  - Stress trace recording with many events and compare elapsed time / file write count before and after.
  - Confirm that a forced session completion still leaves the latest trace lines on disk after `flush()`.

### 2. Remove the quadratic `indexOf()` path from truncation
- Addresses: `CPU-3`
- Files: `app/src/main/kotlin/com/moonkey/androidagent/perception/PerceptorInternals.kt`
- Why first: very small code change, hot path, zero product-risk if ordering is preserved.
- Change:
  - Carry original indices alongside candidates before sorting, or build an `IdentityHashMap`/index map once.
  - Replace repeated `candidates.indexOf(c)` calls with O(1) lookups.
- Verification:
  - Add unit tests that preserve current truncation order and dedup behavior.
  - Benchmark `applyTruncation()` with 500-1000 synthetic candidates.

### 3. Stop accumulating full streaming responses when verbose logging is off
- Addresses: `MEM-2`
- Files: `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt`, `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt`, `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt`, `app/src/main/kotlin/com/moonkey/androidagent/llm/LlmLogger.kt`
- Why first: low-effort cleanup across a few files with immediate memory and CPU savings in release builds.
- Change:
  - Expose a cheap `LlmLogger.isVerboseEnabled` flag.
  - Only allocate `StringBuilder` / completed tool-call buffers when verbose logging is enabled.
  - Keep emitting deltas and tool calls exactly as today.
- Verification:
  - Unit-test that streaming output is unchanged.
  - Confirm no final `ResponsesResult` object is built in non-verbose mode.

## Priority 2

### 4. Make history compression incremental instead of repeatedly recounting tokens
- Addresses: `CPU-4`, partially `CPU-5`
- Files: `app/src/main/kotlin/com/moonkey/androidagent/history/HistoryManager.kt`
- Why here: high impact, but slightly more invasive than the first three because it touches core history bookkeeping.
- Change:
  - Maintain a running token total and adjust it on insert/remove/replace instead of invalidating and rescanning.
  - During compression, subtract evicted item costs as items are removed.
  - If screen downgrading remains hot, maintain screen-observation indices or counts incrementally rather than rebuilding them from scratch.
- Verification:
  - Add tests for token estimates before/after `addItem`, `recordItems`, `replaceAll`, and `compress`.
  - Benchmark compression on large synthetic histories.

### 5. Enable R8/minification and resource shrinking for release
- Addresses: `RES-4`
- Files: `app/build.gradle.kts`, plus any required keep-rule files
- Why here: strong runtime and distribution win, but it may surface keep-rule issues that need iteration.
- Change:
  - Enable `isMinifyEnabled = true` for release.
  - Enable `shrinkResources = true` once minification is on.
  - Add the minimum required keep rules for reflection, serialization, OpenAI SDK types, and accessibility/service entry points.
- Verification:
  - Build a release APK/AAB.
  - Run a smoke test for startup, session creation, tool invocation, and trace writing.
  - Compare APK size and method count before and after.

### 6. Make post-action retries adaptive instead of always doing three full captures
- Addresses: `IO-2`
- Files: `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PostActionAnalysis.kt`
- Why here: good battery/latency win, but it needs care to avoid regressing slow-transition detection.
- Change:
  - Only retry for action classes that commonly cause delayed UI transitions.
  - Stop retrying once a cheap signal changes, such as window/package/root count, instead of requiring a full deep comparison every time.
  - Consider a shorter second attempt and a configuration cap for maximum retry budget.
- Verification:
  - Regression-test known slow flows.
  - Measure average post-action latency and number of captures per action before/after.

## Priority 3

### 7. Collapse Perceptor collection into a single tree walk
- Addresses: `CPU-1`
- Files: `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
- Why here: this is one of the biggest hot-path wins, but it changes core perception behavior and needs careful validation.
- Change:
  - Traverse each root once.
  - During that single pass, collect all eligible nodes and tag them as interactive or non-interactive.
  - Preserve the current prioritization in later phases (`applyTruncation`) instead of encoding it as a second traversal mode.
- Verification:
  - Snapshot parity tests on representative trees.
  - Compare candidate counts, interactive coverage, and capture latency before/after.

### 8. Replace quadratic text enrichment with a local propagation strategy
- Addresses: `CPU-2`
- Files: `app/src/main/kotlin/com/moonkey/androidagent/perception/PerceptorInternals.kt`
- Why here: big CPU win, but the implementation choice matters because text bubbling semantics are product-sensitive.
- Change:
  - Prefer parent/child propagation during traversal, or
  - Build a cheap spatial index once and query it, rather than scanning all text sources for each candidate.
  - Cache merged text for sources instead of recomputing it in the inner loop.
- Verification:
  - Unit tests for current enrichment behavior on nested labels/buttons.
  - Benchmark enrichment cost on large synthetic snapshots.

### 9. Make screenshot cleanup exception-safe, then reduce peak allocation size
- Addresses: `MEM-1`, `RES-1`
- Files: `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityScreenshotCapturer.kt`
- Why here: important for stability, but reducing peak memory beyond cleanup likely needs experimentation.
- Change:
  - Wrap `hardwareBitmap`, `softwareBitmap`, and `scaledBitmap` in nested `try/finally` cleanup so every allocation is released on failure.
  - After cleanup is correct, evaluate ways to reduce peak memory:
    - avoid keeping both original and scaled bitmaps live longer than necessary
    - pre-size or reuse compression buffers where practical
    - consider a lower-cost intermediate format only if image quality remains acceptable
- Verification:
  - Failure-path tests or fault injection around bitmap copy/compress.
  - Memory profiling on large-screen devices during repeated capture.

### 10. Add explicit cancellation hooks to streaming clients
- Addresses: `RES-3`
- Files: `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt`, `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt`, `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt`
- Why last: useful cleanup, but lower immediate payoff than the CPU/IO hotspots above and somewhat dependent on each client library's cancellation primitives.
- Change:
  - Keep a handle to the active call/stream.
  - Cancel or close it from `awaitClose` when the collector goes away.
  - Ensure retries stop promptly on cancellation.
- Verification:
  - Start a long stream, cancel the collector, and confirm sockets/threads stop promptly.
  - Verify no extra retry attempts occur after cancellation.
