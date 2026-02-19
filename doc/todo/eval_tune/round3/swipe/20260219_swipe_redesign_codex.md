# Round3 Swipe Redesign (Codex)

Run: `eval/results/20260219_124436`  
Method: `cog-tune` evidence-first review (trace + code + reference agents)

---

## 1. Swipe 结果审计（Run 内逐 turn）

### 1.1 总览

- 数据源：`eval/results/20260219_124436/swipe_analysis_rows.json`
- 共识别 `29` 次 `swipe`
- 工具执行层：`29/29` 都是 Success（手势注入成功）
- 观测层：`19/29` 出现 warning  
  `Screen content unchanged after swipe - may have reached scroll boundary`
- 语义层（是否推进任务）：`7/29` 成功，`22/29` 失败

### 1.2 参数形态统计

- mode：`direction=13`，`explicit(start/end)=16`
- target：`element_index=8`，`none=21`
- direction：`down=7`，`up=6`，`right=3`，`left=2`，`(none)=11`（explicit 模式）
- distance：`medium=23`，`short=4`，`long=2`

结论：大部分 swipe 没有锚定具体容器（`target=none`），且大量是“执行成功但页面无变化”。

### 1.3 逐 turn 成败表（Exec vs Semantic）

| Task | Turn | Exec | Semantic | Reason |
|---|---:|---|---|---|
| ExpenseAddSingle | 5 | success | failure | 类别横向列表滑到边界，内容不变 |
| ExpenseAddSingle | 6 | success | failure | 界面有变化，但未出现 Health Care；后续误点 FAB 导致流程重置 |
| ExpenseAddSingle | 10 | success | failure | 界面有变化，但仍未定位目标分类；再次进入重填循环 |
| ExpenseAddSingle | 15 | success | failure | 重复同方向滑动，命中边界 |
| ExpenseAddSingle | 16 | success | failure | 加长距离仍不动，说明容器/方向判断错误 |
| FilesMoveFile | 4 | success | failure | 在当前层级向下滑动无变化，可能不在可滚动容器 |
| FilesMoveFile | 5 | success | failure | 反向长滑仍无变化，目录定位策略失效 |
| RecipeAddSingleRecipe | 7 | success | failure | 在表单容器尝试上滑但内容不变（可能焦点/键盘阻塞） |
| RecipeAddSingleRecipe | 8 | success | failure | 反向下滑仍不变，未解除阻塞条件 |
| RecipeAddSingleRecipe | 10 | success | failure | 显式坐标上滑无变化，说明坐标不在有效滚动区 |
| RecipeAddSingleRecipe | 11 | success | failure | 更长显式上滑仍无变化，非距离问题 |
| RecipeAddSingleRecipe | 12 | success | failure | 显式下滑无变化，容器选择错误 |
| RecipeAddSingleRecipe | 20 | success | failure | 编辑态再次上滑仍无变化，未触达目标字段区域 |
| SystemBluetoothTurnOnVerify | 8 | success | failure | 上滑后无变化，蓝牙页并无隐藏关键控件 |
| SystemBrightnessMaxVerify | 2 | success | success | 滑动后成功看到 Display 入口，推进任务 |
| SystemBrightnessMinVerify | 1 | success | success | 下拉通知栏成功，推进到快捷设置路径 |
| SystemBrightnessMinVerify | 2 | success | success | 继续下拉成功，扩展面板 |
| SystemBrightnessMinVerify | 3 | success | success | 再次下拉成功，完成面板展开 |
| SystemBrightnessMinVerify | 5 | success | failure | 拖动亮度条无变化，疑似已到最小/起点不在滑块 |
| SystemBrightnessMinVerify | 6 | success | failure | 再次横拖仍无变化，仍未命中有效滑块轨迹 |
| SystemBrightnessMinVerify | 7 | success | success | 从极右到极左拖动生效，亮度调整成功 |
| SystemBrightnessMinVerify | 9 | success | success | 回到 Home 后下拉成功，进入复核路径 |
| SystemBrightnessMinVerify | 10 | success | success | 继续下拉成功，复核面板状态 |
| SystemBrightnessMinVerify | 11 | success | failure | 重复下拉无变化，面板已展开到上限 |
| SystemBrightnessMinVerify | 12 | success | failure | 换起点下拉仍无变化，非起点问题 |
| SystemBrightnessMinVerify | 14 | success | failure | 在 Home 上滑无变化，未触发预期 app drawer 路径 |
| SystemBrightnessMinVerify | 16 | success | failure | 在设置页上滑无变化，当前点击点非可滚动容器 |
| SystemWifiTurnOffVerify | 6 | success | failure | 上滑无变化，未定位到 Wi-Fi 开关控件 |
| SystemWifiTurnOffVerify | 10 | success | failure | 下滑有变化，但仍未发现“关闭 Wi-Fi”开关，后续走了 Disconnect 路径 |

