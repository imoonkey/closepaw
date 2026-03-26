# Agent Security 现状实现审查

> 本文档记录 Android Agent 当前安全实现的完整基线。
> 审查日期: 2026-03-24
> 审查范围: PolicyEngine, ToolRouter, approval 流程, per-action risk, AskUser, 感知层, 现有设计文档


> 我们的security/approval应该如何设计？按action设计，MEDIUM这层太宽了。

>> **[回复]** 你说得对，按 action type 分级是错误的抽象层。下面第 5 条详细展开。核心结论：risk 应该由 **where（哪个 app）× what（语义意图）** 决定，不是由 action primitive（click/type/scroll）决定。`agent_security/final/design.md` 的 escalation table（AppSensitivity × CapabilityClass）是正确方向，但 CapabilityClass 也不应该直接从 action type 派生——Phase 2 的 ActionSensitivityTag 才是真正的语义层。

> Default risk level和approval mode怎么被用的？medium risk在smart mode跟approval有什么关系吗？我怎么之前很少看到有请求approval的情况？这些有实际被wire被使用吗？

>> **[回复]** **被 wire 了，但等于没用。** 完整路径：`TurnExecutionPhaseRunner` → `ToolRouter.execute()` → `PolicyEngine.check()` → `evaluateRiskLocked()`。代码是通的，但决策逻辑是空壳：SMART mode 下 LOW 和 MEDIUM 都返回 `Allow`，而**所有 tool/action 的默认 risk 都是 LOW 或 MEDIUM，没有任何 HIGH**。所以你从没见过 approval 弹窗是正常的——SMART mode 当前 = AUTO_APPROVE。详见下文 1.7.1。

> 看看doc/todo/security_hardening/agent_security/。不知道这分析有没有用。

>> **[回复]** 有用。`agent_security/final/design.md` 是 double-design 产出的对齐设计，直接可以作为实现蓝图。它解决了这份 review 发现的所有 6 个 gap：加了 AppSensitivity 分类、ObservationPolicy（perception gate）、escalation table、AppSensitivityResolver、user override 机制。8 个 task（T1-T8）有明确 scope 和 acceptance criteria。这份 review 的价值是让你看到**现在的代码到底在干什么**，design.md 的价值是**该改成什么**。

> reference里openclaw, ironclaw(it emphasizes on security)都是怎么搞的。

>> **[回复]** 已派两个 subagent 后台调研 OpenClaw 和 IronClaw 的安全模型。结果会写到 `agent_security_review/reference_openclaw.md` 和 `reference_ironclaw.md`。

> action按type其实很不make sense。因为付款只需要点击，搜索可能需要type。没法说type more risky than click。

>> **[回复]** 完全同意。这是当前设计的根本缺陷。`type` 被标 MEDIUM 而 `click` 是 LOW 在语义上是错的——在支付宝里点击"确认付款"比在搜索框里 type "天气"危险一万倍。**Action primitive 不携带语义信息**，risk 不应该从 primitive 派生。
>>
>> 正确的分层应该是：
>> - **Layer 1（rule-based，确定性）**：App classification（BLOCKED/GUARDED/SENSITIVE/STANDARD）。这个已经在 `agent_security/final/design.md` 里设计好了。落地快、确定性强、不依赖 LLM 判断。
>> - **Layer 2（agent-evaluated，LLM 辅助）**：Action semantic tagging。Agent 在执行前评估"这个 click 的语义是什么"（付款？发消息？删除？导航？）。这是 design.md 里预留的 `ActionSensitivityTag`（Phase 2）。
>>
>> 两层组合：Layer 1 建立 safety floor（银行 app 整体 BLOCKED），Layer 2 在允许的 app 里做细粒度判断（在微信里发红包 vs 读消息）。

> 我觉得有两条路并行: 一条agent自己evaluate，一条是基于一些有效的rule (e.g., app category)。你帮我想想。

