# Auto-Compact（替换 `max_turn`）— v2

> 根据 codex review（`review_codex.md`）修订。v1 保留在 git history。

## 目标

干掉 `maxTurns` 硬上限。换成 **context-window 触发的 auto-compaction** —— agent 想跑多久跑多久；只要对话快撑满 model 的 context window，就把老的那截 history 总结掉，继续跑。

为什么：
- `maxTurns` 是个拍脑袋的整数，正在做正经事但跑得长一点的任务就被它枪毙了（"Max turns reached"，但其实在推进）。
- 而且它直接漏进了 **prompt** —— `PromptBuilder.kt:139` 写 `"Turn $turnNumber/$maxTurns"`，`AgentTurnRunner.kt:223` 加 `"🛑 FINAL TURN"`，等于主动教 model "你只剩 N 个 turn 了"。这会让 model 偏向草草收尾或抄近路。**Prompt 层泄漏才是真正必须杀掉它的理由**，不是 loop guard。
- 现在 `HistoryManager.compress()` 的 Phase 2 是有损 eviction —— 丢 item 留一句没用的话。几轮之后 agent 完全失忆。

成功标准：
- 没有任何生产路径因为 turn 数停止。
- Prompt 文本里没有任何 turn budget 字样。
- Compaction 保住 **当前 user goal**、近期 **supplement**、**tool-call 完整性**（不孤儿 `FunctionCallOutput`）、近期 screen state。
- compaction 失败不能污染或丢 history；并发 supplement 不能被覆盖。

## 先验工作

| | 触发 | cut 规则 | 替换 | 迭代 |
|---|---|---|---|---|
| **pi-mono** | `tokens > contextWindow − reserve` | 反向走累加到 `keepRecentTokens`；可切在任何 user/assistant/custom message；不能切在 tool result | `[summary] + [...kept]`；结构化总结 | 是 —— UPDATE prompt 带上一份 summary |
| **claude-code** | 反应式：turn 后查 `tokens >= contextWindow − 13K` | snip 然后 summarize | 一条 summary 替换 history；保留 `USER_INTENT`；3-strike 熔断 | 是 |
| **codex** | turn 中：`total_usage >= auto_compact_limit` | 插在最后一条 user message 之前 | 替换 history；`prompt_too_long` 反应式重试 | 否 —— 靠 rolling forward |

**采用的 kernel**：
1. 估算 token（history + 静态开销）。超过 `contextWindow − reserve` 触发。
2. 反向走累加 `keepRecentTokens`，在 **安全** 边界切 —— 任何 `Message` item；绝不切在 `FunctionCall` 和它的 `FunctionCallOutput` 之间。
3. LLM 把丢弃的前缀总结成一条结构化 message。
4. 新 history = `[canonical USER_INTENT(当前 goal), COMPACTION_SUMMARY, ...kept]`。
5. CAS-swap history；如果 revision 变了（并发 supplement），丢弃 summary，下个 turn 重试。
6. 如果上一份 summary 存在，喂给 summarizer 配 UPDATE prompt。
7. provider `prompt_too_long` error 时做一次反应式 compact + 重试。

## 方案

### 1. Model context window

JSON catalog 加 `context_window: Int`。cloud 默认 128_000，local backend 默认 8_000（Leap on-device model 通常 4K–32K）。

```kotlin
data class ModelEntry(
    /* ... */
    val contextWindow: Int,  // 必填，校验 > 0
)
```

JSON：
```json
"gpt-5.2": {
  "display_name": "GPT-5.2",
  ...
  "context_window": 400000
}
```

`JsonModelEntry.toModelEntry()` 校验 `require(contextWindow > 0)`。catalog 现在是 **12** 条（gpt-5.2/.4 × {response, chat, codex}、glm-5、minimax-m2.5、kimi-k2.5、qwen3-vl、qwen3.5、autoglm-phone-9b），全部填上真实 window。

### 2. HistoryManager：加 revision + CAS

```kotlin
class HistoryManager(...) {
    // 单调递增，addItem/recordItems/replaceAll 都 bump
    @Volatile private var _revision: Long = 0
    val revision: Long get() = _revision

    @Synchronized
    fun snapshot(): Pair<Long, List<ResponseItem>> = _revision to items.toList()

    /** CAS replace。swap 成功 true，revision 变了 false。 */
    @Synchronized
    fun replaceAllIfRevision(expected: Long, newItems: List<ResponseItem>): Boolean {
        if (_revision != expected) return false
        items.clear()
        items.addAll(newItems)
        lastTokenEstimate = null
        _revision++
        onMutation?.invoke()
        return true
    }

    // 现有 addItem/recordItems/replaceAll 也 bump _revision
}
```

这是 `HistoryManager` 上**唯一**的新状态。修掉 blocker 2 —— supplement 注入会 bump revision；compactor 等 LLM 时它进来，CAS 失败，半成品 summary 丢掉，下个 turn 带着 supplement 重试。

### 3. 新文件：`Compactor.kt`

Per-Agent，不是 per-session。在 `Agent` constructor 里建（或者 `SessionAgentRunner` / `SubAgentRunner` 通过 factory 传），用那个 agent 自己解析的 `ModelEntry` 和 `LLMClient`。

