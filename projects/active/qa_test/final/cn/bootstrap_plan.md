# QA Test Bootstrap — 先写什么

基于实际代码（`ai.closepaw.ui.{settings,capsule,chat,overlay}`）的 behavior inventory 整理。遵循 KISS：**每条都是一个独立 test，state seed + assertion，不需要 LLM，不跨 app**。

目的：
- 拉出一张可执行的 first-batch test 清单
- 每一条都对应真实 Compose 行为，价值清晰
- 写完这批后，后续 bug 报告可以直接挂到已有 test 框架下扩展

---

## Batch 0 — Gradle baseline（一次性）

> 不是 test，是让后面的 test 能跑。写完后再也不用碰。

- `testInstrumentationRunner = AndroidJUnitRunner`
- `testOptions.animationsDisabled = true`
- 依赖：`ui-test-junit4` + `ext:junit` + `ui-test-manifest (debug)`
- 一个 sanity test：启动 `MainActivity`，断言屏幕上有"New Chat"或任何稳定锚点。跑绿就 OK。

---

## Batch 1 — Chat UI（自包含、最稳、价值最高，先做这批）

> 全部可用 `createAndroidComposeRule<MainActivity>()` 或直接测 `@Composable` 函数 + `setContent`，不需要真 ViewModel、不需要 LLM。

| # | Test | 行为 | State seeding |
|---|---|---|---|
| C1 | `EmptyState_ShowsThreeSuggestions` | 空会话显示三条 suggestion chip | messages = empty |
| C2 | `EmptyState_TapSuggestionFiresCallback` | 点 suggestion → 回调带正确文本 | 同上 |
| C3 | `Header_NewChatButtonHiddenWhenEmpty` | 空会话时 New Chat 按钮不显示 | messages = empty |
| C4 | `Header_NewChatButtonVisibleWithMessages` | 有消息时 New Chat 按钮可见 | messages = 1 user msg |
| C5 | `Message_UserBubbleAlignedRight` | 用户消息右对齐 | 1 user msg |
| C6 | `Message_AgentBubbleAlignedLeft` | Agent 消息左对齐 | 1 agent msg |
| C7 | `ThinkingIndicator_ShownWhenAgentThinkingNoContent` | state=Thinking 且无 content → 三点 | agent msg, Thinking, empty blocks |
| C8 | `ThinkingIndicator_HiddenOnceContentArrives` | 有 content 后三点消失 | agent msg with text block |
| C9 | `StreamingText_CursorShownOnLastBlockWhileStreaming` | 最后一块 streaming 时有 cursor | streaming=true |
| C10 | `ActionCard_StateDeterminesVisuals` | 每个 state 的 icon/颜色正确（Proposed/Executing/Success/Failed/Skipped 参数化） | 5 个 state 各 seed 一次 |
| C11 | `ActionCard_TapTogglesExpandedContent` | 点卡片展开/收起 expandedContent | card with expandedContent |
| C12 | `ScrollFab_VisibleWhenScrolledUp` | 手动上滑 → FAB 出现 | 30 条 msg，模拟上滑 |
| C13 | `ScrollFab_TapScrollsToBottomAndHides` | 点 FAB → 回到底部、FAB 隐藏 | 同上 |

---

## Batch 2 — SmartCapsule（状态机，测试价值极高）

> Capsule 是个 state machine，每个 state 有明确 UI 契约。**测状态 → UI 映射**，不测转换路径里的 ViewModel 逻辑（那是 unit test）。

用参数化 test 或一组 test per state，直接 `setContent` 注入 `CapsuleState`。

