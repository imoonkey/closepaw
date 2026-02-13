# Smart Capsule Round1+2+3 Overall Code Review (Codex)

## Scope
- Diff base: `339448dd127c6de7a6612f918be8a7d9351ff7b1..HEAD`
- Focus: `app/` (按你的要求)
- Review focus: 架构合理性、可维护性、KISS/奥卡姆剃刀下的简化空间
- 说明: 我跑了相关新增单测（`UserResponseChannelTest`、`CapsuleStateHolderTest`、`CapsuleModeTest`），当前通过；但这些测试没有覆盖关键结构风险

## Executive Summary
当前代码已经把 Smart Capsule 的核心体验框架搭起来了（state holder、overlay/compose 双端渲染、VD island + viewer联动）。

但从 round1→round3 的连续迭代痕迹看，出现了典型“功能完成但结构变重”的状态：
- `ask_user` 握手有可靠性缺口（可能吞用户响应，且 UI 先乐观跳状态）
- state/renderer/orchestrator 之间职责还不够收敛，导致重复逻辑和行为漂移
- 关键类体量过大，跨层依赖开始出现，后续改动成本会快速上涨

结论：**CHANGES_REQUESTED（先做结构收敛，再继续加功能）**。

## Critical
1. `ask_user` 响应链路存在“先发响应被丢弃 + UI 先跳状态”的死锁风险
- Evidence:
`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/AskUserTool.kt:121`
`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/AskUserTool.kt:126`
`app/src/main/kotlin/com/moonkey/androidagent/session/UserResponseChannel.kt:33`
`app/src/main/kotlin/com/moonkey/androidagent/session/UserResponseChannel.kt:51`
`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:336`
`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:57`
- Why this is critical:
`AskUser` 事件先发出，`awaitResponse()` 的 pending 注册在后面；若用户响应先到，`deliver()` 会返回 false 并丢弃。与此同时 overlay UI 已在 `onUserResponse` 回调里提前切回 Running，用户看起来“已发送”，但 agent 实际仍在等待，可能卡到超时。
- KISS fix:
引入 `preparePending(callId)` 两阶段协议（先注册 pending，再发 AskUser 事件），并让 `UserResponseChannel` 支持 early-response 缓存（按 `callId`）。同时新增 `UserResponseAccepted/UserResponseRejected` 事件，UI 只在 accepted 后转状态。

## High
1. ACCESSIBILITY 模式下 overlay capsule 的显示策略和前台上下文解耦不彻底，容易在主 App 场景错误弹 overlay
- Evidence:
`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:347`
`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:356`
`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:426`
`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt:122`
- Why high:
`pushModeToOverlayCapsule()` 是无前台守卫的直接渲染入口；只要 mode 非 Hidden，`renderMode()` 就可能触发 `show()`。这会把“状态推进”和“是否应显示系统 overlay”耦在一起，后续极易引入主 App 内重复 UI（Compose capsule + Window overlay 同时出现）问题。
- KISS fix:
把“状态更新”与“可见性策略”分开：先在 controller 里统一做 `shouldRenderSystemOverlay()` 判定，再调用 renderer。

2. Main App 下 `👁 Watch` 按钮可出现但回调是 no-op，用户操作无响应
- Evidence:
`app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/SmartCapsuleCompose.kt:304`
`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:134`
- Why high:
UX 上是明确可点击控件，但行为空实现，属于可见功能失效。
- KISS fix:
二选一：
1) 在 MAIN_APP 明确隐藏 `👁`，或
2) 在 `ChatScreen` 接入真实 `onNavigate(NavAction.OPEN_VIEWER)`。

3. 同一套 Capsule 语义在 View renderer 与 Compose renderer 双份实现，已经出现行为漂移
- Evidence:
`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleRenderer.kt:317`
`app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/SmartCapsuleCompose.kt:83`
`app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/SmartCapsuleCompose.kt:320`
- Why high:
当前两套渲染器都在“翻译 CapsuleMode→UI细节”，规则复制会长期漂移。已看到一个实例：View 端在切入 `WaitingForInput` 时清空输入框；Compose 端没有对应转换逻辑。
- KISS fix:
抽出单一 `CapsulePresentationModel`（纯 Kotlin），由它统一产出“按钮、文案、placeholder、row 可见性、nav 可见性”等，View/Compose 只负责绘制。

4. 核心 agent 层反向依赖 UI 层工具函数，分层被打穿
- Evidence:
`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:31`
`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:447`
- Why high:
`agent` 依赖 `ui.overlay.model.sanitizeThought`，后续 UI 改动会影响核心执行层，破坏可替换性和可测试性。
- KISS fix:
把 `sanitizeThought` 下沉到 `agent`/`protocol` 公共 domain util（例如 `protocol/ThoughtTextSanitizer.kt`），UI 和 Agent 共用它，而不是单向依赖 UI 包。

5. 多个关键类已经超过项目约定的 400 行，职责边界模糊
- Evidence:
`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt` (788)
`app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt` (540)
`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt` (511)
`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt` (438)
`app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/SmartCapsuleCompose.kt` (410)
`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleLayoutBuilder.kt` (404)
`app/src/main/kotlin/com/moonkey/androidagent/history/HistoryManager.kt` (402)
- Why high:
超大文件不是风格问题，而是变更风险问题：一个 bugfix 经常会跨 3-4 个关注点一起改，回归概率高。
- KISS fix:
按职责切片，不按“阶段历史”切片。

