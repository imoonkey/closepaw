# Smart Capsule UX Design v2 (Independent)

## 0. Scope 与设计目标
本设计只定义产品与交互，不定义实现代码。目标是让 Smart Capsule 成为「人机协作控制台」，而不是仅显示状态的提示条。

设计覆盖：
- Overlay 上展示一行 `agent thought`
- 三个核心控制 CTA：`补充` / `接管(继续)` / `停止`
- Agent 主动求助能力：`ask_user_for_input` / `ask_user_to_operate`
- 主 App、LiveView、Status Island 三种容器状态联动（含 `[1][2][3]`）

---

## 1. 从问题出发

### 1.1 真实问题
当前用户在 Agent 执行中有三个核心痛点：
1. 看不懂 Agent 下一步意图：只看到碎片状态，缺乏可解释性。
2. 不能在执行中高效介入：需要人工接管、补充约束、处理登录/权限时，路径不清晰。
3. 容器切换心智混乱：主 App / 观看屏幕 / 小岛模式切换规则不一致，用户容易迷路。

### 1.2 谁在什么场景下受影响
- `新手用户`：首次使用自动化，不敢放权，最需要“可解释+可控”。
- `高频用户`：多轮任务中经常要临时补充条件（预算、偏好、过滤条件）。
- `复杂任务用户`：涉及登录、验证码、权限授权、支付确认，需要明确的人机交接。

主要发生在：
- Agent 正在多步执行过程中。
- App 退到后台，用户在目标 App 中观察/干预。
- VD 模式下用户来回切前台/岛态。

### 1.3 为什么重要
如果不能稳定完成“解释-接管-恢复”闭环，用户会：
- 频繁强行停止任务
- 回到手动操作
- 对 Agent 信任下降

### 1.4 成功定义（可验证）
功能上线后，满足以下行为变化：
1. `接管后继续率` 上升：用户接管后能顺利恢复执行，而不是直接终止。
2. `任务中补充使用率` 上升：用户愿意在执行中给增量指令，而不是重开任务。
3. `ask_user 闭环完成率` 上升：Agent 请求用户协助后，能回到自动执行并完成目标。
4. `误操作率` 下降：不存在按钮点了“无反应”或进入死胡同。

---

## 2. 体验原则与关键取舍

### 2.1 原则
1. `单一主线`：任何时刻只有一个主状态，用户始终知道“现在谁在驾驶”。
2. `显式交接`：从自动到人工、从人工回自动，都必须有明确视觉和文案反馈。
3. `最小可用动作`：默认只给最关键动作，避免按钮爆炸。
4. `容器一致`：无论在主 App / LiveView / Island，核心控制语义不变。

### 2.2 关键取舍
1. `能力 vs 简洁`
- 选择：保留 3 个核心控制（补充/接管/停止），把复杂切换放进次级容器按钮 `[1][2][3]`。
- 原因：协作控制优先，导航切换次优先。

2. `强约束 vs 灵活`
- 选择：`ask_user` 拆成两种明确语义（输入 / 操作），不用一个模糊工具。
- 原因：前端状态可预测，减少歧义。

3. `即时中断 vs 系统稳定`
- 选择：`接管` 为协作暂停（当前动作收束后生效），并显式提示“正在交接”。
- 原因：避免中断导致的半执行状态。

---

## 3. 信息架构：双状态机模型

Smart Capsule 交互拆为两个正交状态机：
1. `任务协作状态机`：谁在控制任务（Agent / 用户 / 等待用户）
2. `容器展示状态机`：UI 在哪里展示（主 App / LiveView / Island）

这样能避免把“执行状态”和“页面位置”混成一个巨型状态机。

---

## 4. 状态机 A：任务协作状态机（核心）

### 4.1 状态定义
1. `Idle`
- 无运行中任务。

2. `AutoRunning`
- Agent 自动执行中。

3. `TakeoverPending`
- 用户点了`接管`，系统等待当前动作收束，准备交接。

4. `UserTakeover`
- 人工接管中，Agent 不再发起新动作。

5. `WaitingUserInput`（ask_user_for_input）
- Agent 提问，等待用户文本回答。

6. `WaitingUserAction`（ask_user_to_operate）
- Agent 请求用户做手机操作（登录/授权/验证码）。

7. `Stopping`
- 用户点`停止`后进入收尾中。

8. `Completed`
- 任务结束（成功/不可完成/到达轮数上限）。

9. `Error`
- 任务异常结束。

### 4.2 触发器、守卫、迁移、副作用

1. `Idle -> AutoRunning`
- Trigger: 用户发送任务（主输入框）
- Guard: 权限满足（A11y / Overlay；VD 模式需额外能力可用）
- Side effect: 创建 task，展示 capsule

2. `AutoRunning -> TakeoverPending`
- Trigger: 点击`接管`
- Guard: 当前状态可接管
- Side effect: UI 反馈“正在交接”；禁止重复点击

3. `TakeoverPending -> UserTakeover`
- Trigger: 当前动作收束完成
- Guard: 无
- Side effect: 取消未开始的旧 tool queue；冻结 Agent 动作发起

