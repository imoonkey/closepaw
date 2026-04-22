status: draft

# Proactive Agent UX Design (Codex)

Date: 2026-02-16  
Scope: 将 Android Agent 从“被动触发”升级为“事件驱动的主动协作”

---

## 1. Problem First

### 1.1 当前真实问题
当前 agent 只能在用户手动输入目标后启动。用户在“真实发生关键事件”的时刻（新通知、临近日历事件、新照片等）无法被及时支持，导致：
- 用户错过“当下最该做的动作”。
- 用户需要反复手动打开 app、描述上下文、再触发执行。
- agent 价值停留在“工具”，而不是“随时待命的助手”。

### 1.2 谁在什么场景遇到问题
- 忙碌型用户：消息很多、日程密集，最容易漏重要动作。
- 执行型用户：希望“看到建议就能一键做完”，不想重新描述任务。
- 场景：切 app 中、锁屏后、开会前、网络波动时、权限不完整时。

### 1.3 为什么重要
如果不能在“事件发生时”主动介入，agent 会持续被当成“慢一步的问答机器人”，而不是能提高完成率与响应速度的执行助手。

### 1.4 成功定义（行为变化）
解决后应出现以下变化：
- 用户从“手动发起任务”为主，转为“直接处理系统给出的可执行建议”为主。
- 重要事件有明确处置结果（做了/稍后/忽略），不再沉没。
- 用户对主动能力保持信任：少误报、可控、可撤销。

---

## 2. Product Goal and Non-Goal

## 2.1 Product Goal
构建一个“可控的 proactive loop”：
1. 监听可合法获取的系统事件。  
2. 将事件标准化为 Trigger。  
3. 在不打扰用户的前提下给出下一步建议。  
4. 必要时一键进入既有 `Op.UserInput(...)` 任务循环执行。

## 2.2 Non-Goal
- 不做“全量实时监控用户所有 app 行为”的承诺。
- 不在后台无感执行高风险 UI 操作。
- 不把 proactive 做成无限打扰型通知系统。

---

## 3. Experience Design (Simplest End-to-End)

## 3.1 体验主线
最简路径：`事件发生 -> 价值判定 -> 建议卡片 -> 一键执行/稍后/忽略 -> 可追踪结果`

### Step A: 事件进入（Listen）
首版只支持高价值、边界清晰的三类 Trigger：
- `NotificationPosted`
- `CalendarUpcoming(T-10min / T-30min)`
- `NewPhoto`（仅可访问范围内：你 app 产生或用户已授权范围）

### Step B: 事件理解（Decide）
系统对每条 Trigger 产出：
- `importance`: high / medium / low
- `confidence`: 0~1
- `suggestedAction`: 一句话可执行建议
- `needsApproval`: 是否必须用户确认

### Step C: 用户触达（Present）
统一使用“Proactive Card”（通知 + Smart Capsule + 主界面 Feed 三端一致语义）：
- 标题：发生了什么
- 建议：现在做什么
- 操作：`立即处理` / `稍后提醒` / `忽略` / `静音此类`

### Step D: 执行（Act）
- `立即处理`：转成结构化 prompt，调用既有 `Op.UserInput(...)` 启动任务。
- `稍后提醒`：进入 Snooze 队列并给下次提醒时间。
- `忽略`：本条事件关闭。
- `静音此类`：更新用户偏好，降低后续打扰。

### Step E: 反馈（Close the loop）
执行结束必须回写结果：
- 成功：给出结果摘要和“撤销/继续”入口（如适用）。
- 失败：给出失败原因 + 重试按钮。
- 超时/中断：给出恢复入口（继续执行或改为手动）。

---

## 4. Key Tradeoffs and Decisions

1. 自动化能力 vs 用户信任  
选择：`默认建议优先，自动执行需用户显式开启`（按 Trigger 类型细粒度开关）。

2. 召回率 vs 准确率  
选择：`准确率优先`，先少而准，避免“狼来了”导致用户关闭功能。

3. 即时性 vs 续航  
选择：事件驱动优先，必要时轻量轮询；重计算延后到 Worker。

4. 全能监控想象 vs 平台现实  
选择：严格在 Android 合法能力边界内设计，不承诺无法稳定/合规交付的能力。

---

## 5. State Machine Spec

## 5.1 Proactive Engine 状态机

