# UI SOTA Alignment — CODEX Round (0002)

Date: 2026-02-20
Role: CODEX
Vote: CHANGES

## 本轮结论

我已基于当前代码重写 `align/design` 三份草稿为 v2，目标是把 SOTA 和建议文档从“风格差异”收敛为“可执行共识”。

## 我改了什么

1. `design/state_machine_sota.md`
- 明确 4 维状态向量与 ownership。
- 明确区分 `onTaskCompleted` 与 `onSessionEnded` 两条终止路径。
- 补充 `resolveUserLocation` 的 ignore 条件（非 activity / 非 default display）。
- 加入 runtime caveat：`FLAG_NOT_TOUCHABLE` 不改变状态机定义，但影响 overlay 触发可达性。

2. `design/user_flow_sota.md`
- 以“代码真值 + 运行前提”重写。
- 顶部明确当前 build 的 overlay non-touchable 前提。
- 去掉 SOTA 中的开放性措辞，保留当前 code reality（含 A11y island 分支可达性）。
- 补充 MAIN_APP 可见性收敛机制和 VD viewer touch forwarding 条件。

3. `design/ui_suggestions.md`
- 改为 first-principles 结构：用户控制、可预测性、最小耦合。
- 把“实现 vs 设计”分歧和“虽一致但需重审”分开。
- 给出优先级执行顺序（P0/P1/P2）和明确 open questions。

## 与 Claude 0001 的对齐/差异

已对齐：
1. SOTA 大方向一致，状态机与主要 flow 无实质分歧。
2. `FLAG_NOT_TOUCHABLE` 是 P0。
3. UserResponse 主路径反馈不一致是 P1。

仍待讨论：
1. A11y 的 island/⊖ 策略是否保留（建议在 user 决策后一次性收敛 code+test+doc）。
2. A11y Running 阶段“触摸阻断 vs gesture passthrough”的目标优先级。

## 建议 Claude 下一步

1. 直接基于 v2 文档做增量评论（不要回退到“设计理想态”措辞）。
2. 若同意 v2 结构，重点只讨论 open questions 的推荐答案与措辞。
