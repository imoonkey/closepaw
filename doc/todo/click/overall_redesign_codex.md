# Mobile Action Overall Redesign (Codex)

## 0. 设计结论（先说结论）

这次重构选择 **Option 1（并做增强）**：

1. `AccessibilityPlatform` 只做 **Atomic Actions**（纯底层 API 包装，不做复杂 fallback/retry）。
2. `mobile_action` 的 selector 解析、fallback 编排、UI change 判定，统一放到一个上层 `MobileActionEngine`。
3. `mobile_action` 接口改成 **强约束 one-of target**（一次 action 只能一种 targeting method）。
4. **不做 backward compatibility**：直接切新 schema、新调用链、新文件结构。

这是最符合你提出的核心原则（KISS + Atomic + 清晰边界）的方案。

---

## 1. 当前问题（跨模块）

当前实现的复杂点，不是功能本身复杂，而是“职责散落”：

1. 同一类逻辑分散在 `tool/impl/mobileaction/*` + `tool/handlers/*TargetInvocation*` + `platform/AccessibilityPlatform.kt`。
2. fallback 逻辑在 click/long_press/type/swipe 各写一套，重复且不一致。
3. `UIAction` 同时承担“高层语义”和“底层执行”，导致接口错位。
4. flat 参数（`element_index`/`text`/`x,y`）天然容易多填，造成隐式优先级与歧义。
5. `tool/handlers` 与 `tool/impl/mobileaction` 的分层对读代码的人不直观。

---

## 2. 目标

1. 层次最少且清晰：每层只有一个职责。
2. 一次 action 只允许一个 target 方法（element/text/coordinate one-of）。
3. click/type/long_press 统一 targeting 语义与执行框架。
4. Atomic API 明确：Node API 与 Gesture API 严格分离。
5. fallback 可配置、可测试、可解释（attempt trail）。

---

## 3. 新架构（3 层）

## 3.1 Layer A: Tool Contract（参数与调用入口）

职责：
- 定义 `mobile_action` schema。
- JSON -> typed request。
- 参数合法性校验（含 one-of target）。

不负责：
- 目标解析。
- fallback/retry。
- 平台 API 细节。

产物：`MobileActionRequest`（强类型）。

---

## 3.2 Layer B: MobileAction Engine（核心编排层）

职责：
- 解析 target（element/text/coordinate）。
- 为 action 生成 attempt plan（按策略排序）。
- 执行 attempts（调用 atomic executor）。
- 统一 post-action success 判定（UI change / typed success）。
- 返回统一结果和 attempt trail。

不负责：
- Android Accessibility API 细节。

这是整个重构的“唯一智能层”。

---

## 3.3 Layer C: Atomic Platform（底层执行层）

职责：
- 只做一跳底层调用：
  - Node action: `AccessibilityNodeInfo.performAction(...)`
  - Gesture action: `AccessibilityService.dispatchGesture(...)`
- 返回原子执行结果（成功/失败/取消 + 原因）。

不负责：
- fallback。
- 多 target 切换。
- UI 变更判定。

---

## 4. 新接口设计

## 4.1 Tool 输入（彻底替换现有 flat 参数）

使用判别联合，避免多填冲突。

```json
{
  "action": "click | long_press | type | swipe",
  "target": {
    "kind": "element | text | coordinate",
    "...": "target-specific fields"
  },
  "input": "...",         // type only
  "clear": false,          // type only
  "duration_ms": 1000,     // long_press / swipe(optional)
  "swipe": { ... },        // swipe only
  "agent_thought": "..."
}
```

### Target 结构

1. element
```json
{ "kind": "element", "element_index": 12 }
```

2. text
```json
{ "kind": "text", "text": "Delete", "text_index": 0 }
```

3. coordinate
```json
{ "kind": "coordinate", "x": 540, "y": 1200 }
```

### Swipe 结构（不支持 element/text target）

1. direction mode
```json
{ "action": "swipe", "swipe": { "mode": "direction", "direction": "up", "distance": "medium" } }
```

2. path mode
```json
{ "action": "swipe", "swipe": { "mode": "path", "start": [540, 1500], "end": [540, 500] } }
```

