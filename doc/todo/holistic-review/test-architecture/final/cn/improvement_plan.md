# 测试架构改进计划 — 最终版

**日期**: 2026-04-08
**来源**: 双重设计审查 (Claude + Codex)，交叉审阅，已对齐
**依据**: `review.md`

---

## 指导原则

1. 在添加更多内部循环测试之前，先提升模块边界的信心
2. 优先测试纯 reducer、parser、classifier 和 coordinator，而非 mock 密集的 Android 运行时 fake
3. 当一个类难以测试时，先提取纯逻辑 — 测试纯逻辑
4. 将维护预算花在有真实回归成本的行为上，而非静态数据快照
5. 在扩大测试数量之前先整合测试基础设施

---

## 第 0 阶段: 测试基础设施清理

**目标**: 在添加新测试之前减少重复，使新文件保持精简。

### 0.1 将 RecordingPlatform 整合到 TestFixtures
**影响**: 消除 ClickExecutorTest, ScrollExecutorTest, LongPressExecutorTest 中约 180 行

```kotlin
// In TestFixtures.kt
class RecordingPlatform(
    private val actionResults: List<ActionResult>,
    private val capturedSnapshots: List<ScreenSnapshot>,
    private val displayInfo: DisplayInfo = DisplayInfo(1080, 2400, 3f)
) : AndroidPlatform { ... }
```

### 0.2 在 TestFixtures 中创建共享 LLM client fake
**影响**: 消除 7 个文件中约 200 行

```kotlin
class StubLLMClient(textContent: String = "done") : LLMClient()
class FailingLLMClient(throwable: Throwable) : LLMClient()
class CapturingLLMClient(response: ResponsesResult) : LLMClient()
```

### 0.3 在 TestFixtures 中创建 TestSessionServicesBuilder
**影响**: 消除约 150 行 buildServices() 重复

```kotlin
class TestSessionServicesBuilder {
    var llmClient: LLMClient = StubLLMClient()
    var platform: AndroidPlatform = FakeAndroidPlatform()
    var traceRecorder: TraceRecorder = NoopTraceRecorder
    fun build(): SessionServices { ... }
}
```

### 0.4 统一使用 Google Truth
**文件**: LLMClientFactoryTest.kt, ModelCatalogTest.kt
将 `assertEquals`/`assertTrue` 替换为 `assertThat(x).isEqualTo(y)`。

### 0.5 裁剪低价值快照测试
**可选**: 将 AgentDefTest 精确 tool 列表断言放宽为 `containsAtLeast` 关键 tool + 数量检查。将 OpenAppToolTest alias 条目测试简化为单个行为测试。

---

## 第 1 阶段: LLM 边界 + 安全 Tool

**目标**: 保护外部契约边界和安全敏感 tool 的行为。

### 1.1 CodexRequestBuilderTest
**文件**: `llm/CodexRequestBuilderTest.kt`
**测试用例**:
- request JSON 中用户 vs 助手内容的转换
- Function-call input 和 function-call-output 序列化
- System message 的位置和格式
- 空/缺失字段的优雅处理

### 1.2 CodexSseParserTest
**文件**: `llm/CodexSseParserTest.kt`
**测试用例**:
- 跨 `output_index` 的并行 tool-call 累积
- 尾部 SSE buffer flush 和 `[DONE]` 处理
- 格式错误的 SSE 事件恢复
- 跨 chunk 边界的部分事件缓冲

### 1.3 OpenAIErrorClassifierTest
**文件**: `llm/OpenAIErrorClassifierTest.kt`
**测试用例**:
- 将 rate limit (429) 分类为可重试
- 将服务器错误 (500, 502, 503) 分类为可重试
- 将 auth 错误 (401) 分类为不可重试
- 将 bad request (400) 分类为不可重试
- 从响应体提取有意义的错误消息

### 1.4 CloudStreamRetryPolicyTest
**文件**: `llm/CloudStreamRetryPolicyTest.kt`
**测试用例**:
- shouldRetry 对可重试 HTTP 状态码返回 true
- shouldRetry 对不可重试状态码返回 false
- 超过最大尝试次数后 shouldRetry 返回 false
- Backoff 延迟随尝试次数增加
- Backoff 遵守最大延迟上限
- Retry-after header 提取

### 1.5 ShellToolTest
**文件**: `tool/impl/ShellToolTest.kt`
**测试用例**:
- 被屏蔽的破坏性命令拒绝
- 安全命令接受
- 超时处理
- 输出截断行为

### 1.6 AskUserToolTest
**文件**: `tool/impl/AskUserToolTest.kt`
**测试用例**:
- 另一个请求活跃时拒绝 pending ask-user
- 超时和取消语义
- 成功的用户响应转发

---

## 第 2 阶段: 编排 + Trace

**目标**: 覆盖跨模块回归发生的编排接缝。

### 2.1 SessionCoordinatorTest
**文件**: `session/SessionCoordinatorTest.kt`
**测试用例**:
- 队列 vs 立即提交行为
- 死 session 清理和 consumeDeadSessionFileName()
- Session 创建和清理生命周期

### 2.2 AgentServiceEventHandlerTest
**文件**: `app/AgentServiceEventHandlerTest.kt`
**测试用例**:
- 事件处理器对 recording service 的影响
- Overlay 回调路由
- 状态消息发射

### 2.3 TurnPlanningPhaseRunnerTest
**文件**: `agent/TurnPlanningPhaseRunnerTest.kt`
**测试用例**:
- 规划阶段历史写入
- 仲裁警告发射
- 规划期间的思维发射

