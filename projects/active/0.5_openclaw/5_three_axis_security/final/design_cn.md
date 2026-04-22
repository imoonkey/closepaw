# Android Agent 的三轴安全模型

## 背景

当前 repo 已经存在一个真实的安全接缝：

- `SessionConfig` 携带一个 `approvalMode`
- `SessionToolingBootstrapper` 创建一个 `PolicyEngine`
- `PolicyEngine` 把规范工具与动作映射到 `RiskLevel`
- `ToolRouter` 再把它转成 allow / ask / deny
- 审批状态通过 `ApprovalRequired` 和 `ApprovalResolved` 传到 UI

这是一个不错的起点，但现在一个控制量同时承担了三种职责：

1. 信任当前运行环境
2. 分类工具到底允许做什么
3. 判断是否允许更强的 override

OpenClaw 值得借鉴的点，是把这三件事分开。在 Android 上，具体轴必须根据移动端现实来适配。

## 设计目标

- 让三条轴显式且正交
- 实现继续围绕现有 policy 路径展开
- 保持 Phase 1 足够小且有用
- 不做 backward-compat 翻译层，也不加零散特判

## 非目标

- 在 Android 上搞 Docker 风格 sandbox
- 只靠 prompt 的安全启发式
- 在设置页暴露一整张巨大的 per-tool 开关矩阵

## 对齐后的模型

### Axis 1：监督上下文（supervision context）

这是 Android 对 OpenClaw sandbox 轴的等价改造。关键问题不是“有没有 container”，而是“用户此刻能不能监督这个动作”。

```kotlin
enum class SupervisionContext {
    LOCAL_FOREGROUND,
    LOCAL_BACKGROUND,
    REMOTE
}
```

- `LOCAL_FOREGROUND`：用户在设备前，能即时看到提示
- `LOCAL_BACKGROUND`：本地发起，但提示更容易被错过
- `REMOTE`：离设备控制路径；安全下限最高

这条轴会抬高最低审批要求，但它本身不决定 capability。

### Axis 2：能力策略（capability policy）

这是 Phase 1 最实用的一条轴。

为规范工具 / 动作建立一个小而明确的语义目录：

```kotlin
enum class CapabilityClass {
    OBSERVE,
    NAVIGATE,
    EDIT,
    COMMIT
}
```

规范映射：

- `OBSERVE`：`wait`、`scratchpad`、`write_todos`、当前只读 `shell`
- `NAVIGATE`：`open_app`、`system_button`、`mobile_action.click`、`scroll`、`swipe`
- `EDIT`：`mobile_action.type`
- `COMMIT`：任何发送、删除、确认购买或产生难以撤销副作用的动作

这个目录应该由 `PolicyEngine` 统一拥有，而不是散在 enum 构造函数和随机 `when` 分支里。

为了审批输出和 UI 展示，再派生一个更简单的决策严重级别：

```kotlin
enum class RiskClass {
    SAFE,
    MODERATE,
    HIGH
}
```

推荐默认映射：

- `OBSERVE` -> `SAFE`
- `NAVIGATE` -> `MODERATE`
- `EDIT` -> `MODERATE`
- `COMMIT` -> `HIGH`

语义类才是真正的源数据，risk band 只是它的渲染摘要。

### Axis 3：提权策略（elevation policy）

提权要与普通的 high-risk approval 分开。

High risk 的意思是“需要确认”。
Elevation 的意思是“请求超出正常 capability profile 的能力”。

```kotlin
data class ElevationPolicy(
    val enabled: Boolean = false,
    val allowedScopes: Set<ElevatedScope> = emptySet()
)
```

提权只用于真正的 escape hatch：

- 未来的 unrestricted shell
- 未来的 destructive filesystem operations
- 未来的 remote bypass experiments

不要把普通 `COMMIT` 动作混进 elevation。普通提交类动作仍属于常规审批流。

## Session 模型

用下面的结构替换 `SessionConfig` 中的 `approvalMode`：

```kotlin
data class SessionSecurityConfig(
    val supervisionContext: SupervisionContext = SupervisionContext.LOCAL_FOREGROUND,
    val capabilityPolicy: CapabilityPolicy = CapabilityPolicy.default(),
    val elevationPolicy: ElevationPolicy = ElevationPolicy()
)
```

再直接嵌进 `SessionConfig`：

```kotlin
data class SessionConfig(
    // existing fields...
    val security: SessionSecurityConfig = SessionSecurityConfig()
)
```

这会成为 session 安全策略的单一真相来源。不为旧 enum 保留兼容包装层。

## Policy engine 形状

继续保留一个 policy engine，但给它更丰富的输入：

