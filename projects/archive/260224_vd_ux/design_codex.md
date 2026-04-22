status: draft

# VD UX Redesign (Codex)

Date: 2026-02-23  
Scope: `doc/todo/0.02_vd_ux/qi_note.md` 的两个问题（任务结束杀 VD、VD 输入触发主屏键盘）

## 1. TL;DR

现在的设计把不该绑在一起的东西绑死了：

1. `Task` 结束被当成 `VirtualDisplay` 生命周期结束。  
2. 文本输入路径混入了“点输入框 -> 系统键盘”这种不稳定副作用。

这两个都应该拆开。

最终设计：

1. **VD runtime 与 task 生命周期解耦**：task 完成不再 stop VD。  
2. **VD 文本输入走无键盘协议**：在 VD 下，输入是“写文本动作”，不是“弹系统键盘动作”。  
3. **废弃旧分叉逻辑**：去掉 per-task re-acquire/stop 和 tap-to-focus 相关历史路径。

---

## 2. 当前实现的根因（代码对齐）

### 2.1 Task 完成会释放 VD

在 [`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt) 的 `handleAgentComplete()`：

1. `TaskCompleted` 后直接 `services.platform.stop()`  
2. VD stop 会 release virtual display + close ImageReader（`VirtualDisplayPlatform.stop()`）

结果：YouTube 这类在 VD 里的 app 直接被终止或失去运行环境。

### 2.2 现有输入策略是“补丁式防守”

1. `TypeExecutor` 在 VD 跳过 tap-to-focus（这是对的），但点击动作本身仍可能把输入焦点/键盘副作用带出来。  
2. `VirtualDisplayPlatform.performAction()` 仅在 `SetText*` 后做一次 `dismissMainDisplayKeyboard()`，覆盖面太窄。  
3. 这导致“搜索框相关点击后，主屏突然弹键盘”依然能出现。

---

## 3. 一阶原理与系统边界

### 3.1 正确的 ownership

1. `Task` 是 agent 控制流单元。  
2. `VD runtime` 是应用执行环境单元。  
3. 控制流结束不等于环境应销毁。

把两者绑定是抽象层级错误。

### 3.2 Android 的 IME 现实约束

对普通 app 而言，app-owned virtual display 不能被当成“可靠可控的 IME 显示面板”。  
Android 官方文档明确提到 app-owned virtual display 的输入法限制（安全限制）。参考：

- [Virtual displays](https://source.android.com/docs/core/display/multi_display/three-hardware-displays#virtual-displays)

结论：**VD 下不能把“弹系统键盘”当作核心输入方案**。

---

## 4. 目标与非目标

## 4.1 目标

1. 任务结束后，VD 中 app 状态保持（至少不因 task-complete 被杀）。  
2. VD 模式下不再把主屏键盘当成正常输入机制。  
3. 简化状态机和代码路径，减少 if/补丁链。

## 4.2 非目标

1. 不做向后兼容旧行为（旧路径可直接 deprecate）。  
2. 不承诺“系统键盘稳定显示在 VD”这种平台不可靠能力。  
3. 不做大规模 UI 重做。

---

## 5. 新设计

## 5.1 生命周期重构：Session 管 task，Service 管 VD runtime

### 设计决策

1. `AgentSession` 不再拥有 VD 资源释放权。  
2. `AgentService`（或等价 service-scope manager）拥有 VD runtime。  
3. `TaskCompleted` 只清理 agent 执行态，不清理 VD runtime。  
4. 仅在“明确退出 VD 模式/Service 销毁”时 stop VD runtime。

### 最小状态模型

SessionState 保持现有枚举（`Created/Running/Paused/Idle/Shutdown`），但移除“Idle=释放VD”语义。  

新增独立 runtime 状态：

1. `VdRuntimeState.Stopped`
2. `VdRuntimeState.Running`

二者正交，不再互相嵌套。

### 直接收益

1. “播放音乐后任务结束即停播”问题消失。  
2. follow-up task 不需要 re-acquire VD，减少延迟与失败面。  
3. Session 状态语义更干净：只描述对话/执行，不描述显示设备资源。

## 5.2 文本输入重构：VD 下采用无键盘输入协议

### 核心规则

在 VD 模式，`type` 的定义是：**向目标控件写入文本**，不是“触发键盘后输入”。

### 执行策略（KISS）

1. 保留 `SetTextOnNodeAt / SetTextOnFocused` 为主路径。  
2. 明确禁止 VD 下 tap-to-focus fallback（现状已部分做到，正式上升为协议）。  
3. 对可判定为 editable 的目标，禁止先 `click` 再 `type` 的工作流。  
4. 若 `ACTION_SET_TEXT` 失败，直接失败并给出明确原因（不做隐式键盘黑魔法 fallback）。

这比一堆“失败后再点点看/弹键盘再兜底”更可预测，也更可维护。

## 5.3 主屏键盘泄漏的收敛策略

“避免触发”优先，“补救”次之。

1. 预防：agent 不再对 editable 目标执行 click。  
2. 补救：保留并收敛 keyboard dismiss 为单点 guard（仅针对 agent 引发场景触发，避免干扰用户并行操作）。

---

## 6. 代码改造计划（按最小改动顺序）

## Phase 1: 先止血（最小可用）

1. [`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt)  
   - `handleAgentComplete()` 删除 `services.platform.stop()`  
   - `reacquirePlatform()` 路径删除或退化为 no-op
2. [`app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionState.kt`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionState.kt)  
   - 注释去掉“Idle 会释放 VD”描述
3. [`doc/main/infra/session.md`](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/infra/session.md) 与 [`doc/main/ui/session/state_machine.md`](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/ui/session/state_machine.md)  
   - 同步生命周期语义

## Phase 2: ownership 收敛（最终形态）

1. 把 VD runtime ownership 从 session 下沉到 service scope manager  
2. `SessionServices.cleanup()` 不再负责 stop service-owned VD runtime  
3. `AgentService.onDestroy` 或显式“退出 VD”入口统一 stop

## Phase 3: 输入协议硬化

1. [`app/src/main/kotlin/com/moonkey/androidagent/tool/action/TypeExecutor.kt`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/TypeExecutor.kt)  
   - 明确 VD strict mode：无 tap-to-focus、无隐式键盘 fallback
2. [`app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt)  
   - VD 下点击 editable 目标时 fail-fast（提示改用 type）
3. [`app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt)  
   - 明确规则：editable 字段直接 `type`，不要先 `click`

---

## 7. 废弃项（直接 deprecate）

1. `Hot Idle = release platform` 这套语义（文档与实现一起废弃）。  
2. `reacquirePlatform()` 这类 per-task 资源重建路径。  
3. “点击输入框触发键盘再输入”的隐式假设。

---

## 8. 验收标准（必须可测）

1. VD 模式执行“打开 YouTube 播放歌曲”，任务完成后歌曲继续播放。  
2. follow-up task 启动时不再创建新 VD（log/trace 可证）。  
3. VD 搜索流程中不再出现主屏键盘突兀弹出（至少在 agent 驱动路径稳定消失）。  
4. `./gradlew test`、`./gradlew lint` 通过，且 session 相关测试更新为新语义。

---

## 9. 风险与取舍

1. VD 常驻会增加资源占用。  
   - 这是有意识取舍：换取 app 连续性和正确 UX。  
2. strict type 可能让少数不支持 `ACTION_SET_TEXT` 的页面失败。  
   - 这是有意识取舍：失败可见 > 键盘副作用 + 不可控行为。

这个方向是“少做、做对”。先把抽象层级摆正，再谈优化。