>> **[回复]** 这个直觉是对的，而且两条路不是平行替代，而是**分层叠加**：
>>
>> **Rule-based layer（确定性，zero-latency，Phase 1）：**
>> - App category classification（package name → AppSensitivity tier）
>> - Hardcoded blocklist + keyword heuristic + user override
>> - Perception gate（BLOCKED app 的屏幕内容不发给 LLM）
>> - 优点：确定性、可测试、zero cost、不依赖 LLM 判断
>> - 这就是 `agent_security/final/design.md` 的 Phase 1
>>
>> **Agent-evaluated layer（LLM 辅助，per-action，Phase 2）：**
>> - Agent 在执行 action 前，评估该 action 的语义意图
>> - 输入：当前 screen context + tool call params + task goal
>> - 输出：ActionSensitivityTag（MONEY_MOVEMENT / PUBLIC_POST / DESTRUCTIVE / SECRET_ENTRY / PERMISSION_CHANGE）
>> - PolicyEngine 用 tag 做 escalation（即使 app 是 STANDARD，如果 tag 是 MONEY_MOVEMENT 也要 ask）
>> - 优点：能捕获 rule 层无法覆盖的场景（在 STANDARD app 里的危险操作）
>> - 缺点：依赖 LLM 判断（可能出错）、有 latency、有 token cost
>>
>> **组合逻辑：`finalRisk = max(ruleBasedRisk, agentEvaluatedRisk)`**
>> - Rule layer 是 floor（不可被 agent 降级）
>> - Agent layer 只能 escalate（不能放宽 rule 的约束）
>> - 即使 agent 判断"这个操作安全"，rule 说 BLOCKED 就是 BLOCKED
>>
>> **实现顺序：先 Phase 1（rule-based），再 Phase 2（agent-evaluated）。** Phase 1 已有完整设计（T1-T8），可以直接开始实现。Phase 2 需要单独设计 agent evaluation 的 prompt、latency budget、error handling（agent 说"安全"但实际不安全怎么办）。

---

## 1. PolicyEngine

> 源码: `app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt`

### 1.1 RiskLevel

定义在 `protocol/ApprovalTypes.kt`:

```kotlin
enum class RiskLevel {
    LOW,     // 通常自动批准 (只读/可逆)
    MEDIUM,  // 根据策略可能需要审批
    HIGH     // 通常需要显式审批 (破坏性操作)
}
```

三档风险等级,语义简单明确。

### 1.2 ApprovalMode

定义在 `protocol/SessionConfig.kt`:

```kotlin
enum class ApprovalMode {
    ALWAYS_ASK,     // 每个 tool 都问用户
    AUTO_APPROVE,   // 全部自动批准
    SMART           // 低风险自动, 高风险问用户
}
```

- `ALWAYS_ASK`: 每次 tool call 都触发 `PolicyDecision.AskUser`
- `AUTO_APPROVE`: 直接返回 `PolicyDecision.Allow`, **绕过所有风险判断**
- `SMART`: 走 `evaluateRiskLocked()` 做基于风险的决策

ApprovalMode 通过 `AtomicReference` 存储,支持运行时线程安全变更 (`setApprovalMode()`)。

### 1.3 DEFAULT_RISK_LEVELS

```kotlin
private val DEFAULT_RISK_LEVELS = mapOf(
    "mobile_action" to RiskLevel.MEDIUM,
    "system_button" to RiskLevel.MEDIUM,
    "wait"          to RiskLevel.LOW,
    "open_app"      to RiskLevel.MEDIUM,
    "complete_task"  to RiskLevel.MEDIUM,
    "write_todos"   to RiskLevel.LOW,
    "scratchpad"    to RiskLevel.LOW,
    "delegate_task" to RiskLevel.MEDIUM
)
```

**缺失的 tool**:
- `shell` — 不在 DEFAULT_RISK_LEVELS 中。走 fallback 逻辑: `DEFAULT_RISK_LEVELS[riskKey] ?: RiskLevel.MEDIUM`,所以默认 MEDIUM
- `ask_user` — 不在表中,同样 fallback 到 MEDIUM
- `remember_experience` — 不在表中,fallback 到 MEDIUM

