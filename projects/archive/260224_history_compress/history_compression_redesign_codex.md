# History Compression Redesign (Codex)

## 0. 结论先说
当前 `HistoryManager.compress()` 的核心问题不是“压得不够狠”，而是**压缩单元错了**：它按 item 删，不按“用户意图回合”删。

要修的不是一个 if，而是模型：
- 把“用户真实输入”变成不可丢失的锚点（anchor）。
- 把屏幕、工具输出、assistant 长文本都当成可压缩证据（evidence）。
- 压缩永远围绕 `anchor + evidence` 做，不再直接在原始 item 列表上盲删。

---

## 1. 参考仓库分析（.reference）

### 1.1 Coding Agent 侧

#### A) Codex (`.reference/code_agent/codex`)
- 关键点：
  - `context_manager/history.rs` 先做 normalize（call/output 成对、orphan 清理）。
  - `is_user_turn_boundary()` 区分“真实用户回合” vs 会话前缀注入（`<environment_context>`, `<user_shell_command>` 等）。
  - `compact.rs` 做的是“重写历史”：保留用户信息 + 放入 compaction summary，而不是随便删中间 item。
- 启发：
  - **边界定义是第一性原理**，没有边界就没有正确压缩。
  - 压缩后历史必须仍是合法结构，不靠运气。

#### B) Gemini CLI (`.reference/code_agent/gemini-cli`)
- 关键点：
  - `chatCompressionService.ts` 是分阶段压缩：先截断大 tool output，再在用户边界处分割“压缩段/保留段”。
  - 对压缩结果有二次校验（verification pass）；失败时走截断降级路径。
- 启发：
  - 先做 deterministic 降噪，再做语义摘要，鲁棒性更高。
  - 必须有失败降级路径，不能把压缩当“永远成功”。

#### C) OpenHands (`.reference/code_agent/OpenHands`)
- 关键点：
  - condenser 是可组合 pipeline。
  - `conversation_window_condenser`：保留关键前缀 + 最近窗口 + 清理 dangling 观测。
  - `llm_summarizing_condenser`：把被遗忘段落变成一个 summary event，不是静默删除。
- 启发：
  - 压缩应当显式产生“我删了什么”的表示（summary/digest），而不是无痕抹掉。

#### D) Cline (`.reference/code_agent/cline`)
- 关键点：
  - 支持 condense 后维护 tool_use/tool_result 结构一致性。
  - 维护 truncation range，并在上下文里插入“已截断”提示。
- 启发：
  - 结构不变式和可解释性（truncation notice）必须同时存在。

---

### 1.2 Mobile Agent 侧

#### A) MAI-UI (`.reference/mobile_agent/MAI-UI`)
- 策略：固定 `history_n` 图像窗口；历史动作文本都保留。
- 特点：简单、稳定、无复杂压缩器；主要是“图像窗口裁剪”。

#### B) MobileAgent v3.5 (`.reference/mobile_agent/MobileAgent/Mobile-Agent-v3.5`)
- 策略：老步骤只保留文本动作摘要，图片只保留最近 `history_n`。
- 特点：把最贵的模态（图片）先砍掉。

#### C) DroidRun (`.reference/mobile_agent/droidrun`)
- 策略：
  - CodeAct/FastAgent 直接 `limit_history()`（保首条 + 最近尾部）。
  - Stateless manager 不带完整历史，重建 prompt 时只用 `progress_summary + action_history[-k] + memory`。
- 特点：把“历史”变成 compact state，而不是永远追加聊天日志。

#### D) MiniTap (`.reference/mobile_agent/minitap-mobile-use`)
- 策略：超过阈值后删除旧消息（`RemoveMessage`）。
- 特点：极简但偏粗糙，语义保持弱。

#### E) AutoDevice M3A (`.reference/mobile_agent/autodevice_android_world`)
- 策略：每步都生成 `summary`，后续决策喂 summary history。
- 特点：语义压缩早做了，但历史本身可无界增长。

---

### 1.3 提炼出的共识
1. 先定义“真实用户回合边界”，再谈压缩。
2. 先砍贵模态（screen/tool output），再动语义骨架。
3. 压缩应保留“被压缩痕迹”（digest/summary），不能静默丢失。
4. 结构不变式（tool call-output 成对）要硬保证。

---

## 2. 现有实现的根问题（你项目）

当前 `HistoryManager` 的问题不在一条 while，而在模型表达：
- `ResponseItem.Message(role="user")` 同时承载：
  - 真实用户意图（goal/supplement/correction）
  - 屏幕观测（`isScreenObservation=true`）
  - 其他会话注入文本
- 这导致压缩策略只能用 role 做粗分：不是过保留，就是误删除。
- 压缩按 item 执行，而不是按回合块执行，容易破坏语义连贯性。
- `PromptBuilder` 还有一套屏幕压缩逻辑，和 `HistoryManager` 双轨，容易漂移。

---

