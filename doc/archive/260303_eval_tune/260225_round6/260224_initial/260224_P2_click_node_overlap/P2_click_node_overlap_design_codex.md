# P2 Click Node Overlap - Fix Design (Codex)

## 1. 问题复述

当前 `click(element_index=...)` 的执行链路是：

1. `TargetResolver` 用 snapshot 元素中心点得到 `(x, y)`
2. `node_action_click` 在**实时树**里按坐标做 DFS（逆序 child）找“最上层 clickable”
3. 直接 `ACTION_CLICK`，返回 `true` 即判定成功

在存在 bounds overlap 的页面里，步骤 2 会找到与目标元素不同的 clickable 节点，导致“错误点击 + 成功上报 + 不触发 gesture fallback”。

本质问题：**语义目标（element_index）在 action 层被降级成纯坐标，丢失目标身份信息**。

## 2. 设计目标

1. 对 `element_index/text` 这类语义目标，优先点击“与感知目标一致”的节点，而不是仅按 z-order 命中。
2. 当无法可靠确认节点身份时，必须失败并触发 `gesture_tap` fallback，禁止“误点但 Success”。
3. 对 `coordinate` 目标保持现有行为（坐标即意图，不做语义约束）。
4. 变更尽量局部，优先复用现有 `PointActionExecutorCore` fallback 机制。

## 3. 非目标

1. 不改动 Perceptor 的索引排序策略。
2. 不在本次引入 post-action 语义验证（那是 P0/P1 方向）。
3. 不追求 100% 还原视觉命中，仅保证“不会把明显错误节点当成功”。

## 4. 方案选型

### 方案 A：继续按坐标取 top-most，点击后做弱校验

- 做法：保留当前 finder，点击后仅看“是否 UI 变化”。
- 问题：错误点击通常也会引起 UI 变化，仍会被判成功，无法解决 P2。

### 方案 B：语义锚定节点选择（推荐）

- 做法：把 snapshot 目标的身份特征（bounds/text/desc/id/class）传给 action 层；在坐标命中候选中按语义打分选节点。
- 如果匹配置信度不够或候选歧义，返回 `Failure`，交给 `gesture_tap`。
- 优点：直接修复“错节点成功”；对现有架构侵入小。

### 方案 C：只按 expected bounds 精确匹配

- 做法：只接受 bounds 完全一致节点。
- 问题：布局轻微漂移时误判失败率高，稳定性不足。

**选定：方案 B。**

## 5. 详细设计

## 5.1 数据流改造：把语义目标带到平台层

### 变更点

- `TargetResolver.ResolveResult.Resolved` 新增可选 `semanticHint`
- `UIAction.ClickNodeAt` / `UIAction.LongClickNodeAt` 新增可选 `semanticHint`
- `ClickExecutor`（以及可复用到 `LongPressExecutor`）在语义目标场景传递 hint；坐标目标传 `null`

### 新结构

```kotlin
data class SemanticTargetHint(
    val bounds: Bounds,
    val className: String,
    val resourceId: String,
    val text: String,
    val description: String
)
```

说明：字段全部来自 `PerceptionElement`，不引入 `AccessibilityNodeInfo` 生命周期问题。

## 5.2 Finder 改造：从“首个命中”改为“候选收集 + 评分选择”

`AccessibilityNodeFinder` 新增语义点击入口（点击/长按可共享）：

```kotlin
fun findBestClickableNodeAtLocation(
    root: AccessibilityNodeInfo,
    x: Int,
    y: Int,
    hint: SemanticTargetHint
): MatchResult
```

`MatchResult`：

- `Matched(node)`
- `Ambiguous(reason)`
- `NotFound(reason)`

### 候选收集

- DFS 全遍历包含 `(x,y)` 的子树
- 收集 `isVisibleToUser && isClickable` 候选（不再 first-hit 早退）

### 评分函数（建议权重）