**观察**: `mobile_action` 作为整体被标记为 MEDIUM,但实际 risk 由 `MobileActionName.defaultRiskLevel` 逐 action 解析,所以这条 entry 只在无法解析出具体 action 时才作为 fallback。

### 1.4 MobileActionName.defaultRiskLevel

定义在 `tool/ToolName.kt`:

| Action | defaultRiskLevel |
|--------|-----------------|
| `click` | LOW |
| `long_press` | LOW |
| `type` | MEDIUM |
| `scroll` | LOW |
| `swipe` | LOW |
| `back` | LOW |
| `home` | MEDIUM |
| `wait` | LOW |
| `system_button` | MEDIUM |
| `Unknown(*)` | MEDIUM |

**关键**: `type` 是唯一 MEDIUM 的动作类操作,因为输入文本有更高的副作用风险。但在 SMART 模式下,MEDIUM 也是自动批准的(见下文)。

### 1.5 `evaluateRiskLocked` 完整逻辑

```
输入: toolName, params
输出: PolicyDecision (Allow / AskUser / Deny)

1. check() 被调用
2. 读取 approvalMode (AtomicReference)
3. 将 toolName 转为 canonical name (ToolName.from(toolName).canonical)
4. synchronized(lock) 内:
   a. 检查 denyList → 如匹配,返回 Deny
   b. 检查 allowList → 如匹配,返回 Allow (优先于 deny 以外的一切)
   c. 根据 approvalMode 分支:
      - ALWAYS_ASK → AskUser(reason, riskLevel)
      - AUTO_APPROVE → Allow (直接跳过所有风险判断)
      - SMART → 调用 evaluateRiskLocked(toolName, params)

evaluateRiskLocked(toolName, params):
   1. 调用 getRiskLevelLocked(toolName, params) 获取 riskLevel
   2. 根据 riskLevel:
      - LOW → Allow
      - MEDIUM → Allow  ← 重要: MEDIUM 也是自动批准
      - HIGH → AskUser

getRiskLevelLocked(toolName, params):
   1. 调用 resolveActionName(toolName, params) 尝试解析为 MobileActionName
   2. riskKey = action?.canonical ?: ToolName.from(toolName).canonical
   3. 检查 riskOverrides[riskKey] → 如有,直接返回
   4. 返回 action?.defaultRiskLevel ?: DEFAULT_RISK_LEVELS[riskKey] ?: MEDIUM
```

**resolveActionName(toolName, params)**:
1. 先尝试 `MobileActionName.fromOrNull(toolName)` — 直接匹配 action 名
2. 如果 toolName 是 `mobile_action`,从 `params.optString("action")` 解析
3. 否则返回 null

### 1.6 Allow/Deny Lists

- `allowList: MutableSet<String>` — canonical names
- `denyList: MutableSet<String>` — canonical names
- deny 优先级 > allow 优先级 > approval mode
- `allowTool()` 添加到 allowList 并从 denyList 移除 (互斥)
- `denyTool()` 添加到 denyList 并从 allowList 移除 (互斥)
- `resetTool()` 同时从两个 list 和 riskOverrides 中移除
- `reset()` 清空所有自定义,重置 mode 为 SMART

**当前状态**: 代码中没有任何地方预设 allow/deny list 条目。两个列表在 session 创建时都是空的。它们是纯 runtime API,没有持久化。

### 1.7 发现的问题和缺口

1. **MEDIUM 风险等于 LOW**: 在 SMART 模式下,`evaluateRiskLocked` 对 LOW 和 MEDIUM 都返回 `Allow`。这意味着:
   - `type` (MEDIUM) 自动批准
   - `open_app` (MEDIUM) 自动批准
   - `system_button` (MEDIUM) 自动批准
   - `delegate_task` (MEDIUM) 自动批准
   - **实际效果: SMART 模式只在 HIGH risk 时才 ask user,而当前没有任何默认 HIGH risk 的 tool 或 action**
   - 结论: **SMART 模式目前与 AUTO_APPROVE 的行为完全一致**

