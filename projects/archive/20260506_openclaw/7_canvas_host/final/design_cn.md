# Canvas Host 设计

## 目标

在纯聊天文本与直接设备控制之间，增加一层由 agent 驱动的原生 UI，使 agent 能够：
- 展示结构化结果
- 让用户选择一个选项
- 请求用户确认
- 收集一段简短文本输入
- 在需要人工前置操作时暂停并恢复

这必须保持在现有 Android Agent 架构内：
- Jetpack Compose UI
- 以 `Op` 和 `AgentEvent` 作为唯一状态传输方式
- session-scoped tools 与 suspension
- 持久化 chat / session history

本次发布不考虑 WebView。

## 设计总结

Step 1 引入一套共享 schema、一个规范工具（扩展后的 `ask_user`），以及两条 host policy：
- 主应用 chat 是主要的 canvas host
- Smart Capsule 保持为紧凑控制面，而不是一个迷你通用应用

最终系统同时支持：
- 立即返回的 display-only cards
- 在用户响应或超时前持续挂起的 blocking cards

## 关键决策

### 1. 一套类型化的 interaction schema

用一套类型化 schema 取代当前的 `AskUserType` enum（`QUESTION`、`ACTION`），统一表示所有富交互：

```kotlin
sealed interface InteractionSpec {
    data class Summary(
        val title: String,
        val rows: List<DetailRow>
    ) : InteractionSpec

    data class SingleChoice(
        val title: String,
        val prompt: String,
        val options: List<ChoiceOption>
    ) : InteractionSpec

    data class Confirmation(
        val title: String,
        val prompt: String,
        val details: List<DetailRow> = emptyList(),
        val confirmLabel: String = "Confirm",
        val cancelLabel: String = "Cancel"
    ) : InteractionSpec

    data class TextInput(
        val title: String,
        val prompt: String,
        val placeholder: String? = null,
        val submitLabel: String = "Send"
    ) : InteractionSpec

    data class ActionRequired(
        val title: String,
        val instruction: String,
        val doneLabel: String = "Done"
    ) : InteractionSpec
}
```

响应类型也明确类型化：

```kotlin
sealed interface InteractionResponse {
    data class Choice(val optionId: String) : InteractionResponse
    data class Confirm(val confirmed: Boolean) : InteractionResponse
    data class Text(val value: String) : InteractionResponse
    data object Done : InteractionResponse
}
```

### 2. 扩展 `ask_user`，不要新增第二个工具

把 `ask_user` 扩展为接受完整的 `InteractionSpec` schema。删除旧的 `AskUserType` enum。一个工具、一条 suspension channel、一条 response path。

理由：
- 产品仍处于 pre-release，没有 backward compatibility 成本。
- `ask_user` 已经具备 suspension 所需的机制（`UserResponseChannel`）、call-id 关联、timeout 与取消逻辑。再加一个 `show_canvas` 工具，只会复制一套平行 plumbing。
- 两份评审都指出，双工具方案最大的风险就是 split-brain。一个工具可以直接消除这个问题。
- `Summary`（display-only）天然适合并入：`ask_user` 展示内容给用户，然后立刻返回。LLM 只需学习一个工具名来完成所有结构化交互。
- `Op.UserResponse` 只需把 payload 从 `String` 改为 `InteractionResponse`，不需要新 Op。

### 3. Display 与 blocking cards 都属于 Step 1

Step 1 必须完整覆盖需求：
- `Summary` 是 display-only，立刻返回
- `SingleChoice`、`Confirmation`、`TextInput` 和 `ActionRequired` 是 blocking

Display-only cards **不占用** session 唯一的 pending blocking slot。只有 blocking cards 会占用。

### 4. Capsule 保持小而明确

Host policy 应明确写死：

- `MAIN_APP`
  - chat 渲染所有 card 类型
  - 用户直接在 card 内完成响应

- overlay / viewer contexts
  - capsule 只允许 inline 渲染：
    - `TextInput`
    - `ActionRequired`
  - `SingleChoice`、`Confirmation` 和 `Summary` 不在 capsule 中渲染完整卡片
  - 对这些情况，capsule 只显示紧凑的 pending banner，并提供 “Open app” 入口

这样 Step 1 才现实。完整 canvas host 是 app，capsule 是控制条与兜底入口。

### 5. Rich cards 是 transcript 的一等内容

扩展 transcript model：

```kotlin
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Action(val data: ActionCardData) : ContentBlock
    data class Interaction(val data: InteractionCardData) : ContentBlock
}
```

通过以下链路持久化 rich cards：
- `ContentBlockRecord`
- `MessageConverter`
- `AgentMessageBuffer`
- `SessionRecordingService`

至少持久化：
- `callId`
- `spec`
- `state`
- `responseSummary`

