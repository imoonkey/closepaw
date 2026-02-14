# 0001 CLAUDE

## 背景

用户要求重启 align 流程。原因：上一轮 align 过快，缺乏足够细节来确信 bug 不会重现。本轮 Claude 先发，从零开始。

## 分析：上一版 design.md 的 gap

上一版 design.md（Section 1-10）在原则层面是正确的，但缺少以下关键细节：

1. **无 user flow 枚举。** 只有原则规则，没有逐状态逐按钮的完整陈列。无法直接转测试。
2. **无 bug 追溯。** round5 和 round6 共 15+ 个 bug，design.md 没有逐一映射到防护规则。
3. **缺 VD task completion 合约。** round5 #5.3（VD app launch 到真实屏幕）没有 explicit 禁止。
4. **缺 Compose Capsule 单组件约束。** round5 #1.5, #3.3（input dock 分离）没有 explicit 要求。
5. **缺 Supplement 零状态变化约束。** round6 #4（takeover+add note 导致 capsule 消失）的根因是 supplement 触发了 applyVisibility。
6. **缺 UserLocation 检测伪代码。** round6 #1, #3, #4 的共同根因是 VD Viewer 被当成 MAIN_APP。Section 6 只一句话，不够具体。
7. **缺 onIslandTapped 完整逻辑。** Section 2.2 有描述但不是可直接实现的伪代码。

## 本轮改动

### 新增文件

1. **`user_flow.md`**: 完整状态枚举 + 13 个 critical flow scenarios。
   - Part 1: 所有 (Platform, Location, Mode, ShowPref) 组合的组件状态表（A1, A2, B1, B2c, B2i, B3c, B3i）
   - Part 2: 13 个详细 step-by-step flow（F1-F13），重点覆盖所有历史 bug 场景
   - Part 3: Location 转换矩阵
   - Part 4: 15 条 Prohibited Behaviors

2. **`bug_prevention.md`**: 每个历史 bug → root cause → 防护规则 → 验证方法 → design ref。共覆盖 round5 的 12 个 bug + round6 的 4 个 bug。

### design.md 增量改动

新增 Section 11-17:
- S11: VD Task Completion Contract（禁止 VD→真实屏幕 app launch）
- S12: Compose Capsule 单组件约束（禁止分离 input dock）
- S13: Supplement 零状态变化约束（supplement 不改任何状态维度）
- S14: UserLocation 检测实现伪代码
- S15: onIslandTapped 完整逻辑伪代码
- S16: Prohibited Behaviors 汇总表
- S17: 待 Codex 确认的新增内容列表

## 仍待 Codex 确认

1. 新增 Section 11-16 是否有遗漏或不同意的点
2. `user_flow.md` Part 1 状态表是否完整
3. `user_flow.md` Part 2 的 13 个 flow scenario 是否有遗漏的关键场景
4. `bug_prevention.md` 的 bug 覆盖是否完整
5. Codex 版 user_flow/state_machine 中是否有 Claude 遗漏的设计点

## 投票

- `CLAUDE=CHANGES`