```kotlin
class Compactor(
    private val llmClient: LLMClient,
    private val model: ModelEntry,
    private val staticOverheadTokens: Long = 12_000,  // system prompt + tools + skills + scratchpad
    private val reserveTokens: Long = 24_000,         // 下一次 output (~6K) + summary (~5K) + headroom
    private val keepRecentTokens: Long = 20_000,
) {
    suspend fun maybeCompact(
        currentGoal: String,
        history: HistoryManager,
    ): CompactionOutcome {
        val (rev, items) = history.snapshot()
        val tokens = items.sumOf { it.estimateTokens() } + staticOverheadTokens
        if (tokens <= model.contextWindow - reserveTokens) {
            return CompactionOutcome.Skipped
        }

        val cutIdx = findSafeCutPoint(items, keepRecentTokens)
        if (cutIdx <= 0) return CompactionOutcome.NothingToCompact

        val previousSummary = items.firstNotNullOfOrNull {
            (it as? ResponseItem.Message)?.takeIf { m -> m.kind == COMPACTION_SUMMARY }?.content
        }
        val toSummarize = items.subList(0, cutIdx)
            .filterNot { it is ResponseItem.Message && it.kind == COMPACTION_SUMMARY }

        val summaryText = try {
            summarize(currentGoal, toSummarize, previousSummary)
        } catch (ce: CancellationException) {
            throw ce   // 一定要 rethrow —— cancellation 不算 compaction failure
        } catch (t: Throwable) {
            return CompactionOutcome.Failed(t.message ?: t.javaClass.simpleName)
        }

        val newItems = buildList {
            add(ResponseItem.Message(MessageKind.USER_INTENT, "Goal: $currentGoal"))
            add(ResponseItem.Message(MessageKind.COMPACTION_SUMMARY, summaryText))
            addAll(items.subList(cutIdx, items.size))
        }

        return if (history.replaceAllIfRevision(rev, newItems)) {
            CompactionOutcome.Compacted(before = tokens, after = history.estimateTokenCount())
        } else {
            CompactionOutcome.Stale   // summarize 期间 supplement 进来了；下个 turn 重试
        }
    }

    /** 反应式：LLM 抛 prompt_too_long 时调。 */
    suspend fun forceCompactNow(currentGoal: String, history: HistoryManager): CompactionOutcome { /* keepRecentTokens 砍半，无条件跑 maybeCompact */ }
}
```

**`findSafeCutPoint`**（替换 v1 只看 `SCREEN_OBSERVATION` 的窄规则，换成 pi 的通用规则）：

```kotlin
private fun findSafeCutPoint(items: List<ResponseItem>, keepTokens: Long): Int {
    // 反向走累加 token
    var acc = 0L
    var hitIdx = -1
    for (i in items.indices.reversed()) {
        acc += items[i].estimateTokens()
        if (acc >= keepTokens) { hitIdx = i; break }
    }
    if (hitIdx < 0) return 0  // 总量 < keepTokens —— 没活干

    // 往前 snap 到下一个 SAFE cut 点
    // SAFE = 任何 Message item，或者 FunctionCall（它的 FunctionCallOutput 在右边一起留下来）
    // UNSAFE = FunctionCallOutput（会孤儿掉它的 FunctionCall）
    for (j in hitIdx..items.lastIndex) {
        when (items[j]) {
            is ResponseItem.Message,
            is ResponseItem.FunctionCall -> return j
            is ResponseItem.FunctionCallOutput -> continue
        }
    }
    return items.size  // 尾部没安全点；全保留
}
```

**这样处理 v1 漏掉的 edge case**：
- *只有 supplement 当边界*：supplement 的 `USER_INTENT` 是 `Message` → 合法 cut。不再依赖下一个 `SCREEN_OBSERVATION`。
- *单个超大 newest turn*：snap-forward 返回 `items.size`（找不到安全 cut），返回 `NothingToCompact` 不破坏。Compactor 记 warning；继续发生最终会真的撞 `prompt_too_long`，反应式路径接住。
- *post-action screen 在 `FunctionCallOutput` 里*：它跟它的 `FunctionCall` 始终配对原子保留。

**Result 类型**：
```kotlin
sealed class CompactionOutcome {
    object Skipped : CompactionOutcome()              // 没超阈值
    object NothingToCompact : CompactionOutcome()     // 找不到安全 cut
    object Stale : CompactionOutcome()                // CAS 落空；下个 turn 重试（不算 failure）
    data class Compacted(val before: Long, val after: Long) : CompactionOutcome()
    data class Failed(val reason: String) : CompactionOutcome()  // LLM error；算进熔断器
}
```

### 4. Summarization prompt

放在 `app/src/main/assets/prompts/compaction_initial.md` 和 `compaction_update.md`。Goal **不再** 让 summarizer 重述 —— 由 agent loop 规范化重新注入。

