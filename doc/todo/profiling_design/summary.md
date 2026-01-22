# System Performance Profiling Design Summary

> **Goal**: Cut end-to-end (E2E) execution time by 50% through systematic profiling and targeted optimizations.

This document summarizes three independent design proposals for system performance profiling: **Claude**, **Gemini**, and **Codex**.

---

## Executive Comparison

| Aspect | Claude | Gemini | Codex |
|--------|--------|--------|-------|
| **Depth** | Comprehensive (879 lines) | Concise (84 lines) | Balanced (191 lines) |
| **Focus** | Full framework + detailed code | Quick wins + visualization | Event-driven + experiments |
| **Instrumentation** | Custom `PerformanceProfiler` interface | Simple log-based timing | Existing events + Perfetto traces |
| **Visualization** | Chrome Trace, flame graphs, overlay | Gantt charts (matplotlib/plotly) | JSON Lines + Perfetto |
| **Optimization count** | 6 proposals (O1-O6) | 4 proposals | 5 hypotheses |

---

## Consensus: Turn Cycle Breakdown

All three designs agree on the fundamental turn structure:

```
PERCEPTION → PLANNING (LLM) → EXECUTION → OBSERVATION → SETTLE DELAY
```

### Agreed Time Distribution

| Phase | Estimated % | Primary Bottleneck? |
|-------|-------------|---------------------|
| **LLM API Call** | 50-70% | **Yes - Dominant** |
| **Fixed Delays** | 25-40% | **Yes - Low-hanging fruit** |
| Screen Capture + JSON | 3-5% | No |
| Tool Execution | 3-5% | No |
| Observation Capture | 5-8% | Minor |

### Identified Fixed Delays (from code)

All designs cite the same constants:

| Delay | Value | Location |
|-------|-------|----------|
| Inter-turn settle | 3000ms (via `uiSettleDelayMs`) | `AgentConfig.kt` |
| Post-action settle | 300ms | `BaseTool.kt` |
| Fallback observation | 500ms | `Agent.kt` |
| Tap gesture duration | 100ms | `AccessibilityPlatform.kt` |
| Swipe gesture duration | 300ms | `AccessibilityPlatform.kt` |

---

## Consensus: High-Impact Optimizations

### 1. Adaptive UI Settle Delays ⭐ (All three agree)

**Problem**: Fixed 3000ms delay after each turn is wasteful when UI stabilizes faster.

**Proposed Solution**: Poll screen state at short intervals (~100-200ms); proceed once stable for 2-3 consecutive polls.

| Design | Proposed Min Wait | Stability Detection |
|--------|-------------------|---------------------|
| Claude | 200ms | Compare clickable element positions |
| Gemini | 100ms polling | Accessibility tree hash comparison |
| Codex | Configurable per tool | Screen change stabilization |

**Expected Impact**: ~35% reduction (Claude), ~1500ms savings (Gemini)

---

### 2. Reduce LLM Latency / Context Size ⭐ (All three agree)

**Problem**: LLM call is the dominant time consumer (50-70%).

**Proposed Solutions**:

| Approach | Claude | Gemini | Codex |
|----------|--------|--------|-------|
| Delta-based screen updates | ✅ Detailed implementation | ❌ | ❌ |
| Compact JSON (no pretty-print) | ❌ | ✅ | ✅ |
| Use smaller model (gpt-4o-mini) | ❌ | ✅ | ✅ |
| Smart history truncation | ✅ Detailed | ❌ | ❌ |
| Reduce element count | ❌ | ❌ | ✅ (dynamic budget) |
| Multi-step planning | ❌ | ✅ ("plan next 3 actions") | ✅ (reduce turn count) |

**Expected Impact**: 10-30% reduction per turn

---

### 3. Reduce Redundant Captures (Codex unique insight)

**Problem**: Multiple screen captures per turn (initial, post-action, fallback) are redundant.

**Proposed Solution**: Reuse recent `ToolObservation.ScreenState` when available; skip initial capture if previous observation is fresh.

**Expected Impact**: Medium-high depending on tool frequency

---

## Instrumentation Approaches

### Claude: Custom Profiling Framework

```kotlin
interface PerformanceProfiler {
    fun startSpan(name: String, category: SpanCategory): SpanId
    fun endSpan(spanId: SpanId, metadata: Map<String, Any>? = null)
    fun recordMetric(name: String, value: Long, unit: MetricUnit)
    fun exportChromeTrace(): String
}
```

- **Pros**: Rich metadata, Chrome DevTools integration, flame graphs
- **Cons**: More implementation effort, new abstraction to maintain

### Gemini: Simple Structured Logs