```kotlin
data class PolicyCheckRequest(
    val toolName: String,
    val params: JSONObject,
    val security: SessionSecurityConfig,
    val packageName: String?,
    val sensitivityTags: Set<String> = emptySet()
)
```

决策流程：

1. 根据 tool / action 名称解析规范 subject
2. 把 subject 映射到 `CapabilityClass`
3. 推导基础 `RiskClass`
4. 依据 `supervisionContext` 抬高最低要求
5. 如果 subject 需要 elevation 且没有授权，则 deny
6. 返回 allow / ask / deny，并附带显式 reason

这样保留了当前架构：

- `SessionToolingBootstrapper` 继续把 security config 传给 `PolicyEngine`
- `ToolRouter` 仍然负责 lifecycle 与 approval wait
- approval events 的结构无需大改，只需丰富 payload

## 敏感操作升级

源文档说得对：通用 click 并不总是通用 click，例如：

- 点“Send”
- 点“Pay now”
- 点“Delete”

这些情况应当比普通导航更严格。但必须是确定性的。

Phase 2 增加由 app / package metadata 拥有的 sensitivity tags：

```kotlin
enum class SensitivityTag {
    MESSAGE_SEND,
    PAYMENT,
    DESTRUCTIVE
}
```

Ownership：

- app-skill metadata
- package-specific safety rules
- 从已解析 target 中得到的确定性标签

不属于：

- 自由发挥的 LLM 猜测
- 纯 prompt 分支

如果 package 规则把某目标标成 `PAYMENT`，那么 policy engine 应当把它升级为 `COMMIT`，即使底层原始工具只是 `click`。

## UI 与事件变更

保留当前 approval event 流，只扩充 payload：

`ApprovalDetails` 应新增：

- `capabilityClass`
- `riskClass`
- `supervisionContext`
- `policyReason`
- `sensitivityTags`

这样 approval UI 就能解释“为什么要问这个审批”，而不需要整套新事件系统。

设置项也应保持克制：

- 本地 session 的安全 preset
- 可选的 “ask before edit” 开关
- remote session 对 `COMMIT` 一律强制 `HIGH` approval

不要在正常产品界面中暴露大而全的 per-tool UI。

## 各层代码改动

### Session 层

- `SessionConfig`：用 `security` 替换 `approvalMode`
- `ConversationConfigSnapshot`：持久化 security 字段
- `MainActivity` 与 `AgentService`：根据入口模式和设置构造 `SessionSecurityConfig`

### Tool bootstrap 层

- `SessionToolingBootstrapper.create(security: SessionSecurityConfig)`
- `SessionServices.updateApprovalMode()` 改为 `updateSecurityConfig()`

### Policy 层

- 默认 policy catalog 的 ownership 收拢到 `PolicyEngine`
- `ToolName` / `MobileActionName` 继续保留规范命名
- 把 risk defaults 从 enum constructors 中移出
- 增加确定性的 sensitivity escalation hook

### Protocol / UI 层

- 扩展 `ApprovalDetails`
- 保留 `ApprovalRequired` / `ApprovalResolved`
- 只更新 approval UI 标签，不引入新流程

## Rollout

### Phase 1：以 capability 为先

- 用 `SessionSecurityConfig` 替换 `approvalMode`
- 实现 `SupervisionContext`
- 实现语义 capability catalog 和派生出的 `RiskClass`
- 本地默认策略：
  - `OBSERVE`：auto
  - `NAVIGATE`：auto
  - `EDIT`：默认 auto，但允许配置成 ask
  - `COMMIT`：ask

这已经足够覆盖源需求的主旨：把安全轴拆开，让“工具风险”先成为第一步可用能力。

### Phase 2：sensitivity escalation

- 增加 app / package 所拥有的 sensitivity tags
- 把 send / pay / delete 流程升级为 `COMMIT`
- 确保 prompt / UI 文案里包含被识别出的 sensitivity reason

### Phase 3：remote entry

- 增加真正的 remote entry points
- 对 remote session 的 `COMMIT` 一律强制审批
- 首次设备授权必须在手机上确认

## 关键决策

- 不做假的 Android sandbox 轴，改用 supervision context。
- 不做第二套 policy subsystem，而是扩展现有 `PolicyEngine`。
- 不为 `ApprovalMode` 保留 backward-compat wrapper。
- 不用 prompt 去判断 send / pay / delete。
- Phase 1 主要落在 `SessionConfig`、`PolicyEngine`、`ToolRouter` 和 approval payload。

## 为什么这是正确的切法

它让设计保持简单且诚实。

Repo 里已经有了正确的执行 choke point。缺的不是更多 plumbing，而是把三种不同的策略问题分开，这样系统未来才能扩展，而不会让 `PolicyEngine` 变成一团例外逻辑。