```
You are summarizing an Android agent's progress so another instance can continue from this checkpoint.
The user's current goal is provided separately in the conversation; do NOT restate it.

Output this exact markdown structure:

## Progress
### Done
- [Concrete sub-goals completed; screens reached; data captured.]

### In Progress
- [What the agent was doing right before this summary.]

### Blocked / Failed Attempts
- [Approaches that failed — so we don't retry them. Or "(none)".]

## User Updates
- [If the conversation contained additional user messages (supplements, clarifications, constraints),
   capture them here as standing constraints. Or "(none)".]

## App State
- Foreground app: [package or "unknown"]
- Last meaningful screen: [name / one-line description]
- Key data captured: [text, IDs, values the agent extracted]

## Next Steps
1. [Ordered plan to continue.]

Rules:
- Be concise. Preserve exact strings: package names, button labels, error messages, IDs, numeric values.
- Do NOT add tasks not implied by the conversation. Do NOT speculate.
- Do NOT restate the goal.
```

UPDATE 变体把现有 summary 包在 `<previous-summary>` 里，让 model 把新事件并进去（In Progress → Done 等）。

Summarizer LLM call **不带 tool**，`maxTokens ≈ 5000`。

### 5. PromptBuilder：把 summary 当 user-role context

`MessageKind.COMPACTION_SUMMARY` 现在是 assistant role（从 `COMPRESSION_DIGEST` 继承的）。这样 model 会当成"我之前的输出"读。改：

- `MessageKind.COMPACTION_SUMMARY.apiRole = "user"`。
- `PromptBuilder` 序列化时加前缀：
  ```
  [Context checkpoint from earlier work in this session]
  
  <summary text>
  ```

来源对 model 就清楚了 —— 它读的是 handoff，不是自己的思路。

### 6. Agent loop 接线

`Agent.kt`：

```diff
 while (shouldContinue()) {
     if (pauseState.value) { /* ... */ }
     if (!shouldContinue()) { /* ... */ break }
-    if (turnCount >= config.maxTurns) {
-        Log.w(TAG, "Max turns (${config.maxTurns}) reached")
-        eventDispatcher.status("⚠️ Max turns reached")
-        stopReason = AgentStopReason.MaxTurnsReached
-        break
-    }

+    // 每 turn 开头 auto-compact
+    when (val r = compactor.maybeCompact(config.goal, services.historyManager)) {
+        is CompactionOutcome.Compacted -> {
+            consecutiveCompactionFailures = 0
+            eventDispatcher.status("📚 Compacted history (${r.before} → ${r.after} tokens)")
+        }
+        CompactionOutcome.Failed -> {
+            consecutiveCompactionFailures++
+            if (consecutiveCompactionFailures >= 3) {
+                stopReason = AgentStopReason.Error("Auto-compaction failed 3× in a row")
+                break
+            }
+        }
+        CompactionOutcome.Stale,
+        CompactionOutcome.Skipped,
+        CompactionOutcome.NothingToCompact -> consecutiveCompactionFailures = 0
+    }
+
+    // Eval-only 跑飞兜底（生产：config.evalTurnBudget = null）
+    if (config.evalTurnBudget != null && turnCount >= config.evalTurnBudget) {
+        stopReason = AgentStopReason.Error("Eval turn budget reached (${config.evalTurnBudget})")
+        break
+    }

     turnCount++
     ...
 }
```

第 134 行的 retry gate 删 `hasRemainingTurns`：

```diff
- val hasRemainingTurns = turnCount < config.maxTurns
- val canRetry = hasRemainingTurns && recoverableRetryCount < retryLimit
+ val canRetry = recoverableRetryCount < retryLimit
```

`turnCount` 留 —— 只用于可观测（status event、trace、capsule UI）。**Prompt 文本里没有任何东西读它**。

**`evalTurnBudget`**：`SessionConfig` 和 `AgentExecutionConfig` 上新加的 nullable 字段。生产代码永不设置。eval bridge 从 yaml `max_turns:` 读出来设上。这是**唯一**保留的 turn-budget 概念，明确限定在"防 eval 无限循环烧 $100"这一个 use case。5 行代码；名字一看就知道是干嘛的。

### 7. 反应式 compaction（prompt_too_long 单次重试）

在 `Turn.runStreaming()`（或者 LLM call 所在的地方）catch provider 的 context-overflow error：

```kotlin
try {
    llmClient.stream(...)
} catch (e: ContextWindowExceededException) {
    compactor.forceCompactNow(config.goal, historyManager)
    llmClient.stream(...)  // 重试一次
}
```

`ContextWindowExceededException` 是新异常，由现有 error pipeline（`CloudStreamRetryPolicy` 或其同侪）分类得到。Provider 错误匹配：HTTP 413 / `prompt_too_long` / `request_too_large` / Ollama 的 `prompt too long; exceeded max context length`。pi-mono 的 `ai` 包里有现成模式可抄。

这是静态开销估算漂移的兜底。总共 ~30 LOC。

### 8. 删除清单（扩充版，覆盖漏掉的集成点）

