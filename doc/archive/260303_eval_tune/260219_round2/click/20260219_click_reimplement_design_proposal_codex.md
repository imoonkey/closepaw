# Click Reimplement Design Proposal（Codex）

## 设计目标

你现在的问题本质是：click 逻辑可扩展但不可控，层间耦合导致“哪层错了”难定位。  
本方案目标是把 click 重构成 **清晰、短链路、可停机** 的执行模块。

### 必达目标

1. click 执行路径一眼可读（主路径 <= 3 步）。
2. 失败类型结构化（不是只有 no-op 文本）。
3. 重试可控（固定预算，超限即停止并上抛）。
4. 不破坏现有 `mobile_action` 外部接口（先兼容，再演进）。

### 非目标（本轮不做）

- 不改 `mobile_action` 为多个新 tool（可后续再拆）。
- 不引入纯视觉 VLM click 作为默认路径。
- 不重写平台层（`AccessibilityPlatform` / `VirtualDisplayPlatform` 继续复用）。

---

## 一、目标架构（从“多层隐式”到“单引擎显式”）

将现有 `ClickExecutor + TargetResolver + UiChangeDetector + Invocation glue` 收敛为一个入口：

- `ClickEngine.execute(request, preSnapshot, platform): ClickResult`

### 分层职责（建议）

1. **ClickEngine（编排）**
   - 只负责状态机与重试预算。
   - 不做复杂目标启发式。

2. **ClickTargetResolver（定位）**
   - 只做 selector -> candidate point(s)。
   - 返回带原因的解析结果，不直接“猜测重试策略”。

3. **ClickDispatcher（执行）**
   - 原子执行两种 primitive：
     - `TapAt(x,y)`（主路径）
     - `ClickNodeAt(x,y)`（兜底）

4. **ClickVerifier（验证）**
   - 统一做 post-capture 与状态变化判断。
   - 输出结构化 verdict（Changed / Unchanged / Unverifiable）。

---

## 二、简化后的执行状态机

固定 3 步（默认）：

1. **Resolve**
   - 解析 target，拿到 `primaryPoint` + `altPoint`（最多 2 个点）。
   - 若失败：立即返回 `ResolveFailed`。

2. **PrimaryTap**
   - 先执行 `TapAt(primaryPoint)`。
   - 验证 Changed -> 成功；Unchanged -> 进入 Step3。

3. **FallbackNodeClick 或 AltTap（二选一）**
   - 分支规则（明确、固定）：
     - 如果是语义 target（element/text）：`ClickNodeAt(primaryPoint)`
     - 如果是坐标 target：`TapAt(altPoint)`（若 alt 可用）
   - 再次验证；仍失败则终止并返回 `NoEffectAfterRetries`。

> 核心变化：不再做 re-resolve + jitter 扩散 + 12 次长链路。

---

## 三、失败类型模型（供上层策略消费）

新增结构化结果（示意）：

```kotlin
sealed interface ClickFailure {
  data class ResolveFailed(val reason: String): ClickFailure
  data class OutOfBounds(val x: Int, val y: Int): ClickFailure
  data class DispatchFailed(val step: String, val reason: String): ClickFailure
  data class NoEffectAfterRetries(val attempts: List<String>): ClickFailure
  data class CaptureFailed(val step: String, val reason: String): ClickFailure
}
```

上层可据此做策略切换（scroll/back/reopen），而不是继续重复 click。

---

## 四、与现有代码的映射与改造点

### 保留

- `MobileActionTool` 的外部 schema（兼容现有 prompt 和 eval）。
- `AndroidPlatform` 与 `UIAction` 原子语义。
- `ToolRouter`/`TurnExecutionPhaseRunner` 主流程。

### 重构

1. `tool/action/ClickExecutor.kt`
   - 改为调用 `ClickEngine`，删除 jitter/re-resolve 编排。

2. `tool/action/TargetResolver.kt`
   - 保留 element/text/coordinate 解析；
   - 缩减启发式候选点逻辑（只保留主点 + 备选点）。

3. `tool/action/UiChangeDetector.kt`
   - 保持三态接口，但把“判定说明”作为可选返回，写入 trail。

4. `tool/impl/MobileActionInvocation.kt`
   - 输出中透出结构化失败类型（便于 trace 与后续 policy）。

---

## 五、建议的渐进落地计划

### Phase 0（低风险准备）

- 增加 `ClickResult/ClickFailure` 数据结构；
- 保持旧逻辑不变，仅补结构化日志和 trace 字段。

### Phase 1（切主路径）

- click 默认改为：`TapAt -> verifier -> fallback once`；
- 开关控制：`click_engine_v2_enabled`（可灰度）。

### Phase 2（移除旧复杂分支）

- 删除 re-resolve + jitter + 12-attempt 逻辑；
- 清理与之绑定的描述文案和测试。

### Phase 3（策略联动）

- 在 turn policy 中加入“连续 `NoEffectAfterRetries` N 次 -> 禁止继续同类 click”。

---

## 六、测试与验证计划

### 单测（必须）

1. resolve 失败直接失败，不触发 platform action。
2. primary tap 成功即停止，不执行 fallback。
3. primary no-op + fallback 成功。
4. primary/fallback 都 no-op -> `NoEffectAfterRetries`。
5. capture 失败返回 `CaptureFailed`，不误报 success。

### 回归场景（对准 round2）

- `SimpleSmsSend`：重叠节点确认按钮。
- `FilesMoveFile`：底部边缘按钮 + 长按选择入口。
- `ExpenseAddSingle`：类目点击 no-op 后是否能及时切策略。

### 评估指标

- click 平均尝试次数（目标下降）。
- `tool_failures` 中 click 相关比例（目标下降）。
- `MaxTurnsReached` 里由 click no-op 导致的占比（目标下降）。

---

## 七、为什么这个方案会更“清晰简洁”

1. **少层级**：click runtime 从“多模块各自猜测”变成“单状态机决策”。
2. **少魔法**：固定重试预算，减少 jitter/re-resolve 的不可预测性。
3. **强可观测**：失败类型结构化后，调试和策略联动都更直接。
4. **低迁移风险**：对外 tool schema 不变，可分阶段替换。

---

## 结论

建议采用“**兼容外部接口 + 重写 click 内核状态机**”的路线。  
先把 click 从复杂启发式链路收敛为 3 步执行模型，再逐步把失败类型接入上层策略，这样能最快降低 bug 密度并提升可维护性。
