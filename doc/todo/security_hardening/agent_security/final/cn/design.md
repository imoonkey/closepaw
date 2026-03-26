# Agent Security Policy — 对齐后的设计

## 目标

给 Android Agent 加 app 级别的安全分类，使得：

1. 敏感 app（金融、认证、健康）不被当普通 app 对待——包括 **observation**（LLM 能看到什么）和 **execution**（agent 能做什么）
2. 设计围绕现有的 `PolicyEngine → ToolRouter → approval UI` 路径
3. 未知 app fail safe
4. Policy 是确定性的、可测试的、不由 prompt 控制

## Threat Model

一个有 accessibility service 权限的 AI agent 能做用户能做的一切。三个威胁向量：

1. **Prompt injection** — 恶意屏幕内容诱骗 agent 做非预期操作
2. **Goal drift** — agent 误解任务，导航到危险区域
3. **Cascade** — 一连串单独安全的操作组合成灾难性后果

两个变量决定危险程度：
- **做什么** → `CapabilityClass`（OBSERVE / NAVIGATE / EDIT / COMMIT）
- **在哪做** → foreground app

关键洞察：在这个系统里，**observation 不是免费的**。Screen capture 发生在 `AgentTurnRunner.capturePreTurnSnapshot()`，在 `PolicyEngine` 运行**之前**。A11y tree 和 screenshot 会发给 LLM（外部 API）、history、trace。对于银行 app，这意味着账号、余额、交易记录即使 agent 不执行任何操作也会被暴露。隐私边界必须在 cognition 之前，不只是在 execution 时。

## 架构概览

两个执行点，一个 app profile：

```
                    ┌──────────────────┐
                    │ AppPolicyProfile │  ← 每个 package 解析一次
                    └────────┬─────────┘
                             │
          ┌──────────────────┼──────────────────┐
          ▼                                      ▼
┌─────────────────┐                    ┌─────────────────┐
│ Perception Gate │                    │  PolicyEngine   │
│ (pre-cognition) │                    │ (pre-execution) │
│                 │                    │                 │
│ FULL → 放行     │                    │ Escalation      │
│ MASKED → stub   │                    │ table + escape  │
└─────────────────┘                    └─────────────────┘
```

两个 gate 用同一个 `AppPolicyProfile`。这是一个 policy 模型，不是两个子系统。

## 设计

### 1. AppSensitivity Enum

四个分类层级。Phase 1 有意将它们折叠为三个 enforcement profile。

```kotlin
enum class AppSensitivity {
    BLOCKED,       // 金融、认证、健康、管理 — masked + denied by default
    GUARDED,       // 未知/未分类 app — 谨慎默认值
    SENSITIVE,     // 通讯、社交、邮件 — EDIT/COMMIT escalated
    STANDARD       // 已知安全 / 通用 app — 正常规则
}
```

为什么是四个，不是三个或八个：
- BLOCKED、GUARDED、STANDARD 产生不同的 enforcement
- SENSITIVE 作为独立分类标签保留，即使 Phase 1 与 GUARDED 共享 enforcement
- BLOCKED 覆盖所有既有保密性又有高后果的 app（Codex 的 FINANCIAL、SECRET_STORE、SENSITIVE_RECORDS、ADMIN_CONSOLE 都折叠为相同 policy）
- GUARDED 是未知 app 的 fail-safe（Codex 的 UNCLASSIFIED 洞察——未知 ≠ 安全）
- SENSITIVE 覆盖通讯/社交 app，EDIT/COMMIT 危险但 observation 可以
- STANDARD 是正常 CapabilityClass 规则适用的 app

重要区分：
- `AppSensitivity` 是用户和 policy 推理的分类标签
- `AppPolicyProfile` 是运行时使用的 enforcement profile

### 2. ObservationPolicy

```kotlin
enum class ObservationPolicy {
    FULL,      // 正常：a11y tree + screenshot 发给 LLM/history/trace
    MASKED     // Stub：只有 package name + sensitivity tier + 恢复指引
}
```

Masked observation 将原始 a11y tree 和 screenshot 替换为：

