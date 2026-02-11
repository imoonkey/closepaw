# Virtual Display UI Design

## 1. 设计目标

这次 UI 只做一件事：

- 让用户明确知道 Agent 正在「另一个屏幕」工作。
- 用户想看就秒进 Viewer，不想看就不被打扰。
- 退出 Viewer 后任务继续。
- 任务完成后把结果自然地带回真实主屏。

核心原则：**简单、可预期、无串扰**。

---

## 2. 用户痛点（当前）

- 用户不知道 Agent 当前在操作哪个 app。
- overlay（edge glow + smart capsule）偶尔出现在真实主屏，干扰用户。
- virtual display 输入时，真实主屏键盘弹出，体验割裂。
- 任务完成后，用户要自己找“到底完成在了哪里”。

---

## 3. 交互模型（三个可理解状态）

### A. Background Run（默认）

Agent 在 virtual display 执行，用户留在真实主屏。

- 主屏只显示一个顶部 `Dynamic Island`（灵动岛入口）。
- 岛内信息：`正在操作的 App 图标 + App 名 + 简短状态`。
- 点击灵动岛，进入 Viewer。

### B. Watching（用户主动观看）

用户在 `VirtualDisplayViewerActivity` 观看 Agent 操作。

- 屏幕主内容：virtual display 实时画面。
- `edge glow + smart capsule` 只在 Viewer 内显示。
- 底部上滑手势退出 Viewer（dismiss），任务不中断。

### C. Completion Handoff（任务成功完成）

任务成功后，系统将结果“带回主屏”。

- 行为定义（专业描述）：
  - **Task Continuity Handoff**：将目标 app 的用户上下文从 virtual display 过渡到主屏（display 0）的前台可见状态（best-effort continuity）。
- 用户感知：
  - 自动看到目标 app 出现在主屏前台。
  - 同时给到轻提示：`任务完成，已切到主屏查看结果`。

---

## 4. 关键界面规范

## 4.1 Dynamic Island（主屏唯一持续入口）

信息层级从左到右：

- App icon
- App name（最多 1 行）
- 状态短语（如 `登录中` / `填写地址` / `完成`）
- 可选小状态点（thinking/executing/paused/error）

交互：

- `Tap`：打开 Viewer。
- `Long press`：展开轻量操作（Pause/Resume/Stop）。

显示规则：

- 仅在 `platformMode == VIRTUAL_DISPLAY` 且任务活跃时显示。
- 不显示 edge glow。
- 不覆盖用户输入区域，不抢焦点。

---

## 4.2 Viewer 页面

布局：

- 全屏画面：virtual display 视频流（letterbox 适配）。
- 顶部：轻量标题条（当前 app + 执行阶段）。
- 内容层上方：`edge glow`（四边），状态驱动颜色。
- 底部：`smart capsule`（Pause/Resume/Stop/Open App）。

退出手势：

- 从底部安全区向上滑（达到阈值）=> 关闭 Viewer。
- 关闭后返回用户此前页面，任务继续执行。

状态反馈：

- 成功：边缘 glow 变成功色，胶囊显示 `Done` 后自动淡出。
- 失败：边缘 glow 错误色，胶囊显示错误摘要。
- 暂停：胶囊主按钮变为 Resume。

---

## 4.3 Completion Handoff 文案与行为

触发条件：

- `CompletionReason.GOAL_ACHIEVED`。

默认行为：

- 自动将目标 app bring-to-front 到主屏。
- 若用户仍在 Viewer：先展示完成态 1.2s，再切主屏 app。
- 若用户不在 Viewer：直接切主屏 app，并在灵动岛显示完成提示 2s。

失败回退：

- 若无法完成 handoff，则弹出提示：`任务已完成，点击查看结果 App`。
- 用户点岛后仍可查看 virtual display 最后一帧与目标 app 信息。

---

## 5. 视觉与动效原则

- 动效只保留三类：
  - 灵动岛入场/退场。
  - Viewer 进入/退出（淡入 + 轻微缩放）。
  - glow 状态切换（颜色过渡）。
- 动效目标是“状态清晰”，不是“炫技”。
- 颜色语义保持现有项目语义：active / executing / success / error / paused。

---

## 6. 体验验收标准（UI）

1. Virtual display 运行时，主屏不再出现 edge glow 与 smart capsule。
2. 灵动岛始终可反映当前操作 app（名称与图标可见）。
3. 点击灵动岛 <= 300ms 进入 Viewer。
4. Viewer 中上滑退出成功率 > 99%，且不会中断任务。
5. 任务成功后 1 次动作内可看到主屏目标 app（或明确回退提示）。
6. 全流程中用户不需要理解“displayId”等技术概念。

