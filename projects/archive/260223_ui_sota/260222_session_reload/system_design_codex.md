status: draft

# System Design (Codex): Session Reload + Task Lifecycle

## 1) 目标与边界

本设计只覆盖 `problem_statement.md` 定义的 in-scope：
- session death 后 follow-up 可继续
- LLM 上下文 100% 准确重建（追求 byte-identical input prefix）
- task 完成后释放重资源，按需重建
- Agent/PromptBuilder 对 reload 透明

明确不做：
- 旧 session 文件迁移（无 runtime context 的历史文件直接视为不可 reload）
- 为兼容旧架构增加复杂分支

## 2) 先给结论（设计决策）

1. **LLM context 必须有独立持久化模型**，不能从 UI message/action card 反推。  
2. **单一事实源（single source of truth）是 Runtime Conversation Snapshot**；UI `SessionRecord.messages` 只是展示投影。  
3. **Function call 参数按 raw JSON string 持久化**，不依赖 `JSONObject.toString()` 重建，避免 key 顺序/序列化差异导致 cache miss。  
4. **Session 分层：Conversation State（可持久化） vs Task Runtime（可释放）**。task 完成后释放 platform/llm/tool runtime，只保留可恢复状态。  
5. **继续同一会话时配置冻结**（model/perception/agentMode 等来自会话快照），防止“同历史不同配置”破坏 prompt 一致性。  

## 3) 当前实现的关键差距

- `HistoryManager`（`ResponseItem` 列表）只在内存；进程/服务死亡后丢失。  
- `SessionRecordingService` 存的是 `MessageRecord`（text/action blocks）+ `ScreenStateRecord`，缺少：
  - function call 完整 arguments
  - function call output 完整语义边界（仅 UI summary）
  - history item 的原始顺序语义（尤其 tool call 是否独立 item）
- `TodoState`/`ScratchpadState` 没有 disk persistence。  
- `TaskCompleted` 后 session 仍持有 `platform + llm + tools`，生命周期偏“热驻留”，不符合资源释放目标。  

## 4) 目标架构（KISS 版）

```
Session File (single json)
├─ uiRecord         (existing: messages/screenStates/summary)
└─ runtimeSnapshot  (new: authoritative for reload)
```

核心思想：**一个文件，两类视图**；写入时并行维护，读取 reload 只认 `runtimeSnapshot`。

### 4.1 新增持久化模型

```kotlin
@Serializable
data class SessionRuntimeSnapshot(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val conversationConfig: ConversationConfigSnapshot,
    val historyItems: List<PersistedHistoryItem>,   // ordered, append semantics
    val todos: List<TodoSnapshot>,
    val scratchpad: Map<String, String>,
    val checkpointState: CheckpointState,           // IDLE_READY / RUNNING_DIRTY / CLOSED
    val lastCheckpointAt: Long
)

@Serializable
sealed interface PersistedHistoryItem {
    @Serializable data class Message(
        val role: String,
        val content: String,
        val name: String? = null,
        val isScreenObservation: Boolean = false
    ) : PersistedHistoryItem

    @Serializable data class FunctionCall(
        val id: String,
        val name: String,
        val argumentsRawJson: String   // canonical source for replay
    ) : PersistedHistoryItem

    @Serializable data class FunctionCallOutput(
        val callId: String,
        val content: String,
        val success: Boolean = true,
        val truncated: Boolean = false
    ) : PersistedHistoryItem
}
```

### 4.2 为什么不用 “SessionRecord.messages -> converter -> HistoryManager”

- `MessageRecord.Agent.contentBlocks` 是 UI 结构，不是 LLM 语义结构。  
- 工具调用在 UI 里是 action card；在 LLM history 里应是 `function_call` 独立 item。  
- 反推会引入歧义（例如 tool call 与文本的边界、参数全量 JSON、截断语义），无法保证 byte-identical。  

结论：**允许 runtime -> UI 的单向投影，不允许 UI -> runtime 反向还原作为主路径**。

## 5) 写入策略（runtime 持久化）

### 5.1 写入触发点

- `HistoryManager` 发生变更（`addItem` / `recordItems` / `compress` / `clear`）  
- `TodoState.update/clear`
- `ScratchpadState.write/delete/clear`
- `TaskCompleted` / `SessionCompleted`（强制立即 flush）

### 5.2 I/O 策略

- 日常：debounce 写盘（沿用当前 500ms 思路）  
- 关键节点：同步 flush（task 完成、shutdown）  
- 写盘采用**临时文件 + rename 原子替换**，避免崩溃产生半文件。  

### 5.3 关于 JSON canonicalization

对 `function_call.arguments`：
- runtime 内部仍可保留 `JSONObject` 供执行路径使用  
- 持久化与 replay 一律使用 `argumentsRawJson`  
- `PromptBuilder` 生成 `function_call` input 时优先使用 raw string  

