# 死代码与过度抽象: 最终审查

日期: 2026-04-08
流程: Double-design (Claude + Codex), 交叉审查, /align
基础: CODEX 设计, 结合 Claude 经验证的发现
状态: **ALIGNED**

---

## 范围

- 审查了 `app/src/main/kotlin/com/moonkey/androidagent/` (267 个文件)
- 使用 `rg` 在 `app/src/main/kotlin` 和 `app/src/test/kotlin` 中验证了引用
- 两个 agent 独立审查、交叉审查, 并对发现达成一致
- 一方标记但被另一方否定的条目已排除

---

## 已确认的死代码

### 整个死文件

| # | 文件 | 行数 | 证据 |
|---|------|------|------|
| 1 | `util/StatusUtils.kt` | ~104 | 自身文件外零 import |
| 2 | `tool/handlers/DataQueryInvocation.kt` | ~51 | 零 import, 为不存在的 `list_apps` tool 设计 |
| 3 | `session/SessionServicesSummaryFormatter.kt` | ~31 | 唯一调用者是死方法 `getSummary()` |
| 4 | `.DS_Store` 在源码树中 | -- | Finder artifact 出现在 Kotlin 源码树中 |

### 死方法

| # | 方法 | 文件 | 证据 |
|---|------|------|------|
| 5 | `SessionServices.getSummary()` | `session/SessionServices.kt` | 零调用者 |
| 6 | `SessionServices.updateApprovalMode()` | `session/SessionServices.kt` | 零调用者 |
| 7 | `AppClassifier.addUserOverride()` | `tool/AppClassifier.kt` | 零调用者; `userOverrides` 字段也是死的 |
| 8 | `ToolCallResult.isSuccess()` | `tool/ToolCallResult.kt` | 零调用者; 代码使用 `is Success` 模式匹配 |
| 9 | `ToolCallResult.getOutputOrNull()` | `tool/ToolCallResult.kt` | 零调用者 |
| 10 | `ToolSpec.toFunctionSchema()` | `tool/ToolSpec.kt` | 零调用者; schema 使用 `generateResponsesApiTools()` |
| 11 | `ToolSpec.ValidationResult.isValid()` | `tool/ToolSpec.kt` | 零调用者 |
| 12 | `ToolSpec.ToolExecutionResult.isSuccess()` | `tool/ToolSpec.kt` | 零调用者 |
| 13 | `ActionResult.isSuccess()` | `platform/ActionResult.kt` | 零调用者; 代码使用穷举式 `when` |
| 14 | `AgentRegistry.getAll()` | `agent/subagent/SubAgentRunner.kt` | 零调用者 |

### 死 Composable / UI

| # | 条目 | 文件 | 证据 |
|---|------|------|------|
| 15 | `ApiKeysSection` | `ui/settings/ApiKeyFields.kt` | 仅有声明, 零调用者 |
| 16 | `BackendSelector` | `ui/settings/SettingsDropdowns.kt` | 仅有声明, 零调用者 |
| 17 | `SettingsDropdownOptionWithDescription` | `ui/settings/SettingsDropdown.kt` | 仅有声明, 零调用者 |

### 死 Auth / Onboarding

| # | 条目 | 文件 | 证据 |
|---|------|------|------|
| 18 | `refreshOAuthToken()` | `auth/OpenAiSignIn.kt` | 仅有声明, 零调用者 |
| 19 | `OnboardingViewModel.context` | `onboarding/OnboardingViewModel.kt` | 未使用的构造参数 (仍从 MainActivity 传入) |
| 20 | `DefaultOnboardingDemoController.modelCatalog` | `onboarding/DefaultOnboardingDemoController.kt` | 未使用的构造参数 (仍从 MainActivity 传入) |

### 死字段 / 参数

| # | 条目 | 文件 | 证据 |
|---|------|------|------|
| 21 | `AgentDef.id` | `agent/definition/AgentDef.kt` + 子类 | 写入但从未读取 |
| 22 | `ScreenSnapshotDebug.captureQualityPath` | `model/Models.kt` | 在 AccessibilityPlatform 中设置, 从未读取 |

### 死公共 API 接口面