### 2.4 CognitionTraceRedactorTest
**文件**: `trace/CognitionTraceRedactorTest.kt`
**测试用例**:
- 脱敏文本中间的邮箱地址
- 脱敏 API key 模式 (sk-..., sk_live_...)
- 脱敏 Bearer token
- 单个字符串中脱敏多个模式
- 保持非敏感内容不变
- 处理空和 null 输入
- 脱敏 JSON 格式字符串中的 token

### 2.5 TypeExecutorTest
**文件**: `tool/action/TypeExecutorTest.kt`
**测试用例**:
- 直接文本设置到聚焦字段
- 针对 type action 的特定元素定位
- clear=true 时先清除字段再输入
- clear=false 时追加到现有文本
- 找不到可编辑元素时失败
- VD 模式下禁用 tap-to-focus fallback

### 2.6 (扩展) TurnExecutionPhaseRunnerTest
**文件**: `agent/TurnExecutionPhaseRunnerTest.kt`
**如有余力**，在 SwipeExecutorTest 之前添加:
- Tool 执行后的 observation 捕获
- 历史输出记录
- 失败时中止行为

---

## 第 3 阶段: Onboarding + Chat + 首个 VD 接缝

**目标**: 覆盖首次运行转化、用户可见的 chat 行为以及 virtual display 决策逻辑。

### 3.1 OnboardingViewModelTest
**文件**: `onboarding/OnboardingViewModelTest.kt`
**测试用例**:
- 从存储结果和权限状态确定启动时的步骤选择
- Accessibility poll-after-return 行为
- OAuth 成功/错误转换
- 手动 API-key 验证的成功、无效 key 和瞬态错误路径
- Demo 成功 vs 超时 vs 错误 package 完成

### 3.2 ChatEventReducerTest
**文件**: `ui/chat/ChatEventReducerTest.kt`
**测试用例**:
- Streaming delta 累积和完成转换
- Action-card 从提议到执行到成功/失败的映射
- 重新绑定到活跃 session 时的 replay 截断行为

### 3.3 MessageConverterTest
**文件**: `history/model/MessageConverterTest.kt`
**测试用例**:
- MessageRecord 到 ChatMessage 的往返不变量
- 内容类型映射的边界情况

### 3.4 FileTraceRecorderTest
**文件**: `trace/FileTraceRecorderTest.kt`
**测试用例**:
- Flush 和 close 语义
- Artifact 命名/路径清理
- 并发写入处理

### 3.5 VirtualDisplayViewerTouchHandlerTest
**文件**: `platform/virtualdisplay/VirtualDisplayViewerTouchHandlerTest.kt`
**测试用例**:
- Viewer 坐标缩放和截断
- Tap vs swipe shell fallback 行为
- 无效 display 短路情况

---

## Backlog（已验证，延后）

这些项有效但非首轮必须。视容量优先处理:

| 项 | 备注 |
|----|------|
| TurnExecutionPhaseRunnerTest | 如未在第 2 阶段扩展中完成 |
| CloudLlmRetryTest | 非 streaming retry 契约 |
| SwipeExecutorTest | 较简单的 action；战略价值低于编排 |
| VirtualDisplaySurfaceControllerTest | Surface 模式切换 |
| VirtualDisplayCaptureCoordinatorTest | Pixel-copy fallback |
| AgentTraceArtifactsTest | Artifact 打包边界情况 |
| OnboardingStoreTest | 迁移、outcome 持久化 |
| PermissionStateMonitorTest | 权限修复模型推导 |
| HttpLlmCredentialValidatorTest | 200/401/429/timeout/SSL 映射 |
| MobileActionInvocation / UIActionInvocation 测试 | Fixture 清理后审查 |
| ChatSessionHistoryControllerTest | Session 列表加载/恢复/删除 |
| ChatViewModelTest | 完整 ViewModel 行为 |

---

## 执行总结

| 阶段 | 文件数 | 工作量 | 缓解的关键风险 |
|------|--------|--------|----------------|
| 0 | 1 修改 + 2 更新 | Small | 降低维护成本 |
| 1 | 6 新增 | Medium | LLM 契约 + 安全 tool |
| 2 | 5-6 新增 | Medium | 编排接缝 + trace 隐私 |
| 3 | 5 新增 | Medium | 首次运行转化 + chat 状态 + VD |
| Backlog | 约 12 项 | 不定 | 第二波边界 |

**首轮总计**: 约 16-18 个新增/更新测试文件
**预估净效果**: +800-1000 新测试行, -530 重复行 = **约 +300-500 净行**，同时有意义地扩展边界覆盖

---

## 成功标准

如果以下情况成立，则此计划在发挥作用:
- 风险最高的空白包 (llm, app service, onboarding) 不再空白
- 新测试位于接缝和协作者处，而非静态数据表
- Fixture 重复大幅下降
- Parser/retry/auth/service 回归可以在无设备的情况下被捕获
- 纯 UI 文件仍基本免除，除非逻辑被提取出来

---

## 非目标

- 不扩展纯 Compose 渲染的单元测试
- 不添加实时网络测试
- 不为 protocol/*、主题常量、资源文件或静态内容清单添加全面测试
- 不试图在单元测试中模拟完整的 Android 运行时 — 提取纯接缝，测试纯接缝
- 本轮不重构 auth 存储的可测试性 (backlog)
