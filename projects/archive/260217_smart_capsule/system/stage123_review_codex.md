# Review: Smart Capsule V2 (vs `339448dd127c6de7a6612f918be8a7d9351ff7b1`)

## Scope
- 评审范围：`339448d..HEAD` 的 Smart Capsule V2 相关改动。
- 对照文档：
  - `doc/todo/0.02_smart_capsule/system/stage_1_capsule_foundation.md`
  - `doc/todo/0.02_smart_capsule/system/stage_2_takeover_supplement.md`
  - `doc/todo/0.02_smart_capsule/system/stage_3_ask_user.md`
  - `doc/todo/0.02_smart_capsule/ux_design_1.md`
  - `doc/todo/0.02_smart_capsule/qi_note.md`
  - `doc/todo/0.02_smart_capsule/qi_ui.md`

## Summary
- 结论：**未达到“全部完整实现”**。
- 推荐：**CHANGES_REQUESTED**（先修高优先级功能缺口，再宣称 fully implemented）。
- 已验证测试：
  - `./gradlew :app:testDebugUnitTest --tests "*UserResponseChannelTest" --tests "*CapsuleModeTest"` 通过。

## Critical / High Findings

1. **[High] `ask_user` 工具在现有 agent 配置下实际上不可调用**
- 影响：Stage 3 的核心能力在运行时不可用，`ask_user` 流程无法真正触发。
- 证据：
  - `ask_user` 注册了：`app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt:112`
  - 但三个 AgentDef 的 `allowedTools` 都不包含 `ask_user`：
    - `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt:8`
    - `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt:8`
    - `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt:8`
  - Turn 层会过滤不在 allowlist 的 tools：`app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:150` 和 `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:158`

2. **[High] Takeover 状态时序与设计不一致，且可能在“已接管UI”期间继续执行动作**
- 影响：用户看到已进入 Takeover，但 agent 可能仍在执行当前 turn 的后续 tool calls，破坏“可预期接管”。
- 证据：
  - `SessionTakeover` 在 `pause()` 后立即发出：`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:291` 和 `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:294`
  - 但执行循环不会因 `pause` 打断当前 turn 的后续 tools：`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:471`
  - 设计文档要求：current action 完成后再进入 takeover，并暗含未开始部分不继续：`doc/todo/0.02_smart_capsule/system/stage_2_takeover_supplement.md:95`

## Medium Findings

1. **[Medium] VD 模式下 `ask_user` 没有可完成响应的 UI 通道**
- 影响：问答/操作确认在 VD 背景路径中可能卡死等待。
- 证据：
  - VD 下收到 AskUser 仅更新 island 文案：`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:306`
  - StatusIsland 长按展开只有 pause/resume + stop，无输入/完成按钮：`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/StatusIslandManager.kt:205`

2. **[Medium] `ux_design_1` 里的多上下文导航按钮 [1][2][3] 未实现**
- 影响：文档中的跨上下文导航模型无法使用。
- 证据：
  - 设计定义 [1][2][3]：`doc/todo/0.02_smart_capsule/ux_design_1.md:560`
  - Capsule Layout 当前仅有 [补充][接管/继续][停止]，无 nav 区：`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleLayoutBuilder.kt:136`

3. **[Medium] `ux_design_1` 的 Main App 底部胶囊替换 InputDock 未实现**
- 影响：主 App 与 overlay 不一致，违背“同一心智模型”。
- 证据：
  - 设计要求替换 input dock：`doc/todo/0.02_smart_capsule/ux_design_1.md:602`
  - ChatScreen 仍固定渲染 `InputDock`：`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:105`

4. **[Medium] Stage 3 timeout nudge（4分钟提醒）未实现**
- 影响：长等待场景缺少预期提醒。
- 证据：
  - 设计要求 4 分钟 nudge：`doc/todo/0.02_smart_capsule/system/stage_3_ask_user.md:292`
  - 代码只有 5 分钟 `withTimeoutOrNull`，无中间事件：`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/AskUserTool.kt:125`

5. **[Medium] “ask_user 与 supplement 冲突顺序化”未实现**
- 影响：用户正在输入 supplement 时可能被 WaitingFor* 直接打断。
- 证据：
  - 设计要求“先完成 supplement，再切 ask_user”：`doc/todo/0.02_smart_capsule/ux_design_1.md:634`
  - 当前收到 AskUser 直接 `updateMode(WaitingFor*)`：`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:304`

6. **[Medium] Stage 3/UX 里 WaitingFor* 的展开/收起动画未实现**
- 影响：与设计期望不一致（无 200ms expand/collapse 过渡）。
- 证据：
  - 设计要求动画：`doc/todo/0.02_smart_capsule/system/stage_3_ask_user.md:339`、`doc/todo/0.02_smart_capsule/ux_design_1.md:700`
  - 当前 `updateMode` 直接切状态渲染，无高度动画逻辑：`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt:76`