2. **无 App 级别感知**: PolicyEngine 不接收也不使用当前前台 app 信息。对银行 app 和计算器 app 的 `click` 一视同仁。

3. **无 Observation 门控**: 感知(屏幕截图 + a11y tree)在 `AgentTurnRunner.capturePreTurnSnapshot()` 中无条件捕获,然后发送给 LLM。PolicyEngine 只在 action 执行前介入,不在观察阶段。敏感 app 的屏幕内容(余额、交易记录等)会直接发送到外部 LLM API。

4. **AUTO_APPROVE 可绕过一切**: `AUTO_APPROVE` 模式下,deny list 之外的所有 tool call 都直接 Allow。没有任何 app 分类或敏感度检查能阻止它。

5. **shell tool 缺乏 PolicyEngine 注册**: `shell` 不在 DEFAULT_RISK_LEVELS 中。虽然 fallback 给了 MEDIUM (即自动批准),但 shell 有自己的 `BLOCKED_COMMANDS` 验证层。两个安全机制(PolicyEngine risk + ShellTool validation)没有协同。

6. **hardcoded risk 在 enum constructor 中**: `MobileActionName` 的 `defaultRiskLevel` 是在 sealed class 构造函数中硬编码的,不在 PolicyEngine 的统一表中。这导致 risk 定义分散在两处。

7. **resolveRiskKey 是 private top-level function**: `resolveRiskKey` 和 `resolveActionName` 是文件级私有函数而非 PolicyEngine 成员,略有架构不一致(虽然功能正确)。

---

## 2. ToolRouter

> 源码: `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt`

### 2.1 状态机

```
VALIDATING ──valid──► SCHEDULED ──────────────► EXECUTING ──► SUCCESS
    │                     │                         │
    │invalid              │ policy=ASK             │error
    ▼                     ▼                        ▼
  ERROR           AWAITING_APPROVAL ──────────► ERROR
                        │
                 ┌──────┼──────┐
                 ▼      │      ▼
             EXECUTING  │  CANCELLED
               (approved) (denied/abort/timeout)
```

状态定义在 `tool/ToolCallState.kt`,是 sealed class:
- `Validating` — 验证 tool 和参数
- `Scheduled` — policy 允许,等待执行
- `AwaitingApproval` — 需要用户审批
- `Executing` — 正在执行
- `Success` / `Error` / `Cancelled` — 终态

`isTerminal()` 判断: Success / Error / Cancelled 为终态。

### 2.2 PolicyEngine 调用流程

```kotlin
val policyDecision = policyEngine.check(toolName, params)
```

在 validation 通过、invocation 创建之后调用。

- `Deny` → 返回 Error,状态变为 Error
- `AskUser` → 状态变为 AwaitingApproval,触发审批流
- `Allow` → 状态变为 Scheduled,直接进入执行

### 2.3 Approval 流程

1. 创建 `CompletableDeferred<ApprovalDecision>`,放入 `pendingApprovals[callId]`
2. 构造 `ApprovalDetails` (包含 callId, toolName, args, description, riskLevel)
3. 调用 `onApprovalRequired?.invoke(approvalDetails)` 通知 UI
4. `withTimeout(60_000)` 等待 deferred

**ApprovalDecision** 的三种结果:
- `APPROVED` → 继续执行 (重新截屏,因为等待期间 UI 可能变了)
- `DENIED` → Cancelled("User denied"),只跳过当前 action
- `ABORT` → Cancelled("User aborted session"),终止整个 session

**Timeout**: 60 秒无响应 → Cancelled("Approval timed out")

**Race condition 防护**: deferred 在通知 UI 之前就注册到 `pendingApprovals`,避免 UI 快速响应时找不到 deferred。

### 2.4 Approval 后的 Snapshot 刷新

