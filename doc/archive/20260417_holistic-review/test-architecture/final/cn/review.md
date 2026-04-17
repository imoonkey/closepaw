# 测试架构审查 — 最终版

**日期**: 2026-04-08
**来源**: 双重设计审查 (Claude + Codex)，交叉审阅，已对齐
**范围**: 68 个测试文件 (~10,293 行)，覆盖 `app/src/main/kotlin` 下 267 个 Kotlin 生产文件

---

## 整体评估

测试套件**在纯粹的、确定性的内部逻辑上表现强劲**，但**在运行时边界上薄弱**。策略引擎、prompt 构建、历史压缩、Perceptor 内部实现、action 定位和状态持有者逻辑得到了良好保护。最高变动率、最高集成度的代码 — LLM 线格式、service 生命周期、onboarding/auth、virtual display 编排以及 chat/session 协调 — 则没有。

测试套件并非笼统地测试不足，而是**测试不均衡**。下一步的收益来自将覆盖范围向外推进到应用的不稳定边界。

---

## 模块覆盖图

### 评估等级

| 等级 | 含义 |
|------|------|
| **Strong** | 直接测试覆盖了主要行为和失败路径 |
| **Mixed** | 存在一些有价值的覆盖，但重要的类未被覆盖 |
| **Shallow** | 测试存在但仅触及辅助函数或很窄的切面 |
| **Absent, acceptable** | 低价值的单元测试目标；在其他地方有更好的覆盖 |
| **Absent, concerning** | 有实质性的业务/运行时风险，却没有有意义的直接测试 |

### 执行核心

| 模块 | 生产面 | 测试面 | 等级 | 备注 |
|------|--------|--------|------|------|
| `agent/`（含 cognition, definition, subagent） | 24 文件, 3261 行 | 17 文件, 2395 行 | **Mixed** | Tool 过滤、策略、prompt 构建、模型解析、子 agent 流程、错误恢复表现强劲。缺口: TurnPlanningPhaseRunner, TurnExecutionPhaseRunner, AgentTurnRunner |
| `session/` | 13 文件, 1933 行 | 6 文件, 639 行 | **Mixed** | AgentSession, ScratchpadState, TodoState 已覆盖。SessionCoordinator, SessionAgentRunner, SessionCheckpointCoordinator 未覆盖 |
| `tool/`（含 action, impl, handlers） | 36 文件, 4746 行 | 16 文件, 2644 行 | **Mixed** | Router/policy/validation 和 click/scroll/long-press 表现强劲。缺失: TypeExecutor, SwipeExecutor, UiChangeDetector, ShellTool, AskUserTool |
| `perception/` | 6 文件, 787 行 | 3 文件, 732 行 | **Strong** | Perceptor, PerceptorInternals, ScreenSummary 覆盖良好 |
| `history/` | 15 文件, 2083 行 | 7 文件, 1020 行 | **Strong** | storage, management, recording 覆盖良好。MessageConverter 未覆盖 |
| `memory/` | 3 文件, 384 行 | 2 文件, 240 行 | **Strong** | 聚焦覆盖了存储和召回 |
| `llm/` | 19 文件, 3020 行 | 3 文件, 810 行 | **Absent, concerning** | 仅测试了 ModelCatalog, LLMClientFactory, LFMLLMClient 转换。CodexRequestBuilder, CodexSseParser, OpenAIErrorClassifier, CloudStreamRetryPolicy, CloudStreamRetryRunner 均未测试 |
| `platform/` | 12 文件, 1967 行 | 2 文件, 549 行 | **Mixed** | NodeActionPerformer 和 AppManager 已覆盖。AccessibilityPlatform 及相关运行时逻辑未测试 |
| `platform/virtualdisplay/` | 16 文件, 2192 行 | 0 文件 | **Absent, concerning** | 无直接测试。多个纯协作者 (TouchHandler, SurfaceController, CaptureCoordinator) 可进行单元测试 |

### 应用运行时、Auth 和 Trace

| 模块 | 生产面 | 测试面 | 等级 | 备注 |
|------|--------|--------|------|------|
| `app/` | 14 文件, 3028 行 | 2 文件, 477 行 | **Shallow** | 仅 OverlayLocationPolicy 和 AppSettingsState。AgentService, AgentServiceEventHandler, ServiceOverlayController 未覆盖 |
| `auth/` | 3 文件, 661 行 | 0 文件 | **Absent, concerning** | OAuth 流程辅助函数、JWT 解析、token 交换、凭据持久化零测试 |
| `onboarding/` | 8 文件, 1149 行 | 0 文件 | **Absent, concerning** | OnboardingViewModel (503 行异步状态机) 零直接测试 |
| `trace/` | 11 文件, 1249 行 | 0 直接, 1 间接 | **Shallow** | 仅通过 AgentTraceObservabilityTest 有一条 happy-path redaction 流程。CognitionTraceRedactor, FileTraceRecorder, AgentTraceArtifacts 未隔离测试 |
| `debug/` | 2 文件, 435 行 | 0 文件 | **Absent, acceptable** | 仅 debug 工具 |

