status: draft

# Refactor Design (dispatchGesture assumed working)

## 1. Context

本设计基于前提：

1. `dispatchGesture` 在 eval 场景可稳定生效。
2. overlay touch flag 的修复由其他改动完成，不在本文范围内。
3. 当前目标是重做 action executor 设计，去掉历史补丁堆叠带来的分叉逻辑和重复代码。

输入依据：

1. `doc/todo/eval_tune/round4/debug5/dispatchGesture_flag_verification_claude.md`
2. 当前代码（`ClickExecutor` / `LongPressExecutor` / `ScrollExecutor` / `SwipeExecutor` / `TypeExecutor` / `TargetResolver`）
3. `sop/system_work.md` 设计原则（KISS、高可读、无向后兼容负担）

## 2. Design Goals

1. 保持简单，避免引入新的复杂框架层。
2. 在“node + gesture 双通道”的 action 上统一优先级策略与 fallback 语义。
3. 统一 target 解析模型，消除 scroll 特例解析分叉。
4. 统一成功/失败输出结构和 attempt trail 记录方式。
5. 不再引入 `UiChangeDetector` 作为执行成功判定。
6. 不考虑向后兼容旧分支，直接收敛到单一最新设计。

## 3. Current Pain Points (source-of-truth: code)

1. `ClickExecutor` 与 `LongPressExecutor` 存在大量重复逻辑（resolve、bounds check、attempt trail、post capture、warning 拼接）。
2. `ScrollExecutor` 自己维护 `resolveScrollArea()`，与 `TargetResolver` 分叉，target 语义不统一。
3. action 优先级定义分散在各 executor 内部，策略变更成本高且容易不一致。
4. post-action capture 的 delay/观测构建逻辑在多个 executor 重复实现。
5. 测试覆盖不均衡（scroll/swipe 缺少与 click/longpress 同等粒度的执行策略单测）。

## 4. Target Architecture (steady state)

保持 `MobileActionTool -> executor` 结构不变，但把“公共执行骨架”抽出来，executor 只表达动作语义。

### 4.1 New shared primitives

1. `ResolvedTarget`（新增）  
字段：`point`, `bounds`, `warnings`, `isSemantic`, `source`。  
用途：click/long_press/type/scroll 使用同一份解析结果。

2. `ActionPriorityPolicy`（新增）  
单一策略表，定义每种 action 的主路径与 fallback 路径。  
避免在 executor 内硬编码顺序。

3. `PostActionCapture`（新增）  
统一处理 `delay + captureScreen + buildObservation + captureWarning`。  
executor 不再重复写相同逻辑。

4. `AttemptTrailBuilder`（新增）  
统一 attempt trail 文案格式，减少分散字符串拼接。

### 4.2 Keep existing boundaries

1. 保留 `MobileActionTool` 参数校验职责。
2. 保留各 executor 文件（不做一次性大合并，避免重构爆炸）。
3. 保留 `AndroidPlatform.performAction()` 作为平台抽象边界。
4. 保留 `NodeActionPerformer` 和 `AccessibilityGestureInjector` 为底层执行实现。

## 5. Unified Action Priority (assume dispatchGesture works)

稳定态优先级定义如下：

1. `click`  
semantic target: `gesture_tap -> node_action_click`  
coordinate target: `gesture_tap` only

2. `long_press`  
semantic target: `gesture_long_press -> node_action_long_click`  
coordinate target: `gesture_long_press` only

3. `scroll`  
`gesture_swipe(direction) -> a11y_scroll(direction)`  
说明：在 dispatchGesture 可用前提下，scroll 与 click/long_press 一样先走 gesture，再 fallback node。
（Qi Note: scroll只做a11y scroll，不走gesture swipe。保证语义纯正。)

4. `swipe`  
`gesture_swipe` only

5. `type`  
`set_text_on_node -> tap_to_focus + set_text_on_focused`  
说明：`type` 的主语义是 node set text，不跟随 gesture-first 规则。

## 6. Target Resolution Redesign

### 6.1 TargetResolver single model

`TargetResolver` 升级后返回 `ResolvedTarget`：