| 文件 | 删什么 |
|---|---|
| `protocol/SessionConfig.kt` | `val maxTurns: Int = 50` |
| `protocol/TaskOutcome.kt` | `MAX_TURNS` |
| `agent/Agent.kt` | turn-count guard（83–88）；`hasRemainingTurns` retry 项（134） |
| `agent/AgentRuntimeTypes.kt` | `AgentStopReason.MaxTurnsReached` |
| `agent/AgentExecutionConfig.kt` | `maxTurns: Int = 50`（29） |
| `agent/AgentTurnRunner.kt` | `isFinalTurn(...)` 调用（207）；`"🛑 FINAL TURN"` 文本（223） |
| `agent/TurnPlanningPhaseRunner.kt` | `maxTurns = config.maxTurns` 透传（103） |
| `agent/cognition/prompt/PromptBuilder.kt` | `maxTurns` 参数（44, 112, 134）；`"Turn $turnNumber/$maxTurns"` 文本（138-139）→ 改成 `"Turn $turnNumber"`（或者整段删了） |
| `agent/cognition/policy/TurnBudget.kt` | **整个文件** —— `isFinalTurn`、`DelegationSummary.format(maxTurns, ...)` |
| `agent/cognition/policy/TurnBudgetAndDelegationSummaryTest.kt` | **整个文件**（或者改写成不带 maxTurns 的 DelegationSummary 测试） |
| `agent/definition/AgentRoleDef.kt` | `maxTurns: Int = 50`（26, 71, 84） |
| `agent/subagent/SubAgentRunner.kt` | `maxTurns = resolvedRoleDef.maxTurns`（103, 136）；`AgentStopReason.MaxTurnsReached` 分支 → 走 timeout/error 路径 |
| `session/AgentSession.kt` | `MaxTurnsReached -> MAX_TURNS` 映射（478） |
| `session/SessionCheckpointCoordinator.kt` | `maxTurns =` 快照序列化（89, 112）。老快照含此字段时 reload 静默忽略 |
| `session/SessionAgentRunner.kt` | `maxTurns = config.maxTurns` 透传（85） |
| `session/SessionHistoryBootstrapper.kt` | `autoCompress = true`、`maxTokenBudget = 18_000` —— **关键**：不删的话有损 eviction 还会在新 compactor *之前* 跑 |
| `history/HistoryConfig.kt` | `autoCompress`、`autoCompressThreshold`、`compressTargetRatio`、`maxTokenBudget`（都是绑老 eviction 的）。`recentFullScreens`、`recentWindowSize` 留 |
| `history/HistoryManager.kt` | `compress()` Phase 2 eviction + digest 插入 + `mergeAdjacentDigests()`；`autoCompressIfNeeded()`；`CompressionResult.BudgetUnreachable`。`downgradeOldScreens()` 留 |
| `history/ResponseItem.kt` | `MessageKind.COMPRESSION_DIGEST` → `COMPACTION_SUMMARY`；`COMPACTION_SUMMARY` 的 `apiRole` 返回 `"user"` |
| `history/model/SessionRuntimeSnapshot.kt` | `ConversationConfigSnapshot.maxTurns`（63） |
| `trace/AgentTrace.kt` | `put("max_turns", ...)`（49） |
| `trace/TraceModels.kt` | `@SerialName("max_turns") val maxTurns: Int`（64） |
| `trace/TraceRecorderFactory.kt` | `maxTurns = config.maxTurns`（43） |
| `app/MainActivity.kt` | `const val EXTRA_MAX_TURNS = "max_turns"`（83）；`maxTurns = settingsState.maxTurns`（676） |
| `app/MainActivityIntentPayload.kt` | `maxTurns` 字段 + 解析（20, 94–98, 159） |
| `app/MainActivityIntentApplier.kt` | `payload.maxTurns?.let { ... }`（98） |
| `app/MainActivityContent.kt` | `maxTurns = settingsState.maxTurns`（129） |
| `app/AppSettingsStore.kt` | `DEFAULT_MAX_TURNS`、`KEY_MAX_TURNS`、读写（17, 35, 50, 75, 122, 169） |
| `app/AppSettingsState.kt` | `maxTurns` mutable state + logging + observer（19, 47, 60, 89） |
| `app/AgentService.kt` | `maxTurns = maxSteps` 注入（358） |
| `app/AgentServiceEventHandler.kt` | `TaskOutcome.MAX_TURNS` 分支（192, 200） |
| `ui/overlay/CapsuleStateHolder.kt` | `TaskOutcome.MAX_TURNS -> CapsuleMode.Done("Max steps reached")`（248） |
| `ui/settings/SettingsModels.kt` | `MAX_TURNS_OPTIONS`（43） |
| `ui/settings/SettingsHomePage.kt` | `maxTurns` 参数 + `agentBehaviorSubtitle`（38, 84, 137, 145） |
| `ui/settings/SettingsDropdowns.kt` | `MaxTurnsDropdown`（92, 97, 99–100） |
| `ui/settings/AgentBehaviorSettingsPage.kt` | `maxTurns` 参数 + `MaxTurnsDropdown(...)`（16, 34） |
| `onboarding/OnboardingDemoController.kt` | `maxTurns = MAX_TURNS`（92）；local `MAX_TURNS` 常量；`TaskOutcome.MAX_TURNS` 信息分支 |
| `eval/aw_bridge/native_agent_bridge.py` | `max_turns: int` 字段（30）；intent extra 发送（205–206）—— **以新名 `eval_turn_budget` 接到 `SessionConfig.evalTurnBudget`** |
| `eval/aw_bridge/runner.py` | `max_turns=int(bridge_cfg.get("max_turns", 30))`（268）—— yaml key 不变，流到 `evalTurnBudget` |
| `eval/config/*.yaml` | `max_turns:` key 保留（作为 eval 兜底仍有意义）；语义改为"N turn 后放弃"而不是"以 MAX_TURNS 原因停止" |
| `eval/tests/test_completion_monitor.py` | `"reason: MAX_TURNS"` → `"reason: ERROR"`（信息包含 "eval turn budget"） |
| `scripts/debug-run.sh` | `DEBUG_MAX_TURNS` 只是 log 监听上限，**保留**（重命名可选） |
| `doc/main/**` | 更新 protocol/settings/capsule/loop/eval 文档 |