### UI 和展示层

| 模块 | 生产面 | 测试面 | 等级 | 备注 |
|------|--------|--------|------|------|
| `ui/chat/` | 4 文件, 812 行 | 4 文件, 184 行 | **Shallow** | 测试仅触及辅助函数。ChatViewModel, ChatEventReducer, ChatSessionHistoryController 实质上未测试 |
| `ui/overlay/` + `model/` | 6 文件, 643 行 | 4 文件, 575 行 | **Strong** | 状态和渲染规格覆盖良好 |
| `ui/overlay/compose/`, `ui/overlay/visualizer/` | 10 文件, 1230 行 | 0 文件 | **Absent, acceptable** | Compose/overlay 接线 — 更适合通过 instrumented/UX 流程覆盖 |
| `ui/settings/`, `ui/onboarding/`, `ui/navigation/` 等 | 35 文件, 5604 行 | 0 文件 | **Absent, mostly acceptable** | 声明式 UI — 单元测试优先级低，除非逻辑被提取出来 |

### Schema 和工具类

| 模块 | 生产面 | 测试面 | 等级 | 备注 |
|------|--------|--------|------|------|
| `protocol/`, `model/`, `util/` | 30 文件, 1159 行 | 1 文件, 42 行 | **Absent, mostly acceptable** | 以不可变事件/枚举为主。例外: 包含逻辑的辅助函数 |
| `assets/`, `res/`, `AndroidManifest.xml` | 29 文件, ~714 行 | 0 直接 | **Absent, acceptable** | 资产加载/路径安全由 AssetAppSkillRepositoryTest 覆盖 |

---

## 关键覆盖缺口（按风险排序）

### 1. LLM 线格式、Parser 和 Retry 栈 (HIGHEST)

**文件**: CodexRequestBuilder, CodexSseParser, CodexResponseClient, OpenAIErrorClassifier, CloudLlmRetry, CloudStreamRetryPolicy, CloudStreamRetryRunner

**为何风险最高**: 这是外部契约边界。错误的线格式、损坏的 SSE 解析或错误的 retry/error 分类会导致静默失败、格式错误的 tool call 或浪费 API 额度。

**当前未保护的失败模式**:
- 格式错误的 SSE 事件排序或尾部 buffer 处理
- 并行调用间错误的 tool-call 参数累积
- 误分类的 429/5xx/网络故障
- 部分 stream 输出后的 backoff/retry
- Request-body 格式回归

### 2. 安全敏感 Tool (HIGH)

**文件**: ShellTool, AskUserTool

**为何风险高**: 两者都位于行为/安全边界。ShellTool 执行命令防护和超时/输出行为。AskUserTool 阻塞 agent 并控制用户交互交接。

**失败模式**: 被屏蔽命令逻辑过弱/过宽，超时/截断回归，重复 ask-user 处理破坏 capsule 行为。

### 3. Service 和 Session 编排 (HIGH)

**文件**: AgentService, AgentServiceEventHandler, ServiceOverlayController, SessionCoordinator, SessionAgentRunner, SessionCheckpointCoordinator

**为何风险高**: Agent 周围的运行时外壳。控制启动、关闭、事件收集、输入排队、overlay 协调、session 交接。大型、有状态、coroutine 驱动。设备故障在此变为用户可见。

### 4. Agent 规划和执行编排 (MEDIUM-HIGH)

**文件**: TurnPlanningPhaseRunner, TurnExecutionPhaseRunner, AgentTurnRunner

**原因**: 连接 prompt 构建、LLM streaming、仲裁、tool 执行、observation 捕获和事件发射。各组件已测试但编排接缝未测试。

### 5. Onboarding 和 Auth 流程 (MEDIUM-HIGH)

**文件**: OnboardingViewModel (503 行状态机), DefaultOnboardingDemoController, OnboardingStore, PermissionStateMonitor, HttpLlmCredentialValidator, OpenAIOAuth, OAuthCredentialStore

**原因**: 首次运行转化加凭据处理。多步骤异步状态机，零直接测试保护。

### 6. Trace/隐私 Pipeline (MEDIUM)

