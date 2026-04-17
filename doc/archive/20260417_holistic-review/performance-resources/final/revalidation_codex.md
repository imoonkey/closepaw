# Performance Review Revalidation — Codex

Date: 2026-04-15

Revalidated against the current codebase by reading:

- `doc/todo/holistic-review/performance-resources/final/review.md`
- `doc/todo/holistic-review/performance-resources/final/improvement_plan.md`
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
- `app/src/main/kotlin/com/moonkey/androidagent/platform/BitmapUtils.kt`
- `app/build.gradle.kts`

Summary:

- `STILL_VALID`: 9
- `PARTIALLY_FIXED`: 1
- `LOW_ROI`: 3
- `FIXED`: 0

| ID | Original finding | Current status | Current evidence / assessment |
| --- | --- | --- | --- |
| CPU-1 | Perceptor walks the accessibility tree twice per capture | `STILL_VALID` | `Perceptor.snapshot()` still has two full root loops: one `TraversalMode.INTERACTIVE_ONLY`, then one `TraversalMode.ALL` in `Perceptor.kt:69-94`. `traverse()` still does the expensive node reads in both passes. |
| CPU-2 | Text enrichment is quadratic in candidate count | `STILL_VALID` | `enrichEmptyTextElements()` still scans `textSources` for every interactive candidate that needs enrichment in `PerceptorInternals.kt:19-47`. This remains O(n^2). |
| CPU-3 | Truncation does repeated linear searches | `STILL_VALID` | `applyTruncation()` still calls `candidates.indexOf(c)` in all three selection loops in `PerceptorInternals.kt:50-79`. |
| CPU-4 | History compression repeatedly recomputes token budget | `STILL_VALID` | `compress()` still loops on `estimateTokenCount() > targetTokens` while every eviction path resets `lastTokenEstimate = null` in `HistoryManager.kt:155-267`. `estimateTokenCount()` still rescans `items.sumOf { it.estimateTokens() }` in `HistoryManager.kt:131-136`. |
| CPU-5 | Screen downgrading rescans full history on every new screen | `LOW_ROI` | The behavior still exists: `addItem()` / `recordItems()` call `downgradeOldScreens()` for new screen observations in `HistoryManager.kt:45-59,67-80`, and `downgradeOldScreens()` still rebuilds screen indices by scanning all items in `HistoryManager.kt:327-343`. I would not prioritize an incremental index structure now; see notes below. |
| MEM-1 | Screenshot capture peaks at multiple full-frame allocations | `LOW_ROI` | `AccessibilityScreenshotCapturer.compressScreenshot()` still goes `wrapHardwareBuffer -> copy(ARGB_8888) -> scale -> JPEG bytes` in `AccessibilityScreenshotCapturer.kt:129-190`. The old issue is real, but the practical payoff from reworking this path is low relative to the required redesign; see notes below. |
| MEM-2 | Streaming clients accumulate responses for unused debug logging | `STILL_VALID` | The streaming paths still unconditionally build `StringBuilder` / tool-call accumulators in `CodexResponseClient.kt:166-182`, `OpenAIResponseClient.kt:93-140`, and `ChatCompletionClient.kt:123-237`, while `LlmLogger` still returns immediately when `BuildConfig.DEBUG` is false in `LlmLogger.kt:8-18,58-60`. |
| IO-1 | Trace recording flushes on every single event | `STILL_VALID` | `FileTraceRecorder` still flushes on every `WriteOp.AppendLine` in `FileTraceRecorder.kt:128-132`. |
| IO-2 | Post-action verification triggers up to three full captures per action | `LOW_ROI` | `capturePostActionAnalysis()` still does the initial capture plus 500ms and 1000ms retries when unchanged in `PostActionAnalysis.kt:18-43,83-92`. The behavior is intentional and correctness-sensitive, so I would not optimize this first; see notes below. |
| RES-1 | Bitmap cleanup is not exception-safe | `STILL_VALID` | `AccessibilityScreenshotCapturer` still only guarantees `hardwareBuffer.close()` in `finally` in `AccessibilityScreenshotCapturer.kt:133-191`. `hardwareBitmap`, `softwareBitmap`, and `scaledBitmap` are still recycled only on the normal path. |
| RES-2 | `flush()` does not actually flush the writer | `STILL_VALID` | `WriteOp.Flush` still only completes the deferred in `FileTraceRecorder.kt:135`, and `flush()` still just enqueues that op in `FileTraceRecorder.kt:175-178`. The impact is partly masked by `AppendLine` flushing every line, but the API contract is still wrong. |
| RES-3 | No explicit cancellation for streaming flows | `PARTIALLY_FIXED` | `CodexResponseClient` now keeps `activeCall` and cancels it from `awaitClose` in `CodexResponseClient.kt:145-213`, which is a real fix. `OpenAIResponseClient.kt:79-165` and `ChatCompletionClient.kt:109-246` still do not keep an active stream/call handle and still have no explicit cancellation path. |
| RES-4 | Release builds disable code and resource shrinking | `STILL_VALID` | Release still sets only `isMinifyEnabled = false` in `app/build.gradle.kts:24-31`, and there is still no `isShrinkResources = true`. |