### 1.4 根因归类（按 swipe）

- `Boundary/No-change`：19 次，主要是重复在错误容器或已到边界后继续同策略。
- `Changed but not goal-progress`：3 次（Expense t6/t10, WifiOff t10）。
- `True progress`：7 次，集中在 Brightness 路径。

---

## 2. `.reference/mobile_agent/` 各 agent 的 swipe 定义与实现

## 2.1 MAI-UI

- Tool/action prompt 定义：`swipe(direction, optional coordinate)`  
  `.reference/mobile_agent/MAI-UI/src/prompt.py:31`
- 解析实现：只做 action JSON 解析和坐标归一化  
  `.reference/mobile_agent/MAI-UI/src/mai_naivigation_agent.py:107`
- 执行实现：仓库内主要是 agent 侧输出，未内置设备侧 swipe 注入器（执行在外部 runtime）。

## 2.2 minitap-mobile-use

- Tool definition：`SwipeRequest`（坐标模式/百分比模式 union + duration）  
  `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/controllers/types.py:81`
- Tool prompt：`swipe` + 拆分工具 `swipe_coordinates`/`swipe_percentages`  
  `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/tools/mobile/swipe.py:24`
- Execution：
  - 统一分发：`swipe_request -> controller.swipe`  
    `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/controllers/unified_controller.py:125`
  - Android 执行：`input touchscreen swipe x1 y1 x2 y2 duration`  
    `.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/controllers/android_controller.py:77`

要点：tool schema 强类型，入参清晰；但执行后语义验证较轻（主要看调用是否报错）。

## 2.3 droidrun

- Tool definition：`swipe(coordinate, coordinate2, duration)`  
  `.reference/mobile_agent/droidrun/droidrun/agent/utils/signatures.py:83`
- Tool prompt policy：明确“同方向连续 swipe 无变化应换策略”  
  `.reference/mobile_agent/droidrun/droidrun/config/prompts/executor/system.jinja2:47`
- Tool execution：`convert_point -> driver.swipe`  
  `.reference/mobile_agent/droidrun/droidrun/agent/utils/actions.py:149`
- Driver implementation：
  - 基础 adb swipe + wait  
    `.reference/mobile_agent/droidrun/droidrun/tools/driver/android.py:84`
  - stealth 曲线轨迹（可选）  
    `.reference/mobile_agent/droidrun/droidrun/tools/driver/stealth.py:134`

要点：有 prompt 级“无变化换路”约束；执行层支持更拟人的滑动轨迹。

## 2.4 MobileAgent（v1 / v3 / v3.5）

- v1：
  - 高层是 `page up/page down`  
    `.reference/mobile_agent/MobileAgent/Mobile-Agent-v1/MobileAgent/prompt.py:7`
  - 控制器里是固定模板 `input swipe`  
    `.reference/mobile_agent/MobileAgent/Mobile-Agent-v1/MobileAgent/controller.py:65`