```
Foreground app: com.chase.sig.android
Security tier: BLOCKED (financial app)
Screen content hidden by security policy. Use back/home to leave this app, or ask the user for an override.
```

必须应用于：
- Prompt construction（PromptBuilder）
- History recording（TurnPlanningPhaseRunner）
- Trace artifacts（`AgentTrace` / trace recorder 输出）
- 任何持久化的 screen payloads

### 3. AppPolicyProfile

Phase 1 每个 enforcement 行为对应一个 static profile：

```kotlin
data class AppPolicyProfile(
    val sensitivity: AppSensitivity,
    val observationPolicy: ObservationPolicy,
    val maxCapability: CapabilityClass?,     // null = in-app actions denied
    val allowEscapeActions: Boolean = true   // back/home 始终可用
)
```

默认 profiles：

| Tier | Observation | Max Capability | Escape | 行为 |
|------|-------------|---------------|--------|------|
| BLOCKED | MASKED | `null`（denied） | yes | 不观察，不执行 in-app action。Agent 只看到 app 名 + tier。可以离开。 |
| GUARDED | FULL | COMMIT | yes | 完整观察。OBSERVE 和 NAVIGATE 自动。EDIT 和 COMMIT 需确认。 |
| SENSITIVE | FULL | COMMIT | yes | 完整观察。OBSERVE 和 NAVIGATE 自动。EDIT 和 COMMIT 需确认。 |
| STANDARD | FULL | COMMIT | yes | 完整观察。OBSERVE、NAVIGATE、EDIT 自动。COMMIT 需确认。 |

Escalation table（CapabilityClass × AppSensitivity → RiskClass）：

| CapabilityClass | STANDARD | SENSITIVE | GUARDED | BLOCKED |
|-----------------|----------|-----------|---------|---------|
| OBSERVE | SAFE | SAFE | SAFE | —（masked） |
| NAVIGATE | MODERATE | MODERATE | MODERATE | DENY（仅 escape） |
| EDIT | MODERATE | **HIGH** | **HIGH** | DENY |
| COMMIT | HIGH | HIGH | HIGH | DENY |

组合公式：

```
profile      = AppSensitivityResolver.resolve(packageName)
observation  = profile.observationPolicy          → cognition 之前的 gate
capClass     = resolveCapabilityClass(tool, params)
baseRisk     = capClass.defaultRiskClass()
appRisk      = ESCALATION_TABLE[profile.sensitivity][capClass]
floor        = supervisionContext.minimumRisk()
finalRisk    = max(appRisk, floor)

if profile.maxCapability == null && !isEscapeAction(tool):
    return DENY
if finalRisk == SAFE:     return ALLOW
if finalRisk == MODERATE: return ALLOW
if finalRisk == HIGH:     return ASK_USER(reason)
```

GUARDED 和 SENSITIVE 在 Phase 1 有意共享相同的 enforcement 行。区别在于来源和用户提示信息：
- GUARDED 表示"未知 app，谨慎默认"
- SENSITIVE 表示"已知通讯/个人内容 app"

Phase 1 不要仅因为标签不同就加第二个 enforcement 差异。如果未来证据表明未知 app 需要更严格的 navigation 规则，在后续 phase 添加。

### 4. Escape Actions

即使在 BLOCKED app 里，agent 也不能被困住。Escape actions 无论 `maxCapability` 如何都始终允许：

- `back`
- `home`
- `open_app`（导航离开）

这些映射到 CapabilityClass.NAVIGATE 但绕过 `maxCapability` 检查。它们**不会**绕过 observation masking——agent 仍然看不到屏幕内容。

### 5. Classification Sources

按优先级解析：

#### a) User overrides（最高优先级）

Per-app 持久化。可以收紧任何 app。可以放宽任何 app，但放宽 BLOCKED app 或从 `MASKED` 改为 `FULL` 必须在 UI 中要求明确确认。

```kotlin
data class UserAppOverride(
    val packageName: String,
    val sensitivity: AppSensitivity
)
```

