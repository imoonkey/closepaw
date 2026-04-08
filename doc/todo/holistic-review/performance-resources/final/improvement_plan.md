# Performance Improvement Plan — Final

Based on the aligned double-design review. Ordered by impact-to-effort ratio.
Authors: Claude + Codex

---

## Tier 1: Quick Wins (< 1 hour each)

### 1. Enable R8 minification and resource shrinking
- **Ref:** RES-4
- **File:** `app/build.gradle.kts`
- **Impact:** HIGH | **Effort:** LOW
- **Change:** Enable `isMinifyEnabled = true`, `isShrinkResources = true` for release. Add keep rules for kotlinx.serialization, Shizuku AIDL, OpenAI SDK reflection, Leap SDK JNI.
- **Expected:** 20-40% APK size reduction, faster cold start, lower memory footprint.
- **Verify:** Release APK smoke test (startup, session, tool invocation, tracing). Compare APK size and method count.

### 2. Fix history compression O(n^2) token recalculation
- **Ref:** CPU-4
- **File:** `history/HistoryManager.kt`
- **Impact:** HIGH | **Effort:** LOW
- **Change:** Maintain a running token total during Phase 2 eviction. Subtract evicted item's cost instead of rescanning full history.
- **Verify:** Unit tests for token estimates across add/remove/compress cycles. Benchmark on large synthetic histories.

### 3. Fix truncation O(n^2) indexOf
- **Ref:** CPU-3
- **File:** `perception/PerceptorInternals.kt`
- **Impact:** MEDIUM | **Effort:** LOW
- **Change:** Replace `candidates.indexOf(c)` with HashSet-based dedup or pre-computed identity map. Eliminate all linear scans in selection loops.
- **Verify:** Unit tests preserving current truncation order and dedup behavior. Benchmark with 500-1000 synthetic candidates.

### 4. Fix FileTraceRecorder flush() bug + add batching
- **Ref:** IO-1, RES-2
- **File:** `trace/FileTraceRecorder.kt`
- **Impact:** MEDIUM | **Effort:** LOW
- **Change:** (a) Make `WriteOp.Flush` handler call `writer.flush()`. (b) Stop flushing on every `AppendLine`. Flush on explicit `Flush` op, on close, and optionally on batch threshold (e.g., every 10 lines or when channel is empty).
- **Verify:** Stress trace recording with many events. Confirm session completion leaves latest lines on disk.

### 5. Guard streaming accumulation behind verbose flag
- **Ref:** MEM-2
- **Files:** `llm/CodexResponseClient.kt`, `OpenAIResponseClient.kt`, `ChatCompletionClient.kt`
- **Impact:** LOW | **Effort:** LOW
- **Change:** Expose `LlmLogger.isVerboseEnabled`. Only allocate `StringBuilder` and tool-call buffers when verbose logging is enabled. Keep delta emission unchanged.
- **Verify:** Unit test that streaming output is unchanged. Confirm no `ResponsesResult` object built in non-verbose mode.

### 6. Pre-size ByteArrayOutputStream for JPEG compression
- **Ref:** (Claude-only finding)
- **File:** `platform/BitmapUtils.kt`
- **Impact:** LOW | **Effort:** LOW
- **Change:** `ByteArrayOutputStream(bitmap.width * bitmap.height * 4 / 10)` (clamped to 1KB-512KB). Eliminates 10-12 buffer doublings per compression.
- **Verify:** Verify JPEG output is identical.

---

## Tier 2: Medium Effort (1-4 hours each)

### 7. Single-pass Perceptor traversal
- **Ref:** CPU-1
- **File:** `perception/Perceptor.kt`
- **Impact:** HIGH | **Effort:** MEDIUM
- **Change:** Merge `INTERACTIVE_ONLY` and `ALL` traversals into a single pass. Tag each collected element as interactive or not during traversal. Preserve prioritization in `applyTruncation` (which already handles interactive/non-interactive separation).
- **Verify:** Snapshot parity tests on representative trees. Compare candidate counts, interactive coverage, and capture latency.

### 8. Incremental screen downgrade tracking
- **Ref:** CPU-5
- **File:** `history/HistoryManager.kt`
- **Impact:** MEDIUM | **Effort:** MEDIUM
- **Change:** Maintain screen-observation indices incrementally instead of rebuilding from scratch on every new screen. Avoid O(n) full-history scan per screen addition.
- **Verify:** Unit tests for screen downgrade behavior across long sessions.

### 9. Text enrichment optimization
- **Ref:** CPU-2
- **File:** `perception/PerceptorInternals.kt`
- **Impact:** MEDIUM | **Effort:** MEDIUM
- **Change:** Replace full text-source scan with either parent/child propagation during traversal, or a cheap spatial index built once. Cache `mergedText()` per source.
- **Verify:** Unit tests for enrichment behavior on nested labels/buttons. Benchmark on large synthetic snapshots.

---

## Tier 3: Careful Changes

### 10. Bitmap exception safety
- **Ref:** RES-1
- **File:** `platform/AccessibilityScreenshotCapturer.kt`
- **Impact:** LOW | **Effort:** LOW
- **Change:** Wrap `hardwareBitmap`, `softwareBitmap`, and `scaledBitmap` in nested `try/finally` blocks ensuring recycle on any failure path.
- **Verify:** Failure-path tests or fault injection around bitmap copy/compress.

### 11. Adaptive post-action retries
- **Ref:** IO-2
- **File:** `tool/action/PostActionAnalysis.kt`
- **Impact:** MEDIUM | **Effort:** MEDIUM
- **Change:** Only retry for action classes that commonly cause delayed transitions. Use cheap change signals (window/package/root count) before full deep comparison. Consider shorter second attempt.
- **Verify:** Regression-test known slow flows. Measure average post-action latency before/after.

### 12. Streaming cancellation hooks
- **Ref:** RES-3
- **Files:** `llm/CodexResponseClient.kt`, `OpenAIResponseClient.kt`, `ChatCompletionClient.kt`
- **Impact:** LOW | **Effort:** MEDIUM
- **Change:** Keep a handle to the active call/stream. Cancel from `awaitClose`. Ensure retries stop on cancellation.
- **Verify:** Start long stream, cancel collector, confirm sockets/threads stop promptly.

---

## Implementation Order

1. **R8 minification** — highest ROI, affects every user
2. **History compression O(n^2)** — simple fix, measurable for long sessions
3. **Truncation indexOf O(n^2)** — simple fix, helps dense screens
4. **FileTraceRecorder flush bug + batching** — correctness fix + perf
5. **Streaming accumulation guard** — trivial change
6. **ByteArrayOutputStream pre-sizing** — trivial change
7. **Single-pass Perceptor traversal** — largest CPU win, needs careful validation
8. **Screen downgrade tracking** — helps long sessions
9. **Text enrichment optimization** — product-sensitive, needs careful design
10. **Bitmap exception safety** — correctness hardening
11. **Adaptive post-action retries** — needs regression testing
12. **Streaming cancellation hooks** — client-library-dependent