| # | Test | 行为 |
|---|---|---|
| K1 | `Hidden_RendersNothing` | Hidden 状态不绘制任何 capsule 控件 |
| K2 | `Running_ShowsThoughtAndTakeoverButton` | Running 显示 thought、Takeover 按钮 |
| K3 | `Running_ShowsStopButton` | Running 有 Stop 按钮 |
| K4 | `Takeover_ShowsResumeInsteadOfTakeover` | Takeover 状态下按钮文字变 Resume |
| K5 | `WaitingForInput_ExpandsWithTextFieldAndHint` | 显示 input field + hint + 禁用的 Send |
| K6 | `WaitingForInput_SendEnabledOnlyWhenNonBlank` | 输入空白 → Send 禁用；有内容 → 启用 |
| K7 | `WaitingForInput_TypingFiresOnInputChange` | 打字逐字触发 `onInputChange` |
| K8 | `WaitingForInput_SendClearsField` | 点 Send → 回调触发 + field 清空 |
| K9 | `WaitingForAction_ShowsInstructionAndDoneButton` | 显示 instruction + Done |
| K10 | `WaitingForApproval_ShowsFourScopeButtons` | Allow Once/Session/Always/Deny 都在 |
| K11 | `Done_ShowsSuccessMessageAndAutoDismisses` | 3s 后 Hidden（用 `advanceTimeBy`） |
| K12 | `Error_DismissButtonFiresCallback` | 点 Dismiss → `onDismissError` |
| K13 | `Stop_IsStopPendingDimsButton` | `isStopPending=true` 时 Stop 按钮视觉变化 |
| K14 | `NavigationButtons_FireCorrectEnum` | Minimize/OpenApp/OpenViewer 回调正确 |

---

## Batch 3 — Settings（中价值，多为回调验证）

> Settings 主要是"交互 → 回调 fire 正确值"，低复杂度但易 regression。先挑真正有逻辑的几条，toggle/dropdown 的 n 倍复制不写。

| # | Test | 行为 |
|---|---|---|
| S1 | `Sheet_OpensToHomePage` | 打开 sheet → 显示 HomePage |
| S2 | `Sheet_DismissClosesSheet` | 点 X → sheet 关闭 |
| S3 | `Sheet_BackFromSubPageReturnsToHome` | 从 LLM Auth 返回 → 回到 Home |
| S4 | `Sheet_PageStateSurvivesRotation` | 旋转后仍在当前 page（rememberSaveable） |
| S5 | `LlmAuth_TabSwitchDoesNotCommitBackend` | 切 tab 但不输入 → backend 不变 |
| S6 | `LlmAuth_OAuthStartCommitsBackendAndMethod` | 点 Start OAuth → 两个回调都 fire |
| S7 | `LlmAuth_ApiKeyProviderSwitchesModelOptions` | 切 provider → model dropdown 选项变 |
| S8 | `LlmAuth_IncompatibleModelAutoResetsOnMethodSwitch` | 切方法 → model 被 canonicalize | 
| S9 | `AgentBehavior_ProModeShowsExecutorDropdown` | mode=Pro → executor dropdown 可见；Basic → 隐藏 |
| S10 | `Permissions_SessionTracesToggleShowsWarningBanner` | 开 traces → banner；关 → 消失 |
| S11 | `Permissions_ClearTracesShowsConfirmDialog` | 点 Clear → dialog 出现 |
| S12 | `Permissions_ClearTracesConfirmDisablesButton` | 确认后按钮 disable + label 改 |

---

## 为什么先做 Chat → Capsule → Settings 这个顺序

1. **Chat** 最自包含、最多纯 Compose state rendering，先跑通能把 baseline、selector、模式都验证。
2. **Capsule** 是 state machine，测试密度最高、bug 最容易出现，一旦 Batch 2 绿，后续每次改 capsule 都立即知道有没有破旧 state。
3. **Settings** 多为 "callback fires with right value"，写起来最机械，留到最后批量出。

---

## 约束（继承 KISS design）

- 所有 test 在 `app/src/androidTest/kotlin/ai/closepaw/qa/` 扁平存放。
- 不建 Robot、不建 annotation、不建 base class。
- helper 只有一个文件 `QaHelpers.kt`，里面放：
  - `fun messages(vararg: ChatMessage): List<ChatMessage>` 这种 seed helper
  - `fun capsuleState(...)` factory
- `testTag` 按需加：C3/C4（New Chat button）、C12/C13（Scroll FAB）、K5/K7/K8（input field & send）、S2（dismiss X）、S11/S12（Clear Traces button）。其他能用 text 的就用 text。

---

## 交付物

写完这三批 = 39 个 test。覆盖了 app 里视觉/交互最密的三大区域。之后：

- 修 bug 时：要么新增 test，要么发现它早就被这批 cover 到了。
- 加新 UI 行为时：参考相邻 batch 补一条。
- 不主动做 CI wiring / release smoke lane / Maestro。