- v3：
  - `mobile_use` tool schema：`swipe(coordinate, coordinate2)`  
    `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/function_call_mobile_answer.py:18`
  - 执行：`android_controller.slide -> adb input swipe`  
    `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/android_controller.py:48`
  - 反思器显式定义“swipe 前后不变=失败（C）”  
    `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py:298`
- v3.5：
  - 执行循环：`action in (scroll, swipe) -> adb_tools.slide`  
    `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3.5/mobile_use/run_gui_owl_1_5_for_mobile.py:224`
  - `AdbTools.slide` 封装 adb swipe  
    `.reference/mobile_agent/MobileAgent/Mobile-Agent-v3.5/mobile_use/utils.py:119`

要点：这条系强调 coordinate-to-coordinate swipe，且在 prompt/reflector 中明确 no-change 失败语义。

## 2.5 autodevice_android_world（环境侧参考）

- action type 同时有 `scroll` 与 `swipe`  
  `.reference/mobile_agent/autodevice_android_world/android_world/env/json_action.py:33`
- actuation 中区分：
  - `scroll`: 按方向与容器推导起终点
  - `swipe`: 支持方向和精确终点
  `.reference/mobile_agent/autodevice_android_world/android_world/env/actuation.py:142`

要点：把“语义滚动”和“几何滑动”分层，值得借鉴。

---

## 3. 你当前 swipe 实现拆解（definition + prompt + execution）

## 3.1 Tool definition（mobile_action）

- `swipe` 支持两类输入：
  - `direction(+distance)`
  - `start/end`
  `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt:152`
- `swipe` 可附带 target（`element_index/text/x,y`）但不是强制。
  `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt:199`

关键问题：
- schema 没有把“scroll语义”和“drag语义”拆开，导致模型容易混用。
- target 不是强约束，很多 turn 使用 `target=none`（本 run 为 21/29）。

## 3.2 Tool prompt（agent system prompt）

- Executor 仅给了泛化规则：`swipe up` 表示 scroll down  
  `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt:59`
- Standalone 没有 swipe 专项策略（只给“避免重复”泛规则）  
  `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt:49`

关键问题：
- 缺少“容器优先”“连续 no-change 改策略”“scroll 与 slider drag 区分”等细粒度策略。

## 3.3 Execution（多层链路）

链路：`MobileActionTool -> SwipeExecutor -> AndroidPlatform(UIAction.Swipe) -> GestureInjector`

- 调度入口：  
  `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt:90`
- 几何计算与分支：
  - explicit 优先
  - direction 模式可解析 target；target 解析失败时静默回退到屏幕中心
  `app/src/main/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutor.kt:41`
- 变化检测：
  - 通过 pre/post a11y 文本+描述+bounds 完全相等判定 warning
  `app/src/main/kotlin/com/moonkey/androidagent/tool/action/UiChangeDetector.kt:30`
- 结果契约：
  - 只要手势注入成功就返回 `ActionOutcome.Success`
  - 即使有 boundary warning 也依旧是 success
  `app/src/main/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutor.kt:168`
- 设备注入层：
  - Accessibility：dispatchGesture callback 成功即成功
    `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityGestureInjector.kt:108`
  - VirtualDisplay：线性 move 20 步，UP 成功即成功
    `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayInputInjector.kt:90`

关键问题：
- `target resolve fail -> center fallback` 对 scroll 类任务很危险，容易滑错容器。
- “warning 不升级为失败”会误导模型持续重复无效 swipe。
- boundary 检测是文本集合启发式，不等价于“语义可滚动/已滚动”。

## 3.4 与认知层的连接点（当前不足）

- 你已有 loop policy，但阈值触发偏后（连续 scroll >=5）  
  `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/LoopDetectionPolicy.kt:39`
- swipe 在导航状态中统一映射成 `scroll:<direction>`，能计数但不区分容器/目标  
  `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:274`

---

