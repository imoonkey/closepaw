# Voice-First 对齐方案

## 目标

为 Android Agent 增加一条 voice-first 交互回路，让用户可以：
- 通过说话启动任务，
- 用语音回答 `ask_user` 提示，
- 在 agent 工作中继续发送语音补充，
- 在 agent 完成、需要注意或失败时，听到简短语音反馈。

Version 1 必须覆盖产品当前已经存在的两个入口：
- 主应用内，
- 无障碍悬浮层 Smart Capsule 内。

Version 1 不尝试解决：
- 唤醒词，
- 全双工对话，
- 朗读流式 assistant 文本，
- agent 自定义语音风格控制，
- 第二套纯语音 session 模型。

## 当前代码约束

设计必须贴合当前代码现实。

1. `AgentService` 已经拥有 overlay runtime、当前 overlay-facing 的 `CapsuleStateHolder`，以及通过 `observeSession()` + `AgentServiceEventHandler` 实现的单一 session event collector。
2. `MainActivity` 拥有 `SessionCoordinator`，但 overlay-first 的入口并不经过 `SessionCoordinator`。`AgentService.runAgent()` 已经能直接创建新 session。
3. `SmartCapsuleSurface` 已经通过 `CapsuleMode` 定义了规范的 typed-input 行为：
   - `Hidden` -> 新任务，
   - `WaitingForInput` -> `Op.UserResponse(callId, text)`，
   - 其他状态当前都走 supplement，
   - `WaitingForAction` 的完成目前是 Row 2 上一个显式的 `"done"` 动作，而不是自由文本 `UserResponse`。
4. `CapsuleMode` 已经携带了 voice 需要的路由上下文，包括 `WaitingForInput` 与 `WaitingForAction` 的 `callId`。

这些事实排除了纯 session-scoped 的 voice owner。overlay 模式下的第一句语音在 session 还不存在时就会发生，因此如果 voice controller 只能隶属某个 session，就必须再补一层 bootstrap，这没有必要。

## 核心决策

采用一个与现有架构一致的拆分：

- `AgentService` 拥有 voice transport runtime。
- Session 与 capsule state 决定语音的语义。

具体来说：
- STT、TTS、audio focus、listen/speak 中断、mic 可用性，都放在 service-scoped controller 中；
- transcript 路由与 typed input 使用同一套 capsule / session 规则；
- spoken output 来自现有 agent events，而不是引入一条新的 model output channel。

这能让首句语音的 bootstrap 保持简单，同时保留单一的对话模型。

## 架构

### 1. Voice runtime owner

新增 `voice/VoiceInteractionManager.kt`，由 `AgentService` 持有。

职责：
- 启停 `SpeechRecognizer`，
- 启停 `TextToSpeech`，
- 持有短暂音频状态，
- 在启动 STT 前停止 TTS，
- 给主应用和 overlay capsule renderer 暴露 voice UI state，
- 接收最终 transcript，并通过共享 resolver 路由，
- 对高价值 agent events 产生语音摘要。

它必须是 service-scoped，因为：
- overlay 活在 service 里，
- 第一句语音可能发生在 session 创建前，
- 当前只允许一个 active session，
- 现有 event fan-out 路径本来就在 service 中。

但它**不能**拥有持久化的对话语义。它可以读取当前 session 和 capsule state，但不能自己再发明第二套任务状态机。

### 2. Voice state 与 `CapsuleMode` 保持正交

任务状态和语音传输状态要分离。

新增一个小型 voice UI state：
- `Disabled`
- `Ready`
- `Listening`
- `Processing`
- `Speaking`
- `Error`

`CapsuleMode` 仍然是任务状态机。Voice state 只是在现有 Smart Capsule 界面上叠加一个 mic / speaking affordance。

这与当前代码库一致，因为 `CapsuleStateHolder` 本来就是规范的任务 UI state holder，并且已经把一些短暂 UI flag 放在 `CapsuleMode` 之外。

### 3. 一个共享的 input resolver

Typed input 和 spoken input 必须走同一个 resolver。不要再做一套 voice-only command router。

新增一个共享 resolver，例如 `ui/capsule/CapsuleInputResolver.kt`，同时给以下两端使用：
- `SmartCapsuleSurface` 的 typed submission，
- `VoiceInteractionManager` 的最终 transcript submission。