- `resourceId` 精确匹配：+6
- `className` 精确匹配：+3
- `text/description` 归一化后匹配：+3
- `bounds IoU`：`+ (0..4)`
- 中心点距离衰减：`+ (0..2)`

总分上限约 18。

### 置信度门控

- 若 `bestScore < 7` -> `NotFound(semantic mismatch)`
- 若 `bestScore - secondScore < 2` -> `Ambiguous(overlap candidates)`
- 否则返回 `Matched(bestNode)`

目的：宁可失败走 fallback，也不把低置信度节点当成功。

## 5.3 NodeActionPerformer 行为

`performNodeClickAt` 改为接收可选 hint：

```kotlin
suspend fun performNodeClickAt(x: Int, y: Int, hint: SemanticTargetHint? = null): ActionResult
```

逻辑：

1. `hint == null`：保持旧逻辑 `findClickableNodeAtLocation`。
2. `hint != null`：调用 `findBestClickableNodeAtLocation`。
3. 若 `Ambiguous/NotFound`，返回 `ActionResult.Failure("Semantic node mismatch ...")`。
4. 仅 `Matched` 才执行 `ACTION_CLICK`。

这样 `PointActionExecutorCore` 会自然进入下一 channel（`gesture_tap`）。

## 5.4 兼容性与回退

- 仅语义目标启用“语义锚定查找”。
- 坐标目标继续是“按坐标点击 top-most/gesture”。
- VirtualDisplay / Accessibility 两个平台只需透传 `UIAction` 新字段，不改平台策略。

## 6. 代码改动清单

1. `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TargetResolver.kt`
2. `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt`
3. `app/src/main/kotlin/com/moonkey/androidagent/tool/action/LongPressExecutor.kt`（建议同步）
4. `app/src/main/kotlin/com/moonkey/androidagent/platform/UIAction.kt`
5. `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt`
6. `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt`
7. `app/src/main/kotlin/com/moonkey/androidagent/platform/NodeActionPerformer.kt`
8. `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityNodeFinder.kt`

## 7. 测试设计

## 7.1 单测

1. `AccessibilityNodeFinder`：
   - overlap 场景中，语义 hint 指向 toolbar button，应选中 button 而非 list item。
   - 候选得分接近时返回 `Ambiguous`。
   - 无可靠匹配时返回 `NotFound`。

2. `NodeActionPerformerTest`：
   - 语义不匹配返回 `Failure`，且不执行 `performAction(ACTION_CLICK)`。

3. `ClickExecutorTest`：
   - 语义 click 在 node channel `Failure` 后能 fallback 到 `UIAction.TapAt` 并成功。
   - `coordinate` target 不携带 hint，行为不变。

## 7.2 回归验证

- 复现用例（FilesMoveFile round6）中，“Show roots”不再触发“Open with”循环。
- 常规场景（列表项点击、按钮点击）`node_action_click` 成功率不下降明显。

## 8. 风险与缓解

1. 风险：评分阈值过高导致 node click 失败率上升。
   - 缓解：先保守阈值（如 7/18），并依赖 gesture fallback 保证可用性。

2. 风险：字段匹配依赖 text，受国际化/动态文案影响。
   - 缓解：`resourceId/class/bounds` 权重高于 text；text 只作加分项。

3. 风险：候选收集增加遍历开销。
   - 缓解：仅在语义目标启用；且仅遍历包含点击点的子树。

## 9. 实施顺序

1. 定义 `SemanticTargetHint` 并打通 `TargetResolver -> UIAction`。
2. 在 `AccessibilityNodeFinder` 实现候选评分匹配。
3. 更新 `NodeActionPerformer` 用匹配结果控制 `Failure/Success`。
4. 补齐单测（finder、performer、executor）。
5. 本地验证：`./gradlew test`，再跑目标 debug-run 复现场景。

## 10. 验收标准

1. overlap 复现场景中，不再出现“误点击却 Success”。
2. 语义点击在匹配失败时会自动 fallback 到 gesture。
3. 现有 click/long-press/coordinate 行为保持兼容，测试全部通过。