### States
- `Disabled`: 主动模式关闭。
- `PermissionSetup`: 功能开启但关键权限未就绪。
- `Monitoring`: 正常监听事件。
- `Triage`: 收到 Trigger，进行价值判定与去重。
- `AwaitingUserDecision`: 已发卡片，等待用户选择。
- `Executing`: 已启动 agent loop 处理中。
- `Cooldown`: 事件刚处理完，短时抑制重复打扰。
- `Degraded`: 关键依赖异常（离线/配额/服务不可用），降级运行。

### Transitions / Triggers / Guards / Side Effects
1. `Disabled -> PermissionSetup`  
trigger: 用户打开“主动模式”  
guard: 存在未授权能力  
side effect: 展示权限向导与价值说明。

2. `PermissionSetup -> Monitoring`  
trigger: 必需权限满足  
guard: 监听组件健康  
side effect: 启动监听、初始化去重缓存。

3. `Monitoring -> Triage`  
trigger: 收到新 Trigger  
guard: 非静音、非重复、未超频  
side effect: 计算 `importance/confidence/suggestedAction`。

4. `Triage -> AwaitingUserDecision`  
trigger: `needsApproval=true` 或用户策略为“先确认”  
side effect: 发 Proactive Card（带操作按钮）。

5. `Triage -> Executing`  
trigger: `needsApproval=false` 且策略允许自动执行  
guard: 当前无冲突任务  
side effect: 生成 prompt，调用 `Op.UserInput(...)`。

6. `AwaitingUserDecision -> Executing`  
trigger: 用户点 `立即处理`  
side effect: 启动任务并更新卡片状态为 Running。

7. `AwaitingUserDecision -> Cooldown`  
trigger: 用户点 `忽略` 或卡片过期  
side effect: 记录反馈，抑制同类短期重复。

8. `AwaitingUserDecision -> Monitoring`  
trigger: 用户点 `稍后提醒`  
side effect: 写入 Snooze 队列并返回监听。

9. `Executing -> Cooldown`  
trigger: TaskCompleted/TaskFailed/Interrupted  
side effect: 发送结果卡片，记录 outcome。

10. `Cooldown -> Monitoring`  
trigger: 冷却期结束  
side effect: 清理临时状态。

11. `Any -> Degraded`  
trigger: 网络不可用/LLM不可用/服务异常  
side effect: 切为低打扰模式，仅保留关键提醒。

12. `Degraded -> Monitoring`  
trigger: 依赖恢复健康  
side effect: 恢复标准策略并补发未过期关键提醒。

## 5.2 每个状态的交互定义

### `Disabled`
- 用户看到：设置页“主动模式已关闭”。
- 用户可做：打开总开关。
- 系统响应：无监听，无卡片。
- empty/error：无。

### `PermissionSetup`
- 用户看到：按能力分组的权限步骤（通知、日历、照片）。
- 用户可做：`去授权`、`暂不开启`。
- 系统响应：实时显示已完成项。
- error：授权失败时显示可恢复文案与重试入口。

### `Monitoring`
- 用户看到：平时无打扰；主界面可见“正在守护”状态。
- 用户可做：管理静音规则、调整敏感度。
- 系统响应：后台接收并归一化 Trigger。
- empty：长时间无事件时显示“暂无需要处理的事件”。

### `Triage`
- 用户看到：通常无感。
- 用户可做：无直接操作。
- 系统响应：去重、节流、打分、选策略。
- error：判定失败时降级为“仅通知原事件，不触发 agent”。

### `AwaitingUserDecision`
- 用户看到：Proactive Card（事件 + 建议 + 操作按钮）。
- 用户可做：`立即处理` / `稍后提醒` / `忽略` / `静音此类`。
- 系统响应：每个动作立即给 toast/状态反馈。
- timeout：过期后卡片变 `已过期`，提供“重新生成建议”。

### `Executing`
- 用户看到：Smart Capsule 显示任务进行中状态（含停止/接管能力）。
- 用户可做：`停止`、`接管`、`补充说明`。
- 系统响应：调用既有 session loop，持续回传进度。
- error：失败时显示“重试本次/改为手动”。

### `Cooldown`
- 用户看到：该事件“已处理/已忽略”的轻提示。
- 用户可做：撤销忽略（短时间窗口内）。
- 系统响应：抑制相同 dedupeKey 的重复触发。

### `Degraded`
- 用户看到：顶部提示“主动能力受限（离线/权限/服务）”。
- 用户可做：`重试连接`、`查看原因`、`临时关闭`。
- 系统响应：只保留最关键提醒，不自动执行。

---