### 9. Subagent

`SubAgentRunner.kt:79` 每个 child 起新的 `HistoryManager`。每个 subagent 也在 `childServices` 和 `childModelName` 解析完之后自己构造 `Compactor` —— 绑 **child 自己的** model（可能跟 parent 不同，通过 `subagentModel`）。`Compactor` 是 per-`Agent` instance，不是 per-session。

`SubAgentRunner.kt:103,136` 对 `resolvedRoleDef.maxTurns` 的引用删掉。`MaxTurnsReached` 触发的 delegation-failure summary 路径改成只在 `AgentStopReason.Error` 或 timeout（`timeoutMs` 留作 subagent 跑飞兜底）时触发。

## 组件

**新增**：
- `history/Compactor.kt`（约 180 LOC）
- `assets/prompts/compaction_initial.md`、`compaction_update.md`
- 异常类型：`ContextWindowExceededException`（在 `llm/`）

**修改**：
- `history/HistoryManager.kt` —— 加 revision + CAS API；删 Phase 2 eviction + auto-compress 触发 + BudgetUnreachable。
- `history/ResponseItem.kt` —— `COMPRESSION_DIGEST` → `COMPACTION_SUMMARY`（user role）。
- `history/HistoryConfig.kt` —— 删老 eviction 字段。
- `llm/ModelCatalog.kt` —— 加 `contextWindow` 字段 + JSON 解析 + 校验。
- `llm/CloudStreamRetryPolicy.kt`（或同侪） —— 分类 context-overflow 错误，抛 `ContextWindowExceededException`。
- `agent/Agent.kt` —— turn guard 换成 compaction；per-Agent compactor 构造；`evalTurnBudget` 非空才生效。
- `agent/Turn.kt` —— LLM call 包一层 `ContextWindowExceededException` 重试。
- `agent/cognition/prompt/PromptBuilder.kt` —— 删 `maxTurns` 参数；改 "Turn N" 或全删；`COMPACTION_SUMMARY` 内容加 "[Context checkpoint from earlier work]" 前缀。
- `session/SessionAgentRunner.kt` —— 构造每个 `Agent` 的 `Compactor` 并透传。

**删除**：
- `agent/cognition/policy/TurnBudget.kt`（及其测试）。
- `agent/cognition/policy/TurnBudgetAndDelegationSummaryTest.kt`（或剥掉 maxTurns）。
- 删除清单表里的所有 `maxTurns` / `MAX_TURNS` 引用。

## 交互

```
┌─────────────────────────────────────────────────────────────────┐
│                       Agent Loop (per turn)                     │
│                                                                 │
│  ┌────────────────────────┐                                     │
│  │ compactor              │   est ≤ window − reserve ?          │
│  │  .maybeCompact(goal,h) │──yes─► Skipped                      │
│  │                        │──no──► snapshot(rev,items)          │
│  │                        │       LLM 总结                       │
│  │                        │       CAS replaceAllIfRevision      │
│  │                        │         成功 → Compacted             │
│  │                        │         miss → Stale（重试）         │
│  │                        │       LLM error → Failed（计数）     │
│  └───────────┬────────────┘                                     │
│              ▼                                                  │
│  ┌────────────────────────┐                                     │
│  │ Perceive → Think → Act │ ──prompt_too_long──►                │
│  │ → Observe              │   forceCompactNow → 重试一次          │
│  └────────────────────────┘                                     │
└─────────────────────────────────────────────────────────────────┘
```

**Compaction state machine**：

| State | Event | Next | Side effect |
|---|---|---|---|
| Idle | tokens ≤ threshold | Idle | 无 → `Skipped` |
| Idle | tokens > threshold | Reading | snapshot `(rev, items)` |
| Reading | 找不到安全 cut | Idle | → `NothingToCompact` |
| Reading | 找到安全 cut | Summarizing | 发起 LLM call |
| Summarizing | LLM success | Swapping | build newItems |
| Summarizing | LLM error | Idle | → `Failed(reason)`；loop counter++ |
| Summarizing | CancellationException | (propagates) | rethrow（**绝不**算 failure） |
| Swapping | CAS success | Idle | → `Compacted(before, after)`；loop counter = 0 |
| Swapping | CAS miss | Idle | → `Stale`；loop counter = 0；下个 turn 重试 |