```kotlin
val executionSnapshot = if (approvalWasRequired) {
    context.platform.captureScreen()
} else {
    context.currentSnapshot
}
```

如果经过了审批等待,会重新截屏,因为在等待期间用户可能操作了设备。这是一个合理的设计。

### 2.5 `resolveApproval(callId, decision)`

UI 通过此方法解析待审批项。通过 `callId` 查找 `pendingApprovals` 中的 deferred 并 complete。返回 bool 表示是否找到待审批项。

### 2.6 Cancellation

- `cancel(callId)`: 向 deferred 注入 ABORT,移除 activeToolCalls
- `cancelAll()`: 所有待审批 deferred 注入 ABORT,清空所有跟踪

### 2.7 UI 通知链

```
TurnExecutionPhaseRunner.executeSingleToolCall()
  → toolRouter.execute(..., onApprovalRequired = { details -> emitApprovalRequired(details) })
    → eventEmitter(ApprovalRequired(sessionId, timestamp, actionId, description, details))
      → AgentSession.events (SharedFlow)
        → UI collects and shows approval dialog
```

用户决策:
```
UI → session.submit(Op.Approve(actionId, decision))
  → AgentSession → toolRouter.resolveApproval(callId, decision)
    → deferred.complete(decision)
```

### 2.8 观察到的问题

1. **activeToolCalls 清理**: 在 Error 和 Cancelled 状态中显式 `remove(callId)`, Success 路径在 `finally` 中清理。Early return 路径(validation error, policy deny)也显式清理。逻辑正确。

2. **并发安全**: `pendingApprovals` 和 `activeToolCalls` 用 `ConcurrentHashMap`,deferred 用 coroutines 原语。审批注册在 UI 通知之前完成。设计合理。

3. **没有 appContext 传递**: `execute()` 不接收也不传递任何 app/package 信息给 PolicyEngine。

---

## 3. Approval Types

> 源码: `app/src/main/kotlin/com/moonkey/androidagent/protocol/ApprovalTypes.kt`

### ApprovalDecision

```kotlin
enum class ApprovalDecision {
    APPROVED,  // 批准
    DENIED,    // 拒绝当前 action (session 继续)
    ABORT      // 终止整个 session
}
```

### RiskLevel

```kotlin
enum class RiskLevel { LOW, MEDIUM, HIGH }
```

### ApprovalRequirement (未使用)

```kotlin
sealed interface ApprovalRequirement {
    data object None : ApprovalRequirement
    data class Required(val reason: String, val riskLevel: RiskLevel) : ApprovalRequirement
    data class Forbidden(val reason: String) : ApprovalRequirement
}
```

**注意**: `ApprovalRequirement` 在当前代码中 **没有被使用**。PolicyEngine 直接返回 `PolicyDecision`,不经过 ApprovalRequirement。这可能是遗留设计或未来预留。

### ApprovalDetails

```kotlin
data class ApprovalDetails(
    val callId: String,
    val toolName: String,
    val args: JSONObject,
    val description: String = "",
    val riskLevel: RiskLevel = RiskLevel.MEDIUM
)
```

当前字段非常简单。没有 app context、没有 capability class、没有 escalation reason。

### Approval Events

定义在 `protocol/ApprovalEvents.kt`:

```kotlin
data class ApprovalRequired(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val actionId: String,
    val description: String,
    val details: ApprovalDetails
) : ApprovalDomainEvent

data class ApprovalResolved(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val actionId: String,
    val decision: ApprovalDecision
) : ApprovalDomainEvent
```

事件通过 `AgentEventDomains.kt` 中定义的 `ApprovalDomainEvent` 接口归类。

---

## 4. 每个 Action 的 Risk Level

### MobileActionName (tool/ToolName.kt)

| Sealed class 成员 | canonical | defaultRiskLevel |
|---|---|---|
| Click | `click` | LOW |
| LongPress | `long_press` | LOW |
| Type | `type` | MEDIUM |
| Scroll | `scroll` | LOW |
| Swipe | `swipe` | LOW |
| Back | `back` | LOW |
| Home | `home` | MEDIUM |
| Wait | `wait` | LOW |
| SystemButton | `system_button` | MEDIUM |
| Unknown(*) | 动态 | MEDIUM |

