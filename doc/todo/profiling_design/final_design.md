# System Performance Profiling: Final Design

> **Goal**: Measure where time actually goes in the agent execution cycle, without assumptions.

This document focuses solely on **profiling methodology**. Optimization decisions will be made after data is collected.

---

## 1. What We Know vs. What We Don't

### Known (from code)

| Delay | Value | Location |
|-------|-------|----------|
| Inter-turn settle | `config.uiSettleDelayMs` | `Agent.kt:95` |
| Post-action settle | 300ms | `BaseTool.kt:165` |
| Fallback observation | 500ms | `Agent.kt:333` |
| Tap gesture duration | 100ms | `AccessibilityPlatform.kt` |
| Swipe gesture duration | 300ms | `AccessibilityPlatform.kt` |
| Approval timeout | 60s | `ToolRouter.kt:38` |

### Unknown (must measure)

- Actual LLM API latency distribution
- Screen capture time (accessibility tree traversal)
- JSON serialization time
- History building time
- Tool execution time (excluding fixed delays)
- How these vary across different tasks and screen complexity

---

## 2. Metrics Definition

### Primary Metrics (per turn)

| Metric | Description | Unit |
|--------|-------------|------|
| `t_turn` | Total turn duration | ms |
| `t_capture` | `platform.captureScreen()` duration | ms |
| `t_json` | `Perceptor.toPromptJson()` duration | ms |
| `t_history` | `buildInputItems()` duration | ms |
| `t_llm_total` | `llmClient.chatWithTools()` duration | ms |
| `t_llm_network` | HTTP request/response time only | ms |
| `t_tool_exec` | `toolRouter.execute()` duration per tool | ms |
| `t_action` | `platform.performAction()` duration | ms |
| `t_observe` | Post-action observation capture | ms |
| `t_settle` | All explicit `delay()` calls | ms |

### Secondary Metrics

| Metric | Description | Unit |
|--------|-------------|------|
| `n_elements` | Element count in snapshot | count |
| `n_tokens_est` | Estimated prompt token count | count |
| `n_tools` | Tool calls per turn | count |
| `prompt_size` | User context string length | bytes |
| `history_items` | Items in history | count |

### Derived Metrics

```
t_turn = t_capture + t_json + t_history + t_llm_total + sum(t_tool_exec) + t_settle
t_llm_overhead = t_llm_total - t_llm_network  # SDK/parsing overhead
```

---

## 3. Instrumentation Plan

### 3.1 Logging Format

Use structured logs with a stable prefix for easy parsing:

```
PERF|<session_id>|<turn_number>|<span_name>|<duration_ms>|<metadata_json>
```

Examples:
```
PERF|abc123|1|turn|7234|{}
PERF|abc123|1|capture|85|{"elements":42}
PERF|abc123|1|json|23|{"bytes":4521}
PERF|abc123|1|llm_total|4102|{"model":"gpt-4o"}
PERF|abc123|1|tool_click|412|{"index":5}
```

### 3.2 Instrumentation Points

#### Agent.kt - `executeTurn()`

```kotlin
private suspend fun executeTurn(): TurnOutcome {
    val turnStart = System.currentTimeMillis()
    turnCount++
    val turnId = "turn-$turnCount"
    
    // ... existing code ...
    
    // 1. PERCEPTION
    val captureStart = System.currentTimeMillis()
    val snapshot = services.platform.captureScreen()
    val captureMs = System.currentTimeMillis() - captureStart
    logPerf("capture", captureMs, mapOf("elements" to snapshot.elements.size))
    
    // 2. BUILD CONTEXT
    val jsonStart = System.currentTimeMillis()
    val screenJson = Perceptor.toPromptJson(snapshot)
    val jsonMs = System.currentTimeMillis() - jsonStart
    logPerf("json", jsonMs, mapOf("bytes" to screenJson.length))
    
    // 3. LLM CALL (instrumentation inside Turn.kt)
    val llmStart = System.currentTimeMillis()
    val turnResult = turn.run(systemPrompt, userContext, services.config.model)
    val llmMs = System.currentTimeMillis() - llmStart
    logPerf("llm_total", llmMs, mapOf("tools" to turnResult.toolCalls.size))
    
    // 4. TOOL EXECUTION (per tool)
    for (toolCall in turnResult.toolCalls) {
        val toolStart = System.currentTimeMillis()
        val result = services.toolRouter.execute(...)
        val toolMs = System.currentTimeMillis() - toolStart
        logPerf("tool_${toolCall.name}", toolMs, mapOf("success" to (result is Success)))
    }
    
    // 5. INTER-TURN DELAY
    val settleStart = System.currentTimeMillis()
    delay(config.uiSettleDelayMs)
    val settleMs = System.currentTimeMillis() - settleStart
    logPerf("settle_inter_turn", settleMs, mapOf("configured" to config.uiSettleDelayMs))
    
    // TURN TOTAL
    val turnMs = System.currentTimeMillis() - turnStart
    logPerf("turn", turnMs, mapOf("outcome" to outcome::class.simpleName))
}
```