3-strike 熔断器在 agent loop 里，不在 compactor。

**防御层**：

```
addItem(SCREEN_OBSERVATION)
        │
        ▼
downgradeOldScreens()   ◄── Layer 0：免费本地。老的完整 screen → 一行 summary。
        │                   每次 screen capture 都跑。
        ▼
下个 turn 开头：
compactor.maybeCompact()  ◄── Layer 1：主动。est > window − reserve 时 LLM summarize。
        │
        ▼
LLM call（turn 中）
        │ catch ContextWindowExceededException
        ▼
compactor.forceCompactNow() → 重试  ◄── Layer 2：反应式兜底。
```

## 默认值

| 常量 | 值 | 理由 |
|---|---|---|
| `staticOverheadTokens` | 12_000 | System prompt + tool schemas + scratchpad + app skills + memory —— 测量天花板 |
| `reserveTokens` | 24_000 | 下一 turn output (~6K) + summary message (~5K) + 静态漂移余量 (~13K) |
| `keepRecentTokens` | 20_000 | ~3–5 个最近 turn verbatim 保留 |
| `forceCompact` 缩水 `keepRecentTokens` | 10_000 | 反应式路径：更激进 |
| `MAX_CONSECUTIVE_COMPACTION_FAILURES` | 3 | 跟 Claude Code 一样 |
| 默认 `contextWindow`（cloud，JSON 缺失） | 128_000 | 保守；所有 cloud 条目实际都 ≥ 这个 |
| 默认 `contextWindow`（local backend） | 8_000 | Leap 端上 model 通常 4–32K；强制 JSON 指定 |
| `evalTurnBudget`（生产） | `null` | 生产没 turn cap |
| `evalTurnBudget`（eval 默认） | 30（来自 yaml） | eval 兜底，跟现有 eval 语义一致 |

## 任务

### task-1：`model-catalog-context-window`
- **Scope**：`llm/ModelCatalog.kt`、`assets/llm_models.json`、`ModelCatalogTest.kt`。
- **Accept**：`ModelEntry.contextWindow: Int`（必填，校验 `> 0`）。12 条 JSON 全部填上。Local backend 默认 fallback 返回 8_000。解析 test 覆盖 fallback 和校验失败。
- **Deps**：无。

### task-2：`history-revision-cas`
- **Scope**：`history/HistoryManager.kt` + `HistoryManagerTest.kt`。
- **Accept**：
  - `_revision: Long`。`addItem`、`recordItems`、`replaceAll`、`clear` 都 bump。
  - `snapshot(): Pair<Long, List<ResponseItem>>` 原子读。
  - `replaceAllIfRevision(expected, newItems): Boolean` CAS-swap；revision 不匹配返回 false。
  - 测试：fake 长 LLM call 期间 supplement 进来 → CAS 失败，supplement 保住。
- **Deps**：无。

### task-3：`compactor-core`
- **Scope**：新 `history/Compactor.kt` + `CompactorTest.kt`。`assets/prompts/compaction_initial.md`、`compaction_update.md`。
- **Accept**：
  - `findSafeCutPoint`：反向走，往前 snap；永不返回 `FunctionCallOutput` 的 index。Edge case 单测：超大 newest turn → `items.size`；只有 supplement 的边界 → 切在 `USER_INTENT`；阈值落在 `FunctionCallOutput` 中间 → snap 到下一个安全点。
  - `maybeCompact` 返回 `Skipped` / `NothingToCompact` / `Stale` / `Compacted(before, after)` / `Failed(reason)`。
  - rethrow `CancellationException`（测：fake LLM call 期间 cancel 干净传播）。
  - 输出 history = `[USER_INTENT(当前 goal), COMPACTION_SUMMARY, ...kept]`。老 summary 替换不重复。迭代测试：第二次 compaction 用 UPDATE prompt 带上一份 summary。
  - `forceCompactNow` 缩 `keepRecentTokens` 无条件跑。
- **Deps**：task-1、task-2。

### task-4：`history-manager-cleanup`
- **Scope**：`history/HistoryManager.kt`、`ResponseItem.kt`、`HistoryConfig.kt`、`SessionHistoryBootstrapper.kt`。
- **Accept**：
  - `MessageKind.COMPRESSION_DIGEST` → `COMPACTION_SUMMARY`；它的 `apiRole` 返回 `"user"`。
  - `HistoryManager.compress()` Phase 2 eviction + `mergeAdjacentDigests()` + `autoCompressIfNeeded()` 删掉。
  - `CompressionResult.BudgetUnreachable` 删。
  - `HistoryConfig`：`autoCompress`、`autoCompressThreshold`、`compressTargetRatio`、`maxTokenBudget` 删。`recentFullScreens`、`recentWindowSize` 留。
  - `SessionHistoryBootstrapper`：删 `autoCompress = true` 和 `maxTokenBudget` 参数。
  - `downgradeOldScreens()` 留，仍由 `addItem`/`recordItems` 调用。
- **Deps**：task-3。