## Detailed Notes

### CPU-2 and CPU-3 are more exposed now than the old review implied

`PerceptorFilterConfig.maxElements` is now `500` in `PerceptorFilterConfig.kt:9-19`, and traversal still allows collecting up to `2 * maxElements` candidates before truncation in `Perceptor.kt:221-223`. That means the quadratic enrichment path and repeated `indexOf()` path can now operate on substantially larger candidate sets than the old review text suggested.

### CPU-5 is real, but the incremental fix is not attractive right now

The scan-on-every-screen behavior is still there, but `HistoryManager` is also bounded by auto-compression and a `maxTokenBudget` of `100_000` with only `recentFullScreens = 2` in `HistoryConfig.kt:4-11`. Replacing the current full scan with incremental screen-index bookkeeping would add maintenance complexity across every mutation path in `HistoryManager` (`addItem`, `recordItems`, `replaceAll`, normalization, group eviction, digest insertion, merge) for a bounded linear scan over an already-compressed in-memory list.

### Screenshot-related findings have lower practical impact in the current architecture

The current default perception mode is `AccessibilityOnly` in `PerceptionConfig.kt:11-35`, and `AccessibilityPlatform.captureScreen()` only attempts screenshot capture when the current perception mode includes screenshots or tracing is enabled in `AccessibilityPlatform.kt:83-96`. That lowers the day-to-day impact of `MEM-1` and the screenshot portion of `IO-2`.

I still marked `MEM-1` as real because the allocation chain is unchanged, but avoiding those copies would likely require a larger pipeline change than is justified for a path that is not on by default.

### IO-2 is a correctness tradeoff, not a clean perf win

`capturePostActionAnalysis()` is used by multiple action executors, not just one code path. The retry behavior is explicitly there to catch slow UI transitions after actions. Making it adaptive would need a reliable cheap-change heuristic that works across apps, windowing modes, and delayed intent launches. The downside of getting that wrong is degraded action verification, which is a worse failure mode than the extra captures in the current design.

### RES-1 is narrower than the original wording, but still not fixed

The current implementation does recycle `hardwareBitmap` immediately after `copy()` and recycles `softwareBitmap` / `scaledBitmap` before debug persistence and trace storage in `AccessibilityScreenshotCapturer.kt:139-161`. So the remaining leak window is narrower than a casual reading of the old review suggests. The problem is still real on exceptional paths between allocation and those recycle calls.

### RES-2 is still semantically wrong, but mostly masked by IO-1

Because `AppendLine` still calls `writer.flush()` on every event in `FileTraceRecorder.kt:128-132`, the broken `WriteOp.Flush` handler is currently more of an API-contract bug than an active durability bug. If batching is ever introduced to fix `IO-1`, `RES-2` immediately becomes user-visible and needs to be fixed at the same time.

### RES-3 is the only item with a meaningful partial fix

The old review said all three streaming clients only logged from `awaitClose`. That is no longer true. `CodexResponseClient` now stores the active OkHttp call and cancels it explicitly on flow close in `CodexResponseClient.kt:145-213`. The SDK-backed `OpenAIResponseClient` and `ChatCompletionClient` still do not have an equivalent explicit cancellation handle, so the original concern still applies there.

### Plan-only note: the `BitmapUtils` pre-sizing idea is still not implemented

`improvement_plan.md` included a non-review item about pre-sizing `ByteArrayOutputStream` for JPEG compression. That is still unimplemented: `BitmapUtils.compressJpeg()` still uses a plain `ByteArrayOutputStream()` in `BitmapUtils.kt:36-40`. I did not assign it a status in the table because it was not one of the original review findings.
