# Round2 Click Execution 问题总结（Codex）

## 范围与结论

- 数据来源：
  - `doc/todo/eval_tune/round2/20260219_aw_subset_core_default_claude.md`
  - `doc/todo/eval_tune/round2/20260219_aw_subset_core_default_codex.md`
- 结论：Round2 的 click 失败不是单点 bug，而是「目标定位不稳 + 执行反馈弱 + 重试策略偏盲目」叠加导致。

## 主要问题簇（按对 click 的直接影响排序）

### 1) 遮挡/重叠节点导致点不中（高频阻塞）

- 症状：同一区域存在多个可点击节点（父子重叠），模型点一个报 occluded，换另一个又 no-op。
- 典型任务：`SimpleSmsSend`、`ExpenseAddSingle`。
- 直接后果：重复点击同一局部区域，无法收敛，最终 `MaxTurnsReached`。

### 2) 点击返回成功但 UI 无变化（no-op）

- 症状：`ACTION_CLICK` 或 gesture tap 已 dispatch，但连续多次“no UI change”。
- 典型任务：`FilesMoveFile`、`ExpenseAddSingle`。
- 直接后果：系统把“可执行”误当“有效执行”，进入重试循环。

### 3) 边缘区域控件点击不稳定（底部按钮）

- 症状：元素中心点贴近屏幕边界（尤其底部），`ACTION_CLICK returned false` 或 tap 无效。
- 典型任务：`FilesMoveFile` 的 “Just once / Always”。
- 直接后果：卡在系统对话框，无法前进。

### 4) long_press 与 click 组合失败（选择类 UI）

- 症状：节点在 a11y 树里 `long_clickable=false`，但真实 UI 需长按选中；fallback 仍 no-op。
- 典型任务：`FilesMoveFile`。
- 直接后果：无法进入“已选择”状态，后续 move 路径全部失效。

### 5) 失败后策略切换不足，重复动作过多

- 症状：相同 click/long_press 序列重复 2-3 轮以上，缺少“终止并换策略”。
- 典型任务：`SimpleSmsSend`、`ExpenseAddSingle`。
- 直接后果：turn 被重试吃光，放大 click 子系统的小错误。

## 根因归纳（聚焦 click execution）

1. **目标解析层过度依赖局部启发式**  
   遮挡判定、候选点选择、重试抖动都在 click 链里，但缺少可解释的“为何换点/为何继续”判据。

2. **执行成功判定过于粗粒度**  
   当前主要靠“UI 指纹是否变化”；对「点击有效但页面轻微变化」和「点击无效但有噪声变化」区分不足。

3. **重试预算较大且策略单一**  
   多次尝试基本是同一目标附近点击，少量抖动并不能覆盖语义级替代路径。

4. **边界条件没有前置防护**  
   边缘元素、重叠元素、系统弹窗这类高风险目标在点击前缺少专门分支。

## 对后续重构的要求（从 round2 反推）

- 把 click 从“多层隐式回退”改为“少步显式状态机”。
- 先定义“可停止条件”：连续 no-op 到阈值即停止，并把失败原因结构化返回给上层策略。
- 对高风险目标（边缘、重叠、长按依赖）做前置分流，不要靠统一重试兜底。
- 缩短尝试链路，宁可早失败 + 上层换策略，也不要 10+ 次局部点击。