**文件**: CognitionTraceRedactor, AgentTraceArtifacts, FileTraceRecorder, LlmInputItemsTraceSerializer

**原因**: 隐私 bug 代价高昂。当前的间接测试仅证明一条 happy path — 未隔离 redaction 或 artifact 打包的边界情况。

### 7. Chat 状态管理 (MEDIUM)

**文件**: ChatViewModel, ChatEventReducer, ChatSessionHistoryController, MessageConverter

**原因**: 用户可见行为。Session 恢复、replay 截断、streaming 更新排序、action-card 转换可能在辅助函数测试通过的同时发生回归。

### 8. 超越 Click/Scroll/Long-Press 的 Action 验证 (MEDIUM)

**文件**: TypeExecutor, SwipeExecutor, UiChangeDetector, PointActionExecutorCore

**原因**: Action 套件存在偏斜 — typing 和 swipe 验证是频繁的设备故障点但未被覆盖。

### 9. Virtual Display 纯协作者 (MEDIUM)

**文件**: VirtualDisplayViewerTouchHandler, VirtualDisplaySurfaceController, VirtualDisplayCaptureCoordinator

**原因**: "难以单元测试" 不等于 "应保持未测试"。Touch handler 和 surface controller 有纯决策逻辑，今天就可以进行单元测试。

---

## 测试质量分析

### 优势

1. **行为优先测试**: 测试描述用户相关的结果，而非实现细节
2. **描述性命名**: 反引号风格的 Kotlin 测试名称始终精确
3. **Fake 优于 mock**: FakeAndroidPlatform、scripted LLM client、RecordingPlatform — 可读且抗重构
4. **真实边界情况覆盖**: inline tool-call 恢复、path traversal 拒绝、hint 污染、重复索引
5. **结构清晰**: 一致的 arrange-act-assert，命名良好的辅助函数，用于文件系统测试的 TemporaryFolder
6. **正确的 coroutine 测试**: 使用 runTest 和 TestScope, advanceTimeBy，无真实延迟

### 问题

1. **覆盖聚集**: 500 个测试方法集中在已安全的文件中 (CapsuleStateHolderTest: 41, PerceptorInternalsTest: 34, ModelCatalogTest: 34)，而运行时包为空白
2. **Fixture 重复**: 3x RecordingPlatform, 7+ LLMClient fake, 5x buildServices() 辅助函数 — 约 530 行重复代码
3. **断言库混用**: 大多数使用 Truth，但 LLMClientFactoryTest 和 ModelCatalogTest 使用 JUnit 断言
4. **低价值精确数据断言**: AgentDefTest 快照精确 tool 列表，OpenAppToolTest 检查特定 alias-map 条目 — 高维护成本，低信心收益
5. **缺失边界/对抗测试**: 风险最高的未测试代码正是格式错误或意外输入到达的地方

---

## 不应添加的测试

- Compose UI 渲染测试（使用 `/ux-visual-debug` 或 instrumented 测试）
- Protocol 事件/枚举 data class 测试（纯数据载体）
- 针对 OpenAI/OAuth/Shizuku 的实时网络测试（应测试 parser/classifier/collaborator）
- 模拟整个 Android 运行时的大型 mock 密集测试（提取纯逻辑，测试纯逻辑）
- 静态 map、app-skill 内容、颜色、字符串的逐条测试（测试加载器和行为）
- AndroidManifest、资源、AIDL 声明的泛覆盖测试
- Virtual display 端到端单元测试（属于 instrumented 覆盖领域）

---

## 覆盖良好的关键路径（无需操作）

- **策略引擎** (PolicyEngineTest): 所有层级、逃逸 action、审批模式
- **Tool 仲裁** (TurnToolPolicyTest): 认知 vs. screen tool、完成延迟
- **循环检测** (LoopDetectionPolicyTest): Screen 相似度、Jaccard 阈值
- **Tool router** (ToolRouterTest): 审批流程、超时、并发、取消
- **历史压缩** (HistoryManagerTest): 所有 P0 不变量
- **Agent 错误恢复** (AgentErrorRecoveryTest): DNS、超时、context length
- **Turn tool 过滤** (TurnToolFilteringTest): inline 恢复、allowlist、streaming 抑制
- **子 agent 生命周期** (SubAgentRunnerTest): 成功、超时、complete_task、步数限制
- **Click executor** (ClickExecutorTest): Node click、gesture fallback、text promotion、hotspot、OOB、验证
- **Node action performer** (NodeActionPerformerTest): Recycling、hint guard、text entry 污染
- **Prompt builder** (PromptBuilderTest): Memory、observation、function history、app skill 注入