## 工具契约

`ask_user` 的 schema 变成：

```json
{
  "kind": "single_choice",
  "title": "Choose a flight",
  "prompt": "I found 3 reasonable options.",
  "options": [
    { "id": "ua1", "label": "UA123 8:20 AM", "description": "$320 · nonstop" }
  ]
}
```

Display-only 示例：

```json
{
  "kind": "summary",
  "title": "Search results",
  "rows": [
    { "label": "Top result", "value": "UA123 8:20 AM" }
  ]
}
```

blocking 与 display 语义由 `kind` 推导，不需要额外 flag。

工具输出示例：
- Choice：`User response: {"kind":"single_choice","option_id":"ca1234"}`
- Confirmation：`User response: {"kind":"confirmation","confirmed":true}`
- Text input：`User response: {"kind":"text_input","text":"..."}`
- Action done：`User response: {"kind":"action_required","done":true}`
- Summary：`Displayed summary: "Search results" (3 rows)`
- Timeout：`User did not respond within the timeout. Consider continuing without their input or trying a different approach.`

## 协议

用以下结构替换 `AskUser`、`AskUserType` 与 `AskUserDomainEvent`：

```kotlin
sealed interface InteractionDomainEvent : AgentEvent

data class InteractionRequested(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val callId: String?,        // null for Summary (display-only)
    val spec: InteractionSpec,
    val blocking: Boolean
) : InteractionDomainEvent

data class InteractionResolved(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val callId: String,
    val response: InteractionResponse?,
    val displayText: String,
    val outcome: InteractionOutcome
) : InteractionDomainEvent

enum class InteractionOutcome {
    RESOLVED,
    TIMED_OUT,
    CANCELLED
}
```

`Op.UserResponse` 改为携带类型化响应：

```kotlin
data class UserResponse(
    val callId: String,
    val response: InteractionResponse   // was: String
) : Op
```

删除：`AskUser`、`AskUserType`、`AskUserDomainEvent`、`Op.CanvasResponse`。
不需要新增 Op，`Op.UserResponse` 就是唯一通道。

`displayText` 与 `responseSummary` 之所以故意分开：
- `displayText` 是写入 transcript 的面向会话的 resolved 语句
- `responseSummary` 是存进 `InteractionCardData` 的紧凑卡片摘要

它们多数情况下会是同一段字符串，但不应合并，因为 transcript copy 与 card copy 的职责不同，即使底层 response payload 相同，也可能需要不同文案。

## 运行时行为

### Display-only card

1. LLM 调用 `ask_user(kind=summary, ...)`
2. tool 发出 `InteractionRequested(blocking=false)`
3. transcript 追加一个 `Interaction` block（state=`Display`）
4. tool 立即返回一个规范化 summary output，供 runtime history 记录

### Blocking card

1. LLM 调用 `ask_user(kind=single_choice|confirmation|text_input|action_required, ...)`
2. tool 发出 `InteractionRequested(blocking=true)`
3. UI 根据 host policy 在当前宿主中渲染请求
4. 用户提交 `Op.UserResponse(callId, InteractionResponse)`
5. `UserResponseChannel` 完成挂起的 deferred
6. tool 返回规范化输出
7. session 发出 `InteractionResolved`
8. transcript 更新 card state，并追加可读的 response summary

## Timeout 与取消

- Blocking requests 继续使用当前默认 timeout：5 分钟
- 超时时：
  - card state 变为 `TimedOut`
  - tool 返回规范化的 “user did not respond” 结果
  - session 发出 `InteractionResolved(outcome=TIMED_OUT, response=null, ...)`
- session stop / shutdown 时：
  - pending request 被取消
  - card state 变为 `Cancelled`

## 状态模型

```kotlin
data class InteractionCardData(
    val callId: String?,             // null for Summary
    val spec: InteractionSpec,
    val state: InteractionState,
    val responseSummary: String? = null
)

enum class InteractionState {
    Display,       // Summary — non-blocking, already resolved
    Pending,       // Blocking — waiting for user
    Resolved,      // User responded
    TimedOut,      // No response within timeout
    Cancelled      // Session stopped while pending
}
```

唯一 pending slot 的规则只对 `Pending` blocking cards 生效。
Display-only cards 不占这个 slot。

## Capsule Mode 变更

用下面这个结构替换 `WaitingForInput` 与 `WaitingForAction`：

```kotlin
data class WaitingForInteraction(
    val callId: String,
    val spec: InteractionSpec,
    val canRenderInline: Boolean    // true for TextInput, ActionRequired
) : CapsuleMode
```

删除：`WaitingForInput`、`WaitingForAction`。

`canRenderInline` 在进入该 mode 时由 spec 类型推导：
- `true` → 直接在 capsule 中渲染 interaction UI
- `false` → 渲染紧凑的 “respond in app” banner

