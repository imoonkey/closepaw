# Performance and Resource Review

Scope: targeted review of the requested hotspots only. I did not sweep the rest of the codebase.

Reviewed files:
- `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/perception/PerceptorInternals.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityScreenshotCapturer.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/history/HistoryManager.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/trace/FileTraceRecorder.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PostActionAnalysis.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/LlmLogger.kt`
- `app/build.gradle.kts`

## CPU

### CPU-1: Perceptor walks the accessibility tree twice per capture
- File: `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
- Line range: `69-94`
- Impact: `HIGH`
- Description: `snapshot()` traverses every root once in `INTERACTIVE_ONLY` mode and then again in `ALL` mode. That doubles tree walking, node property reads, bounds extraction, dedup-key construction, and child retrieval for the hottest capture path in the app.
- Evidence: The code performs two full `for (root in roots)` loops, each calling `traverse(...)` from the root. `traverse()` is not a cheap pass: it reads text/description/hint/resource ID, action support, bounds, visibility ratio, and class name for each visited node.

### CPU-2: Text enrichment is quadratic in candidate count
- File: `app/src/main/kotlin/com/moonkey/androidagent/perception/PerceptorInternals.kt`
- Line range: `23-46`
- Impact: `HIGH`
- Description: `enrichEmptyTextElements()` builds `textSources`, then for every interactive element missing text it scans all text sources looking for containment. On dense screens this becomes an O(n^2) pass on top of the already expensive tree capture.
- Evidence: The function does `candidates.map { ... textSources.asSequence().filter { contains(...) } ... }`. With the current capture cap of up to `maxElements * 2`, this can devolve into hundreds of thousands of containment checks plus repeated `mergedText()` calls per capture.

### CPU-3: Truncation does repeated linear searches inside selection loops
- File: `app/src/main/kotlin/com/moonkey/androidagent/perception/PerceptorInternals.kt`
- Line range: `58-77`
- Impact: `MEDIUM`
- Description: `applyTruncation()` sorts the candidate lists and then repeatedly calls `candidates.indexOf(c)` while filling `kept`. That turns the selection stage into another O(n^2) pass even after the sort has already established ordering.
- Evidence: `indexOf()` is invoked in the interactive loop, the non-interactive loop, and the fallback loop. Each call scans the original list linearly.

### CPU-4: History compression repeatedly recomputes the entire token budget during eviction
- File: `app/src/main/kotlin/com/moonkey/androidagent/history/HistoryManager.kt`
- Line range: `131-136`, `188-240`
- Impact: `HIGH`
- Description: the eviction loop checks `estimateTokenCount()` on every iteration, but every removal resets `lastTokenEstimate` to `null`. That forces a full `items.sumOf { it.estimateTokens() }` rescan after each single removal, making compression cost grow roughly quadratically with history size.
- Evidence: `estimateTokenCount()` caches only until mutation. Inside `compress()`, every `removeAt()` path sets `lastTokenEstimate = null`, and the `while` loop condition immediately calls `estimateTokenCount()` again.

### CPU-5: Screen downgrading rescans all history whenever a new screen observation arrives
- File: `app/src/main/kotlin/com/moonkey/androidagent/history/HistoryManager.kt`
- Line range: `45-59`, `67-84`, `326-343`
- Impact: `MEDIUM`
- Description: adding a new screen observation triggers `downgradeOldScreens()`, which rebuilds the full list of screen indices and then revisits all older screens. The work grows with session length and is paid on every new screen message.
- Evidence: `downgradeOldScreens()` does `items.withIndex().filter(...).map(...)` across the entire history, then iterates all screens except the most recent tail. `addItem()` and `recordItems()` call it every time a new `SCREEN_OBSERVATION` is recorded.

## Memory

### MEM-1: Screenshot capture peaks at multiple full-frame allocations before compression
- File: `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityScreenshotCapturer.kt`
- Line range: `127-180`
- Impact: `HIGH`
- Description: the screenshot path wraps the hardware buffer, copies it to a full software `ARGB_8888` bitmap, may allocate a second scaled bitmap, then materializes the compressed JPEG as a `ByteArray` and keeps that array in `ScreenImage`. Peak memory is therefore several copies of the same frame in different forms.
- Evidence: `Bitmap.wrapHardwareBuffer(...)` is followed by `hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)`, then `BitmapUtils.scaleBitmapIfNeeded(...)`, then `BitmapUtils.compressJpeg(...)`, and finally the JPEG bytes are retained inside `ScreenImage`. Recycling of the earlier bitmaps only happens after compression succeeds.

### MEM-2: Streaming clients retain the full response in memory even though deltas are already emitted
- File: `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt`, `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt`, `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt`, `app/src/main/kotlin/com/moonkey/androidagent/llm/LlmLogger.kt`
- Line range: `CodexResponseClient.kt:151-169`, `OpenAIResponseClient.kt:95-142`, `ChatCompletionClient.kt:122-218`, `LlmLogger.kt:58-78`
- Impact: `MEDIUM`
- Description: all three streaming clients accumulate full text and completed tool-call lists while also emitting incremental events downstream. Those accumulators are only used to call `LlmLogger.logOutput()`, but the logger exits immediately when `BuildConfig.DEBUG` is false, so release builds still pay the memory and append cost for data they never use.
- Evidence: each client appends every text delta into a `StringBuilder` and stores tool calls in lists, then constructs a `ResponsesResult` solely for `LlmLogger.logOutput(...)`. `LlmLogger.logOutput()` returns immediately unless `VERBOSE_LOGGING` is enabled.

## Battery / IO

### IO-1: Trace recording flushes the JSONL writer on every single event
- File: `app/src/main/kotlin/com/moonkey/androidagent/trace/FileTraceRecorder.kt`
- Line range: `128-132`
- Impact: `HIGH`
- Description: `AppendLine` writes one line and immediately flushes the `BufferedWriter`. That defeats batching, increases write amplification, and forces the app into much more frequent disk IO than necessary during traced runs.
- Evidence: every `WriteOp.AppendLine` path executes `writer.append(...)`, `writer.newLine()`, and `writer.flush()` inside the writer loop.

### IO-2: Post-action verification can trigger up to three full screen captures for a single action
- File: `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PostActionAnalysis.kt`
- Line range: `22-40`, `81-90`
- Impact: `HIGH`
- Description: when the first post-action capture looks unchanged, the code performs two more delayed captures. In this app, `platform.captureScreen()` is a heavy operation: it re-runs screen perception and may also take and compress a screenshot. Repeating that up to three times per action directly increases CPU, battery, and latency.
- Evidence: the function always performs an initial capture, then retries after `500ms` and `1000ms` if the result is still `Unchanged`. `captureAttempt()` calls `platform.captureScreen()` each time.

## Resource Management

### RES-1: Bitmap cleanup is not exception-safe in the screenshot compression path
- File: `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityScreenshotCapturer.kt`
- Line range: `129-155`, `184-186`
- Impact: `MEDIUM`
- Description: only the `HardwareBuffer` is closed in `finally`. The wrapped hardware bitmap, software bitmap, and scaled bitmap are recycled only on the normal path. If any exception occurs after allocation and before the explicit `recycle()` calls, those large native allocations stay live until GC runs.
- Evidence: `hardwareBuffer.close()` is the only cleanup in `finally`. `hardwareBitmap.recycle()`, `softwareBitmap.recycle()`, and `scaledBitmap.recycle()` are performed conditionally in the success path.

### RES-2: `flush()` does not actually flush the writer, despite callers relying on it for durability
- File: `app/src/main/kotlin/com/moonkey/androidagent/trace/FileTraceRecorder.kt`
- Line range: `135`, `175-179`
- Impact: `MEDIUM`
- Description: the public `flush()` API is implemented as a channel barrier only. The queued `WriteOp.Flush` completes its deferred without calling `writer.flush()`. That means the method name promises stronger durability than it actually provides.
- Evidence: in `runWriterLoop()`, the `WriteOp.Flush` branch only executes `op.done.complete(Unit)`. The public `flush()` method sends that op and waits on the deferred. No writer flush occurs in that path.

### RES-3: Streaming flows do not expose an explicit cancellation path for active network calls
- File: `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt`, `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt`, `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt`
- Line range: `CodexResponseClient.kt:135-196`, `OpenAIResponseClient.kt:86-175`, `ChatCompletionClient.kt:113-231`
- Impact: `MEDIUM`
- Description: each client runs the network stream inline inside `callbackFlow` and only uses `awaitClose` for logging after the retry loop finishes. There is no explicit `cancel()` or close hook tied to collector cancellation, so abandoning the flow cannot proactively stop an in-flight network read or retry cycle.
- Evidence: the callback blocks call the streaming APIs directly before `awaitClose`, and the `awaitClose` handlers only log `"flow closed"`. No active call handle or stream handle is stored for cancellation.

### RES-4: Release builds disable code shrinking and resource shrinking
- File: `app/build.gradle.kts`
- Line range: `24-27`
- Impact: `MEDIUM`
- Description: the `release` build has `isMinifyEnabled = false`, and there is no corresponding `shrinkResources` setting. That leaves dead code, methods, and resources in the shipping app, increasing APK size and the amount of code/resources the runtime has to carry.
- Evidence: the release build type contains only `isMinifyEnabled = false`; no R8/proguard files or resource shrinking are enabled.
