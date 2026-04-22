# Design Review: mobile_action 重构方案

评审范围：
- `doc/todo/click/overall_redesign_codex.md`
- `doc/todo/click/mobile_action_architecture_v2.md`

评审原则：仅评价系统设计侧面（职责边界、正确性、鲁棒性、可测试性、演进与迁移），不评价写作表达。

## 1) Review: `overall_redesign_codex.md`

### High
1. 成功判定与 fallback 的组合有重复触发风险。`click/long_press` 默认要求可观察 UI 变化（`doc/todo/click/overall_redesign_codex.md:241`），若首次原子动作“已生效但无显式 UI 变化”会继续下一次尝试（`doc/todo/click/overall_redesign_codex.md:197`、`doc/todo/click/overall_redesign_codex.md:200`），可能造成双击式副作用（如点赞/开关被反向切换）。
2. 全量切断旧 schema/调用链，迁移风险较高。文档明确不做兼容（`doc/todo/click/overall_redesign_codex.md:10`、`doc/todo/click/overall_redesign_codex.md:339`），但未给灰度策略、开关、回滚路径，系统级上线风险偏高。

### Medium
1. `NodeLocator` 仅用 `pathFromRoot + fingerprint`（`doc/todo/click/overall_redesign_codex.md:259`、`doc/todo/click/overall_redesign_codex.md:269`）在动态列表/懒加载场景可能漂移，需补充“锚点重定位 + 相似度匹配 + 置信度阈值”机制。
2. 明确禁止 target 类型间切换（`doc/todo/click/overall_redesign_codex.md:192`）提升可解释性，但对弱感知场景会降低恢复率；建议设计可控策略位（例如 strict/assist 模式）。
3. `success contract` 定义了 `unverifiable_success`（`doc/todo/click/overall_redesign_codex.md:243`），但未定义该状态在上层 planner 的后续动作策略（继续/重试/请求确认）。

### 评分（系统设计维度）
- 职责边界清晰度：`9.2/10`
- 正确性与安全性：`7.3/10`
- 运行时鲁棒性：`7.1/10`
- 可测试性：`8.8/10`
- 可演进性：`8.5/10`
- 迁移可控性：`6.6/10`

**综合评分：`7.9/10`**

结论：`CHANGES_REQUESTED`（主要是成功判定与迁移策略两个高风险点）。

---

## 2) Review: `mobile_action_architecture_v2.md`

### Critical
1. `UiChangeDetector` 在 pre/post 任一缺失时直接返回 changed=true（`doc/todo/click/mobile_action_architecture_v2.md:690`），会把“不可验证”当“成功”，导致自治代理产生系统性假阳性。

### High
1. 明确存在“成功后再尝试”路径：`ACTION_CLICK` 成功但无 UI 变化会继续 gesture tap（`doc/todo/click/mobile_action_architecture_v2.md:290`、`doc/todo/click/mobile_action_architecture_v2.md:295`，实现示意见 `doc/todo/click/mobile_action_architecture_v2.md:474`、`doc/todo/click/mobile_action_architecture_v2.md:495`），有重复副作用风险。
2. target 解析主要退化为坐标（`doc/todo/click/mobile_action_architecture_v2.md:275`、`doc/todo/click/mobile_action_architecture_v2.md:505`），缺少节点级重定位语义，快变 UI 下误点风险高于节点优先策略。
3. 删除 `ElementNotFound` 等结构化错误类型（`doc/todo/click/mobile_action_architecture_v2.md:232`、`doc/todo/click/mobile_action_architecture_v2.md:235`）后，上层难以做策略化恢复（如“刷新感知后重试 element_index”）。

### Medium
1. `Target` 注释中的“优先级顺序”与“单 target 强约束”并存（`doc/todo/click/mobile_action_architecture_v2.md:148` + `doc/todo/click/mobile_action_architecture_v2.md:538`），概念上存在冲突，建议统一语义。
2. 迁移阶段删除范围大（`doc/todo/click/mobile_action_architecture_v2.md:829` 至 `doc/todo/click/mobile_action_architecture_v2.md:838`），虽有 phase 划分，但缺少 feature flag 保护与双路观测指标定义。

### 评分（系统设计维度）
- 职责边界清晰度：`8.4/10`
- 正确性与安全性：`6.1/10`
- 运行时鲁棒性：`6.2/10`
- 可测试性：`8.3/10`
- 可演进性：`7.6/10`
- 迁移可控性：`7.2/10`

**综合评分：`7.3/10`**

结论：`CHANGES_REQUESTED`（至少先修复 1 个 Critical + 2 个 High 再进入实现）。

---

## 3) 总体建议（仅设计层）

1. 以 `overall_redesign_codex` 为主架构基线（边界更干净、节点语义更强），吸收 `architecture_v2` 的分阶段迁移与执行器拆分思路。
2. 在两版设计中统一修复两类共性高风险：
   - 禁止“dispatch 成功但无 UI change 时自动二次触发”
   - 将 `unverifiable` 从“成功”改为独立状态，并定义上层策略
3. 增加 rollout 设计：feature flag、A/B 指标（误点率/二次触发率/失败恢复率）、回滚路径。