Resolver 规则：
- `Hidden`、`Done`、`Error` -> start / send 新任务，
- `WaitingForInput` -> `Op.UserResponse(callId, transcript)`，
- `WaitingForAction`：
  - 精确归一化为 `"done"` -> `Op.UserResponse(callId, "done")`
  - 其余内容 -> `Op.Supplement(transcript)`
- `Running`、`TakeoverPending`、`Takeover` -> `Op.Supplement(transcript)`。

这比两个初稿都更贴近当前 typed UI 的现实：
- 当前 app 已把 `WaitingForAction` 的完成定义为显式 `"done"`，
- 非提问状态下的自由文本本来就是 supplement，而不是另一条 response channel。

### 4. v1 不做本地语音控制命令

Version 1 不要在客户端把 `"stop"`、`"take over"`、`"resume"` 之类的转录结果直接映射成 control ops。

理由：
- 语音转录是有噪声的，
- 这些控制已经有显式按钮，
- 本地关键词匹配会引入第二条脆弱的意图路径，而 typed input 没有这种分叉，
- 也会提高普通任务文本被误判的概率。

如果以后确实需要语音控制命令，应该在证据充分时有意识地加入，而不是靠几个字符串匹配就上线。

### 5. 单一事件订阅点

继续以 `AgentServiceEventHandler` 作为 session events 的单一 fan-out 点。

扩展这条路径，让高价值事件同时更新：
- overlay / 主应用的 capsule state，
- `VoiceInteractionManager` 的 spoken feedback。

不要为 voice 再新建第二个独立 event collector。service 已经有一个 collector，重复只会增加漂移和竞态风险。

v1 中值得朗读的高价值事件：
- `AskUser`，
- `TaskCompleted`，
- `SessionError`，
- 如果没有更合适完成文案时的 `SessionCompleted`，
- 如实现评审后确有必要，也可加 takeover / resume 确认。

不要朗读：
- `MessageDelta`，
- 工具 / 动作噪音，
- 中间思考更新，
- 冗长的状态流。

## 组件

新增目录 `app/src/main/kotlin/com/moonkey/androidagent/voice/`：

- `VoiceInteractionManager.kt`
  - service-scoped 编排器，
  - 管理 STT、TTS、audio focus 和 voice UI state，
  - 通过共享 resolver 路由 transcript，
  - 从 `AgentServiceEventHandler` 接收高价值 agent events。

- `SpeechRecognizerAdapter.kt`
  - 对 `SpeechRecognizer` 的薄封装，
  - 发出 partial transcript、final transcript、timeout 和结构化错误，
  - 不包含 session 逻辑。

- `SpeechOutputAdapter.kt`
  - 对 `TextToSpeech` 的薄封装，
  - 显式提供 `speak()` 与 `stop()`，
  - 初始化 / 运行时失败要明确上报。

- `VoiceUiState.kt`
  - 表示短暂语音传输状态的 sealed type。

- `VoiceSummaryFormatter.kt`
  - 把现有 events 转成简短可朗读文案。

可能需要改动的现有文件：

- `app/src/main/AndroidManifest.xml`
  - 增加 `android.permission.RECORD_AUDIO`。

- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`
  - 创建并持有 `VoiceInteractionManager`，
  - 给 overlay 和主应用暴露语音动作，
  - 跨 session creation / reload 保持 voice runtime 存活，
  - 在 service destroy 时停止 voice runtime。

- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandler.kt`
  - 把高价值 session events 转发给 voice summary handling。

- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt`
  - 继续暴露 `CapsuleMode` 与 `callId` 作为路由上下文，
  - 不要把 voice transport state 吸进来。

- `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt`
  - typed input 也改用共享 resolver，
  - 渲染 mic affordance 和 voice status，
  - 如合适，可把 partial transcript 显示在现有 input draft 中。

- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`
  - 请求运行时麦克风权限，
  - 把 mic 动作委托给 `AgentService.instance`，
  - 权限缺失时打开设置页或发起权限请求。

- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt`
  - 增加语音相关设置。

## 交互模型

### 输入流

Version 1 只做 push-to-talk。

流程：
1. 用户点击 Smart Capsule 上的 mic。
2. App 检查麦克风权限和 recognizer 可用性。
3. 当前 TTS 立即停止。
4. STT 开始监听。
5. Partial transcript 可用于更新现有草稿 UI。
6. Final transcript 交给共享 capsule input resolver。
7. Resolver 提交与 typed input 完全相同的 op 路径。

不存在 voice-specific 的 session protocol。

### 输出流

语音输出只做简短摘要，不朗读长文本。

例子：
- `AskUser(question)` -> 朗读问题。
- 成功完成且有结果文本 -> 朗读结果。
- 成功完成但没有有价值的结果文本 -> `"Task completed."`
- 错误 -> 朗读简短错误摘要。

TTS 必须可被随时打断。每次开始 listening 之前都要先停止当前 speech。

### `WaitingForAction`

这个状态必须遵循当前 UI 契约，而不是重新发明一套。

规则：
- `"done"` 表示用户完成了请求动作，并提交 `Op.UserResponse(callId, "done")`。
- 其他任何 transcript 都视为 supplement，而不是特殊的 action response。

这与当前 Row 2 的 `Done` 按钮和 typed free-form 行为一致。

仍有一个 UX 细节待定：
- 在 `WaitingForAction` 下，如果 transcript 不是 `done`，语音应当立刻自动提交 supplement，还是先落到 draft text 里等待确认？

默认建议：直接自动提交为 supplement，因为这与 capsule 里其他 push-to-talk 行为一致，也能避免额外一次点击。

### 权限与失败处理

- 主应用内缺少 `RECORD_AUDIO`：
  - 监听前先请求运行时权限。

- 用户不在 app 内时缺少 `RECORD_AUDIO`：
  - capsule 明确显示错误，
  - deep-link 到 `MainActivity` 以完成授权。

- 没有 recognizer：
  - 禁用 voice input，
  - 如果 TTS 可用则保留文本输入和 TTS。

- 没有 TTS：
  - 保留 voice input，
  - 只禁用 spoken feedback。

- Audio focus 被拒绝：
  - 显示短暂可见的错误，
  - 把它视为单次尝试失败，而不是永久禁用。

- 识别出错但已经有 partial transcript：
  - 如果可行，保留 partial text 到 draft，方便用户编辑或手动发送。

任何失败都不能静默消失。

## 设置

设置应保持简单。

建议字段：
- `voiceInputMode: OFF | PUSH_TO_TALK | AUTO_TALK`
- `spokenFeedbackEnabled: Boolean`

V1 推荐默认值：
- `voiceInputMode = OFF`
- `spokenFeedbackEnabled = OFF`

原因：
- app 以 accessibility service 身份运行，可能出现在公开场景，
- 意外发声的代价高于静默可用能力，
- voice 是 opt-in 功能，不应让用户猝不及防。

当用户首次启用 voice input 时，UI 可以提示是否一并开启 spoken feedback。`AUTO_TALK` 默认关闭，放到 phase 2。

## Rollout

### Phase 1

- 在主应用和 overlay 中加入 push-to-talk mic，
- 共享 typed / spoken input resolver，
- 简洁的 spoken summaries，
- 通过“开始 STT 前先停 TTS”实现 barge-in，
- 明确的权限与失败处理。

### Phase 2

- `AUTO_TALK` 模式，
- TTS 结束后或 `AskUser` 后的可选 auto-rearm，
- 更好的 partial transcript 展示和调参。

### Phase 3

- 唤醒词或其他被动触发方式，仍然叠加在同一套 service-owned transport manager 之上。

## 为什么这是正确的合并方案

这次合并保留了两个初稿各自最强的部分，也删掉了最弱的部分。

保留：
- voice state 与 `CapsuleMode` 分离，
- typed 与 spoken input 共用一套路由模型，
- spoken output 只做摘要，
- push-to-talk 优先于 auto-talk 与 wake word。

删掉：
- 纯 session-scoped 的 voice ownership，因为它无法处理“第一句语音还没有 session”这个现实；
- 客户端语音控制关键词，因为它会引入一条脆弱的第二命令路径；
- 把 `WaitingForAction` 下的自由语音文本当成 `UserResponse`，因为当前 UI 并不是这么工作的。

结果比两个极端方案都更简单：
- 一个 transport owner，
- 一套 conversation model，
- 一个 event fan-out 点，
- 一个 input resolver。

## 开放问题

1. 在 `WaitingForAction` 中，非 `done` 的语音 transcript 应该立刻自动提交为 supplement，还是先落到可编辑草稿中？
2. 用户第一次启用 voice input 时，spoken feedback 应该保持关闭，等待单独开启；还是自动启用一次并明确提示？

这两个问题都不阻塞整体架构。