## 4. Redesign：让 swipe 更 robust

## 4.1 目标

- 把“手势成功”提升为“语义成功优先”。
- 减少无锚点 swipe、重复 no-change swipe、错容器 swipe。
- 区分 `scroll` 与 `drag` 两类 intent。

## 4.2 设计原则

- 明确意图：`scroll`（找内容）和 `swipe`（拖动控件）分开。
- 容器优先：能定位可滚动容器就不做全屏中心滑动。
- 可验证：每次 swipe 返回结构化观测信号，不只是一句 success 文本。
- 早停换路：同容器同方向 no-change 达阈值立即换策略。

## 4.3 具体改造

### A. Tool schema 重构

- 在 `mobile_action` 下新增 `swipe_intent`：
  - `scroll`
  - `drag`
- `scroll` 参数：
  - `direction` 必填
  - `container_element_index` 可选但强推荐
  - `distance` 和 `max_attempts`
- `drag` 参数：
  - `start/end` 或 `from_element_index + to_edge`
- 增加 `expected_effect`（如 `reveal_more_items`, `expand_panel`, `set_slider_min`）。

### B. Prompt policy 强化

- 在 Executor/Standalone prompt 明确规则：
  - 先找 `isScrollable=true` 容器，再 scroll。
  - 连续 2 次 no-change 不得同策略重试。
  - 设置类任务区分：`turn off wifi` 不是 `disconnect network`。
  - slider 用 `drag`，列表用 `scroll`。

### C. SwipeExecutor 语义升级

- 取消默认 center fallback：
  - 指定了 target 但解析失败时返回失败（或显式 `fallback_allowed=true` 才允许）。
- 返回结构化 outcome（至少内部结构）：
  - `gesture_dispatched`
  - `target_resolved`
  - `content_changed`
  - `boundary_reached`
  - `likely_wrong_container`
- no-change 分级：
  - `NoChangeBoundary`
  - `NoChangeUnknown`
  两者至少一个应映射为 failure 或可感知的 partial failure，不再全部 success。

### D. 观测信号增强

- 除文本集合比较外，加入：
  - scrollable 节点的可见子项窗口变化
  - 关键节点 bounds 位移
  - screenshot hash 变化（已有 `compare` 可复用）
  - 可行时接入 `TYPE_VIEW_SCROLLED`（若能在平台层记录）

### E. 认知层联动

- 新增 `SwipeStallPolicy`：
  - key 维度：`(package, container_signature, direction, intent)`
  - 连续 2 次 no-change 触发换路建议
- 把 swipe 结果结构化写入导航状态，避免仅靠 `scroll:direction` 粗粒度计数。

---

## 5. 预期收益与验证计划

## 5.1 预期收益

- 直接目标：把“无效 swipe 比例”从当前 `22/29` 显著压低。
- 间接目标：降低 MaxTurnsReached 中由 swipe 循环造成的浪费。

## 5.2 验证计划

1. 单测（executor 级）  
   - target 解析失败行为  
   - no-change 分级映射  
   - container-based endpoint 计算
2. 回归任务  
   - `ExpenseAddSingle`, `RecipeAddSingleRecipe`, `SystemWifiTurnOffVerify`, `FilesMoveFile`
3. eval 对比  
   - 先跑 `eval/config/aw_subset_smoke.txt`  
   - 再跑 `eval/config/aw_subset_core.txt`  
   对比指标：`scripted_success_rate`, `tool_failure_rate`, `MaxTurnsReached rate`, `per-task swipe no-change ratio`

---

## 6. 优先级落地（建议）

1. P0：`NoChange` 结果语义化 + 停止把所有 swipe 都当 success。  
2. P0：target 失败不再默认 center fallback。  
3. P1：prompt 增加 swipe 专项策略（容器优先、两次不变换路）。  
4. P1：scroll/drag intent 分离。  
5. P2：更强观测信号（scroll event / 子项窗口变化）与策略联动。

