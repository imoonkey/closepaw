# Agent 身份与人格：对齐后的设计

## 目标

吸收 OpenClaw 在结构化 agent identity 文件上的有用思路，同时不破坏 Android Agent 现有的 prompt ownership 模型。

这个设计必须完成四件事：

1. agent 行为的 prompt 编写不再依赖修改 Kotlin 字符串字面量。
2. identity / personality 可以在 session 级别配置。
3. 安全关键的执行策略必须与柔性的 persona / style 明确分离。
4. planner 和 executor 必须共享同一 session identity。

## 来自当前 repo 的事实基础

当前：

- `AgentDef` 拥有 `executionRole`、`allowedTools`、delegation requirement，以及一个整体式 `systemPrompt`。
- `StandaloneAgentDef`、`PlannerAgentDef`、`ExecutorAgentDef` 都内嵌了大段多行 prompt 字符串。
- `SessionAgentRunner` 只在 session 启动时解析一次模板变量，并将最终 prompt 冻结到 `AgentExecutionConfig`。
- `PromptBuilder` 已经把其他 prompt 层分离开：
  - tool semantics 属于 tool descriptions
  - app / package 行为属于 `app_skills/<package>/SKILL.md`
  - task / history / observation 属于 runtime input items

这意味着 system prompt 中剩余的内容，本质上主要是 role contract 和 agent identity。设计应该把这两者干净分开，而不是再次混在一起。

## 设计原则

1. ownership 必须明确。
2. runtime assembly 必须简单。
3. 文件用于编写内容，不是为了再发明一门小语言。
4. 不要把 tool rules 或 app knowledge 重新集中进 persona 文件。
5. identity 选择在 session 启动时冻结。
6. planner / executor 继承必须自动生效。

## 最终架构

### 1. 两层 prompt，不是一层

最终 instructions 由两个独立层组合而成：

1. **Role contract**
   - 对某个 app build 来说是不可变的
   - 负责 role 定义、关键规则、执行循环、working memory policy、task modes、completion doctrine
   - 以文件形式维护，方便编辑
   - 不允许用户配置

2. **Identity profile**
   - 按 session 选择
   - 负责身份、价值观、沟通风格，以及可选的 role addenda
   - v1 通过 preset 选择进行配置
   - 后续可支持带校验的自定义 profile

其他内容继续保持原 owner：

- tool usage semantics：tool descriptions
- app / package guidance：app skills
- task / history / observation：runtime input items

### 2. Asset 布局

使用两棵完全独立的 asset 树，让边界一眼可见：

```text
app/src/main/assets/
  agent_contracts/
    standalone/
      10_role.md
      20_rules.md
      30_execution.md
      40_memory.md
      50_task_modes.md
      60_completion.md
      90_environment.md
    planner/
      10_role.md
      20_rules.md
      30_execution.md
      40_memory.md
      50_task_modes.md
      60_completion.md
      90_environment.md
    executor/
      10_role.md
      20_rules.md
      30_execution.md
      40_memory.md
      50_task_modes.md
      60_completion.md
      90_environment.md

  agent_identities/
    balanced/
      IDENTITY.md
      PRINCIPLES.md
      USER.md
      STANDALONE.md
      PLANNER.md
      EXECUTOR.md
    efficient/
      IDENTITY.md
      PRINCIPLES.md
      USER.md
    careful/
      IDENTITY.md
      PRINCIPLES.md
      USER.md
```

规则：

- `agent_contracts/` 是随 app 一起发布的合同文本。它虽然是 prompt text，但不是“persona preset”。
- `agent_identities/` 是人格层。可选的 role addenda 默认为空。
- 数字前缀定义 contract section 顺序。
- Identity 文件使用固定 schema，而不是靠解析 markdown heading 猜结构。

### 3. Runtime types

概念上：

```kotlin
data class IdentityProfile(
    val id: String,
    val identity: String,
    val principles: String,
    val userContract: String,
    val roleAddenda: Map<AgentExecutionRole, String> = emptyMap()
)
```

`AgentDef` 不再持有原始 prompt 文本。它只保留 role metadata 和一个 role key：

```kotlin
internal abstract class AgentDef {
    abstract val id: String
    abstract val executionRole: AgentExecutionRole
    abstract val promptRole: String
    abstract val allowedTools: Set<String>
    abstract val requiresDelegationToolRegistration: Boolean
}
```

新增运行时组件：

- `AgentContractRepository`
  - 从 `agent_contracts/<role>/` 读取有序 contract sections
- `AgentIdentityRepository`
  - 读取并校验 `agent_identities/<id>/`
- `AgentInstructionComposer`
  - 用 contract + identity + device template values 组装最终 instructions

