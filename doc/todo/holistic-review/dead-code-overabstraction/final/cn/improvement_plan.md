# 死代码与过度抽象: 最终改进计划

日期: 2026-04-08
流程: Double-design (Claude + Codex), 已对齐
状态: **APPROVED**

---

## 原则

- 在重构活代码之前, 先删除已验证的死代码
- 保留真正的抽象 (多平台, 多后端 LLM, trace/no-trace)
- 简化过程中不改变行为
- 每个 phase 后进行验证

---

## Phase 1: 安全删除

目标: 移除价值为零、风险接近零的代码。

### 1.1 删除整个死文件

- `app/src/main/kotlin/com/moonkey/androidagent/.DS_Store`
- `app/src/main/kotlin/com/moonkey/androidagent/util/StatusUtils.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/DataQueryInvocation.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServicesSummaryFormatter.kt`

### 1.2 从活文件中删除死方法

- `SessionServices.getSummary()` -- `session/SessionServices.kt`
- `SessionServices.updateApprovalMode()` -- `session/SessionServices.kt`
- `AppClassifier.addUserOverride()` + `userOverrides` 字段 -- `tool/AppClassifier.kt`
- `ToolCallResult.isSuccess()` -- `tool/ToolCallResult.kt`
- `ToolCallResult.getOutputOrNull()` -- `tool/ToolCallResult.kt`
- `ToolSpec.toFunctionSchema()` -- `tool/ToolSpec.kt`
- `ToolSpec.ValidationResult.isValid()` -- `tool/ToolSpec.kt`
- `ToolSpec.ToolExecutionResult.isSuccess()` -- `tool/ToolSpec.kt`
- `ActionResult.isSuccess()` -- `platform/ActionResult.kt`
- `AgentRegistry.getAll()` -- `agent/subagent/SubAgentRunner.kt`

### 1.3 删除死 composable / UI

- `ApiKeysSection` -- `ui/settings/ApiKeyFields.kt` (保留 `ApiKeyField`, 它是活的)
- `BackendSelector` -- `ui/settings/SettingsDropdowns.kt`
- `SettingsDropdownOptionWithDescription` -- `ui/settings/SettingsDropdown.kt`

### 1.4 删除死 auth

- `refreshOAuthToken()` -- `auth/OpenAiSignIn.kt`

### 1.5 删除死字段

- `ScreenSnapshotDebug.captureQualityPath` -- 移除字段及 `AccessibilityPlatform` 中的 setter

### 1.6 验证

```
./gradlew :app:compileDebugKotlin
./gradlew test
rg 'StatusUtils|DataQueryInvocation|SessionServicesSummaryFormatter|getSummary|updateApprovalMode|addUserOverride|toFunctionSchema|isValid\(\)|getOutputOrNull|ApiKeysSection|BackendSelector|SettingsDropdownOptionWithDescription|refreshOAuthToken|captureQualityPath' app/src/main/kotlin
```

---

## Phase 2: 死参数、API 接口面与死分支

目标: 移除未使用的参数, 收缩公共 API, 移除死分支。

### 2.1 移除死构造参数

- `OnboardingViewModel.context` -- 移除参数及 `MainActivity` 中的调用点实参
- `DefaultOnboardingDemoController.modelCatalog` -- 移除参数及 `MainActivity` 中的调用点实参

### 2.2 从层级中移除死属性

- `AgentDef.id` -- 从 `AgentDef.kt`, `StandaloneAgentDef.kt`, `PlannerAgentDef.kt`, `ExecutorAgentDef.kt` 中移除

### 2.3 收缩 `SessionHistoryManager`

- 将 `loadSessionByFileName()` 改为 private
- 删除 `deleteSessionByFileName()`
- 删除 `getMostRecentSession()`
- 删除 `hasActiveSession()`
- 删除 `endSession()`

### 2.4 移除死分支

- 删除 `ExecutorStepDecision.WarnApproaching`
- 简化 `ExecutorStepPolicy.evaluate()`, 仅发出 `Continue` 或 `ForceStop`
- 验证 `AgentTurnRunner.buildWarnings()` 仍正常工作 (它仅处理 `ForceStop`)

### 2.5 验证

```
./gradlew :app:compileDebugKotlin
./gradlew test
```

---