### task-5：`prompt-builder-decontextualize-summary`
- **Scope**：`agent/cognition/prompt/PromptBuilder.kt`。
- **Accept**：
  - 所有入口的 `maxTurns` 参数删。
  - `"Turn $turnNumber/$maxTurns"` 改 `"Turn $turnNumber"`（或者整段删了如果没用）。
  - `COMPACTION_SUMMARY` 序列化时加 `"[Context checkpoint from earlier work]\n\n"` 前缀。
  - Prompt 文本里没有任何 turn budget 字样。
- **Deps**：task-4。

### task-6：`reactive-compaction-retry`
- **Scope**：`llm/CloudStreamRetryPolicy.kt`（或同侪）、新 `ContextWindowExceededException`、`agent/Turn.kt`。
- **Accept**：
  - HTTP 413 / `prompt_too_long` / `request_too_large` / Ollama 溢出串归类为 `ContextWindowExceededException`。
  - `Turn.runStreaming()` catch 一次，调 `compactor.forceCompactNow(...)`，重试 LLM call。
  - 测试：mock provider 第一次抛，重试成功。
- **Deps**：task-3。

### task-7：`agent-loop-rewire`
- **Scope**：`agent/Agent.kt`、`agent/AgentTurnRunner.kt`、`session/SessionAgentRunner.kt`、`agent/subagent/SubAgentRunner.kt`。
- **Accept**：
  - `Agent` constructor 接 `Compactor`（由 `SessionAgentRunner` 或 `SubAgentRunner` 用 agent 自己解析的 model + LLM client 构造）。
  - 每 turn 开头：`compactor.maybeCompact(config.goal, services.historyManager)`。`Compacted` → status event + 计数器清零。`Failed` → 计数器++；≥3 停 `Error`。其余 outcome → 计数器清零。
  - turn-count guard 删；`hasRemainingTurns` retry 项删。
  - `AgentTurnRunner` 不再调 `isFinalTurn`；没 `"FINAL TURN"` 警告。
  - `evalTurnBudget` 检查：`config.evalTurnBudget != null && turnCount >= it` → 停 `Error("Eval turn budget reached (N)")`。
  - `SubAgentRunner`：`childServices`/`childModelName` 准备好之后再建 compactor。`MaxTurnsReached` 分支删（subagent 跑飞靠 `timeoutMs` 兜底）。
- **Deps**：task-3、task-4、task-5。

### task-8：`protocol-and-session-strip-maxturns`
- **Scope**：`protocol/SessionConfig.kt`、`protocol/TaskOutcome.kt`、`agent/AgentRuntimeTypes.kt`、`agent/AgentExecutionConfig.kt`、`agent/definition/AgentRoleDef.kt`、`agent/cognition/policy/TurnBudget.kt`、`session/AgentSession.kt`、`session/SessionCheckpointCoordinator.kt`、`history/model/SessionRuntimeSnapshot.kt`、`trace/{AgentTrace.kt, TraceModels.kt, TraceRecorderFactory.kt}`。
- **Accept**：
  - `maxTurns` 字段从 `SessionConfig`、`AgentExecutionConfig`、`AgentRoleDef`、`ResolvedAgentRole`、`ConversationConfigSnapshot` 删除。
  - **加**：`SessionConfig.evalTurnBudget: Int? = null` 和 `AgentExecutionConfig` 上同一个。默认 null。透过 `SessionAgentRunner` 到 `AgentExecutionConfig`。
  - `AgentStopReason.MaxTurnsReached` 删。
  - `TaskOutcome.MAX_TURNS` 删。
  - `TurnBudget.kt` 删。它的 `DelegationSummary.format` 要么删要么改写成不带 `maxTurns` 参数（如果 subagent error 路径还需要）。
  - Trace：`max_turns` 字段/metadata 从 `AgentTrace.kt`、`TraceModels.kt`、`TraceRecorderFactory.kt` 删。
  - Checkpoint 序列化不再写 `maxTurns`；老快照 reload 时忽略。
  - 依赖文件的编译错误在同 task 里追完修完。
- **Deps**：task-7。

### task-9：`app-and-ui-strip-maxturns`
- **Scope**：`app/{MainActivity, MainActivityIntentPayload, MainActivityIntentApplier, MainActivityContent, AppSettingsStore, AppSettingsState, AgentService, AgentServiceEventHandler}.kt`、`ui/overlay/CapsuleStateHolder.kt`、`ui/settings/{SettingsModels, SettingsHomePage, SettingsDropdowns, AgentBehaviorSettingsPage}.kt`、`onboarding/OnboardingDemoController.kt`。
- **Accept**：
  - 所有 `maxTurns` / `EXTRA_MAX_TURNS` / `DEFAULT_MAX_TURNS` / `KEY_MAX_TURNS` / `MAX_TURNS_OPTIONS` / `MaxTurnsDropdown` 引用删。
  - "Max Turns" UI 控件从 `AgentBehaviorSettingsPage` 删；布局缩短。Settings subtitle 不再打 `"$maxTurns turns"`。
  - `TaskOutcome.MAX_TURNS -> ...` 分支删（when-exhaustiveness 恢复）。
  - `OnboardingDemoController` 的 `maxTurns = MAX_TURNS` 删；local `MAX_TURNS` 常量删；`TaskOutcome.MAX_TURNS` 信息分支删。