> 直接移除旧字段：`x1,y1,x2,y2`、以及 swipe 的 element/text targeting。

---

## 4.2 核心类型（Kotlin）

```kotlin
sealed interface MobileActionRequest {
    data class Click(val target: TargetSpec): MobileActionRequest
    data class LongPress(val target: TargetSpec, val durationMs: Long): MobileActionRequest
    data class Type(val target: TargetSpec, val input: String, val clear: Boolean): MobileActionRequest
    data class Swipe(val spec: SwipeSpec): MobileActionRequest
}

sealed interface TargetSpec {
    data class Element(val elementIndex: Int): TargetSpec
    data class Text(val text: String, val textIndex: Int): TargetSpec
    data class Coordinate(val x: Int, val y: Int): TargetSpec
}
```

---

## 4.3 Atomic 接口（Platform）

```kotlin
interface AtomicUiExecutor {
    suspend fun execute(action: AtomicUiAction, snapshot: ScreenSnapshot?): AtomicUiResult
}

sealed interface AtomicUiAction {
    data class NodeAction(val selector: NodeSelector, val op: NodeOp): AtomicUiAction
    data class GestureTap(val x: Int, val y: Int): AtomicUiAction
    data class GestureLongPress(val x: Int, val y: Int, val durationMs: Long): AtomicUiAction
    data class GestureSwipe(val startX: Int, val startY: Int, val endX: Int, val endY: Int, val durationMs: Long): AtomicUiAction
}

sealed interface NodeSelector {
    data class ByElementLocator(val locator: NodeLocator): NodeSelector
    data class ByPoint(val x: Int, val y: Int): NodeSelector
    data object FocusedEditable: NodeSelector
}

enum class NodeOp { CLICK, LONG_CLICK, SET_TEXT, CLEAR_AND_SET_TEXT }
```

关键点：`AccessibilityPlatform` 只实现 `AtomicUiExecutor`，不做策略。

---

## 5. Fallback 策略（统一策略表）

## 5.1 统一规则

1. 一次 action 只解析一个 `TargetSpec`。
2. 不在 target 类型之间切换（因为 one-of，避免隐式行为）。
3. fallback 只发生在“同一 target 的不同 atomic 路径”。

## 5.2 Click

1. `element/text` -> resolve element ->
   - Attempt 1: `NodeAction(locator, CLICK)`
   - Attempt 2: `NodeAction(ByPoint(center), CLICK)`
   - Attempt 3: `GestureTap(center)`
2. `coordinate` ->
   - Attempt 1: `NodeAction(ByPoint(x,y), CLICK)`
   - Attempt 2: `GestureTap(x,y)`

## 5.3 Long Press

1. `element/text` ->
   - Attempt 1: `NodeAction(locator, LONG_CLICK)`
   - Attempt 2: `NodeAction(ByPoint(center), LONG_CLICK)`
   - Attempt 3: `GestureLongPress(center, duration)`
2. `coordinate` ->
   - Attempt 1: `NodeAction(ByPoint(x,y), LONG_CLICK)`
   - Attempt 2: `GestureLongPress(x,y,duration)`

## 5.4 Type

1. `element/text` ->
   - Attempt 1: `NodeAction(locator, SET_TEXT/CLEAR_AND_SET_TEXT)`
   - Attempt 2: `GestureTap(center)` + `NodeAction(FocusedEditable, SET_TEXT/CLEAR_AND_SET_TEXT)`
2. `coordinate` ->
   - Attempt 1: `NodeAction(ByPoint(x,y), SET_TEXT/CLEAR_AND_SET_TEXT)`
   - Attempt 2: `GestureTap(x,y)` + `NodeAction(FocusedEditable, SET_TEXT/CLEAR_AND_SET_TEXT)`

## 5.5 Swipe

- 仅 gesture 路径，不做 element/text 解析。
- direction/path 都转换成单次 `GestureSwipe(...)`。
- 若无可观察变化，返回明确 warning（可能到达边界）。

---

## 6. Success Contract（统一）

每个 attempt 返回两层结果：

1. `dispatchResult`（底层 API 是否成功）
2. `interactionResult`（是否达成可观察效果）

统一判定：