## Phase 3: Interface 简化

目标: 移除单实现 interface 和两阶段注入。

### 3.1 折叠 `OnboardingDemoController`

当前状态:
- `OnboardingViewModel` 持有 `var demoController: OnboardingDemoController? = null`
- `MainActivity` 构造 `DefaultOnboardingDemoController` 并在构造后赋值
- 仅一个实现, 无测试替身

变更:
1. 删除 `OnboardingDemoController.kt` (interface 文件)
2. 将 `DefaultOnboardingDemoController` 重命名为 `OnboardingDemoController` (具体类)
3. 通过 `OnboardingViewModel` 构造函数传递 (非延迟赋值)
4. 从 view model 中移除 nullable 可变字段
5. 更新 `MainActivity` 构造点

### 3.2 折叠 `LlmCredentialValidator`

当前状态:
- `LlmCredentialValidator` interface 仅有一个实现: `HttpLlmCredentialValidator`
- `OnboardingViewModel.createValidatorForProvider()` 直接实例化具体类型

变更:
1. 删除 `LlmCredentialValidator.kt` (interface 文件)
2. 将 `Result` sealed interface 移动为 `HttpLlmCredentialValidator` 的嵌套类 (或重命名为具体 result 类型)
3. `createValidatorForProvider()` 返回具体 validator
4. `validateApiKey()` 对具体 result 类型做模式匹配

### 3.3 验证

```
./gradlew :app:compileDebugKotlin
./gradlew test
```

手动冒烟测试: 权限步骤, API key 验证, demo 步骤启动/取消

---

## Phase 4: Sub-Agent Catalog 简化

目标: 保留 planner/executor 架构, 移除虚假的 catalog 灵活性。

### 4.1 从 `delegate_task` 中移除 `agent_name`

1. 从 `DelegateTaskTool.parameterSchema` 中移除 `agent_name`
2. 移除 `agent_name` 验证和查找逻辑
3. 将委派目标硬编码为 executor
4. 简化 tool 描述 (不再列出 "Available agents")

### 4.2 折叠 registry/definition 层

1. 将 `AgentRegistry.createDefault()` 替换为 `SessionAgentRunner` 中直接的 executor 配置
2. 保留一个小的 `ExecutorSubAgentConfig` data holder, 或内联 executor 常量
3. 移除 `AgentRegistry`
4. 移除 `ExecutorAgent` object
5. 移除基于 registry 派生的目录 prompt 生成

保留:
- `IsolatedSubAgentRunner`
- `SubAgentRequest` / `SubAgentResult`
- Planner/executor 架构

### 4.3 移除 `narrativeSummaryOnLimit`

- 从 `AgentDefinition` (或其定义位置) 中移除
- 在 executor step-limit 路径中硬编码当前的 `true` 行为

### 4.4 验证

```
./gradlew test --tests '*DelegateTaskToolTest'
./gradlew test --tests '*SubAgentRunnerTest'
./gradlew :app:compileDebugKotlin
```

手动冒烟测试: basic 模式启动 standalone agent, pro 模式使用 delegate_task, 被委派的 executor 正确完成

---

## Phase 5: 可选 / 延期

仅在方便或触及相邻代码时处理:

- `ToolRouterContext` 扁平化 (单 interface/impl, 单一调用者)
- `AgentEventDomains.kt` marker interface (12 个 marker, 无过滤消费者)
- `AgentError.kt` (待 `SessionError` 发出路径验证)
- `ScreenSnapshot.hasScreenshot` (待独立验证)

---

## 执行顺序

```
Phase 1 (文件/方法/字段删除)          -- 最安全, 优先执行
Phase 2 (参数, API 接口面, 死分支)    -- Phase 1 之后安全执行
Phase 3 (interface 合并)              -- 需要重命名 + 构造函数变更
Phase 4 (sub-agent catalog)           -- 架构级, 需仔细测试
Phase 5 (可选)                        -- 延期
```

---

## 预估影响

- **约移除 600+ 行**
- **4 个整文件删除**
- **2 个 interface 折叠**
- **17+ 个死方法/字段/参数移除**
- **Sub-agent catalog 实质性简化**
- **5 个死 sealed class variant / 分支移除**
- **死 settings UI、auth 和 onboarding 残留物清理**