- **Deps**：task-8。

### task-10：`eval-bridge-rename`
- **Scope**：`eval/aw_bridge/native_agent_bridge.py`、`eval/aw_bridge/runner.py`、`eval/tests/{test_runner.py, test_native_agent_bridge.py, test_completion_monitor.py, test_runner_preflight_policy.py}`。
- **Accept**：
  - `max_turns`（yaml key）**保留**在 yaml 配置里（不给运维加心智负担）。
  - `native_agent_bridge.py` 从 cfg 读 `max_turns`，intent extra 发送时改为 `eval_turn_budget`（新）而不是 `max_turns`。
  - `MainActivityIntentPayload` 解析 `EXTRA_EVAL_TURN_BUDGET` → `SessionConfig.evalTurnBudget`。
  - `test_completion_monitor.py`：`"reason: MAX_TURNS"` 测试改成 `"reason: ERROR"` + 信息包含 "eval turn budget" 校验。
  - yaml schema 不变。eval 复现性不变。
- **Deps**：task-9。

### task-11：`tests-update`
- **Scope**：`app/src/test/kotlin/ai/closepaw/{session,agent,app,ui,history,trace}` 下所有引用 `maxTurns`/`MAX_TURNS`/`MaxTurnsReached`/`COMPRESSION_DIGEST` 的 test。
- **Accept**：
  - 纯测 cap 的 test（`AgentRunLoopTest:194,206,194-220`、`CapsuleApprovalTransitionTest:209`、`SessionCheckpointConfigSnapshotTest`）删。
  - 用 `maxTurns = N` 当 mock-LLM 兜底的 test 改写：mock 显式终止（counted scripted client / N 次后明确发 `complete_task`）。
  - 新：`CompactorTest` 覆盖 cut-point edge case、CAS race、cancellation 传播、迭代上一 summary、超大 turn no-op。
  - 新：`HistoryManagerCasTest` 覆盖并发 supplement vs CAS。
  - 新：`TurnReactiveCompactionTest` 覆盖 prompt_too_long 重试路径。
  - 新：agent loop test —— recoverable error 可无限重试（没 `hasRemainingTurns` 闸）；compaction `Failed`×3 触发 Error 停止；`Stale` 不加 failure 计数。
  - Prompt 输出 test：observation 文本绝不包含 `"Turn N/M"` 或 `"FINAL TURN"`。
- **Deps**：task-9。

### task-12：`verify`
- **Scope**：`./gradlew clean assembleDebug lint test` + 一次手工 debug-run。
- **Accept**：全绿。手工 `scripts/debug-run.sh "Open Settings"` 无 max-turn UI/status 完成。临时把 `reserveTokens` 调高强制触发 compaction；观察 `📚 Compacted history` status event 且下一 turn 继续正常。
- **Deps**：task-1 到 task-11。

## 取舍（vs v1）

**为什么 CAS 不用粗粒度锁？**
粗 `Mutex` 包住 compaction 会让 supplement 注入在 LLM call 期间（2–10s）阻塞。用户打字 supplement 就是为了*现在*纠正 agent；让他等到 summarize 完违背了目的。CAS 20 LOC，能给我们 read-during-await 语义。

**为什么 `evalTurnBudget` 保留不全删？**
Eval 有合法需求兜跑飞循环成本（每个 eval task ~$10 天花板）。完全删了把它推给"外部监听 turn-started event + 发 Stop"也行，但 eval bridge 复杂度翻倍。5 行 nullable 旋钮换个名字（不再叫 `maxTurns`），更便宜，限定在它真正的用途，也不会泄漏进 prompt。

**为什么静态开销估算 + 反应式重试，而不是算完整 prompt size？**
算完整 prompt 要跑 `PromptBuilder`（它有自己的副作用：trace 记录、observation 构建），就为了数 token。每 turn 跑两次（估算一次、真发一次）很浪费。测量的静态开销天花板 + 24K reserve 覆盖常见情况；反应式重试覆盖漂移。总成本：~30 LOC catch-and-retry vs ~150 LOC prompt-size-estimator 接线。

**为什么 `HistoryConfig` 保留 `recentWindowSize` 和 `recentFullScreens`？**
两个都在存活的子系统里用：`recentFullScreens` 控制 downgrade 之前多少 screen 保持完整保真（Layer 0 防御）；`recentWindowSize` 测试和无碰尾巴逻辑都读，cut-point 搜索里也可能复用。留着不贵，删它是 yak-shaving。

**为什么 per-Agent Compactor 不是 session 级？**
`SubAgentRunner` 每个 child 新 `HistoryManager`，可能用 `subagentModel`（不同 `contextWindow`、不同 `LLMClient`）。Session 级 compactor 要么压错 history 要么用错 model。Per-Agent 结构上正确，每个 subagent 多一个 `Compactor` 实例（subagent 反正都短命）。

**为什么 summary prompt 里去掉 `## Goal`？**
当前 goal 由 agent loop 每次 compaction 规范化重新注入。让 summarizer 同时重述会引入漂移（model 略改 goal 措辞，然后我们就一直保留这个改写版）。单一真相源：`AgentExecutionConfig.goal`。