### ToolName (tool/ToolName.kt)

`ToolName` 是 sealed class,定义了 tool 级别的标识。`isScreenChanging` 属性区分会改变屏幕的 tool:

| ToolName | isScreenChanging |
|---|---|
| MobileAction | true |
| OpenApp | true |
| Wait | true |
| SystemButton | true |
| DelegateTask | true |
| CompleteTask | false |
| WriteTodos | false |
| Scratchpad | false |
| RememberExperience | false |

**risk 解析链**:
1. 对 `mobile_action` tool,先从 params 的 `action` 字段解析出 MobileActionName
2. 用 MobileActionName 的 defaultRiskLevel
3. 对其他 tool,用 DEFAULT_RISK_LEVELS 表
4. 都没有匹配时 fallback 到 MEDIUM

**实际结果**: 在 SMART 模式下,没有任何 tool 或 action 默认触发 AskUser,因为:
- 所有 defaultRiskLevel 都是 LOW 或 MEDIUM
- SMART 模式对 MEDIUM 自动批准
- 没有 HIGH 默认值

---

## 5. AskUser Tool

> 源码: `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/AskUserTool.kt`

### 功能

Agent 主动请求用户帮助的工具。两种类型:
- `question`: 需要文字回答
- `action`: 需要用户操作手机 (登录、权限、验证码)

### 与安全/审批的交互

**AskUser 完全独立于 PolicyEngine/approval 流程**:
- AskUser 通过 `UserResponseChannel` (CompletableDeferred) 挂起,等待用户响应
- Approval flow 通过 `ToolRouter.pendingApprovals` (CompletableDeferred) 等待
- 两者是 **不同的机制**,不共享代码或状态

**AskUser 不经过 approval**:
- AskUser 作为普通 tool 注册,default risk = MEDIUM (fallback)
- 在 SMART 模式下自动批准,在 AUTO_APPROVE 下也自动批准
- 只在 ALWAYS_ASK 模式下才会先问 approval (然后再问 ask_user 本身的问题 — 两层确认)

**Timeout**: 5 分钟无响应返回超时消息 (不是失败,是 Success 带提示)

### 安全影响

AskUser 本身不执行任何 UI 操作,只是通讯桥梁。但它的存在意味着:
- Agent 可以无条件地向用户显示消息 (没有频率限制)
- Agent 在用户操作手机后重新获取控制权时,不会重新评估安全策略

---

## 6. 当前缺口分析

### 6.1 App 级别感知: 完全不存在

PolicyEngine 的 `check(toolName, params)` 签名不接受任何 app/package 信息。

- `AgentTurnRunner.capturePreTurnSnapshot()` 确实获取了 `platform.getCurrentPackageName()`,但只用于 trace 和 UI 事件,**不传给 PolicyEngine**
- `TurnExecutionPhaseRunner.executeSingleToolCall()` 调用 `toolRouter.execute()` 时不传递 package 信息
- 无论在银行 app、Settings 还是计算器中,所有操作的 risk 判断完全相同

### 6.2 Observation/Perception 门控: 完全不存在

```kotlin
// AgentTurnRunner.capturePreTurnSnapshot() 中:
val snapshot = services.platform.captureScreen()
val currentPackage = services.platform.getCurrentPackageName()
// snapshot 无条件传给 planning phase → LLM
```

- 没有任何代码检查当前 app 是否敏感
- a11y tree 和 screenshot 无条件发送给 LLM (外部 API)
- 无条件写入 history 和 trace
- **隐私边界在认知之前完全不存在**

### 6.3 Hardcoded App Blocklists: 不存在

- 没有银行、金融、密码管理器等 app 的 blocklist
- 没有 package name 匹配逻辑
- `OpenAppTool` 有 `AppAliases.PACKAGE_MAP` 做名称→包名映射,但这是功能性的,不是安全性的