规则：
- `AUTO_APPROVE` 永远不改变 app classification
- Per-app override 是 unmask 或 unblock BLOCKED app 的唯一方式
- 放宽 BLOCKED app 必须是刻意的 override，不能是 global mode 的副作用

#### b) App skill metadata（第二优先级）

SKILL.md frontmatter。需要新增解析（当前 `AppSkillRepository.stripFrontmatter()` 丢弃它——需要一个并行的 `parseFrontmatter()` 方法）。

```markdown
---
package: com.example.app
security: SENSITIVE
---
```

#### c) Built-in defaults（第三优先级）

Hardcoded 已知 packages（精确匹配）+ keyword heuristic（对 lowercased package name 做子串匹配，只 escalate 不 de-escalate）。

```kotlin
object AppSensitivityDefaults {
    private val BLOCKED_PACKAGES = setOf(
        // 银行
        "com.chase.sig.android", "com.wf.wellsfargomobile",
        "com.citi.citimobile", "com.infonow.bofa",
        // Crypto
        "com.coinbase.android", "com.binance.dev",
        // 支付
        "com.venmo", "com.squareup.cash",
        "com.paypal.android.p2pmobile",
        "com.google.android.apps.walletnfcrel",
        // 认证/密钥
        "com.onepassword.android", "com.authy.authy",
        // ...
    )

    private val SENSITIVE_PACKAGES = setOf(
        "com.whatsapp", "org.telegram.messenger",
        "com.google.android.gm", "com.microsoft.office.outlook",
        "com.twitter.android", "com.instagram.android",
        // ...
    )

    // Heuristic patterns — 只 escalate，不 de-escalate
    private val BLOCKED_PATTERNS = listOf(
        "bank", "crypto", "wallet", "brokerage", "payment"
    )
    private val SENSITIVE_PATTERNS = listOf(
        "messenger", "chat", "email", "social"
    )

    fun classify(packageName: String): AppSensitivity {
        val pkg = packageName.lowercase()
        if (pkg in BLOCKED_PACKAGES) return AppSensitivity.BLOCKED
        if (pkg in SENSITIVE_PACKAGES) return AppSensitivity.SENSITIVE
        if (BLOCKED_PATTERNS.any { it in pkg }) return AppSensitivity.BLOCKED
        if (SENSITIVE_PATTERNS.any { it in pkg }) return AppSensitivity.SENSITIVE
        return AppSensitivity.GUARDED  // 未知 → 谨慎，不是 standard
    }
}
```

#### d) Fail-safe default

如果没有 source 匹配：`GUARDED`。未知 app 有完整 observation 但 EDIT/COMMIT 需要确认。比 STANDARD 更保守，但不阻止 agent 发挥作用。

如果多个非 fallback 规则匹配，最高显式 sensitivity 胜出：

`BLOCKED > SENSITIVE > STANDARD`

`GUARDED` 只在没有任何匹配时作为 fallback。这确定性地解决双用途 app。一个同时是通讯和支付的 package 是 `BLOCKED`。

### 6. 与 PolicyEngine 集成

当前：
```kotlin
fun check(toolName: String, params: JSONObject): PolicyDecision
```

之后：
```kotlin
fun check(toolName: String, params: JSONObject, appContext: AppContext?): PolicyDecision

data class AppContext(
    val packageName: String,
    val profile: AppPolicyProfile
)
```

`evaluateRiskLocked` 的变化：

1. 如果 `appContext.profile.maxCapability == null` 且 action 不是 escape action → DENY
2. 从 tool/action 解析 `CapabilityClass`
3. 查 escalation table 得到 `appContext.profile.sensitivity` 对应的 risk
4. 通过 `max()` 应用 supervision floor
5. 返回 Allow / AskUser / Deny

`ApprovalMode` 作为 Phase 1 的 global override 保持不变：
- `ALWAYS_ASK` 对所有原本允许的 action 都询问
- `SMART` 使用 escalation table 结果
- `AUTO_APPROVE` 可以绕过 `ASK_USER`，但永远不能绕过 `DENY`、永远不能 unmask BLOCKED app、永远不能改变 classification

