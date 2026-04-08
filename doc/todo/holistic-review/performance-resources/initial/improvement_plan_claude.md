# Performance Improvement Plan

Based on the full-codebase review. Ordered by impact-to-effort ratio.

---

## Priority 1: High Impact, Low Effort

### P1.1 Enable R8 minification for release builds

**Review ref:** A12
**File:** `app/build.gradle.kts`
**Effort:** ~1 hour (configure + test)

```kotlin
// Current
release {
    isMinifyEnabled = false
}

// Proposed
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

Will need a `proguard-rules.pro` with keep rules for:
- kotlinx.serialization classes (session persistence)
- Shizuku AIDL interfaces
- OpenAI SDK reflection (if any)
- Leap SDK JNI

Expected savings: 20-40% APK size reduction, faster cold start, lower memory footprint.

### P1.2 Fix O(n^2) token recalculation in compression

**Review ref:** A6
**File:** `history/HistoryManager.kt`
**Effort:** ~30 minutes

Replace repeated full-sum recalculation with delta tracking during Phase 2 eviction.

```kotlin
// In compress(), before Phase 2 loop:
var runningTokens = estimateTokenCount()

// Each time an item is removed:
runningTokens -= removedItem.estimateTokens()
// Skip setting lastTokenEstimate = null; use runningTokens directly