```
PERF: [SessionID] [TurnID] [Component] [DurationMs]
```

- **Pros**: Minimal implementation, easy parsing
- **Cons**: Less context, no nesting/hierarchy

### Codex: Leverage Existing Events + Android Tracing

```json
{"ts":1700000000000,"type":"TURN_START","sessionId":"...","turn":1}
{"ts":1700000001500,"type":"LLM_END","turn":1,"ms":1250,"model":"gpt-4o"}
```

- Uses existing `AgentEvent` infrastructure
- Adds `android.os.Trace` sections for Perfetto integration
- **Pros**: Minimal new code, native Android tooling
- **Cons**: Requires Android Studio/Perfetto for full analysis

---

## Visualization Tools

| Tool | Claude | Gemini | Codex |
|------|--------|--------|-------|
| Chrome Trace (`chrome://tracing`) | ✅ Primary | ❌ | ❌ |
| Gantt chart (Python) | ❌ | ✅ | ❌ |
| Perfetto system trace | ❌ | ❌ | ✅ |
| Flame graphs | ✅ | ❌ | ❌ |
| Real-time overlay on device | ✅ | ❌ | ❌ |
| Screenshot correlation | ❌ | ✅ | ✅ |

---

## Unique Contributions by Design

### Claude
- **Parallel Observation Capture**: Start observation immediately after gesture dispatch (150ms savings)
- **Action Batching**: Execute multiple tools with single final observation
- **Streaming LLM Responses**: Process tool calls as they arrive
- **Accessibility Tree Caching**: Reduce redundant traversals

### Gemini
- **Model Distillation**: Use gpt-4o-mini for simple navigation, gpt-4o for complex reasoning
- **Parallel Pre-fetching**: Pre-compute next prompt while tool executes
- **Speculative Multi-step Planning**: Prompt for next 3 actions at once

### Codex
- **Event-Driven Profiling**: Reuse existing `AgentEvent` system
- **Approval Wait Analysis**: Measure impact of 60s approval timeouts
- **Per-Tool Delay Configuration**: Different settle times for tap vs scroll
- **A/B Testing Framework**: Track success rate alongside latency
- **Risks and Safeguards Section**: Cautions about reliability trade-offs

---

## Recommended Synthesis

### Phase 1: Baseline Profiling (Prerequisite)

1. **Implement lightweight logging** (Gemini's format + Codex's JSON Lines)
   - Simple `PERF:` prefixed logs for quick analysis
   - JSON Lines file for structured data
2. **Add Android Trace sections** (Codex) for Perfetto correlation
3. **Run baseline experiments** on standard tasks:
   - "Open Settings"
   - "Open Chrome"  
   - "Toggle Wi-Fi"
4. **Generate time breakdown** to validate assumptions

### Phase 2: Quick Wins (Highest ROI)

| Priority | Optimization | Expected Savings | Effort |
|----------|--------------|------------------|--------|
| **P0** | Adaptive UI settle delays | ~2500ms/turn | Low |
| **P0** | Reduce post-action delay (300→150ms) | ~150ms/tool | Low |
| **P1** | Compact JSON (no pretty-print) | ~5-10% LLM time | Low |
| **P1** | Reuse recent screen captures | ~100ms/turn | Medium |

### Phase 3: LLM Optimizations (Medium Effort)

| Priority | Optimization | Expected Savings | Effort |
|----------|--------------|------------------|--------|
| **P1** | Smart history truncation | ~500-1000ms/turn | Medium |
| **P2** | Delta-based screen updates | ~10-20% prompt size | Medium |
| **P2** | Model switching (mini/full) | Variable | Medium |
| **P3** | Streaming responses | Perceived latency | Medium |

### Phase 4: Advanced (If Needed)

- Action batching for multi-tool turns
- Multi-step planning prompts
- Accessibility tree caching

---

## Success Criteria

| Metric | Current (Est.) | Target | Measurement |
|--------|----------------|--------|-------------|
| Avg Turn Duration | ~7-8s | ~3.5-4s | Profiler |
| Inter-Turn Delay | 3000ms | ~500ms | Profiler |
| Post-Action Delay | 300ms | 150ms | Profiler |
| LLM Context Size | ~20KB | ~12KB | Token estimate |
| E2E "Open Settings" | ~15s | ~8s | End-to-end |

---

## References

- [Claude Design](./claude.md) - Comprehensive framework
- [Gemini Design](./gemini.md) - Concise approach
- [Codex Design](./codex.md) - Event-driven design
- [Agent Infrastructure](../main/agent_infra.md) - System architecture
- [Development Guide](../dev/development.md) - Debug workflow
