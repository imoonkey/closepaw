# Performance & Resource Efficiency Review — Final

Scope: `app/src/main/kotlin/com/moonkey/androidagent/`
Date: 2026-04-08 | Revalidated: 2026-04-16
Authors: Claude + Codex (double-design alignment)

---

## CPU

### CPU-1: Perceptor walks the accessibility tree twice per capture
- **File:** `perception/Perceptor.kt:69-94`
- **Impact:** HIGH
- **Description:** `snapshot()` traverses every root once in `INTERACTIVE_ONLY` mode and again in `ALL` mode, doubling tree walking, node property reads, bounds extraction, dedup-key construction, and child retrieval on the hottest capture path.
- **Evidence:** Two full `for (root in roots)` loops, each calling `traverse(...)`. `traverse()` reads text/description/hint/resource ID, action support, bounds, visibility ratio, and class name per node.

### CPU-2: Text enrichment is quadratic in candidate count
- **File:** `perception/PerceptorInternals.kt:19-47`
- **Impact:** MEDIUM
- **Description:** `enrichEmptyTextElements()` scans all text sources for every interactive element missing text. With `maxElements=500` and 2x over-collection (up to 1000 candidates), this produces up to hundreds of thousands of containment checks plus repeated `mergedText()` calls.
- **Evidence:** `candidates.map { ... textSources.asSequence().filter { contains(...) } ... }`. Bounded by the candidate cap, but O(n^2) within that bound.
- **Note (2026-04-16):** More exposed than originally estimated — `PerceptorFilterConfig.maxElements` is 500 with 2x over-collection.

### CPU-3: Truncation does repeated linear searches
- **File:** `perception/PerceptorInternals.kt:50-79`
- **Impact:** MEDIUM
- **Description:** `applyTruncation()` calls `candidates.indexOf(c)` in the interactive loop, non-interactive loop, and fallback loop. Each `indexOf` is O(n) linear scan with expensive data-class `equals()`. With up to 1000 candidates from 2x over-collection, this is significant.
- **Evidence:** `indexOf()` invoked in all three selection branches.

### CPU-4: History compression repeatedly recomputes token budget
- **File:** `history/HistoryManager.kt:131-136, 155-267`
- **Impact:** HIGH
- **Description:** The eviction loop checks `estimateTokenCount()` on every iteration, but every `removeAt()` sets `lastTokenEstimate = null`, forcing a full `items.sumOf { it.estimateTokens() }` rescan. Compression cost grows quadratically with history size.
- **Evidence:** `estimateTokenCount()` caches only until mutation. Inside `compress()`, every removal path invalidates the cache.

### ~~CPU-5: Screen downgrading rescans full history on every new screen~~ LOW_ROI
- **File:** `history/HistoryManager.kt:45-59, 67-80, 327-343`
- **Impact:** ~~MEDIUM~~ LOW_ROI
- **Description:** `downgradeOldScreens()` rebuilds screen indices by scanning all items on every new screen observation.
- **Status (2026-04-16):** Real but not worth fixing. History is bounded by auto-compression with `maxTokenBudget=100_000` and `recentFullScreens=2`. Incremental index bookkeeping would add maintenance complexity across every mutation path (`addItem`, `recordItems`, `replaceAll`, normalization, group eviction, digest insertion, merge) for a bounded linear scan over an already-compressed in-memory list.

---

## Memory

### ~~MEM-1: Screenshot capture peaks at multiple full-frame allocations~~ LOW_ROI
- **File:** `platform/AccessibilityScreenshotCapturer.kt:129-190`
- **Impact:** ~~MEDIUM~~ LOW_ROI
- **Description:** The screenshot path creates a hardware bitmap, copies to full ARGB_8888, may allocate a scaled bitmap, then materializes JPEG bytes. Peak ~12-15MB per capture.
- **Status (2026-04-16):** Default perception mode is `AccessibilityOnly` (`PerceptionConfig.kt`), and `AccessibilityPlatform.captureScreen()` only attempts screenshot capture when perception mode includes screenshots or tracing is enabled. This path is rarely hit in normal operation. Reducing peak memory would require a larger pipeline redesign not justified for an off-by-default path.

### MEM-2: Streaming clients accumulate responses for unused debug logging
- **File:** `llm/CodexResponseClient.kt:151-169`, `OpenAIResponseClient.kt:95-142`, `ChatCompletionClient.kt:122-218`
- **Impact:** LOW
- **Description:** All streaming clients build `StringBuilder` accumulators and tool-call lists solely for `LlmLogger.logOutput()`, which returns immediately in release builds (`VERBOSE_LOGGING = BuildConfig.DEBUG`).
- **Evidence:** Accumulators constructed unconditionally; `LlmLogger.logOutput()` exits immediately when disabled.