// After Phase 2:
lastTokenEstimate = null  // invalidate cache for next external call
```

This changes O(n^2) eviction to O(n).

### P1.3 Fix O(n^2) indexOf in applyTruncation

**Review ref:** A2
**File:** `perception/PerceptorInternals.kt`
**Effort:** ~30 minutes

Replace `candidates.indexOf(c)` with an identity-based index map built once.

```kotlin
internal fun applyTruncation(
    candidates: List<PerceptorCandidateElement>,
    maxElements: Int,
    interactiveKeepRatio: Float
): List<PerceptorCandidateElement> {
    if (candidates.size <= maxElements) return candidates
    val interactiveCap = (maxElements * interactiveKeepRatio).toInt().coerceIn(1, maxElements)
    val nonInteractiveFloor = maxElements - interactiveCap
    val interactive = candidates.filter { it.isInteractive }.sortedByDescending { score(it) }
    val nonInteractive = candidates.filter { !it.isInteractive }.sortedByDescending { score(it) }

    val kept = ArrayList<PerceptorCandidateElement>(maxElements)
    val seen = HashSet<PerceptorCandidateElement>(maxElements)

    for (c in interactive.take(interactiveCap)) {
        if (seen.add(c)) kept.add(c)
    }
    for (c in nonInteractive.take(nonInteractiveFloor)) {
        if (seen.add(c)) kept.add(c)
    }
    if (kept.size < maxElements) {
        for (c in (interactive + nonInteractive)) {
            if (kept.size >= maxElements) break
            if (seen.add(c)) kept.add(c)
        }
    }
    return kept
}
```

Uses `HashSet` on the element objects directly (data class equals/hashCode) instead of position-based dedup via `indexOf`.

---

## Priority 2: Medium Impact, Medium Effort

### P2.1 Single-pass tree traversal in Perceptor

**Review ref:** A1
**File:** `perception/Perceptor.kt`
**Effort:** ~2 hours

Merge the two traversal modes into a single pass. Tag each collected element as interactive or not, then apply the truncation logic afterward (which already handles interactive/non-interactive separation).

The current two-pass design was likely chosen to ensure interactive elements get priority when the `maxElements * 2` cap is hit. This can be preserved by collecting interactive elements first in the single pass (they pass the `shouldKeep` check with lower requirements), then accepting non-interactive elements from the same traversal.

Approach: single `traverse()` call with both modes' criteria combined. The `seenKeys` already prevents duplicates. The only change is removing the mode-based `shouldKeep` check and instead always using the `ALL` mode criteria, then adjusting `applyTruncation` to handle priority (which it already does via `interactiveKeepRatio`).

### P2.2 Pre-size ByteArrayOutputStream for JPEG compression

**Review ref:** A5
**File:** `platform/BitmapUtils.kt`
**Effort:** ~15 minutes

```kotlin
fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
    val safeQuality = quality.coerceIn(1, 100)
    // Estimate: JPEG at quality 70 is roughly 1/10 of raw pixel data
    val estimatedSize = (bitmap.width * bitmap.height * 4) / 10
    val output = ByteArrayOutputStream(estimatedSize.coerceIn(1024, 512_000))
    val success = bitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, output)
    return if (success) output.toByteArray() else null
}
```

Eliminates 10-12 buffer doublings per compression.

### P2.3 Batch flush in FileTraceRecorder

**Review ref:** B5
**File:** `trace/FileTraceRecorder.kt`
**Effort:** ~1 hour

Replace per-line flush with periodic or count-based flushing:

```kotlin
private suspend fun runWriterLoop() {
    var writer: BufferedWriter? = null
    var linesSinceFlush = 0
    try {
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(traceFile, true), Charsets.UTF_8))
        for (op in channel) {
            when (op) {
                is WriteOp.AppendLine -> {
                    writer.append(op.line)
                    writer.newLine()
                    linesSinceFlush++
                    if (linesSinceFlush >= 10 || channel.isEmpty) {
                        writer.flush()
                        linesSinceFlush = 0
                    }
                }
                is WriteOp.WriteBytes -> writeBytes(op.relativePath, op.bytes)
                is WriteOp.WriteUtf8 -> writeText(op.relativePath, op.content)
                is WriteOp.Flush -> {
                    writer.flush()
                    linesSinceFlush = 0
                    op.done.complete(Unit)
                }
            }
        }
    } finally {
        writer?.flush()
        writer?.close()
    }
}
```

### P2.4 Skip sort in UiChangeDetector fingerprint

**Review ref:** A10
**File:** `tool/action/UiChangeDetector.kt`
**Effort:** ~10 minutes

Elements from Perceptor are already indexed 0..n-1 in order. The `sortedBy { it.index }` allocates a new list needlessly.

```kotlin
// Current
for (element in elements.sortedBy { it.index }) {

// Proposed -- elements are already ordered by index from Perceptor.snapshot()
for (element in elements) {
```

---

## Priority 3: Low Impact / By-Design

### P3.1 MemoryStore: Move file I/O off synchronized block (if needed)

**Review ref:** B7
**Effort:** LOW if refactored to use `Mutex` + `withContext(Dispatchers.IO)`

Currently `@Synchronized` + blocking file I/O. Only a concern if `recall()` is ever called from the main thread. Verify the call site is always on IO dispatcher. If so, this is fine as-is.

### P3.2 Post-action retry captures (A9)

**Review ref:** A9
**Effort:** N/A

The 3-capture retry pattern (300ms + 500ms + 1000ms) is intentional for detecting slow UI transitions. Reducing it would hurt action verification accuracy. The only optimization would be skipping the retry captures for actions known to be fast (e.g., type actions), but this adds complexity for marginal gain.

---

## Not Recommended (Premature Optimization)

- **Pooling Rect/Point objects in Perceptor:** The objects are short-lived and small. JVM's escape analysis likely handles these efficiently. Object pooling would add complexity without measurable benefit.
- **Replacing JSONObject with manual string building in toPromptJson:** JSONObject's overhead is dominated by the string operations, which manual building wouldn't significantly improve. The code is clear as-is.
- **Caching AccessibilityNodeInfo fields:** The a11y framework's field access is already cheap (Binder-cached values). Caching would add complexity and stale-data risk.

---

## Implementation Order

1. **P1.1** -- R8 minification (highest ROI, affects every user)
2. **P1.2** -- Fix compression O(n^2) (simple fix, measurable improvement for long sessions)
3. **P1.3** -- Fix truncation O(n^2) (simple fix, helps dense screens)
4. **P2.2** -- Pre-size BAOS (trivial change)
5. **P2.4** -- Skip sort in fingerprint (trivial change)
6. **P2.3** -- Batch trace flushes (moderate effort, helps I/O during active sessions)
7. **P2.1** -- Single-pass traversal (largest code change, measurable CPU reduction)