| # | 条目 | 文件 | 证据 |
|---|------|------|------|
| 23 | `SessionHistoryManager.deleteSessionByFileName()` | `history/SessionHistoryManager.kt` | 零调用者 |
| 24 | `SessionHistoryManager.getMostRecentSession()` | `history/SessionHistoryManager.kt` | 零调用者 |
| 25 | `SessionHistoryManager.hasActiveSession()` | `history/SessionHistoryManager.kt` | 零调用者 |
| 26 | `SessionHistoryManager.endSession()` | `history/SessionHistoryManager.kt` | 零调用者 |
| 27 | `SessionHistoryManager.loadSessionByFileName()` | `history/SessionHistoryManager.kt` | 仅内部调用 -> 应改为 private |

### 死分支

| # | 条目 | 文件 | 证据 |
|---|------|------|------|
| 28 | `ExecutorStepDecision.WarnApproaching` | `agent/cognition/policy/ExecutorStepPolicy.kt` | 有生产但从未被消费; `AgentTurnRunner` 仅处理 `ForceStop` |

---

## 已确认的过度抽象

### 单实现 Interface

| # | Interface | 实现 | 操作 |
|---|-----------|------|------|
| 29 | `OnboardingDemoController` | `DefaultOnboardingDemoController` | 合并; 通过构造函数传递; 消除 nullable 延迟赋值 |
| 30 | `LlmCredentialValidator` | `HttpLlmCredentialValidator` | 合并; 从 `createValidatorForProvider()` 返回具体类型 |

### Sub-Agent Catalog (虚假的 Marketplace)

| # | 条目 | 证据 | 操作 |
|---|------|------|------|
| 31 | `delegate_task` 中的 `agent_name` | 仅有一个有效目标 ("executor") | 移除参数, 硬编码目标 |
| 32 | `AgentRegistry` + catalog 查找 | 仅注册一个条目 | 折叠; 直接使用 executor 配置 |
| 33 | `narrativeSummaryOnLimit` | 从未覆盖默认值 `true` | 移除; 硬编码行为 |

---

## 明确非死代码 (交叉审查修正)

以下条目被一方或双方设计标记, 但在交叉审查中被否定:

| 条目 | 存活原因 |
|------|---------|
| `ToolCallState.kt` | 被 `ToolRouter.kt` 积极用于状态追踪 |
| `Bounds.width/height/centerX/centerY` | 被 `ScrollExecutor.kt` 使用 |
| `ScreenSnapshot.hasElements` | 被 `TargetResolver.kt`, `ObservationBuilder.kt` 使用 |
| `ToolCallResult.Success.data` | 由 `WriteTodosTool`, `DelegateTaskTool` 等填充 |
| `ToolExecutionResult.Success.data` | 由 `ToolRouter.kt` 转发 |
| `MobileActionName.Back/Home/Wait/SystemButton` | 被 `PolicyEngine.isEscape()`, `ToolUi.kt` 使用 |
| `ObservationBuilder.kt` / `ScreenSummary.kt` | 从 `UIActionInvocation`, `OpenAppTool`, `PostActionAnalysis` 调用 |
| `ScreenSnapshot.textEnriched` | 被 `Perceptor.kt` 和 `UiChangeDetector.kt` 使用 |

---

## 延期处理 (需进一步调查)

| 条目 | 问题 |
|------|------|
| `AgentError.kt` | `SessionError.error` 类型为 `AgentError`; 需验证整个 `SessionError` 发出路径是否为死路径 |
| `ScreenSnapshot.hasScreenshot` | 可能未使用, 但需独立验证 (之前与存活的 `hasElements` 一起分组) |
| `AgentEventDomains.kt` marker interface | 低价值的 domain 分类, 无消费者对其过滤; 仅有装饰作用 |
| `ToolRouterContext` | 单 interface/impl, 单一调用者; 较低优先级的简化 |

---

## 已验证并保留的抽象

| 条目 | 理由 |
|------|------|
| `LLMClient` | 4 个生产实现 |
| `AndroidPlatform` | 2 个生产实现 |
| `TraceRecorder` | `FileTraceRecorder` + `NoopTraceRecorder` |
| `AgentDef` 层级 | 3 个真实角色 (planner, executor, standalone) |
| `AppSkillRepository` | Null-object 模式, 用于 sub-agent services |
| `ToolSpec` / `ToolInvocation` | 干净的 spec/execution 分离, 多个 tool |