#### Turn.kt - `run()`

```kotlin
suspend fun run(...): TurnResult {
    // History building
    val historyStart = System.currentTimeMillis()
    val inputItems = buildInputItems(userContext)
    val historyMs = System.currentTimeMillis() - historyStart
    logPerf("history_build", historyMs, mapOf("items" to inputItems.size))
    
    // LLM network call (instrumented in LLMClient)
    val response = llmClient.chatWithTools(...)
    
    // Response parsing
    val parseStart = System.currentTimeMillis()
    val result = processResponse(response.textContent, response.toolCalls)
    val parseMs = System.currentTimeMillis() - parseStart
    logPerf("response_parse", parseMs, mapOf("toolCalls" to result.toolCalls.size))
    
    return result
}
```

#### LLMClient.kt - `executeChatWithTools()`

```kotlin
private fun executeChatWithTools(...): ResponsesResult {
    // Network timing only
    val networkStart = System.currentTimeMillis()
    val response = client.responses().create(builder.build())
    val networkMs = System.currentTimeMillis() - networkStart
    logPerf("llm_network", networkMs, mapOf("model" to model.toString()))
    
    // ... response parsing ...
}
```

#### BaseTool.kt - `BaseToolInvocation.execute()`

```kotlin
override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
    // Action execution
    val actionStart = System.currentTimeMillis()
    val result = context.platform.performAction(uiAction, context.currentSnapshot)
    val actionMs = System.currentTimeMillis() - actionStart
    logPerf("action_perform", actionMs, mapOf("action" to uiAction::class.simpleName))
    
    // Post-action observation
    val observeStart = System.currentTimeMillis()
    delay(UI_SETTLE_DELAY_MS)  // This is included in observation time
    val observation = capturePostActionObservation(context)
    val observeMs = System.currentTimeMillis() - observeStart
    logPerf("observe_post_action", observeMs, mapOf("settle_ms" to UI_SETTLE_DELAY_MS))
    
    // ...
}
```

### 3.3 Helper Function

Add to a new file `util/PerfLogger.kt`:

```kotlin
object PerfLogger {
    private var sessionId: String = "unknown"
    private var turnNumber: Int = 0
    
    fun setSession(id: String) { sessionId = id }
    fun setTurn(turn: Int) { turnNumber = turn }
    
    fun log(span: String, durationMs: Long, metadata: Map<String, Any> = emptyMap()) {
        val metaJson = JSONObject(metadata).toString()
        Log.i("PERF", "PERF|$sessionId|$turnNumber|$span|$durationMs|$metaJson")
    }
}
```

---

## 4. Data Collection

### 4.1 Output Format

Write to both logcat and a file for reliability:

**File**: `debug-output/perf_<session_id>.jsonl`

```json
{"ts":1700000000000,"session":"abc123","turn":1,"span":"capture","ms":85,"elements":42}
{"ts":1700000000085,"session":"abc123","turn":1,"span":"json","ms":23,"bytes":4521}
{"ts":1700000000108,"session":"abc123","turn":1,"span":"llm_network","ms":3800,"model":"gpt-4o"}
{"ts":1700000003908,"session":"abc123","turn":1,"span":"tool_click","ms":412,"index":5}
{"ts":1700000004320,"session":"abc123","turn":1,"span":"turn","ms":7234}
```

### 4.2 Collection Script

Extend `scripts/debug-run.sh`:

```bash
#!/bin/bash
# debug-run-profiled.sh

GOAL="$1"
DEBUG_DIR="debug-output/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$DEBUG_DIR"

# Start agent
adb shell "am start -n $PACKAGE/.app.MainActivity \
    --es api_key '$OPENAI_API_KEY' \
    --es goal '$GOAL' \
    --ez auto_start true"

# Capture perf logs in background
adb logcat -s PERF:I > "$DEBUG_DIR/perf.log" &
LOGCAT_PID=$!

# Wait for completion or timeout
# ... existing screenshot capture logic ...

# Stop log capture
kill $LOGCAT_PID

# Parse logs to JSONL
grep "^PERF|" "$DEBUG_DIR/perf.log" | \
    awk -F'|' '{
        print "{\"ts\":" systime() "000,\"session\":\"" $2 "\",\"turn\":" $3 ",\"span\":\"" $4 "\",\"ms\":" $5 ",\"meta\":" $6 "}"
    }' > "$DEBUG_DIR/perf.jsonl"

echo "Profiling data: $DEBUG_DIR/perf.jsonl"
```

---

## 5. Experiment Design

### 5.1 Task Set

Use a small, repeatable set with varying complexity:

| Task | Expected Complexity | Expected Turns |
|------|---------------------|----------------|
| "Open Settings" | Low | 1-2 |
| "Open Chrome" | Low | 1-2 |
| "Open Settings and turn off Wi-Fi" | Medium | 3-5 |
| "Search for 'Bluetooth' in Settings" | Medium | 3-4 |
| "Open Chrome and search for 'weather'" | High | 4-6 |