### 6.4 AUTO_APPROVE 的绕过能力

`AUTO_APPROVE` 模式下:

```kotlin
ApprovalMode.AUTO_APPROVE -> PolicyDecision.Allow
```

- 跳过所有风险评估
- **只有 denyList 能阻止** — 而 denyList 当前始终为空
- 没有任何 app 分类能阻止 AUTO_APPROVE
- 没有任何 "不可自动批准" 的底线

### 6.5 SMART 模式的实际效果

如 1.7.1 所述: SMART 模式对 MEDIUM 自动批准,而没有任何默认 HIGH 的 tool/action。

**SMART 模式当前 = AUTO_APPROVE**,唯一区别是 SMART 会检查 allow/deny list (但列表为空)。

### 6.6 Shell Tool 的独立安全层

ShellTool 有自己的 `BLOCKED_COMMANDS` 验证:

```kotlin
private val BLOCKED_COMMANDS = setOf(
    "rm", "mv", "cp", "chmod", "chown",
    "pm", "am", "settings", "reboot",
    "su", "sh", "bash", "eval", "exec"
)
```

这是在 `validate()` 阶段拦截的,先于 PolicyEngine。但只检查第一个 token:
- `rm -rf /` → 被拦截 (rm)
- `cat file | rm` → 不拦截 (cat 不在 blocklist)
- `xargs rm` → 不拦截 (xargs 不在 blocklist)

Shell 验证只是一层浅薄的防护。

### 6.7 Approval 流程中的 Race Condition 分析

**已防护**:
- deferred 在 UI 通知前注册,避免快速响应丢失
- `ConcurrentHashMap` 用于 pendingApprovals

**潜在问题**:
- `cancelAll()` 和并发 `resolveApproval()` 之间可能存在微妙的时序窗口: cancelAll 先 `forEach complete(ABORT)` 再 `clear()`,如果 resolveApproval 在 clear 后执行会返回 false (无害)
- 多个 tool call 同时等待 approval 时, `cancelAll()` 会批量 ABORT,但 UI 可能只展示了一个 approval dialog — 用户体验问题,不是安全问题

### 6.8 DelegateTask (SubAgent) 的安全传递

`DelegateTaskTool` 创建 SubAgent 执行子任务。SubAgent 有自己的 `SessionServices` (通过 `IsolatedSubAgentRunner`),但:
- SubAgent 共享同一个 `ToolRouter` 和 `PolicyEngine`
- 所以 SubAgent 受到与主 agent 相同的 policy 约束
- 但 SubAgent 的 tool call 也不传递 app context

### 6.9 remember_experience 和 memory 系统

`RememberExperienceTool` 可以写入持久化文件。classified 为 non-screen-changing,risk fallback 到 MEDIUM (自动批准)。

安全隐患: agent 可以将屏幕上观察到的敏感信息写入持久化 memory 文件,即使未来加了 observation masking,已存的 memory 条目不会被清理。

---

## 7. 现有文档

### 7.1 doc/main/infra/tools.md

记录了 tool 系统架构、PolicyEngine 的三种 mode、tool 执行 lifecycle 状态机图。是准确的但缺少 security 深度分析。

关于 PolicyEngine 只有一张表:

| Mode | Behavior |
|------|----------|
| `ALWAYS_ASK` | Prompt user before every tool |
| `AUTO_APPROVE` | Never ask, auto-approve all |
| `SMART` | Auto-approve low-risk, ask for high-risk |

没有提到 MEDIUM 也被自动批准这个关键细节。

### 7.2 doc/main/app/settings.md

记录了设置 UI 和 SessionConfig 编译。注意: **approvalMode 不在 Settings UI 中暴露**。设置页没有 approval mode 的下拉框或开关。`ApprovalMode.SMART` 是硬编码默认值。

### 7.3 doc/todo/0.5_openclaw/5_three_axis_security/