1. click / long_press / swipe：默认需要可观察 UI change。
2. type：优先信任 `SET_TEXT` 成功；若不可验证再用 UI change 兜底。
3. 无 pre/post snapshot 时，标记 `unverifiable_success`，但写入 warning。

输出必须包含 `attempt_trail`，例如：
- `element(12) -> node_click: no_ui_change`
- `element(12) -> point_click: failed(no_clickable_node)`
- `element(12) -> gesture_tap: success(ui_changed)`

---

## 7. element_index 的“真正节点化”

你提到的关键点是对的：`element_index` 不该天然退化成坐标。

为此建议在 perception 里引入 `NodeLocator`（无 node 引用，不泄漏）：

```kotlin
data class NodeLocator(
    val windowId: Int?,
    val pathFromRoot: List<Int>,
    val fingerprint: NodeFingerprint
)
```

`PerceptionElement` 增加 `locator: NodeLocator`。

执行时：
1. 通过 `pathFromRoot` 在 fresh tree 重新定位节点。
2. 用 `fingerprint`（class/resourceId/text/bounds）校验。
3. 节点不可用时才降级到 point/gesture fallback。

这样 element target 才真正“先节点后坐标”。

---

## 8. 文件与模块重组（解决 handlers/impl 混乱）

建议把 mobile_action 相关代码收敛到一个包：

```text
app/src/main/kotlin/com/moonkey/androidagent/
├── tool/impl/MobileActionTool.kt                # schema + parse entry
├── tool/mobileaction/
│   ├── contract/MobileActionRequest.kt
│   ├── contract/TargetSpec.kt
│   ├── contract/SwipeSpec.kt
│   ├── validation/MobileActionValidator.kt
│   ├── engine/MobileActionInvocation.kt
│   ├── engine/MobileActionExecutor.kt
│   ├── engine/AttemptPlanner.kt
│   ├── engine/AttemptRunner.kt
│   ├── engine/UiOutcomeEvaluator.kt
│   └── targeting/TargetResolver.kt
└── platform/
    ├── atomic/AtomicUiAction.kt
    ├── atomic/AtomicUiExecutor.kt
    └── AccessibilityPlatform.kt                 # implements AtomicUiExecutor only
```

重构后：
1. 删除 `tool/handlers/ClickTargetInvocation.kt` 等四个 TargetInvocation。
2. 删除 `tool/impl/mobileaction/*ActionHandler.kt`（或仅保留极薄 action parser）。
3. `tool/handlers` 仅保留通用 invocation（如果仍需要）。

---

## 9. 为什么这是最优解（相对 Option 2）

若选 Option 2（UIAction 对齐 prompt target，fallback 下沉到 platform），会有三个问题：

1. 违反 Atomic 原则：platform 会重新变成“策略层+执行层”混合。
2. platform 体积继续膨胀，复杂度回流到 `AccessibilityPlatform.kt`。
3. 测试困难：策略测试被迫依赖 Android 行为 mock，不利于快速迭代。

当前方案把复杂度集中在可单测的 `MobileActionEngine`，platform 保持薄且稳定。

---

## 10. 最小实现顺序（建议）

1. 定义新 `mobile_action` schema（nested target + swipe mode），移除旧 flat targeting。
2. 落地 `MobileActionRequest` + validator + parser。
3. 落地 `AtomicUiAction/Executor`，把 `AccessibilityPlatform` 改成纯原子执行。
4. 落地 `MobileActionExecutor`（统一 attempt planner + runner + outcome evaluator）。
5. 更新 agent prompt 示例与 `ActionDescriptionFormatter`。
6. 单测覆盖：
   - one-of target 校验
   - 各 action fallback 顺序
   - ui-change success contract
   - attempt_trail 可读性

---

## 11. 非目标（本次不做）

1. 不做 task-level semantic verification（例如“真的提交成功”）。
2. 不做 multi-action 一次调用。
3. 不做旧 schema 兼容。

---

## 12. 一句话摘要

**把 mobile_action 重构成：`强类型 one-of 输入` -> `单一 MobileActionEngine 负责编排与 fallback` -> `Atomic AccessibilityPlatform 只做底层调用`，并通过 `NodeLocator` 让 element_index 真正优先走节点语义。**
