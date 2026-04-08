# Performance & Resource Efficiency Review

Scope: `app/src/main/kotlin/com/moonkey/androidagent/`
Date: 2026-04-08

---

## Perspective A: Runtime Performance (CPU, Memory, Battery, Network)

### A1. Perception: Double Tree Traversal

**File:** `perception/Perceptor.kt` lines 69-94
**Impact:** MEDIUM
**Category:** CPU

The `snapshot()` function traverses every root twice: once in `INTERACTIVE_ONLY` mode, then again in `ALL` mode. Both traversals walk the entire accessibility tree using DFS. The `seenKeys` set prevents duplicate *collection*, but every node is still visited, bounds-checked, and field-extracted twice.

For a typical screen with 300-500 nodes and 2-3 roots, this means 600-1500 node visits where 300-500 would suffice with a single pass that captures both interactive and non-interactive elements.

### A2. Perception: O(n^2) in `applyTruncation` via `indexOf`

**File:** `perception/PerceptorInternals.kt` lines 50-80
**Impact:** MEDIUM
**Category:** CPU

`applyTruncation` calls `candidates.indexOf(c)` for each kept element. Since `indexOf` does linear scan on a list, this is O(n*k) where n = candidates, k = kept. At maxElements=500 with 2x over-collection (1000 candidates), this is up to 500K comparisons. The elements are data classes with many fields, making each `equals()` comparison expensive.

The keptIndices `HashSet` is already there but keyed on the list position, which is itself found via the expensive `indexOf`. An identity-based approach or pre-computed map would eliminate this.

### A3. Perception: `enrichEmptyTextElements` quadratic containment check

**File:** `perception/PerceptorInternals.kt` lines 19-48
**Impact:** LOW
**Category:** CPU

For each interactive element needing enrichment, the code scans all `textSources` to find contained text. This is O(interactive * textSources). The `.asSequence().take(3)` limits per-element work, but the filter on `contains(bounds)` still runs against the full text source list. At 500 elements this is manageable, but worth noting.

### A4. Screenshots: Intermediate Bitmap Allocations

**File:** `platform/AccessibilityScreenshotCapturer.kt` lines 123-187
**Impact:** MEDIUM
**Category:** Memory

The screenshot pipeline creates up to 3 bitmaps per capture:
1. `hardwareBitmap` from `wrapHardwareBuffer`
2. `softwareBitmap` from `.copy(ARGB_8888)` -- full-resolution ARGB (4 bytes/pixel)
3. `scaledBitmap` from `scaleBitmapIfNeeded`

On a 1080x2340 display, the software bitmap alone is ~10MB. Plus the scaled version and JPEG bytes, peak memory per capture is ~12-15MB. This happens on `Dispatchers.Default`, so it won't block UI, but it's a GC pressure point.

The code correctly recycles all bitmaps and the hardware buffer, so there are no leaks. But the peak memory is high.

### A5. Screenshots: `ByteArrayOutputStream` default sizing

**File:** `platform/BitmapUtils.kt` lines 36-41
**Impact:** LOW
**Category:** Memory

`ByteArrayOutputStream()` starts with a 32-byte internal buffer and doubles on growth. For a JPEG that typically lands at 50-150KB, this causes 10-12 reallocation+copy cycles. Pre-sizing with an estimate (e.g., `ByteArrayOutputStream(bitmap.width * bitmap.height / 10)`) would eliminate most of this churn.

### A6. History: Repeated `estimateTokenCount` recalculation

**File:** `history/HistoryManager.kt` lines 131-136, 188-213
**Impact:** MEDIUM
**Category:** CPU

`estimateTokenCount()` is called multiple times during compression (Phase 2 loop), and each call recomputes the sum when `lastTokenEstimate` is null. However, every `items.removeAt(i)` sets `lastTokenEstimate = null`, forcing a full O(n) recalculation on the next iteration. In Phase 2, this creates O(n^2) total work across all eviction iterations.