---

## Battery / IO

### IO-1: Trace recording flushes on every single event
- **File:** `trace/FileTraceRecorder.kt:128-132`
- **Impact:** MEDIUM
- **Description:** `AppendLine` writes one line and immediately flushes the `BufferedWriter`, defeating batching and increasing write amplification during traced runs.
- **Evidence:** Every `WriteOp.AppendLine` path executes `writer.append(...)`, `writer.newLine()`, `writer.flush()`.

### ~~IO-2: Post-action verification triggers up to three full captures per action~~ LOW_ROI
- **File:** `tool/action/PostActionAnalysis.kt:18-43, 83-92`
- **Impact:** ~~MEDIUM~~ LOW_ROI
- **Description:** When first post-action capture looks unchanged, the code retries after 500ms and 1000ms. Each retry calls `platform.captureScreen()`. Total budget: 1800ms per action.
- **Status (2026-04-16):** This is a correctness tradeoff, not a clean perf win. The retry behavior catches slow UI transitions across diverse apps, windowing modes, and delayed intent launches. Making it adaptive requires a reliable cheap-change heuristic that works universally. Getting it wrong degrades action verification — a worse failure mode than the extra captures.

---

## Resource Management

### RES-1: Bitmap cleanup is not exception-safe
- **File:** `platform/AccessibilityScreenshotCapturer.kt:133-191`
- **Impact:** LOW
- **Description:** Only `HardwareBuffer` is closed in `finally`. Intermediate bitmaps are recycled only on the normal path.
- **Note (2026-04-16):** Leak window is narrower than originally stated — `hardwareBitmap` is recycled immediately after `copy()`, and `softwareBitmap`/`scaledBitmap` are recycled before debug persistence. The remaining gap is between allocation and those recycle calls on exceptional paths.

### RES-2: `flush()` does not actually flush the writer
- **File:** `trace/FileTraceRecorder.kt:135, 175-178`
- **Impact:** MEDIUM
- **Description:** The public `flush()` API sends a `WriteOp.Flush` through the channel, but the handler only completes the deferred without calling `writer.flush()`.
- **Note (2026-04-16):** Currently masked by IO-1 (per-line flush on every `AppendLine`). If IO-1 batching is implemented, RES-2 becomes immediately user-visible. Must be co-fixed with IO-1.

### RES-3: No explicit cancellation for streaming flows — PARTIALLY_FIXED
- **File:** `OpenAIResponseClient.kt:79-165`, `ChatCompletionClient.kt:109-246`
- **Impact:** LOW
- **Description:** `OpenAIResponseClient` and `ChatCompletionClient` still have no explicit cancellation path — `awaitClose` handlers only log.
- **Status (2026-04-16):** `CodexResponseClient` now stores the active OkHttp call and cancels it from `awaitClose` (`CodexResponseClient.kt:145-213`). The other two SDK-backed clients still lack equivalent cancellation hooks.

### RES-4: Release builds disable code and resource shrinking
- **File:** `app/build.gradle.kts:24-27`
- **Impact:** HIGH
- **Description:** `isMinifyEnabled = false` leaves dead code from OpenAI SDK, OkHttp, Compose, Leap SDK, etc. in the APK. No R8 tree shaking, no optimization, no resource shrinking. Estimated 20-40% APK bloat.
- **Evidence:** Release build type contains only `isMinifyEnabled = false`.

---

## Appendix: Confirmed Correct Patterns

These areas were reviewed and found to be correctly implemented. No changes needed.

- **Bitmap lifecycle:** All bitmap paths properly recycle intermediates. `if (scaledBitmap !== softwareBitmap)` prevents double-recycle. `UiChangeDetector.decodeToGrayscale8x8` uses `try/finally`.
- **Session cleanup:** `SessionServices.cleanup()` properly sequences resource teardown. `AgentSession.handleShutdown()` is idempotent.
- **Session idle timeout:** 5-minute auto-shutdown prevents resource leaks from forgotten sessions.
- **SessionRecordingService:** 500ms debounced saves prevent excessive disk I/O.
- **FileTraceRecorder channel:** `trySend` drop-on-full is correct for diagnostic tracing.
- **LlmLogger gating:** `VERBOSE_LOGGING = BuildConfig.DEBUG` gates all logging in release.
- **Token estimation:** `0.25f * content.length` is efficient and conservative.
- **Compose overlay:** `DisposeOnDetachedFromWindow` ensures proper composition cleanup.

## Appendix: Not Recommended (Premature Optimization)

- Pooling Rect/Point objects in Perceptor — JVM escape analysis handles these.
- Replacing JSONObject with manual string building — readability outweighs marginal gain.
- Caching AccessibilityNodeInfo fields — already Binder-cached, adds stale-data risk.
