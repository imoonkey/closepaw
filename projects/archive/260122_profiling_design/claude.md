# System Performance Profiling Design

> **Goal**: Cut end-to-end execution time by 50% through systematic profiling and targeted optimizations.

## Executive Summary

This document outlines a comprehensive approach to profiling the Android Agent system, identifying performance bottlenecks, and proposing optimizations. Based on code analysis, the current turn cycle includes multiple fixed delays (~3.6s minimum) on top of variable LLM latency (~2-10s), resulting in ~6-14 seconds per turn. A realistic 50% reduction target would bring this to ~3-7 seconds per turn.

---

## Table of Contents

1. [Current System Analysis](#current-system-analysis)
2. [Profiling Framework Design](#profiling-framework-design)
3. [Instrumentation Plan](#instrumentation-plan)
4. [Visual Debugging Tools](#visual-debugging-tools)
5. [Performance Bottleneck Analysis](#performance-bottleneck-analysis)
6. [Optimization Proposals](#optimization-proposals)
7. [Implementation Roadmap](#implementation-roadmap)
8. [Appendix: Timing Constants](#appendix-timing-constants)

---

## Current System Analysis

### Turn Cycle Breakdown

A single ReAct turn consists of these phases:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Single Turn Timeline                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PERCEPTION   │    PLANNING    │   EXECUTION   │  OBSERVATION  │   DELAY    │
│  (50-200ms)   │  (2000-10000ms)│  (100-500ms)  │  (350-500ms)  │  (3000ms)  │
│               │                │               │               │            │
│  captureScreen │   LLM call    │ performAction │ post-capture  │ uiSettle   │
│  + toPromptJson│               │               │ + JSON        │ Delay      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Identified Fixed Delays (from code)

| Location | Delay | Purpose | File |
|----------|-------|---------|------|
| `AgentConfig.uiSettleDelayMs` | 3000ms | Inter-turn UI settle | `AgentConfig.kt:21` |
| `SessionConfig.actionDelayMs` | 2000ms | Config default (overridden) | `Op.kt:106` |
| `BaseToolInvocation.UI_SETTLE_DELAY_MS` | 300ms | Pre-observation settle | `BaseTool.kt:165` |
| `Agent.captureObservationWithSnapshot()` | 500ms | Fallback observation | `Agent.kt:333` |
| `AccessibilityPlatform.performType()` | 100ms | Focus settle | `AccessibilityPlatform.kt:137` |
| `AccessibilityPlatform.DEFAULT_GESTURE_DURATION_MS` | 100ms | Tap gesture | `AccessibilityPlatform.kt:29` |
| `AccessibilityPlatform.SWIPE_GESTURE_DURATION_MS` | 300ms | Swipe gesture | `AccessibilityPlatform.kt:30` |

### Estimated Time Distribution Per Turn

| Phase | Min (ms) | Typical (ms) | Max (ms) | % of Total |
|-------|----------|--------------|----------|------------|
| Screen Capture | 50 | 100 | 200 | 1-2% |
| JSON Serialization | 20 | 50 | 100 | 1% |
| History Building | 10 | 30 | 50 | <1% |
| **LLM API Call** | 2000 | 4000 | 10000 | **50-70%** |
| Tool Execution | 100 | 250 | 500 | 3-5% |
| Post-Action Capture | 350 | 400 | 700 | 5-8% |
| **Inter-Turn Delay** | 3000 | 3000 | 3000 | **25-40%** |
| **TOTAL** | ~5500 | ~7800 | ~14500 | 100% |

---

## Profiling Framework Design

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    Performance Profiling System                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────┐ │
│  │  Instrumentation │───►│   Metric Store   │───►│  Analyzer   │ │
│  │     Layer        │    │   (In-Memory)    │    │  + Report   │ │
│  └─────────────────┘    └─────────────────┘    └─────────────┘ │
│           │                      │                      │       │
│           ▼                      ▼                      ▼       │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────┐ │
│  │   Span Tracer   │    │  JSON Export    │    │  Visualizer │ │
│  │  (per component)│    │  (for analysis) │    │  (Chrome)   │ │
│  └─────────────────┘    └─────────────────┘    └─────────────┘ │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Core Profiling Interface

```kotlin
/**
 * Performance profiler interface for timing instrumentation.
 */
interface PerformanceProfiler {
    /** Start a timing span */
    fun startSpan(name: String, category: SpanCategory): SpanId
    
    /** End a timing span */
    fun endSpan(spanId: SpanId, metadata: Map<String, Any>? = null)
    
    /** Record a metric value */
    fun recordMetric(name: String, value: Long, unit: MetricUnit)
    
    /** Get profiling report */
    fun getReport(): ProfilingReport
    
    /** Export to Chrome Trace format */
    fun exportChromeTrace(): String
}

enum class SpanCategory {
    PERCEPTION,    // Screen capture, accessibility tree
    LLM,           // API calls, response parsing
    TOOL,          // Tool execution, observation
    NETWORK,       // HTTP requests
    SYSTEM,        // Delays, state management
    HISTORY        // History building, truncation
}

enum class MetricUnit {
    MILLISECONDS,
    BYTES,
    COUNT
}
```

### Metric Storage

```kotlin
data class TimingSpan(
    val id: SpanId,
    val name: String,
    val category: SpanCategory,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val metadata: Map<String, Any>,
    val turnNumber: Int?,
    val threadName: String
)

data class ProfilingReport(
    val sessionId: SessionId,
    val totalDurationMs: Long,
    val turnCount: Int,
    val spans: List<TimingSpan>,
    val aggregates: Map<SpanCategory, CategoryAggregate>,
    val criticalPath: List<TimingSpan>,
    val recommendations: List<OptimizationRecommendation>
)

data class CategoryAggregate(
    val category: SpanCategory,
    val totalMs: Long,
    val avgMs: Long,
    val minMs: Long,
    val maxMs: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val count: Int
)
```

---

## Instrumentation Plan

### Phase 1: Core Turn Timing

Instrument the main ReAct loop in `Agent.kt`:

```kotlin
// Agent.kt - executeTurn()
private suspend fun executeTurn(): TurnOutcome {
    val turnSpan = profiler.startSpan("turn_${turnCount}", SpanCategory.SYSTEM)
    
    // 1. PERCEPTION
    val perceptionSpan = profiler.startSpan("perception", SpanCategory.PERCEPTION)
    val snapshot = services.platform.captureScreen()
    profiler.endSpan(perceptionSpan, mapOf("elements" to snapshot.elements.size))
    
    // 2. PLANNING (LLM)
    val llmSpan = profiler.startSpan("llm_call", SpanCategory.LLM)
    val turnResult = turn.run(systemPrompt, userContext, model)
    profiler.endSpan(llmSpan, mapOf(
        "toolCalls" to turnResult.toolCalls.size,
        "contentLength" to (turnResult.content?.length ?: 0)
    ))
    
    // 3. EXECUTION
    for (toolCall in turnResult.toolCalls) {
        val toolSpan = profiler.startSpan("tool_${toolCall.name}", SpanCategory.TOOL)
        val result = services.toolRouter.execute(...)
        profiler.endSpan(toolSpan, mapOf("success" to (result is Success)))
    }
    
    profiler.endSpan(turnSpan, mapOf("outcome" to outcome::class.simpleName))
    return outcome
}
```

### Phase 2: Component-Level Timing

| Component | Spans to Add | Key Metrics |
|-----------|--------------|-------------|
| `Perceptor` | `accessibility_tree_traverse`, `json_serialize` | Element count, node depth |
| `LLMClient` | `http_request`, `response_parse` | Token count, response size |
| `HistoryManager` | `build_items`, `truncate`, `normalize` | Item count, token estimate |
| `ToolRouter` | `validation`, `policy_check`, `execute`, `observation` | Tool name, state transitions |
| `AccessibilityPlatform` | `gesture_dispatch`, `node_search`, `capture` | Gesture type, element count |

### Phase 3: Network Timing

```kotlin
// LLMClient.kt - wrap HTTP client
private fun executeChatWithTools(...): ResponsesResult {
    val networkSpan = profiler.startSpan("openai_api", SpanCategory.NETWORK)
    
    try {
        val response = client.responses().create(builder.build())
        profiler.endSpan(networkSpan, mapOf(
            "status" to "success",
            "responseId" to response.id(),
            "outputItems" to response.output().size
        ))
        return processResponse(response)
    } catch (e: Exception) {
        profiler.endSpan(networkSpan, mapOf("status" to "error", "error" to e.message))
        throw e
    }
}
```

---

## Visual Debugging Tools

### 1. Timeline Visualization (Chrome Trace Format)

Export profiling data to Chrome's `chrome://tracing` format:

```kotlin
fun exportChromeTrace(): String {
    val events = mutableListOf<JSONObject>()
    
    for (span in spans) {
        // Begin event
        events.add(JSONObject().apply {
            put("name", span.name)
            put("cat", span.category.name)
            put("ph", "B")  // Begin
            put("ts", span.startTimeMs * 1000)  // microseconds
            put("pid", 1)
            put("tid", span.threadName.hashCode())
        })
        
        // End event
        events.add(JSONObject().apply {
            put("name", span.name)
            put("cat", span.category.name)
            put("ph", "E")  // End
            put("ts", span.endTimeMs * 1000)
            put("pid", 1)
            put("tid", span.threadName.hashCode())
            put("args", span.metadata)
        })
    }
    
    return JSONArray(events).toString()
}
```

Usage:
```bash
# Export trace after run
adb pull /sdcard/Android/data/com.moonkey.androidagent/files/profile_trace.json .
# Open chrome://tracing and load the file
```

### 2. Enhanced Debug Script

Extend `debug-run.sh` with timing capture:

```bash
#!/bin/bash
# debug-run-profiled.sh

# ... existing setup ...

# Enable profiling mode
adb shell "am start -n $PACKAGE/.app.MainActivity \
    --es api_key '$OPENAI_API_KEY' \
    --es goal '$GOAL' \
    --ez auto_start true \
    --ez profile_mode true" >/dev/null

# Monitor with timing extraction
while [[ $TURN -lt $MAX_TURNS ]]; do
    # ... capture screenshots ...
    
    # Extract timing from logcat
    adb logcat -d | grep "PERF:" | tail -20 > "$DEBUG_DIR/turn_${TURN}_timing.txt"
done

# Generate timing summary
echo "=== TIMING SUMMARY ===" > "$DEBUG_DIR/timing_summary.txt"
grep "PERF:" "$DEBUG_DIR/agent.log" | \
    awk -F'|' '{
        category[$2] += $3;
        count[$2]++;
    }
    END {
        for (c in category) {
            printf "%s: %dms total, %dms avg (%d calls)\n", c, category[c], category[c]/count[c], count[c]
        }
    }' >> "$DEBUG_DIR/timing_summary.txt"
```

### 3. Real-Time Profiling Overlay

Add profiling overlay to OverlayManager:

```kotlin
// OverlayManager.kt
private fun updateProfilingStats(stats: ProfilingStats) {
    if (!showProfilingOverlay) return
    
    val text = buildString {
        appendLine("Turn ${stats.turnNumber}: ${stats.turnDurationMs}ms")
        appendLine("  LLM: ${stats.llmMs}ms (${stats.llmPercent}%)")
        appendLine("  Perception: ${stats.perceptionMs}ms")
        appendLine("  Tools: ${stats.toolMs}ms")
        appendLine("  Delays: ${stats.delayMs}ms")
    }
    
    profilingTextView.text = text
}
```

### 4. Flame Graph Generator

For deeper analysis, generate flame graphs from profiling data:

```kotlin
fun generateFlameGraph(spans: List<TimingSpan>): String {
    val stacks = mutableListOf<String>()
    
    // Build call stacks from nested spans
    for (span in spans) {
        val stack = buildStack(span)
        val entry = "${stack.joinToString(";")} ${span.durationMs}"
        stacks.add(entry)
    }
    
    return stacks.joinToString("\n")
}

// Output to flamegraph.pl compatible format
// Then: flamegraph.pl profile.txt > profile.svg
```

---

## Performance Bottleneck Analysis

### Critical Path Analysis

Based on code analysis, the critical path for a typical turn:

```
[LLM API Call]──────────────────────────────────────────────▶ 70% of time
       │
       ├─[Network Latency]────────────────────────▶ ~2-8 seconds
       │
       └─[Response Parsing]───────────────────────▶ ~50-100ms

[Fixed Delays]──────────────────────────────────────────────▶ 25% of time
       │
       ├─[Inter-Turn Delay]───────────────────────▶ 3000ms (configurable)
       │
       └─[Post-Action Settle]─────────────────────▶ 300ms

[Screen Operations]─────────────────────────────────────────▶ 5% of time
       │
       ├─[Accessibility Tree Traversal]───────────▶ 50-150ms
       │
       ├─[JSON Serialization]─────────────────────▶ 20-50ms
       │
       └─[Gesture Execution]──────────────────────▶ 100-300ms
```

### Bottleneck Rankings

| Rank | Bottleneck | Impact | Effort to Fix | ROI |
|------|------------|--------|---------------|-----|
| 1 | Inter-turn delay (3000ms) | Very High | Low | **Excellent** |
| 2 | LLM network latency | Very High | Medium | Good |
| 3 | Post-action observation delay (300ms) | Medium | Low | **Excellent** |
| 4 | Fallback observation delay (500ms) | Medium | Low | Good |
| 5 | History context size | Medium | Medium | Good |
| 6 | Screen capture (accessibility) | Low | High | Poor |
| 7 | JSON serialization | Very Low | Medium | Poor |

---

## Optimization Proposals

### O1: Adaptive UI Settle Delays (High Impact, Low Effort)

**Current State:**
- Fixed 3000ms inter-turn delay
- Fixed 300ms post-action observation delay

**Proposed Solution:**

```kotlin
class AdaptiveDelayManager {
    companion object {
        private const val MIN_SETTLE_MS = 200L
        private const val MAX_SETTLE_MS = 3000L
        private const val SCREEN_CHANGE_THRESHOLD = 0.1f  // 10% change
    }
    
    private var lastSnapshot: ScreenSnapshot? = null
    private var consecutiveStableFrames = 0
    
    /**
     * Calculate adaptive delay based on screen stability.
     * 
     * Algorithm:
     * 1. Poll screen at MIN_SETTLE_MS intervals
     * 2. Compare element fingerprints
     * 3. If stable for 2 consecutive polls, proceed
     * 4. Cap at MAX_SETTLE_MS total wait
     */
    suspend fun waitForStableScreen(
        platform: AndroidPlatform,
        timeout: Long = MAX_SETTLE_MS
    ): ScreenSnapshot {
        val startTime = System.currentTimeMillis()
        var currentSnapshot: ScreenSnapshot? = null
        
        while (System.currentTimeMillis() - startTime < timeout) {
            currentSnapshot = platform.captureScreen()
            
            if (isScreenStable(currentSnapshot)) {
                consecutiveStableFrames++
                if (consecutiveStableFrames >= 2) {
                    break
                }
            } else {
                consecutiveStableFrames = 0
            }
            
            lastSnapshot = currentSnapshot
            delay(MIN_SETTLE_MS)
        }
        
        return currentSnapshot ?: platform.captureScreen()
    }
    
    private fun isScreenStable(current: ScreenSnapshot): Boolean {
        val previous = lastSnapshot ?: return false
        
        // Quick fingerprint comparison
        if (current.elements.size != previous.elements.size) return false
        
        // Compare clickable element positions (most important for actions)
        val currentClickable = current.elements.filter { it.isClickable }
            .map { "${it.center.x},${it.center.y}" }.toSet()
        val previousClickable = previous.elements.filter { it.isClickable }
            .map { "${it.center.x},${it.center.y}" }.toSet()
        
        return currentClickable == previousClickable
    }
}
```

**Expected Improvement:**
- Reduce average delay from 3300ms to ~400-800ms
- **Savings: ~2500ms per turn (35% reduction)**

---

### O2: Parallel Observation Capture (Medium Impact, Low Effort)

**Current State:**
- Tool executes → 300ms delay → capture observation → proceed

**Proposed Solution:**
Start observation capture immediately after gesture dispatched:

```kotlin
class BaseToolInvocation(...) {
    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        // Start action and observation in parallel
        return coroutineScope {
            // Launch gesture
            val actionDeferred = async {
                context.platform.performAction(uiAction, context.currentSnapshot)
            }
            
            // Wait for action to complete
            val actionResult = actionDeferred.await()
            
            if (actionResult is ActionResult.Success) {
                // Start observation with minimal delay (gesture already done)
                delay(150)  // Reduced from 300ms
                val observation = capturePostActionObservation(context)
                ToolExecutionResult.Success(actionResult.message, observation)
            } else {
                // Handle failures
                ...
            }
        }
    }
}
```

**Expected Improvement:**
- Reduce post-action delay from 300ms to ~150ms
- **Savings: ~150ms per tool call**

---

### O3: LLM Context Optimization (High Impact, Medium Effort)

**Current State:**
- Full screen JSON sent every turn (~80 elements × ~200 chars = ~16KB)
- History grows unbounded until truncation

**Proposed Solutions:**

#### O3.1: Delta-Based Screen Updates

```kotlin
class DeltaPerceptor {
    private var previousSnapshot: ScreenSnapshot? = null
    
    /**
     * Generate delta JSON showing only changes from previous screen.
     * Include full context for first turn or after significant changes.
     */
    fun toDeltaPromptJson(snapshot: ScreenSnapshot, forceFullContext: Boolean = false): String {
        val previous = previousSnapshot
        previousSnapshot = snapshot
        
        if (previous == null || forceFullContext) {
            return Perceptor.toPromptJson(snapshot)
        }
        
        val added = snapshot.elements.filter { elem ->
            previous.elements.none { it.matches(elem) }
        }
        val removed = previous.elements.filter { elem ->
            snapshot.elements.none { it.matches(elem) }
        }
        val unchanged = snapshot.elements.size - added.size
        
        // If >50% changed, send full context
        if (added.size + removed.size > snapshot.elements.size * 0.5) {
            return Perceptor.toPromptJson(snapshot)
        }
        
        return JSONObject().apply {
            put("type", "delta")
            put("unchanged_count", unchanged)
            put("added", JSONArray(added.map { it.toJson() }))
            put("removed", JSONArray(removed.map { it.toJson() }))
            put("total_elements", snapshot.elements.size)
        }.toString(2)
    }
}
```

#### O3.2: Selective History Truncation

```kotlin
class SmartHistoryManager : HistoryManager() {
    /**
     * Aggressive truncation for tool outputs that aren't recent.
     * Keep full detail only for last N turns.
     */
    override fun forPrompt(): List<ResponseItem> {
        val items = super.forPrompt()
        val turnCount = items.count { it is ResponseItem.Message && it.role == "user" }
        
        return items.mapIndexed { index, item ->
            when {
                item is ResponseItem.FunctionCallOutput && index < items.size - 10 -> {
                    // Heavily truncate old tool outputs
                    item.copy(content = truncateToSummary(item.content))
                }
                else -> item
            }
        }
    }
    
    private fun truncateToSummary(content: String): String {
        // Keep first line (result) and element count
        val firstLine = content.lineSequence().firstOrNull() ?: content.take(100)
        val elementMatch = Regex(""""total_elements":\s*(\d+)""").find(content)
        val elements = elementMatch?.groupValues?.get(1) ?: "?"
        return "$firstLine\n[Screen: $elements elements - details truncated]"
    }
}
```

**Expected Improvement:**
- Reduce average prompt size by 30-50%
- Faster LLM processing (fewer input tokens)
- **Savings: ~500-1000ms per turn**

---

### O4: Streaming LLM Responses (Medium Impact, Medium Effort)

**Current State:**
- Wait for complete LLM response before processing

**Proposed Solution:**
Use streaming to start processing tool calls as they arrive:

```kotlin
class StreamingTurn(...) {
    suspend fun runStreaming(
        systemPrompt: String,
        userContext: String,
        onPartialResult: (PartialTurnResult) -> Unit
    ): TurnResult {
        val stream = llmClient.chatWithToolsStreaming(...)
        
        stream.collect { chunk ->
            when (chunk) {
                is StreamChunk.Text -> {
                    // Update UI with thinking progress
                    onPartialResult(PartialTurnResult.ThinkingUpdate(chunk.text))
                }
                is StreamChunk.ToolCall -> {
                    // Tool call complete - can start validation early
                    onPartialResult(PartialTurnResult.ToolCallReady(chunk.toolCall))
                }
            }
        }
        
        return stream.finalResult()
    }
}
```

**Expected Improvement:**
- Better perceived responsiveness
- Earlier tool validation start
- **Savings: ~200-500ms perceived latency**

---

### O5: Action Batching for Multi-Tool Turns (Medium Impact, Low Effort)

**Current State:**
- Each tool call: execute → capture observation → next tool
- Redundant observations between tools in same turn

**Proposed Solution:**

```kotlin
class BatchedToolExecutor {
    /**
     * Execute multiple tools with single final observation.
     * 
     * Preconditions:
     * - Tools don't depend on each other's outcomes
     * - Intermediate screen changes don't affect subsequent tools
     */
    suspend fun executeBatch(
        toolCalls: List<ToolCallRequest>,
        context: ToolExecutionContext
    ): List<ToolCallResult> {
        val results = mutableListOf<ToolCallResult>()
        
        // Execute all tools without intermediate observations
        for ((index, toolCall) in toolCalls.withIndex()) {
            val isLast = index == toolCalls.lastIndex
            
            val result = toolRouter.execute(
                toolCall = toolCall,
                context = context,
                skipObservation = !isLast  // Only capture on last tool
            )
            results.add(result)
            
            // Minimal delay between tools (not full settle)
            if (!isLast) delay(100)
        }
        
        return results
    }
}
```

**Expected Improvement:**
- Reduce per-tool overhead from ~400ms to ~100ms for batched tools
- **Savings: ~300ms × (N-1) for N tools in a turn**

---

### O6: Accessibility Tree Caching (Low Impact, High Effort)

**Note:** This is a lower-priority optimization due to complexity.

**Current State:**
- Full tree traversal on every `captureScreen()` call

**Proposed Solution:**
Cache and incrementally update accessibility tree:

```kotlin
class CachingPerceptor {
    private var cachedTree: List<PerceptionElement>? = null
    private var cacheTimestamp: Long = 0
    private const val CACHE_VALIDITY_MS = 100L
    
    fun snapshotWithCache(root: AccessibilityNodeInfo?): ScreenSnapshot {
        val now = System.currentTimeMillis()
        
        // Check if cache is still valid
        if (cachedTree != null && (now - cacheTimestamp) < CACHE_VALIDITY_MS) {
            return ScreenSnapshot(now, cachedTree!!)
        }
        
        // Full traversal
        val elements = traverse(root)
        cachedTree = elements
        cacheTimestamp = now
        
        return ScreenSnapshot(now, elements)
    }
}
```

**Expected Improvement:**
- Reduce redundant traversals in rapid-fire calls
- **Savings: ~20-50ms in specific scenarios**

---

## Implementation Roadmap

### Phase 1: Instrumentation (1-2 days)

1. Create `PerformanceProfiler` interface and implementation
2. Add timing spans to `Agent.executeTurn()`
3. Add timing to `LLMClient.chatWithTools()`
4. Implement Chrome Trace export
5. Create `debug-run-profiled.sh` script

**Deliverables:**
- Profiling framework in `com.moonkey.androidagent.profiling/`
- Enhanced debug script
- Baseline measurements

### Phase 2: Quick Wins (2-3 days)

1. **O1: Adaptive UI Settle Delays**
   - Implement `AdaptiveDelayManager`
   - Replace fixed delays in `AgentConfig` and `BaseTool`
   - Add fallback to fixed delay on timeout

2. **O2: Parallel Observation Capture**
   - Reduce `UI_SETTLE_DELAY_MS` from 300ms to 150ms
   - Verify gesture completion before observation

**Deliverables:**
- ~35-40% e2e time reduction
- Before/after profiling comparison

### Phase 3: LLM Optimizations (3-5 days)

1. **O3.1: Delta-Based Screen Updates**
   - Implement `DeltaPerceptor`
   - Update `Turn.buildUserContext()` to use deltas
   - Add system prompt guidance for delta interpretation

2. **O3.2: Smart History Truncation**
   - Implement `SmartHistoryManager`
   - Tune truncation thresholds based on profiling

3. **O4: Streaming Responses** (optional)
   - Evaluate OpenAI streaming API compatibility
   - Implement `StreamingTurn` if beneficial

**Deliverables:**
- ~10-20% additional reduction
- Smaller context = faster LLM responses

### Phase 4: Advanced Optimizations (5+ days)

1. **O5: Action Batching**
   - Analyze multi-tool turn patterns
   - Implement `BatchedToolExecutor`
   - Add tool dependency detection

2. **O6: Accessibility Caching** (if needed)
   - Implement `CachingPerceptor`
   - Profile cache hit rates

**Deliverables:**
- Final ~5-10% optimization
- Comprehensive profiling report

---

## Success Metrics

| Metric | Current | Target | Measurement Method |
|--------|---------|--------|-------------------|
| Avg Turn Duration | ~7.8s | ~3.9s | Profiler aggregate |
| Inter-Turn Delay | 3000ms | ~500ms | Profiler span |
| Post-Action Delay | 300ms | 150ms | Profiler span |
| LLM Context Size | ~20KB | ~12KB | Token estimate |
| E2E Task Time (Open Settings) | ~15s | ~8s | End-to-end timing |

---

## Appendix: Timing Constants

### Current Values (from code)

```kotlin
// AgentConfig.kt
val uiSettleDelayMs: Long = 3000  // Inter-turn delay

// Op.kt (SessionConfig)
val actionDelayMs: Long = 2000  // Config default

// BaseTool.kt
private const val UI_SETTLE_DELAY_MS = 300L  // Post-action observation

// Agent.kt
delay(500)  // Fallback observation capture

// AccessibilityPlatform.kt
private const val DEFAULT_GESTURE_DURATION_MS = 100L
private const val SWIPE_GESTURE_DURATION_MS = 300L
private const val GESTURE_TIMEOUT_MS = 5000L

// LLMClient.kt
private const val INITIAL_BACKOFF_MS = 1000L
private const val MAX_BACKOFF_MS = 60000L
```

### Proposed Optimized Values

```kotlin
// AgentConfig.kt
val uiSettleDelayMs: Long = 500  // Adaptive fallback max

// BaseTool.kt
private const val UI_SETTLE_DELAY_MS = 150L  // Reduced

// Agent.kt
delay(300)  // Reduced fallback
```

---

## References

- [Agent Infrastructure](../main/agent_infra.md) - System architecture
- [Agent Protocol](../main/agent_protocol.md) - Event/Op definitions
- [Development Guide](../dev/development.md) - Debug workflow
- [Visual Debugging Guide](../../scripts/agent_process_visual_debug.md) - Existing debug tools