后续的 `SessionSecurityConfig` 迁移是独立的。这个设计只要求 global mode 继续作为最终 approval floor，而不是 app trust 的来源。

### 7. 与 Perception Pipeline 集成

在 `AgentTurnRunner.capturePreTurnSnapshot()` 中新增 gate：

```kotlin
val snapshot = platform.captureScreen()
val packageName = platform.getCurrentPackageName()
val profile = appSensitivityResolver.resolve(packageName)

val observation = if (profile.observationPolicy == ObservationPolicy.MASKED) {
    MaskedObservation(packageName, profile.sensitivity)
} else {
    FullObservation(snapshot)
}
```

Masked observation 传播到：
- `TurnPlanningPhaseRunner`（prompt construction）
- History manager
- Trace recording

### 8. open_app 交互

`open_app` 是特殊的，因为 policy 相关的 package 是**目标**，不是当前 foreground。

对于 `open_app`，目标 package 必须在 policy check 之前用执行时相同的解析逻辑解析。

Phase 1 设计：
- 从 `OpenAppTool` 的 name-to-package 匹配逻辑中提取一个纯粹的共享 resolver
- `TurnExecutionPhaseRunner` 在调用 `ToolRouter` 之前使用该 resolver
- `OpenAppTool` 在执行期间复用同一个 resolver

不要在 policy 代码中重复 name-resolution 逻辑，也不要为了这个 case 把 `open_app` 拆成两阶段 tool lifecycle。

如果目标解析为 BLOCKED：
- `open_app` 本身返回 `AskUser`，理由："This is a financial app. Automation is blocked by default. Approve to open (screen will remain masked)."
- 如果批准，app 打开但 observation 仍为 MASKED，in-app actions 仍为 DENIED，直到用户做 override。

如果目标解析在 policy check 之前失败，按当前 tool 路径处理：返回正常的"app not found"失败，不是 policy denial。

### 9. Reserved Phase 2 Hook: Action Sensitivity Tags

Phase 1 **不**实现 target-level classifier（把 generic `click` 升级为 `COMMIT`）。

Phase 1 **会**预留 hook，使 policy API 之后不需要再重新设计：

```kotlin
enum class ActionSensitivityTag {
    MONEY_MOVEMENT,
    PUBLIC_POST,
    DESTRUCTIVE,
    SECRET_ENTRY,
    PERMISSION_CHANGE
}
```

Phase 1 规则：
- `PolicyCheckRequest` 和 `ApprovalDetails` 可以携带 `actionSensitivityTags`
- Phase 1 始终保持该 set 为空
- Enforcement 正确性不能依赖这些 tags

这保持了第一个实现的小规模，同时承认真正的 gap：app-level classification 是 Phase 1 的 safety floor，不是完整的长期答案。

### 10. Approval Context Enrichment

```kotlin
data class ApprovalDetails(
    val callId: String,
    val toolName: String,
    val args: JSONObject,
    val description: String = "",
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    // NEW
    val appContext: AppContext? = null,
    val escalationReason: String? = null,
    val actionSensitivityTags: Set<ActionSensitivityTag> = emptySet()
)
```

UI 使用 `escalationReason` 来解释为什么弹出了确认：
- "Financial app — automation blocked by default"
- "Unknown app — text input requires approval"
- "Messaging app — sending messages requires approval"

## Data Flow

```
AgentTurnRunner.capturePreTurnSnapshot()
  → platform.getCurrentPackageName()
  → AppSensitivityResolver.resolve(packageName) → AppPolicyProfile
  → if MASKED: 用 stub observation 替换 snapshot
  → 把 observation 传给 planning phase

TurnExecutionPhaseRunner（每个 tool call）
  → 解析 AppContext（foreground pkg，或 open_app 的 target pkg）
  → toolRouter.execute(toolName, params, context, callId, appContext, ...)
    → policyEngine.check(toolName, params, appContext)
      → 检查 maxCapability / escape action
      → 解析 CapabilityClass
      → 查 ESCALATION_TABLE
      → max(appRisk, supervisionFloor)
      → Allow / AskUser / Deny
```

## Tasks