## 组件变更

### 删除
- `AskUserType.kt`
- `AskUserEvents.kt`

### 新增
- `InteractionSpec.kt`：sealed hierarchy + `ChoiceOption`、`DetailRow`
- `InteractionResponse.kt`：sealed response hierarchy
- `InteractionEvents.kt`：`InteractionRequested`、`InteractionResolved` 和 domain marker
- `InteractionCardData.kt`：transcript card data + `InteractionState` enum
- `ChoiceCard.kt`、`ConfirmationCard.kt`、`SummaryCard.kt`、`TextInputCard.kt`、`ActionRequiredCard.kt`：Compose 渲染器

### 修改
- `Op.kt`：`UserResponse.response: String` → `InteractionResponse`
- `UserResponseChannel.kt`：`CompletableDeferred<String>` → `CompletableDeferred<InteractionResponse>`
- `AskUserTool.kt`：重写，构建 `InteractionSpec` 并处理所有 kind
- `AgentEventDispatcher.kt`：`emitAskUser` 替换为 `emitInteractionRequested/Resolved`
- `AgentEventDomains.kt`：用 `InteractionDomainEvent` 替换 `AskUserDomainEvent`
- `ChatEventReducer.kt`：处理新事件，插入并更新 interaction cards
- `ChatMessage.kt`：新增 `ContentBlock.Interaction`
- `CapsuleMode.kt`：用 `WaitingForInteraction` 替换 `WaitingForInput/Action`
- `CapsuleStateHolder.kt`：从 spec 类型推导 `canRenderInline`
- `MessageBubble.kt`：把 `ContentBlock.Interaction` 路由到 card composables
- `ContentBlockRecord` / `MessageConverter` / `AgentMessageBuffer`：新增 interaction persistence

## 非目标

- WebView 或任意 HTML / JS 渲染
- 把 capsule 变成完整 card 浏览器
- 进程死亡后恢复进行中的 blocking request
- Step 1 就支持多 request queue

## 为什么不是 WebView

WebView 会引入第二套渲染栈、JS bridge、生命周期复杂度（而且容易泄漏）、更大的安全面（agent 生成 HTML 可能带来 XSS 风险），以及更难的持久化 / 回放。而要验证“结构化原生卡片是否真的提升 agent-user 协作”，这些都不是必要条件。如果将来某个已验证场景被原生卡片卡住，再在同一个 `InteractionSpec` 模型之下加一个 WebView host 实现也不迟。

## 为什么是一工具而不是两工具

两份初始设计都同意类型化 schema，真正的分歧是：扩展 `ask_user`，还是增加 `show_canvas`。扩展 `ask_user` 更优，因为：

1. **没有 split-brain。** 一个工具意味着一条 suspension channel、一个 Op、一对 event、一条 reducer path、一个 capsule mode。第二个工具会把这些全部复制一遍。
2. **没有迁移歧义。** 如果 `show_canvas` 与 `ask_user` 并存，那旧的 `QUESTION` / `ACTION` 怎么办？它们和 `TextInput` / `ActionRequired` 有重叠。LLM 和 prompt tuning 就得学会 “这两类用 `ask_user`，那四类用 `show_canvas`”。扩展 `ask_user` 则非常明确：永远用 `ask_user`。
3. **产品仍是 pre-release。** 不存在 backward compatibility 负担。直接删旧 enum，重定 response channel 即可。
4. **`Summary` 也很自然。** `ask_user(kind=summary)` 就是 “把这份结构化信息展示给用户”。工具立即返回即可。名字对 display-only 来说确实略显不完美，但属于命名审美问题，不是设计问题。

## Trade-offs

### 优势
- 一个工具、一条 channel、一个 pending-interaction slot，概念面最小
- Typed spec / response 消除了字符串解析
- Step 1 就覆盖 display-only cards（Summary）
- Capsule 保持小而明确，只有 inline-renderable specs 才展开
- 干净替换旧路径，没有双通道税

### 成本
- 每新增一种 interaction type，就要加对应 app 代码（Compose composable + spec variant）
- 表达能力弱于任意 HTML
- Pending interaction 的进程死亡恢复仍然不做（与今天一致）
- `ask_user` 对 `Summary` 这种 display-only 情况来说名字略别扭，但可接受

## 开放问题

1. **Multi-select**：`SingleChoice` 是否应加入 `multiSelect: Boolean`，还是应当另起一个 `MultiChoice` spec？先延后，单选已覆盖当前需求。
2. **Card update**：Agent 能否在同一 turn 中更新已显示的 Summary card？当前设计不支持（display-only，fire-and-forget）。如果将来确实需要，可增加 `InteractionUpdated` event。