这一步是“cache 命中稳定性”的关键，不可省略。

## 6) Reload 路径设计

### 6.1 触发场景

- 当前无活跃 `AgentSession`，用户发送新输入  
- 用户主动选择某个可恢复 session 并继续对话  

### 6.2 Reload 流程

1. 读取目标 `SessionRuntimeSnapshot`  
2. 校验 `checkpointState == IDLE_READY` 且 schema/version 有效  
3. 用 `conversationConfig` 创建新的 `AgentSession`（同会话配置）  
4. hydrate：
   - `HistoryManager <- historyItems`（严格原顺序）
   - `TodoState <- todos`
   - `ScratchpadState <- scratchpad`
5. 再处理新的 `Op.UserInput`，进入下一 task  

对 `Agent` / `PromptBuilder` 而言，这和“从未中断”的内存 session 无差别。

## 7) Task 生命周期重构（资源释放）

引入逻辑分层：

- **Conversation State（冷状态）**  
  `history + todos + scratchpad + config + checkpoint meta`  
- **Task Runtime（热状态）**  
  `platform + llm client + tool router/registry + runner`

状态机（简化）：

```
Created -> RunningTask -> IdleCheckpointed -> RunningTask -> ... -> Closed
```

在 `TaskCompleted`：
1. flush runtime snapshot  
2. 标记 `IDLE_READY`  
3. 释放 Task Runtime 重资源  

下一次 `UserInput` 再按需重建 Task Runtime。

## 8) 100% 重现的硬性不变量

1. **顺序不变**：`historyItems` 顺序与原运行一致。  
2. **边界不变**：message/function_call/function_call_output 的 item 边界不合并不重排。  
3. **参数原文不变**：function_call 参数用 raw JSON 字符串。  
4. **输出文本不变**：function_call_output content 原文保留（含 truncation 标记文本）。  
5. **配置不漂移**：继续会话使用冻结配置快照。  
6. **PromptBuilder 路径单一**：reload 与热态走同一构建路径。  

建议增加一个 lightweight 校验：记录 `llm_input_items` 序列化后的 hash（仅用于 debug 断言，不参与业务逻辑）。

## 9) 失败与降级策略

- snapshot 缺失或损坏：该 session 标记为“仅可浏览，不可继续”，不做模糊重建。  
- schemaVersion 不支持：直接 fail-fast，提示升级/放弃 reload。  
- flush 失败：保守策略是不释放 runtime（避免产生“以为可恢复但不可恢复”的状态）。  

## 10) 对现有模块的改动面（实现导向）

- `history/model/SessionRecord.kt`：新增 `runtimeSnapshot` 字段  
- `history/`：新增 runtime snapshot serializer/hydrator  
- `HistoryManager`：暴露 snapshot 导出/导入接口（或 observer 回调）  
- `TodoState` / `ScratchpadState`：增加 snapshot 导出导入  
- `PromptBuilder`：function_call arguments 使用 raw string 路径  
- `AgentSession` / `SessionServices`：Task Runtime 延迟重建与 task 后释放  
- `MainActivity`/session 创建入口：支持“无 active session 时自动 reload 可恢复会话”  

## 11) 分阶段计划（仅设计，不实现）

### Stage A: 数据模型与持久化基础
- 定义 `SessionRuntimeSnapshot` 与 `PersistedHistoryItem`
- 扩展 `SessionRecord` 并实现原子写
- 打通 history/todo/scratchpad snapshot 导出导入

### Stage B: 运行时写入与恢复链路
- 在变更点挂接 checkpoint 写入
- 实现 reload coordinator（load -> validate -> hydrate）
- 增加失败降级路径与可观测日志

### Stage C: 生命周期瘦身
- 拆分 Conversation State / Task Runtime
- Task 完成后释放重资源，follow-up 时按需重建
- 完整回归（含 process death / service restart）

## 12) 验证方案（实现后）

- **单元测试**
  - `PersistedHistoryItem <-> ResponseItem` 双向无损
  - function_call raw arguments 不被重排
  - todo/scratchpad snapshot 精确恢复
- **集成测试**
  - 跑到 task 完成 -> 销毁 session -> reload -> follow-up，校验 `PromptBuilder` 输入序列一致
  - supplement / tool-call-heavy / screen-observation-heavy 场景
- **端到端**
  - debug-run 中断进程后继续对话
  - 对比 `llm_input_items` 序列化输出一致性（prefix）

---

这个方案保持 KISS：**不引入重型 event sourcing，不靠 UI 反推语义，不做历史兼容包袱**。核心是把“LLM 真正读取的结构”作为第一公民持久化，生命周期上把“可恢复状态”和“重资源运行态”彻底分开。  
