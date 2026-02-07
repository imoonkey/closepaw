# Perception + Targeting 分析（Codex）

日期：2026-02-07
数据源：`debug-output/run_*`（共 24 个 run，380 份 raw tree + 380 份 sanitized tree）

## 1. 关键统计

### 1.1 Raw a11y tree（`viewIdResourceName`）
- 节点总数：23128
- `viewIdResourceName` 非空：983（4.2%）
- 包分布不均匀：YouTube / SystemUI 基本为 0；Notion、Gmail 有一定覆盖。

### 1.2 Processed/sanitized tree（原 `resource_id`）
- 元素总数：10791
- `resource_id` 非空：858（8.0%）
- 至少含一个非空 `resource_id` 的快照：82/380（21.6%）

### 1.3 mobile_action selector 实际使用（trace 中 LLM tool calls）
- `click/type/long_press` 共 75 次：
  - `element_index`: 52
  - `text`: 16
  - `coordinates`: 4
  - `resource_id`: 1
- 发现失败模式：模型会产出 `desc` / `target_text`，但当前 click schema 不接受，导致校验失败。

## 2. 结论

1. `resource_id` 在树里不是“完全不存在”，但在当前任务分布中利用率很低，且模型几乎不使用。
2. 现阶段收益更高的优化是：
   - 收敛字段语义（避免 `text`/`desc` 双轨）；
   - 消除 prompt 与 tool schema 不一致（尤其 `desc` 漂移）；
   - 压缩无信息字段（false flags、`enabled=true`）。

## 3. 本次落地改动（按你的要求）

1. perception 输出（sanitized）
- 删除字段：`resource_id`, `resource_id_index`, `desc`, `desc_index`, `enabled`
- `text` 合并策略：`text = original_text.ifBlank { description }`
- `clickable`/`editable`/`scrollable`：仅 `true` 时输出，`false` 时省略

2. targeting prompt
- prompt 中不再引导使用 `desc`

3. targeting 参数
- `mobile_action` 参数移除：`resource_id`, `resource_id_index`

4. 代码一致性
- 同步移除 handler/invocation 中 `resource_id` 选择分支
- selector fallback 统一为：`bounds -> coordinates -> text -> element_index`

## 4. 风险与后续

- 风险：对依赖 `resource_id` 的少量场景（尤其 Notion 类）会失去一条 selector 通道。
- 你后续扩展 test 类型后，可按同样流程重跑统计，再决定是否恢复 `resource_id`。