## Medium
1. `AgentSession.events` 用 replay SharedFlow 解决“晚订阅”，但没有 event id/去重策略
- Evidence:
`app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:127`
`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:87`
- Risk:
重连 collector 或恢复 session 时可能重放旧事件，UI 重复插入 message/banner。
- Suggestion:
给 `AgentEvent` 加 `eventId`，UI 侧做 last-seen 去重，或者在 session 层区分“stateful stream”和“transient stream”。

2. Chat 层直接拉 `AgentService.instance`，UI 与 Service 生命周期强耦合
- Evidence:
`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:65`
- Risk:
状态来源不可注入，不利于单测和预览；service 重建后的重连逻辑不显式。
- Suggestion:
通过 ViewModel 注入 `CapsuleStateHolder`/`StateFlow`（由 Activity 组装），避免 Composable 直连 singleton。

3. `UserResponseChannel` 的并发保护是 volatile 双字段，不是原子状态机
- Evidence:
`app/src/main/kotlin/com/moonkey/androidagent/session/UserResponseChannel.kt:24`
`app/src/main/kotlin/com/moonkey/androidagent/session/UserResponseChannel.kt:34`
- Risk:
`pending` 与 `pendingCallId` 的可见性不是“事务一致性”；理论上仍可出现瞬时不一致读。
- Suggestion:
改成 `Mutex + data class Pending(callId, deferred)` 或单 `AtomicReference<PendingState>`。

4. 有一些 API 参数目前是“名义存在、实际未使用”
- Evidence:
`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:51`
`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:87`
- Risk:
接口噪音会误导调用方，以为 taskId/callId 在状态机里有语义。
- Suggestion:
要么真正用起来（如用于幂等/校验），要么删掉参数保持最小接口。

## Low
1. `StatusIslandManager` 留有已降级后的残余状态字段
- Evidence:
`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/StatusIslandManager.kt:39`
`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/StatusIslandManager.kt:89`
- Suggestion:
继续清理 dead state，保持 island 为纯状态显示组件。

## Structural Refactor Plan (KISS + Occam)

### Phase A (必须先做，1-2天)
1. 修 `ask_user` 握手协议
- 引入 `preparePending(callId)` + early response 缓冲。
- 新增 `AgentEvent.UserResponseAccepted/Rejected`。
- UI 仅在 accepted 后从 Waiting* 切回 Running。

2. 统一系统 overlay 的可见性守卫
- 把 `pushModeToOverlayCapsule()` 包在 `if (shouldRenderSystemOverlay())`。
- `shouldRenderSystemOverlay()` 只看 `platformMode + context + foreground + hasActiveTask`。

3. 修 MAIN_APP 的 `👁` 行为
- 要么隐藏，要么接入真实导航。

### Phase B (结构降重，2-4天)
1. 抽 `CapsulePresentationModel`
- 输入：`CapsuleMode + CapsuleContext + PlatformMode`
- 输出：Row可见性、按钮集、文案、nav显示、输入模式。
- `SmartCapsuleRenderer` 和 `SmartCapsuleCompose` 只渲染该 model。

2. 分拆 `ServiceOverlayController`
- `CapsuleStateCoordinator`（只做状态转换）
- `A11yOverlayDriver`（capsule+glow）
- `VirtualDisplayOverlayDriver`（island+viewer联动）

3. 下沉跨层工具
- `sanitizeThought` 迁到 domain/protocol util。

### Phase C (稳定性与可测试性，持续)
1. 给 `AgentEvent` 增 eventId，补 UI 去重。
2. 给 navigator 场景补集成测试：MAIN_APP ↔ SCREEN_VIEWING ↔ BACKGROUND。
3. 逐步把 >400 行文件拆为小组件（先拆 `AgentService`/`ServiceOverlayController`）。

## Suggested Test Additions (针对结构，不是 edge-case 微修)
1. `ask_user` 先响应后 await 的竞态回归测试（应不丢响应）。
2. ACCESSIBILITY + MAIN_APP 前景时，系统 overlay 不应被 `ThoughtUpdate` 拉起。
3. MAIN_APP + VD 模式下，`👁` 按钮行为测试（显隐与点击行为一致）。
4. `CapsulePresentationModel` parity 测试（Compose/View 同输入同输出）。

## What Is Already Good
1. `CapsuleStateHolder` 把模式定义集中化，这是正确方向。
2. `StatusIslandManager` round3 的简化（移除 inline controls）是向 Occam 前进的一步。
3. 为 `CapsuleMode`、`CapsuleStateHolder`、`UserResponseChannel` 补了单测，基础盘已建立。

## Final Recommendation
- 结论: **CHANGES_REQUESTED**
- 优先级: 先做 Phase A（协议与可见性守卫），再做 Phase B（去重与解耦）
- 原因: 当前多数“小 bug”其实是结构耦合和双实现漂移的表象，先收敛结构可显著降低后续修 bug 成本
