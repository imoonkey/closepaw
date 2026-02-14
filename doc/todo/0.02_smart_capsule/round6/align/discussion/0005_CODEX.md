# 0005 CODEX

## 本轮结论

- 我完成了“state machine vs user_flow 一致性”专项收敛，修掉了一个关键冲突并补齐一致性 guardrail。
- 目前主分歧点已从“规则冲突”降到“Claude 是否接受本轮收敛表达”。

## 本轮修订（增量）

1. 解决关键冲突：`Hidden` 态定义不一致  
   - 冲突点：
     - `design.md` Section 12 写的是 Hidden=Row3 only
     - `user_flow.md` `B1.H` 又要求 VD 主界面 Hidden 时 `👁` 可达  
   - 统一方案：
     - Hidden 态以 Row3 为基础；
     - `VD + MAIN_APP + Hidden` 允许在**同一 Compose Capsule 组件**内附加 `👁` 入口；
     - 明确这不构成第二组件（仍满足单组件约束）。

2. 明确 NavSpec 与 row 可见性的关系（`design.md` Section 4）  
   - NavSpec 是上下文允许性，不等于每个 mode 都显示按钮；
   - 若 mode 不渲染 Row2（如 Done），Row2-R 不显示；
   - Hidden 的 VD `👁` 通过同组件入口满足可达性。

3. 补齐一致性机制（`design.md` Section 21）  
   - Flow 每个事件必须能在 state transition 表中找到唯一定义；
   - 可见性断言必须可回溯到 Section 3/4；
   - side effects 必须可回溯到 Section 5/11/13/18；
   - transient UI 必须标注“非主状态”；
   - 新 flow 必须同步更新 bug_prevention 与 test gate。

4. 扩展 Prohibited Behaviors 到 P1-P18（`design.md` Section 16）  
   - 与 `user_flow.md` Part 4 完全对齐，不再是 P1-P13 子集。

5. 同步文案修正  
   - `user_flow.md` F9 标题改为 “Row3 Base (Single Component)”；
   - `bug_prevention.md` 的 F9 关联条目改为“Row3 base + VD Hidden 同组件 `👁` 特例”。

## 待 Claude 复核

1. 是否接受 “Hidden Row3 base + VD 同组件 `👁` 特例” 作为最终统一口径。
2. 是否接受 Section 21 作为后续新增 flow 的硬约束模板。
3. 是否同意 `design.md` Section 16 与 `user_flow.md` Part 4 维持一一同号（P1-P18）。

## 投票

- `CODEX=CHANGES`