1. 对 `element_index/text`：返回 `point + bounds + warnings + isSemantic=true`
2. 对 `x,y`：返回 `point + bounds=null + warnings + isSemantic=false`
3. 对缺失 snapshot 的语义 target：返回明确 `NotFound` reason（保持现有行为）

### 6.2 Scroll unification

`ScrollExecutor` 不再自己做 `resolveScrollArea()`。

1. 若传了 `element_index`，从 `ResolvedTarget.bounds` 生成 swipe 区域。
2. 若没传 target，用 display 全屏 bounds 作为默认区域。
3. 坐标和区域计算统一由 resolver + helper 提供，不再内嵌在 executor。

## 7. Executor Simplification Plan

### 7.1 ClickExecutor / LongPressExecutor

收敛为同构结构：

1. resolve target
2. bounds guard
3. 按 `ActionPriorityPolicy` 依次尝试 primary/fallback
4. 记录 attempt trail
5. success 时走 `PostActionCapture` 生成 observation
6. failure 时走统一失败文案 builder

### 7.2 ScrollExecutor

保持独立文件，但内部步骤与 click/longpress 对齐：

1. resolve area
2. primary gesture swipe
3. fallback a11y scroll
4. 统一 post-action capture

### 7.3 SwipeExecutor / TypeExecutor

1. `SwipeExecutor` 复用 `PostActionCapture` 和 `AttemptTrailBuilder`。
2. `TypeExecutor` 保持语义路径不变，但复用统一 capture/trail helper。

## 8. Deletions (intentional, no backward compatibility)

1. 删除 executor 内重复的 post-capture 私有实现。
2. 删除 scroll 自有 target area 解析分支（迁移到统一 resolver/helper）。
3. 删除分散的 attempt trail 字符串拼装逻辑。
4. 删除已被统一 helper 替代的重复 bounds/warning 拼接代码。

## 9. Stage Plan and TODOs

## Stage 1: Action Execution Refactor

### System-Step-1 Design TODO

1. 本文档作为 Stage 1 设计基线（`status: draft`）。
2. 等 master review 后按 review note 迭代（若有）。

### System-Step-2 Implementation TODO (not implemented in this turn)

1. 新增 `ResolvedTarget` 与 `ActionPriorityPolicy`。
2. 重构 `TargetResolver` 输出，覆盖 click/long_press/type/scroll 四类调用方。
3. 新增 `PostActionCapture`、`AttemptTrailBuilder`。
4. 改造 `ClickExecutor`、`LongPressExecutor`、`ScrollExecutor` 到统一骨架。
5. 改造 `SwipeExecutor`、`TypeExecutor` 复用公共 helper。
6. 清理重复私有函数和过时分支。

### System-Step-3 Verification TODO (not implemented in this turn)

1. 单测  
`ClickExecutorTest`、`LongPressExecutorTest` 更新并补充策略顺序断言。  
新增 `ScrollExecutorTest`、`SwipeExecutorTest`、`TypeExecutor` 关键路径断言。  
新增 `TargetResolver` bounds/source/isSemantic 覆盖。

2. Action-level 回归  
`scripts/action-test.sh` 覆盖 click/long_press/scroll/swipe，验证 primary/fallback 顺序与结果。

3. Eval 回归  
最小集：`SystemBrightnessMax,SystemBrightnessMin` 多轮。  
扩展集：core subset，确认无新增 regression。

4. 观测一致性  
检查 trace 中 `tool_result` 的 attempt trail 与策略矩阵一致。  
确认不再依赖 `UiChangeDetector` 做成功判定。

## 10. Risks and Guardrails

1. 风险：scroll 在不同 app 上 gesture 解释不一致。  
护栏：保留 a11y fallback，且失败文案必须明确主路径与 fallback 结果。

2. 风险：resolver 升级导致旧调用方断言失配。  
护栏：先补 resolver 单测，再迁移 executor。

3. 风险：重构后日志可读性下降。  
护栏：attempt trail 文案统一且固定格式，便于 grep 对比。

## 11. Final Design Decision

在“dispatchGesture 已可靠”的前提下，采用**gesture-first（适用于 click/long_press/scroll）+ node fallback** 的统一策略；保持 `type` 语义优先；通过最小新增公共组件收敛重复逻辑，不引入大而新的调度框架。