### 5.2 Procedure

1. **Environment Control**
   - Same device, OS version, network
   - Clear app state between runs: `adb shell pm clear com.moonkey.androidagent`
   - Airplane mode OFF, Wi-Fi ON, screen brightness fixed
   - No other apps running

2. **Repetition**
   - Run each task **10 times**
   - Discard first run as warm-up
   - Report: median, p90, min, max

3. **Data Collection**
   - Screenshot per turn
   - Full perf.jsonl
   - Agent logs for error diagnosis

### 5.3 Checklist

```
[ ] Device: _________________ (model)
[ ] OS: Android _____________ (version)
[ ] Network: ________________ (Wi-Fi SSID or "Mobile")
[ ] App version: ____________ (git commit)
[ ] Model: __________________ (gpt-4o / gpt-4o-mini)
[ ] Date: ___________________
```

---

## 6. Analysis Plan

### 6.1 Per-Turn Breakdown

For each turn, compute:

```
t_turn_actual = measured turn duration
t_turn_sum = t_capture + t_json + t_history + t_llm_total + sum(t_tool) + t_settle

gap = t_turn_actual - t_turn_sum  # Should be small; if large, missing instrumentation
```

### 6.2 Phase Contribution

Aggregate across all turns:

```python
import pandas as pd

df = pd.read_json('perf.jsonl', lines=True)

# Group by span type
summary = df.groupby('span')['ms'].agg(['sum', 'mean', 'median', 'count', 'std'])

# Calculate percentage of total
total_turn_time = df[df['span'] == 'turn']['ms'].sum()
summary['pct'] = summary['sum'] / total_turn_time * 100

print(summary.sort_values('pct', ascending=False))
```

Expected output format:

```
span            sum      mean    median  count   std     pct
--------------- -------- ------- ------- ------- ------- -----
llm_network     45000    4500    4200    10      800     58.2%
settle_inter    30000    3000    3000    10      5       38.8%
observe_post    3000     300     300     10      10      3.9%
capture         800      80      75      10      15      1.0%
...
```

### 6.3 Visualization

Generate a stacked bar chart per turn:

```python
import matplotlib.pyplot as plt

# Pivot data: rows=turns, columns=span types
pivot = df.pivot_table(index='turn', columns='span', values='ms', aggfunc='sum')

# Plot stacked bar
pivot.plot(kind='bar', stacked=True, figsize=(12, 6))
plt.xlabel('Turn')
plt.ylabel('Duration (ms)')
plt.title('Time Breakdown by Turn')
plt.legend(loc='upper right')
plt.savefig('turn_breakdown.png')
```

### 6.4 Questions to Answer

After collecting data, answer these:

1. **What percentage of turn time is LLM network latency?**
   - If >50%: LLM is dominant, focus on context size / model selection
   - If <30%: Look elsewhere

2. **What percentage is fixed delays?**
   - Sum of `settle_inter_turn` + `observe_post_action`
   - If >30%: Adaptive delays are high-value

3. **How does screen complexity affect capture/JSON time?**
   - Correlate `n_elements` with `t_capture` and `t_json`

4. **What's the variance in LLM latency?**
   - High variance → network/API instability
   - Low variance → consistent bottleneck

5. **Are there unexpected time sinks?**
   - Large `gap` between `t_turn_actual` and `t_turn_sum` indicates missing instrumentation

---

## 7. Implementation Steps

### Phase 1: Instrumentation (Day 1)

1. Create `util/PerfLogger.kt` with logging helper
2. Add timing to `Agent.executeTurn()` - 5 spans
3. Add timing to `Turn.run()` - 2 spans
4. Add timing to `LLMClient.executeChatWithTools()` - 1 span
5. Add timing to `BaseToolInvocation.execute()` - 2 spans
6. Update `debug-run.sh` to capture PERF logs

### Phase 2: Baseline Collection (Day 2)

1. Run "Open Settings" × 10
2. Run "Search for Bluetooth in Settings" × 10
3. Verify data completeness (no missing spans)
4. Generate initial breakdown report

### Phase 3: Analysis (Day 3)

1. Compute phase contributions
2. Generate visualizations
3. Answer the 5 questions above
4. Document findings in `profiling_results.md`

---

## 8. Success Criteria

Profiling is complete when:

- [ ] All 11 spans are instrumented and emitting data
- [ ] At least 2 tasks run 10× each with complete data
- [ ] Phase contribution percentages computed
- [ ] Answers to 5 analysis questions documented
- [ ] Top 3 actual bottlenecks identified with data backing

---

## References

- `Agent.kt` - Main turn loop
- `Turn.kt` - LLM call orchestration
- `LLMClient.kt` - OpenAI API wrapper
- `BaseTool.kt` - Tool execution with observation
- `Perceptor.kt` - Screen capture and JSON serialization