## 6. Interaction Contract: 按钮与反馈（No Broken Windows）

## 6.1 Proactive Card 按钮规范
1. `立即处理`  
- 目的：立刻把建议转成任务执行。  
- 可用条件：会话空闲；依赖可用。  
- 禁用条件：已有运行任务或依赖不可用。  
- 反馈：进入 Running 状态 + 进度可视化。

2. `稍后提醒`  
- 目的：延迟处理但不丢失。  
- 可用条件：始终可用。  
- 反馈：显示具体提醒时间（例如“15 分钟后提醒”）。

3. `忽略`  
- 目的：关闭本条事件。  
- 可用条件：始终可用。  
- 反馈：标记已忽略，可在短窗口撤销。

4. `静音此类`  
- 目的：减少未来噪音。  
- 可用条件：同源 Trigger 出现频率超过阈值后高亮。  
- 反馈：明确静音范围与时长（如“2 小时内静音来自 X 的低优先级通知”）。

## 6.2 文案一致性
- 建议文案格式统一为：`发生了X，建议你现在做Y，预期收益Z`。
- 所有失败态必须给“下一步动作”，禁止只显示错误码。

---

## 7. Edge Cases and Failure Handling

1. 权限未授予/被撤销  
- 行为：立即降级到 `PermissionSetup` 或 `Degraded`。  
- UX：显示缺失权限对功能影响，不中断其他可用 Trigger。

2. 网络离线或 LLM 超时  
- 行为：生成“简化建议”（规则引擎）或仅提醒原事件。  
- UX：显示“已离线降级”，给重试按钮。

3. 事件洪峰（短时间大量通知）  
- 行为：聚合成单张摘要卡。  
- UX：默认展示 Top 3 重要项，其余收起。

4. 会话忙碌时新 Trigger 到来  
- 行为：进入等待队列，按优先级排序。  
- UX：提示“已加入待处理队列”。

5. 用户中途取消执行  
- 行为：发送 `Interrupt/Shutdown`，保留上下文可恢复。  
- UX：提供“稍后继续此任务”。

6. 无效输入或语义不清  
- 行为：先 ask_user 澄清，再执行。  
- UX：给 2~3 个快速选项，避免纯文本重输。

7. 部分失败（只完成一半动作）  
- 行为：返回已完成与未完成列表。  
- UX：一键“继续未完成部分”。

8. 锁屏/前台限制  
- 行为：不强行执行 UI 操作，转为“解锁后继续”。  
- UX：通知文案说明等待原因。

---

## 8. MVP Scope (Phase-by-Phase)

## Phase 1: 可上线的最小主动能力
- Trigger: `NotificationPosted` + `CalendarUpcoming`
- 策略: 默认“先建议后执行”
- 渠道: 通知卡片 + 主界面 Proactive Feed
- 结果: 用户可一键触发既有 agent loop

## Phase 2: 增强自动化
- 增加 `NewPhoto`（受权限边界约束）
- 增加“低风险自动执行”开关（按 Trigger 类型）
- 增加跨事件聚合（例如“通勤前准备包”）

## Phase 3: 个性化与学习
- 基于用户历史反馈自适应阈值
- 精细化静音策略（来源/时间段/重要性）
- 增加“为什么推荐我”解释层，提升信任

---

## 9. Success Metrics

## 9.1 核心指标
- `Trigger->Action Rate`: 有多少 Trigger 最终产生用户行动。
- `Median Time To Action`: 从事件发生到开始执行的中位时长。
- `Proactive Acceptance Rate`: `立即处理` 点击率。
- `False Positive Rate`: 被 `忽略/静音` 的占比。
- `Task Completion Rate`: proactive 触发任务完成率。

## 9.2 守护指标
- 打扰投诉率（关闭主动模式、全局静音、负反馈）。
- 电量与后台资源占用。
- 权限流失率（用户撤销权限比例）。

---

## 10. 与现有系统对齐（实现前约束）

- 保持“执行入口单一化”：最终仍走 `Op.UserInput(...)`，不引入平行任务协议。  
- 保持 Smart Capsule 心智一致：proactive 只改变“何时触发”，不改变“如何执行”。  
- 保持安全边界：高风险动作必须可见、可停、可接管。  
- 保持可回放：每个 Trigger 的判定与用户选择都要可审计。

---

## 11. 一句话产品定义

Proactive Agent 不是“替用户做所有事”，而是“在关键时刻把正确的下一步，变成一次可控的一键执行”。
