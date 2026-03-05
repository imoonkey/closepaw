# Round3 Swipe Design Review (Codex)

## 总结
整体方案方向是合理的，且优先级拆分（P0/P1/P2）清晰，能直接命中当前 swipe 的主要失效模式。  
但有 2 个 P0 级别的设计缺口建议先补齐，否则实现时会出现“文档可行、代码不可落地”或改动面超预期的问题。

## P0 Findings（建议先修正文档）

1. `ScrollNode(nodeId, scrollAction)` 设计当前不可直接落地
- 设计位置：`doc/todo/eval_tune/round3/swipe/align/design/design.md:44`、`doc/todo/eval_tune/round3/swipe/align/design/design.md:47`
- 问题：当前 `PerceptionElement` 没有稳定 `nodeId`、也没有父子关系，只有扁平元素属性（`index/text/bounds/...`），无法按文档字面实现“nearest scrollable ancestor + nodeId action”。
- 代码证据：`app/src/main/kotlin/com/moonkey/androidagent/model/Models.kt:107`
- 建议：把接口改成“可实现语义”，例如：
  - `UIAction.ScrollNodeAt(x, y, action)`（运行时重新找包含点的可滚动节点）
  - 或 `UIAction.ScrollNodeBySelector(text/resourceId/bounds-hint, action)`（语义重定位）

2. P0 改动文件清单漏了跨平台分支，风险低估
- 设计位置：`doc/todo/eval_tune/round3/swipe/align/design/design.md:143`
- 问题：新增 `UIAction` variant 后，`when(action)` 的平台实现要同步更新；不仅是 `AccessibilityPlatform`，`VirtualDisplayPlatform` 也必须处理，否则编译/行为会不一致。
- 代码证据：`app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt:194`、`app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt:250`
- 建议：把 `NodeActionPerformer`、`VirtualDisplayPlatform` 加入 P0 文件清单和风险评估。

## P1 Findings（建议增强）

1. API 级别与 action 选择策略建议写成明确矩阵
- 设计位置：`doc/todo/eval_tune/round3/swipe/align/design/design.md:35`
- 建议补充：
  - API < 23：优先 `ACTION_SCROLL_FORWARD/BACKWARD`
  - API >= 23：优先方向性 action（`UP/DOWN/LEFT/RIGHT`），不可用时回退到 `FORWARD/BACKWARD`
  - 执行前检查 node 支持的 `actionList`，`performAction=false` 直接走 gesture fallback

2. `no_effect` 从 executor 到 cognition 的“结构化字段”需在设计里指明承载位置
- 设计位置：`doc/todo/eval_tune/round3/swipe/align/design/design.md:101`
- 当前现状：action 分类仅到 `scroll:<direction>`，还没有 `no_effect` 维度。
- 代码证据：`app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:270`
- 建议：在 P1 中指明字段落点（如 `ToolExecutionResult.Success` metadata / trace envelope），避免退化成字符串解析。

3. Stall policy 阈值从 5 -> 2 的改动建议附“仅针对 no_effect swipe”的约束
- 设计位置：`doc/todo/eval_tune/round3/swipe/align/design/design.md:124`
- 当前现状：`LoopDetectionPolicy` 只有全局 `maxConsecutiveScrollActions=5`。
- 代码证据：`app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/LoopDetectionPolicy.kt:15`
- 建议：文档明确“只对连续同方向且 no_effect=true 的 swipe 使用阈值 2”，避免误伤正常长列表浏览。

## 可借鉴 Round2 Click Design 的点

1. 增加一节 `Why These Decisions Match Current Code Reality`
- round2 的这节非常有效，能提前暴露“设计假设 vs 现有实现”的差距，减少落地偏差。

2. 增加“外部契约稳定性”声明
- round2 明确了哪些 contract 不变，帮助控制改动半径；round3 建议同样明确 `mobile_action` 兼容边界和默认行为。

3. Phase gate 写成可判定门槛
- round2 对 Phase2 触发条件写得更硬（基于 eval 证据），round3 可把 P1/P2 的进入条件也量化，减少“做不做靠感觉”。

4. 保留一个实现同步文档（类似 round2 `impl_summary.md`）
- 用来记录设计偏差与实际实现差异，避免后续再出现“文档已对齐、代码已漂移”。

## API Behavior 核验（官方文档）

1. `AccessibilityNodeInfo.performAction(...)` 返回 `true` 只表示 action 请求被执行，不等价于“页面一定发生可见变化”。  
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#performAction(int)

2. `ACTION_SCROLL_FORWARD/BACKWARD` 可用于通用滚动；方向性 `ACTION_SCROLL_UP/DOWN/LEFT/RIGHT` 为 API 23+ 能力。  
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#ACTION_SCROLL_FORWARD  
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#ACTION_SCROLL_BACKWARD  
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction#ACTION_SCROLL_UP

3. `dispatchGesture()` 会取消当前正在进行的手势（包括用户手势和其他服务手势），因此 node-action 优先在交互稳定性上是有依据的。  
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler)

4. `TYPE_VIEW_SCROLLED` 事件确实可用，但要接入连续事件流需要服务层缓存/线程设计，放到 P2 是合理的。  
- https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent#TYPE_VIEW_SCROLLED

## 最终判断
- `design.md` 作为方向性方案是合理的，建议在实现前先修正上述 2 个 P0 表述（`nodeId` 模型与跨平台改动面）。
- 修正后即可进入 round3 patch 实施，且风险评估会更接近真实工程成本。