4. `UserTakeover -> AutoRunning`
- Trigger: 点击`继续`
- Guard: 无阻塞弹窗（如 ask_user 未完成）
- Side effect: 立即抓取当前屏幕新状态，发给 LLM 重规划

5. `AutoRunning -> WaitingUserInput`
- Trigger: `ask_user_for_input(question)`
- Guard: 同一时刻无其他 pending ask_user
- Side effect: 展示问题面板，进入等待

6. `AutoRunning -> WaitingUserAction`
- Trigger: `ask_user_to_operate(instruction)`
- Guard: 同上
- Side effect: 展示操作说明面板，进入等待

7. `WaitingUserInput -> AutoRunning`
- Trigger: 用户提交文本回答
- Guard: 文本非空（去除空白后）
- Side effect: 回传 tool 结果，下一轮继续执行

8. `WaitingUserAction -> AutoRunning`
- Trigger: 用户点击`我已完成`
- Guard: 可选确认（若停留时长过短则二次确认）
- Side effect: 抓取新屏幕，继续执行

9. `AutoRunning/UserTakeover/Waiting* -> Stopping`
- Trigger: 点击`停止`
- Guard: 无
- Side effect: 停止后续动作，收尾并写入结束原因

10. `Stopping -> Completed`
- Trigger: 清理完成

11. `Any -> Error`
- Trigger: 平台错误/权限失效/关键异常
- Side effect: 错误文案 + 重试/恢复入口

---

## 5. 状态机 B：容器展示状态机（主 App / LiveView / Island）

### 5.1 容器状态
1. `AppHome`：主 App 聊天页底部 Smart Capsule
2. `LiveView`：屏幕观看页底部 Smart Capsule
3. `IslandCompact`：悬浮小岛（最小形态）
4. `IslandExpanded`：从小岛点击后展开为底部 Capsule（过渡态）

### 5.2 `[1][2][3]` 定义（UI 状态控制）
- `[1] 缩小`：进入 `IslandCompact`
- `[2] 主App`：进入 `AppHome`
- `[3] 看屏幕`：进入 `LiveView`

### 5.3 容器切换规则（与模式联动）

#### ACCESSIBILITY 模式
1. 在 `AppHome`
- Waiting（Idle/UserTakeover/Waiting*）显示 `[1]`
- AutoRunning 不显示容器切换（避免误切）

2. 在 `LiveView`
- AutoRunning 显示 `[1][2]`
- 不显示 `[3]`（当前已在看屏幕）

3. 在 `IslandCompact`
- 显示 `[2]`（必要时可显示 `[3]`，若产品要求保留）

4. 在 `IslandExpanded`
- 显示 `[1][2]`，不显示 `[3]`

#### VIRTUAL_DISPLAY 模式
1. 在 `AppHome`
- 显示 `[1][3]`，不显示 `[2]`

2. 在 `LiveView`
- 显示 `[1][2][3]`（`[3]`用于刷新/重入 LiveView）

3. 在 `IslandCompact`
- 显示 `[2][3]`

4. 在 `IslandExpanded`
- 显示 `[1][2][3]`

---

## 6. 每个任务状态下的 UI 规范

### 6.1 AutoRunning
用户看到：
- 顶行一行 thought（单行截断）
- 控制行：`补充` `接管` `停止`

用户可做：
- 补充文本
- 请求接管
- 停止任务

系统响应：
- thought 每次动作前更新
- 点击接管后进入 `TakeoverPending`

加载/空/错：
- loading: thought 占位文案“正在规划下一步…”
- empty: 无 thought 时显示上一步动作摘要
- error: 在不跳状态下显示短错误，允许重试/接管/停止

### 6.2 TakeoverPending
用户看到：
- 中间按钮变 disabled，文案“正在交接…”
- 小提示“当前动作完成后将交给你”

用户可做：
- 可继续点`停止`

系统响应：
- 当前动作完成后自动进 `UserTakeover`

### 6.3 UserTakeover
用户看到：
- 顶部 thought 变灰（冻结）
- 控制行：`补充` `继续` `停止`

用户可做：
- 手动操作手机
- 补充额外指令
- 点击继续交还控制

系统响应：
- 点击继续后立即新一轮感知与规划

### 6.4 WaitingUserInput
用户看到：
- 标题：`等待你的回答`
- 问题正文
- 输入框 + `发送`
- 保留 `停止`

用户可做：
- 输入并发送
- 取消（若允许）

系统响应：
- 空输入不提交，给出 inline 错误
- 提交后进入 AutoRunning

### 6.5 WaitingUserAction
用户看到：
- 标题：`请先操作手机`
- 指令正文（例：请登录你的淘宝账号）
- CTA：`我已完成`
- 次 CTA：`停止`

用户可做：
- 去操作系统界面
- 完成后确认

系统响应：
- 点“我已完成”后抓屏并继续