A delta-based approach (subtract the evicted item's tokens from the running total) would make this O(n).

### A7. History: `normalizeHistory` repeated filtering

**File:** `history/HistoryManager.kt` lines 382-403
**Impact:** LOW
**Category:** CPU

`normalizeHistory` creates two filtered lists (`callIds`, `outputCallIds`) then does set operations and `indexOfFirst` lookups. Each `indexOfFirst` is O(n). For typical history sizes (50-200 items), this is fast, but it runs on every `compress()` and `forPrompt()` call.

### A8. LLM Streaming: `callbackFlow` overhead for CodexResponseClient

**File:** `llm/CodexResponseClient.kt` lines 130-197
**Impact:** LOW
**Category:** CPU/Memory

The streaming path uses `callbackFlow` + `trySend`, which is appropriate. The SSE parser creates `JSONObject` for every event, which involves string allocation and parsing. For a typical LLM response with 50-200 events, this is fine. The `StringBuilder` accumulators for text and tool call arguments are correctly used.

### A9. Post-Action Analysis: Up to 3 screen captures per action

**File:** `tool/action/PostActionAnalysis.kt` lines 17-91
**Impact:** MEDIUM
**Category:** Battery/CPU

`capturePostActionAnalysis` captures the screen after every UI action, with up to 2 retry captures (at +500ms and +1000ms delays) if no change is detected. Each capture involves a full a11y tree traversal + optional screenshot. The total budget is 1800ms per action.

This is by design (detecting slow transitions), but for rapid sequences of actions the cumulative cost is significant. A 10-turn session with 2 actions per turn = 20 captures at minimum, potentially 60 with retries.

### A10. UiChangeDetector: Sorting elements for fingerprint

**File:** `tool/action/UiChangeDetector.kt` lines 76-101
**Impact:** LOW
**Category:** CPU

`fingerprintFromElements` sorts elements by index before hashing. Since elements from Perceptor are already indexed in order, this sort is always a no-op sort on a pre-sorted list. `sortedBy` still allocates a new list and iterates. Using the elements directly (since they're already ordered by index from Perceptor) would save the allocation.

### A11. Overlay: Full-screen glow Canvas on every frame

**File:** `ui/overlay/compose/EdgeGlowCompose.kt`, `GlowOverlayHost.kt`
**Impact:** LOW
**Category:** Battery/GPU

The glow overlay uses `Canvas(fillMaxSize)` with 4 gradient `drawRect` calls. With the infinite pulse animation, this recomposition runs continuously while active. The overlay uses `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` so it doesn't intercept events, but it does consume GPU cycles during pulse animation.

The overlay is only shown during active agent execution and auto-hides after completion, which is correct behavior. The performance cost during active use is acceptable since the agent is already actively consuming resources.

### A12. Build Config: No minification in release

**File:** `app/build.gradle.kts` line 26
**Impact:** HIGH
**Category:** APK size / startup

`isMinifyEnabled = false` in the release build type means:
- No R8/ProGuard tree shaking -- dead code from OpenAI SDK, OkHttp, Compose, Leap SDK, etc. ships in the APK
- No code optimization
- No name mangling (slight bytecode overhead)
- Estimated APK size bloat: 20-40% larger than necessary

For an accessibility service that runs in the background, startup time and memory footprint matter. R8 can significantly reduce both.

---

## Perspective B: Resource Management (Leaks, Cleanup, Efficiency)

### B1. Bitmap lifecycle: Correct and thorough

**Files:** `AccessibilityScreenshotCapturer.kt`, `VirtualDisplayScreenshotProcessor.kt`, `BitmapUtils.kt`, `UiChangeDetector.kt`
**Impact:** N/A (positive finding)

All bitmap paths properly recycle intermediate bitmaps. The `if (scaledBitmap !== softwareBitmap)` identity check prevents double-recycle. `UiChangeDetector.decodeToGrayscale8x8` correctly uses `try/finally` to recycle decoded bitmaps. No bitmap leaks detected.

### B2. AccessibilityNodeInfo lifecycle: Mostly correct, one subtlety

**Files:** `perception/Perceptor.kt`, `platform/AccessibilityNodeFinder.kt`
**Impact:** LOW

Perceptor correctly recycles child nodes via `shouldRecycle` flag and does NOT recycle roots (caller responsibility). `AccessibilityNodeFinder` properly recycles non-winning candidates.

Minor concern: In `findActionableNodeAtLocation`, if an exception is thrown during the `collect` traversal, nodes that were added to `candidates` before the exception are not recycled. In practice, the traversal is simple field access that shouldn't throw, so this is theoretical.

### B3. HistoryManager synchronization: Correct but coarse

**File:** `history/HistoryManager.kt`
**Impact:** LOW
**Category:** Thread contention

All public methods are `@Synchronized`, which is correct for thread safety. The `compress()` method holds the lock for its entire multi-phase pipeline, which could block `addItem()` from the agent thread during compression. In practice, compression is fast (ms-scale), so this is unlikely to cause visible latency.

### B4. SessionRecordingService: Debounced saves -- good pattern

**File:** `history/SessionRecordingService.kt`
**Impact:** N/A (positive finding)

The 500ms debounce on saves prevents excessive disk I/O during rapid agent turns. `scheduleSave` cancels the previous job before launching a new one. `completeSession` correctly waits for any pending save before doing the final write. No fire-and-forget I/O that could lose data.

### B5. FileTraceRecorder: Channel-based write coalescing

**File:** `trace/FileTraceRecorder.kt`
**Impact:** LOW
**Category:** I/O efficiency

Good design: uses a `Channel(2048)` to buffer writes and processes them in a single writer loop. However, `writer.flush()` is called after EVERY `AppendLine` (line 130). For a busy agent session producing 10+ events per second, this means 10+ fsync-equivalent calls per second. Batching flushes (e.g., flush every N lines or on a timer) would reduce I/O overhead.

### B6. FileTraceRecorder: `trySend` drop-on-full is correct

**File:** `trace/FileTraceRecorder.kt` lines 97-101
**Impact:** N/A (positive finding)

When the channel is full (2048 pending), events are dropped with a warning log. This is the correct choice for a diagnostic trace -- never block the agent loop for tracing. The channel capacity is generous enough that drops should be rare.

### B7. MemoryStore: File I/O on synchronized methods

**File:** `memory/MemoryStore.kt`
**Impact:** LOW
**Category:** Thread contention

`append()` and all `read*()` methods are `@Synchronized` and perform file I/O (read+write) while holding the lock. Since memory operations happen at most once per turn (recall) and rarely (remember), this is fine. But if called from the main thread, it would block.

The `MemoryRecaller.recall()` calls `readUserMemory()` + `readDeviceMemory()` + `readAppMemory()`, which are 3 synchronized file reads. This is called from the prompt construction path, which should be on an IO dispatcher.

### B8. OkHttpClient per CodexResponseClient: No shared pool

**File:** `llm/CodexResponseClient.kt` line 222-232
**Impact:** LOW
**Category:** Resource management

Each `CodexResponseClient` creates its own `OkHttpClient` with a separate connection pool and dispatcher. The `LLMClientFactory` caches clients by provider key, so in practice there's usually only one. The `cleanup()` method correctly evicts connections and shuts down the dispatcher.

The `OpenAIResponseClient` uses the OpenAI SDK's built-in client, which manages its own pool. Both approaches are fine for single-session use.

### B9. Session cleanup: Thorough and ordered

**File:** `session/SessionServices.kt` lines 209-236, `session/AgentSession.kt` lines 469-510
**Impact:** N/A (positive finding)

`SessionServices.cleanup()` properly:
1. Cancels pending tool calls and user responses
2. Clears history (frees string references)
3. Stops the platform (releases VD resources)
4. Cleans up LLM clients (evicts connections)
5. Closes trace recorder last (captures teardown artifacts)

`AgentSession.handleShutdown()` is idempotent (double-call safe) and properly sequences the shutdown.

### B10. Session idle timeout: Correct auto-cleanup

**File:** `session/AgentSession.kt` lines 547-559
**Impact:** N/A (positive finding)

5-minute idle timeout (`IDLE_TIMEOUT_MS = 300_000L`) auto-shutdowns the session, preventing resource leaks from forgotten sessions. The timeout is cancelled on new user input and re-armed after task completion. This is a good battery-conscious pattern.

### B11. SSE Parser: StringBuilder for multi-line data fields

**File:** `llm/CodexSseParser.kt` lines 22-61
**Impact:** LOW
**Category:** Memory

The SSE parser uses `StringBuilder` + `clear()` per event block. This is efficient. One minor allocation: `data.toString().trim()` creates a new string on every blank line (event boundary). For typical SSE streams this is negligible.

### B12. LlmLogger: Debug-only, properly gated

**File:** `llm/LlmLogger.kt`
**Impact:** N/A (positive finding)

`VERBOSE_LOGGING = BuildConfig.DEBUG` gates all logging methods. In release builds, `logInput` and `logOutput` return immediately without any string formatting or iteration. No performance cost in production.

### B13. Token estimation: Char-based approximation is efficient

**File:** `history/ResponseItem.kt`, `history/HistoryManager.kt`
**Impact:** N/A (positive finding)

Using `0.25f * content.length` as a token estimate avoids the need for a tokenizer library. The estimate is conservative enough for budget management. The `TOKENS_PER_CHAR` constant appears in both `ResponseItem.kt` and `HistoryManager.kt` (slight redundancy but not a performance issue).

### B14. Compose overlay: `DisposeOnDetachedFromWindow` is correct

**File:** `ui/overlay/compose/OverlayComposeHost.kt` line 37
**Impact:** N/A (positive finding)

The `ViewCompositionStrategy.DisposeOnDetachedFromWindow` ensures Compose composition is cleaned up when the overlay is removed from the window manager. No composition leaks.

---

## Synthesis: Top Findings by Impact

| # | Finding | Impact | Category | Effort |
|---|---------|--------|----------|--------|
| A12 | No R8 minification in release | HIGH | APK size/startup | LOW |
| A6 | O(n^2) token recalculation in compression | MEDIUM | CPU | LOW |
| A2 | O(n^2) indexOf in applyTruncation | MEDIUM | CPU | LOW |
| A1 | Double tree traversal in Perceptor | MEDIUM | CPU | MEDIUM |
| A4 | Peak ~15MB bitmap memory per capture | MEDIUM | Memory | MEDIUM |
| A9 | Up to 3 captures per action (1.8s budget) | MEDIUM | Battery | N/A (by design) |
| B5 | Per-line flush in trace writer | LOW | I/O | LOW |
| A5 | ByteArrayOutputStream default sizing | LOW | Memory | LOW |
| A10 | Unnecessary sort in fingerprint | LOW | CPU | LOW |
| B7 | Synchronized file I/O in MemoryStore | LOW | Contention | LOW |