### 4. 最终 prompt 形态

最终 system prompt 仍然是一个普通字符串。它的 section 变成：

1. Role
2. Identity
3. Principles
4. User Contract
5. Critical Rules
6. Execution Loop
7. Working Memory
8. Task Modes
9. Completion
10. Device Environment

Ownership：

- 第 1、5、6、7、8、9、10 节来自 role contract
- 第 2、3、4 节来自选中的 identity profile
- identity profile 中的可选 role addenda 会插入到对应 role section 下

这样既实现文件化编写，又不丢失 contract 与 persona 的硬边界。

### 5. Session 级 identity 选择

向 `SessionConfig` 增加 `identityProfileId: String`。

选择流程：

1. `AppSettingsStore` 持久化用户选中的 profile id。
2. 创建 session 时把它复制进 `SessionConfig`。
3. `SessionAgentRunner` 解析主 `AgentDef`。
4. `AgentInstructionComposer` 根据以下输入生成 instructions：
   - `promptRole`
   - `identityProfileId`
   - device template values
5. 最终 instruction string 被冻进 `AgentExecutionConfig`。

规则：

- identity 不允许在 session 中途变化
- 设置变更只影响未来 session
- 非法 id 回退到 `balanced`
- fallback 必须显式记录日志

### 6. Planner 与 executor 的继承

在 `PRO` 模式下：

- planner 与 executor 共享相同的 `identityProfileId`
- executor 使用 `promptRole = "executor"`，但仍然套用同一个 identity profile
- 可选的 `EXECUTOR.md` addendum 可以改变语气或强调点，但不能改 tool policy

这修复了当前 delegated execution 实际上绑定在静态 executor prompt 路径上的问题。

### 7. 校验与安全规则

Identity profiles 属于外部化内容，必须做校验。

必需项：

- 只允许出现规定文件
- `IDENTITY.md`、`PRINCIPLES.md` 和 `USER.md` 为必填
- section length bounds
- 只允许 plain text 或 markdown

Identity 文件中禁止包含的 owner 领域：

- tool allowlist 变更
- model routing
- delegation topology
- app skill 内容
- screen-specific 或 task-specific 指令

在 v1 中，role contracts 不允许用户编辑。这就是关键的安全边界。

## 为什么选这个设计

### 吸收 Claude 方案的部分

- prompt 编写从 Kotlin 字符串中抽离，改为文件
- asset-loading 模式与现有 app-skill 使用方式一致
- 运行时改动集中在 instruction sourcing 上

### 吸收 Codex 方案的部分

- role contract 与 identity profile 是两种不同的东西
- identity 是 session-scoped 的
- planner / executor inheritance 是一项一等设计要求
- tool / app guidance 必须留在 persona 层之外

### 为什么不做可任意编辑的 prompt packs

那会模糊以下边界：

- 不可变的执行合同
- 可调的人格层
- tool guidance
- app / package guidance

而 repo 这段时间的演化方向恰恰是在强化这些 ownership 边界。现在反过来做，会是退步。

### 为什么不把 role contract 继续留在代码里

那当然能保住 ownership，但会错过这个需求最直接的收益：修改主 contract 文本不再需要 rebuild。

正确折中是：

- contract text 改为文件编写
- contract ownership 仍然保持内部且受保护
- identity 成为可配置层

## v1 的 preset 范围

v1 只发布少量 preset：

- `balanced`：默认，最接近当前行为
- `efficient`：更简洁，更偏执行
- `careful`：更强调校验与阻塞说明

不要加：

- 按 task 自动切 persona
- 按 app 自动切 persona
- 允许用户自由编辑完整 system prompt

这些都会引入隐藏行为变化，或者直接压垮 ownership 边界。

## 受影响的代码路径

预期会触及：

- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionConfig.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt`
- prompt / agent 层新增的 repository / composer 类
- `app/src/main/assets/agent_contracts/` 与 `app/src/main/assets/agent_identities/` 下的新 assets

## 非目标

- 改 tool schemas 或 tool descriptions
- 替换 app skills
- 在 session 中途变更 identity
- 围绕旧 prompt strings 做兼容垫片
- task-specific persona 自动选择
- v1 就开放完整自由 prompt 编辑

## 建议

现在就落地这套 split：

1. 把当前 role prompt 内容抽成内部 contract assets
2. 引入 session-scoped identity profiles，作为独立 asset 层
3. 用一个 runtime component 组合最终 instructions
4. 让 planner / executor 共享同一 identity

这样既拿到文件化 prompt 编写带来的维护性收益，也拿到可配置 identity 带来的产品收益，同时不削弱 prompt ownership discipline。