### 6.6 Stopping / Completed / Error
- `Stopping`：显示“正在停止…”并禁用非必要按钮
- `Completed`：显示结果摘要，提供“新任务”入口
- `Error`：显示可理解错误 + `重试` / `接管` / `停止`

---

## 7. 按钮级规范（No Broken Windows）

### 7.1 核心控制按钮
1. `补充`
- Purpose: 在不中断任务前提下插入用户消息
- Enabled: `AutoRunning`、`UserTakeover`
- Disabled: `Stopping`
- Feedback: 弹输入层；发送后 toast/inline “已补充，下一步生效”

2. `接管`
- Purpose: 从自动执行切到人工控制
- Enabled: `AutoRunning`
- Disabled: `TakeoverPending`、`Waiting*`、`Stopping`
- Feedback: 立即进入“正在交接”态

3. `继续`
- Purpose: 人工控制后交还 Agent
- Enabled: `UserTakeover`
- Disabled: `WaitingUserInput`（未答复前）
- Feedback: 按钮 loading + thought 更新

4. `停止`
- Purpose: 安全终止当前任务
- Enabled: 除 `Idle/Completed` 外都可
- Feedback: 二次确认（高风险场景可选）+ 进入 Stopping

### 7.2 容器按钮 `[1][2][3]`
1. `[1] 缩小`
- Purpose: 降低遮挡，保留控制能力
- Enabled: 非 `Stopping`
- Feedback: 缩放动效 + Island 出现

2. `[2] 主App`
- Purpose: 返回主对话上下文
- Guard: 主 App 可用且未被系统限制

3. `[3] 看屏幕`
- Purpose: 进入 LiveView 观察/调试执行
- Guard: 当前模式支持对应观看能力

所有按钮必须有：
- 可见性规则
- enable/disable 规则
- 点击反馈（视觉 + 状态变更）

禁止：
- 点击无反馈
- 不可达状态
- 没有返回路径

---

## 8. 边界与异常处理

### 8.1 权限
1. Accessibility 未授权
- 进入阻断页，不启动任务
- 提供“一键去设置”

2. Overlay 未授权
- 在主 App 内继续任务，但提示“无法显示浮窗控制条”
- 用户可选择去授权

3. VD 能力不可用（Shizuku/创建失败）
- 自动降级到 Accessibility 模式并明确提示

### 8.2 网络与延迟
1. LLM 延迟高
- thought 显示“正在思考…（网络较慢）”
- 超时提供“重试本轮 / 接管 / 停止”

2. tool 执行超时
- 进入可恢复错误，不直接崩任务

### 8.3 输入与取消
1. 补充输入为空
- 禁止发送，输入框下提示

2. ask_user 等待中用户点停止
- 直接结束等待并进入 Stopping

3. 用户连续快速点接管/继续
- 节流 + 按钮短时锁定

### 8.4 离线
- 新任务不可启动
- 已在 `UserTakeover` 可继续人工完成
- 恢复网络后支持“继续任务”

### 8.5 部分失败
- 某一步失败不等于任务失败
- 保留下一步选项：`重试该步` / `接管` / `停止`

---

## 9. 文案与一致性规范

### 9.1 thought 文案
- 一行、动作导向、面向用户
- 不暴露内部推理
- 中文建议 <= 18 字（超长省略）

示例：
- `正在打开淘宝并进入搜索`
- `需要你登录后我再继续`

### 9.2 术语统一
- Pause 不对外显示，统一用 `接管`
- Resume 不对外显示，统一用 `继续`
- Ask user 文案统一前缀：`需要你…`

### 9.3 布局一致
- 胶囊结构固定两层：`thought 行 + 控制行`
- 主 App 与 LiveView 的按钮顺序完全一致

---

## 10. 端到端关键流程（最简）

### Flow A: 正常自动执行 + 用户补充
1. 用户发起任务 -> AutoRunning
2. Agent 展示 thought 并执行
3. 用户点`补充`并发送“价格低于300”
4. 系统确认已补充 -> 下一轮按新约束继续

### Flow B: 接管并恢复
1. AutoRunning 中用户点`接管`
2. 进入 TakeoverPending -> UserTakeover
3. 用户手动完成登录
4. 点`继续` -> 抓取新屏幕 -> AutoRunning

### Flow C: Agent 主动 ask_user
1. Agent 调用 `ask_user_for_input`
2. 进入 WaitingUserInput，显示问题
3. 用户回答并发送
4. Agent 收到答案，继续执行

### Flow D: 进入 Island 再回主 App/LiveView
1. 用户点`[1]`缩小到 IslandCompact
2. 点`[2]`回主 App 或点`[3]`进 LiveView
3. 控制语义保持一致，无需重新学习

---

## 11. 验收清单（UX）

上线前必须全部满足：
1. 任一状态下，用户都知道“谁在控制”。
2. 任一可见按钮都有明确反馈与状态变化。
3. 不存在 dead end（可恢复或可退出）。
4. `ask_user` 两类请求都能完成闭环。
5. 主 App / LiveView / Island 间切换规则一致且可预测。
6. 失败路径有明确 next action，而不是“卡住”。