完整的三轴安全设计文档 (Claude + Codex 协作产出)。两个版本:
- `final/design.md` (英文)
- `final/design_cn.md` (中文)

核心设计:
- **Axis 1: SupervisionContext** (LOCAL_FOREGROUND / LOCAL_BACKGROUND / REMOTE)
- **Axis 2: CapabilityPolicy** (OBSERVE / NAVIGATE / EDIT / COMMIT → 对应 RiskClass)
- **Axis 3: ElevationPolicy** (escape hatch for unrestricted operations)

设计状态: `discussion/status.txt` 显示 `NEXT=DONE CODEX=APPROVE CLAUDE=APPROVE` — 设计已批准,但 **未实现**。

### 7.4 doc/todo/security_hardening/agent_security/final/design.md

更详细的 App-level Security Policy 设计。包含:
- `AppSensitivity` 枚举 (BLOCKED / GUARDED / SENSITIVE / STANDARD)
- `ObservationPolicy` (FULL / MASKED) — 解决 perception gating
- `AppPolicyProfile` — 每个 sensitivity tier 的 enforcement 规则
- Escalation table (CapabilityClass x AppSensitivity → RiskClass)
- `AppSensitivityDefaults` — hardcoded 银行/crypto/支付/密码管理器 blocklist
- `AppSensitivityResolver` — 解析链 (user override → skill metadata → built-in → GUARDED)
- 8 个实现 task (T1-T8) 的细分
- Phase 2 的 ActionSensitivityTag 预留

设计状态: 已完成,**未实现**。

### 7.5 doc/todo/security_hardening/note.md

记录了基础安全加固项 (EncryptedSharedPreferences, cleartext traffic, allowBackup 等) 和 agent security 讨论的起始上下文。

---

## 总结: 当前安全基线

### 已有的

| 组件 | 状态 | 有效性 |
|------|------|--------|
| PolicyEngine 框架 | 已实现 | 框架完整,但决策逻辑是空壳 (SMART = AUTO_APPROVE) |
| ToolRouter state machine | 已实现 | 健壮,approval 流程正确 |
| Approval UI event flow | 已实现 | 事件链完整,从 ToolRouter → UI 再回来 |
| per-action RiskLevel | 已实现 | 有分类但没有任何 action 是 HIGH |
| allow/deny list API | 已实现 | API 存在但从不被预填充 |
| ShellTool command blocklist | 已实现 | 浅层防护,可被管道/间接调用绕过 |
| ApprovalMode | 已实现 | 三种模式,但 SMART 无实际效果 |
| 三轴安全设计 | 已设计 | 未实现 |
| App-level security 设计 | 已设计 | 未实现 |

### 完全缺失的

| 能力 | 状态 |
|------|------|
| App 级别 risk 感知 | 不存在 — PolicyEngine 不知道前台 app |
| Observation/perception gating | 不存在 — 所有 app 屏幕内容无条件发给 LLM |
| App blocklist (银行/金融/auth) | 不存在 — 没有任何 package 特殊处理 |
| CapabilityClass 语义分类 | 不存在 — 只有扁平的 RiskLevel |
| SupervisionContext | 不存在 — 不区分前台/后台/远程 |
| ElevationPolicy | 不存在 |
| 有效的 SMART 模式 | 不存在 — MEDIUM 自动批准意味着一切自动批准 |
| Sensitivity tags (send/pay/delete) | 不存在 |
| Memory 系统的 security-aware 写入 | 不存在 — agent 可以将任何观察到的内容持久化 |

### 执行安全的唯一真正 choke point

当前系统的安全全部依赖于一个事实: **PolicyEngine + ToolRouter 是所有 tool execution 的唯一通道**。没有任何 tool 能绕过 `ToolRouter.execute()`。这是正确的架构基础,但 choke point 内部的决策逻辑当前不产生实际约束。

设计文档 (`design.md`) 的路线图是正确的方向: 在保持这个 choke point 的前提下,丰富决策输入 (app context, capability class, supervision context) 和决策逻辑 (escalation table, perception gate)。