### T1: `app-sensitivity-types`
**Scope:** `protocol/AppSensitivity.kt`
**内容:** `AppSensitivity` enum、`ObservationPolicy` enum、`AppPolicyProfile` data class、`AppContext` data class、static profile table。
**验收:** 编译通过，profile lookup 有 unit test。
**依赖:** 无。

### T2: `app-sensitivity-defaults`
**Scope:** `tool/AppSensitivityDefaults.kt`
**内容:** Hardcoded 已知 packages + keyword heuristic。Unknown → GUARDED。
**验收:** Unit tests：已知金融 → BLOCKED，已知通讯 → SENSITIVE，未知 → GUARDED，pattern match 工作。
**依赖:** T1。

### T3: `app-sensitivity-resolver`
**Scope:** `tool/AppSensitivityResolver.kt`
**内容:** Resolution chain：user override → skill metadata → built-in defaults → GUARDED。Highest-sensitivity-wins 冲突处理。放宽 BLOCKED 需要 explicit-acknowledgment。
**验收:** Unit tests：优先级顺序正确，user overrides 被应用。
**依赖:** T1、T2。

### T4: `perception-gate`
**Scope:** `agent/AgentTurnRunner.kt`、`agent/TurnPlanningPhaseRunner.kt`、`trace/*`
**内容:** 在 `capturePreTurnSnapshot()` 中加 gate，当 profile 为 MASKED 时用 masked stub 替换 observation。确保 stub 传播到 prompt、history、trace。
**验收:** 当 foreground app 是 BLOCKED 时，LLM input 包含 masked stub 而非原始 a11y tree 或 screenshot。Trace 文件也被 mask。
**依赖:** T3。

### T5: `policy-engine-app-context`
**Scope:** `tool/PolicyEngine.kt`
**内容:** 给 `check()` 加 `appContext`。加 escalation table。加 `maxCapability` + escape action 逻辑。通过 `max()` 与 supervision floor 组合。
**验收:** Unit tests：BLOCKED app + click → DENY；BLOCKED app + back → ALLOW；GUARDED app + type → ASK；STANDARD app + scroll → ALLOW。
**依赖:** T1、T3。

### T6: `tool-router-app-context`
**Scope:** `tool/ToolRouter.kt`、`protocol/ApprovalTypes.kt`
**内容:** 把 `appContext` 穿过 `execute()` → `policyEngine.check()`。充实 `ApprovalDetails`。加预留的 `actionSensitivityTags` 字段，默认为空。
**验收:** Approval details 包含 app context。现有 tests 通过（appContext 默认为 null）。
**依赖:** T5。

### T7: `turn-runner-integration`
**Scope:** `agent/TurnExecutionPhaseRunner.kt`、`session/SessionToolingBootstrapper.kt`
**内容:** 在每个 tool call 之前解析 `AppContext`。提取并复用共享的 `open_app` target resolver，在 policy 和 execution 时使用。把 resolver 接入 session bootstrap。
**验收:** End-to-end：BLOCKED package 触发 deny；GUARDED package 对 type 触发 ask；`open_app` 到 BLOCKED target 在启动前询问，不重复 app-resolution 逻辑。
**依赖:** T3、T6。

### T8: `skill-metadata-security`
**Scope:** `agent/cognition/prompt/AppSkillRepository.kt`、`app/src/main/assets/app_skills/*/SKILL.md`
**内容:** 从 SKILL.md frontmatter 解析 `security:`（独立于现有 `stripFrontmatter`）。Feed into resolver。
**验收:** 一个带 `security: SENSITIVE` 的 SKILL.md 使该 package 被分类为 SENSITIVE。
**依赖:** T3。

## Phase 2，不是 Phase 1

Target-level action sensitivity detection 留作 future work。

该 future work 应当：
- 保持确定性和 local
- 在 policy check 之前填充 `actionSensitivityTags`
- 处理 locale/app-specific semantics，不把决策所有权移到 prompt 里

Phase 1 没有它也是完整的，因为 BLOCKED、GUARDED、SENSITIVE app classification 已经建立了 safety floor。