## Low Findings

1. **[Low] Row1 “点击打开主 App”未接线**
- 影响：文档定义的快捷入口缺失。
- 证据：
  - 设计要求 row1 可点击打开 app：`doc/todo/0.02_smart_capsule/ux_design_1.md:75`
  - Manager 暴露了 `onOpenApp` 但 build/render 未绑定 row1 点击：`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt:43`、`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleLayoutBuilder.kt:91`

2. **[Low] supplement “已收到”延迟恢复文本 runnable 可能覆盖更新后的 thought**
- 影响：极端并发下 thought 文案回退到旧值。
- 证据：
  - 设置延迟恢复：`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt:519`
  - `updateMode` 未清理该 runnable：`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt:81`

3. **[Low] TaskStarted 默认 thought 与文档偏差**
- 影响：启动时显示用户输入片段而非“思考中...”。
- 证据：
  - 代码：`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt:605`

---

## Q1. Stage 1/2/3 是否完整实现？

| Stage | 结论 | 说明 |
|---|---|---|
| Stage 1 | **部分完成** | CapsuleMode、ThoughtUpdate、两行 UI 基本到位；但 row1 open-app 交互未落地，且若按 UX1 口径的 nav 扩展位也未实现。 |
| Stage 2 | **部分完成** | Takeover/Supplement 主流程存在；但 takeover 事件时序与“真正暂停点”不一致，当前 turn 后续动作仍可能继续执行。 |
| Stage 3 | **部分完成（关键缺口）** | ask_user tool 与等待态 UI 已写；但 `ask_user` 在 allowlist 下不可触发、VD 下不可答复、4分钟 nudge 未做、部分交互边界未做。 |

## Q2. `ux_design_1.md` 是否实现完？未完成属于“没设计完”还是“设计了没实现”？

结论：**没有实现完**，且主要是“**设计已明确但未实现**”。

| UX项 | 设计状态 | 实现状态 | 分类 |
|---|---|---|---|
| 核心胶囊状态机（Running/Takeover/Waiting/Done/Error） | 已明确 | 基本实现 | 已实现（部分细节偏差） |
| agent_thought 管线 | 已明确 | 已实现 | 已实现 |
| takeover/supplement 基础交互 | 已明确 | 已实现 | 已实现（存在时序问题） |
| ask_user question/action 形态 | 已明确 | 部分实现 | 设计已明确但实现不完整 |
| ask_user 4分钟 nudge | 已明确 | 未实现 | 设计已明确但未实现 |
| WaitingFor* 展开/收起动画 | 已明确 | 未实现 | 设计已明确但未实现 |
| Row1 点击打开主 App | 已明确 | 未实现 | 设计已明确但未实现 |
| [1][2][3] 导航按钮矩阵 | 已明确 | 未实现 | 设计已明确但未实现 |
| Main App 底部用 Smart Capsule 替换 InputDock | 已明确 | 未实现 | 设计已明确但未实现 |
| Island 长按展开后 [2][3] 导航 | 已明确 | 未实现（仅pause/stop） | 设计已明确但未实现 |

## Q3. `qi_note.md` 与 `qi_ui.md` 的想法是否实现完？

### `qi_note.md`

| 原始想法 | 结论 |
|---|---|
| 1. agent thought 从 prompt 到 UI | **部分完成**：UI 管线已接；但并未在所有 Agent prompt 中形成统一“必须带 agent_thought”的约束。 |
| 2. pause=接管，resume 前取消未完成 tool_call 并基于新屏幕继续 | **未完成**：fresh perceive 基本符合，但“取消未完成 tool_call”未满足。 |
| 3. 补充按钮注入 user message | **已实现** |
| 4. ask_user（question/action）+ UI | **部分完成**：框架已实现，但存在可达性与场景覆盖缺口（见 High/Medium）。 |

### `qi_ui.md`

- 文档本身在开头已声明“只是大概，后半 button 细节很多不对或未写完”：`doc/todo/0.02_smart_capsule/qi_ui.md:1`。
- 所以该文档未完成项分两类：
  - **设计未完成**：`qi_ui` 自己承认的后半细节。
  - **设计已提出但未实现**：主 app/观看/后台三态一致性、[1][2][3] 按钮矩阵、状态切换路径（A/B）等。

## Testing Gaps
- 缺少 `AskUserTool` 单测与端到端测试（当前仅 `UserResponseChannelTest` + `CapsuleModeTest`）。
- 缺少 takeover 时序/中断边界的自动化回归测试。
- 缺少多上下文（Main App / A11y overlay / VD island）一致性测试。

## Recommendation
- 先修复 High 项（尤其 `ask_user` 可达性 + takeover 时序一致性）后再标记“Stage 1/2/3 fully implemented”。