## 3. 新设计（First Principles + KISS）

### 3.1 非协商不变式（必须满足）
1. `USER_INTENT` 永不删除（Goal、Supplement、Correction）。
2. 压缩后历史必须可归一化为合法序列（call/output 配对完整）。
3. 屏幕观测永远是首要压缩目标。
4. 压缩是确定性的（同输入同输出），便于复现和测试。
5. 无法达到预算时必须显式返回 `BudgetUnreachable`，不能假装成功。

### 3.2 数据模型（建议直接替换，放弃兼容）

在 `ResponseItem.Message` 增加强类型分类，避免 role-string 猜测：

- `MessageKind.USER_INTENT`
- `MessageKind.USER_CONTEXT`（环境/系统注入）
- `MessageKind.SCREEN_OBSERVATION`
- `MessageKind.ASSISTANT_TEXT`
- `MessageKind.COMPRESSION_DIGEST`

并新增：
- `anchorId: String?`（仅 `USER_INTENT` 需要）
- `turnId: String?`（用于回合归组）

这样 `dropLastNUserTurns()` 和 `compress()` 都按 `USER_INTENT` 算回合边界，不再靠 `role == "user"`。

### 3.3 压缩单元：TurnBlock

压缩前先把线性 items 归组为 `TurnBlock`：
- `anchor`: 一个 `USER_INTENT`
- `evidence`: 直到下一个 `USER_INTENT` 之前的全部 items（screen/tool/assistant/context）

之后的所有压缩都在 block 上做，不在全局 item 上盲删。

### 3.4 压缩算法（3阶段）

#### Phase 0: Normalize
- 先执行结构归一化：补缺失 output、清 orphan output、配对校验。

#### Phase 1: Lossless-ish Shrink（先砍贵数据）
- 对旧 `SCREEN_OBSERVATION` 降级为 one-line summary（保留最近 `recentFullScreenTurns` 原文）。
- 对旧 `FunctionCallOutput` 应用分级截断（`CONSERVATIVE -> AGGRESSIVE -> MINIMAL`）。
- 目标：尽量不动语义骨架，只降 payload。

#### Phase 2: Turn Digest（语义压缩）
- 从最老 `TurnBlock` 开始，把 `evidence` 替换为 1 条 `COMPRESSION_DIGEST`（assistant role）。
- `anchor` 永远保留原文。
- digest 模板固定：
  - 用户意图
  - 关键动作/结果
  - 失败原因（如有）
  - 当前约束（如有）

#### Phase 3: Hard Guard
- 若仍超预算：合并最老多个 digest 为一个更短 digest。
- 若再超预算且只剩 `USER_INTENT + digest`：返回 `BudgetUnreachable`（由上层决定提醒用户新开会话）。

### 3.5 单一压缩职责
- `PromptBuilder` 不再做历史压缩（删 `compressOldScreenObservations()` 逻辑）。
- 压缩只在 `HistoryManager` 发生，`forPrompt()` 只读最终结果。

---

## 4. 代码落地方案（按你当前包结构）

### 4.1 主要改动文件
- `history/ResponseItem.kt`
  - 引入 `MessageKind`、`turnId`、`anchorId`。
- `history/HistoryManager.kt`
  - 新增 `buildTurnBlocks()`、`compressPhase1()`、`compressPhase2()`、`compressPhase3()`。
  - `dropLastNUserTurns()` 改为基于 `MessageKind.USER_INTENT`。
  - `compress()` 返回 `CompressionResult`（`Compressed | Noop | BudgetUnreachable`）。
- `agent/TurnPlanningPhaseRunner.kt`
  - `recordScreenObservation()` 写入 `MessageKind.SCREEN_OBSERVATION`。
- `session/AgentSession.kt`、`agent/Agent.kt`
  - goal/supplement 写入 `MessageKind.USER_INTENT`。
- `agent/cognition/prompt/PromptBuilder.kt`
  - 删除历史压缩职责，仅读 history。

### 4.2 废弃项
- `ResponseItem.Message.isScreenObservation`（由 `MessageKind` 替代）
- `PromptBuilder.compressOldScreenObservations()`

---

## 5. 必测用例（P0）

1. 任意预算下，`USER_INTENT` 不丢失。
2. 压缩后 call/output 始终成对。
3. 屏幕观测优先降级，且最近 N 屏保持原文。
4. 回滚 `dropLastNUserTurns(n)` 只按 `USER_INTENT` 计数，不误伤 context/screen。
5. 连续多次 `compress()` 幂等（第二次基本 Noop）。
6. 预算无法满足时返回 `BudgetUnreachable`。

---

## 6. 为什么这版更简单

- 只有一个核心抽象：`TurnBlock(anchor + evidence)`。
- 只有一条规则：`anchor` 不可删，`evidence` 可压。
- 只有一个压缩入口：`HistoryManager.compress()`。

这比“到处加特判保 user message”更短、更稳、更可读。
