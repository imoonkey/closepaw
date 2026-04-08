# Performance & Resource Efficiency Review — Final

Scope: `app/src/main/kotlin/com/moonkey/androidagent/`
Date: 2026-04-08
Authors: Claude + Codex (double-design alignment)

---

## CPU

### CPU-1: Perceptor walks the accessibility tree twice per capture
- **File:** `perception/Perceptor.kt:69-94`
- **Impact:** HIGH
- **Description:** `snapshot()` traverses every root once in `INTERACTIVE_ONLY` mode and again in `ALL` mode, doubling tree walking, node property reads, bounds extraction, dedup-key construction, and child retrieval on the hottest capture path.
- **Evidence:** Two full `for (root in roots)` loops, each calling `traverse(...)`. `traverse()` reads text/description/hint/resource ID, action support, bounds, visibility ratio, and class name per node.

### CPU-2: Text enrichment is quadratic in candidate count
- **File:** `perception/PerceptorInternals.kt:23-46`
- **Impact:** MEDIUM
- **Description:** `enrichEmptyTextElements()` scans all text sources for every interactive element missing text. With `maxElements * 2` cap, this can produce hundreds of thousands of containment checks plus repeated `mergedText()` calls.
- **Evidence:** `candidates.map { ... textSources.asSequence().filter { contains(...) } ... }`. Bounded by the candidate cap, but O(n^2) within that bound.

### CPU-3: Truncation does repeated linear searches
- **File:** `perception/PerceptorInternals.kt:58-77`
- **Impact:** MEDIUM
- **Description:** `applyTruncation()` calls `candidates.indexOf(c)` in the interactive loop, non-interactive loop, and fallback loop. Each `indexOf` is O(n) linear scan with expensive data-class `equals()`.
- **Evidence:** `indexOf()` invoked in all three selection branches.

### CPU-4: History compression repeatedly recomputes token budget
- **File:** `history/HistoryManager.kt:131-136, 188-240`
- **Impact:** HIGH
- **Description:** The eviction loop checks `estimateTokenCount()` on every iteration, but every `removeAt()` sets `lastTokenEstimate = null`, forcing a full `items.sumOf { it.estimateTokens() }` rescan. Compression cost grows quadratically with history size.
- **Evidence:** `estimateTokenCount()` caches only until mutation. Inside `compress()`, every removal path invalidates the cache.

### CPU-5: Screen downgrading rescans full history on every new screen
- **File:** `history/HistoryManager.kt:45-59, 67-84, 326-343`
- **Impact:** MEDIUM
- **Description:** Adding a new screen observation triggers `downgradeOldScreens()`, which rebuilds the full list of screen indices then revisits all older screens. Work grows with session length.
- **Evidence:** `downgradeOldScreens()` does `items.withIndex().filter(...).map(...)` across entire history, called on every `SCREEN_OBSERVATION` recording.

---

## Memory

### MEM-1: Screenshot capture peaks at multiple full-frame allocations
- **File:** `platform/AccessibilityScreenshotCapturer.kt:127-180`
- **Impact:** MEDIUM
- **Description:** The screenshot path creates a hardware bitmap, copies to full ARGB_8888, may allocate a scaled bitmap, then materializes JPEG bytes. Peak ~12-15MB per capture on 1080x2340 display.
- **Evidence:** `wrapHardwareBuffer` → `.copy(ARGB_8888)` → `scaleBitmapIfNeeded` → `compressJpeg`. Recycling occurs on normal path only.

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

### IO-2: Post-action verification triggers up to three full captures per action
- **File:** `tool/action/PostActionAnalysis.kt:22-40, 81-90`
- **Impact:** MEDIUM
- **Description:** When first post-action capture looks unchanged, the code retries after 500ms and 1000ms. Each retry calls `platform.captureScreen()` (perception + optional screenshot). Total budget: 1800ms per action.
- **Evidence:** Always performs initial capture, retries twice if `Unchanged`. Intentional for slow-transition detection.

---

## Resource Management

### RES-1: Bitmap cleanup is not exception-safe
- **File:** `platform/AccessibilityScreenshotCapturer.kt:129-155, 184-186`
- **Impact:** LOW
- **Description:** Only `HardwareBuffer` is closed in `finally`. Hardware bitmap, software bitmap, and scaled bitmap are recycled only on the normal path. Exceptions after allocation leave large native allocations for GC.
- **Evidence:** `hardwareBuffer.close()` is the only `finally` cleanup. All `recycle()` calls are in the success path.

### RES-2: `flush()` does not actually flush the writer
- **File:** `trace/FileTraceRecorder.kt:135, 175-179`
- **Impact:** MEDIUM
- **Description:** The public `flush()` API sends a `WriteOp.Flush` through the channel, but the handler only completes the deferred without calling `writer.flush()`. Callers expecting durability get a queue barrier instead.
- **Evidence:** `WriteOp.Flush` branch executes `op.done.complete(Unit)` only. No `writer.flush()`.

### RES-3: No explicit cancellation for streaming flows
- **File:** `llm/CodexResponseClient.kt:135-196`, `OpenAIResponseClient.kt:86-175`, `ChatCompletionClient.kt:113-231`
- **Impact:** LOW
- **Description:** `awaitClose` handlers only log. No active call/stream handle is stored for cancellation. Abandoning the flow cannot proactively stop in-flight network reads or retry cycles.
- **Evidence:** `awaitClose` blocks contain only logging.

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
