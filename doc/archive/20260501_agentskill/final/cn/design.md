# ClosePaw Agent Skills 支持 — 最终设计

## 目标

为 ClosePaw 添加最小化的 agentskills.io `SKILL.md` 标准支持，使 Android agent 能够在现有 foreground app skill 系统之外，发现、编目、激活和使用通用 Agent Skills。

Agent Skills 是面向任务/能力的指导。现有 app skills 是面向特定 package 的 UI 指导。两套系统共存，不改变现有 `assets/app_skills/<package>/SKILL.md` 的行为。

成功标准：

- Skills 可从 Android 安全位置发现
- Model 能看到紧凑的 catalog，无需加载每个 skill body
- Model 或用户可以 activate 一个 skill
- Skill instructions 不得绕过 ClosePaw tools、role allowlists、`PolicyEngine`、app privacy gates 或用户审批

## v1 非目标

- 不做 remote registry 或用户侧 install UI（v1 通过 ADB push 安装，未来接 skill hub）
- 不将现有 app skills 迁移为 Agent Skills
- 不新增绕过 Android sandbox 的权限路径
- 不做 compaction 特殊处理
- 不做 activation budget enforcement（context window 是天然 budget）
- 不做 activation turn barrier（现有 TurnToolPolicy 已足够）

## 需保留的现有架构

现有 app skills：

- 位于 `app/src/main/assets/app_skills/<package>/SKILL.md`
- 通过 `AppSkillRepository.load(packageName)` 加载
- 由 `TurnPlanningPhaseRunner` 每 turn 注入
- 以 foreground Android package name 为 key

App skill frontmatter 将升级为 agentskills.io compatible 格式——加入 `name` 和 `metadata.package` 字段。命名规则：`app-<short-name>`，short-name 用用户可识别的 app 名称（如 `app-chrome`、`app-uber`、`app-tasks`）。Package name 存 `metadata.package`。目录结构和 load 路径不变，两套 skills 共用同一个 frontmatter parser。

当前 prompt 顺序不变：

```text
History -> Working Memory -> Recalled Memory -> App Skill -> Observation
```

Agent Skills 不改变此顺序。Catalog 追加到 system prompt，skill body 作为 tool result 进入 history。

## Skill Root

唯一 skill root：

```text
context.filesDir/skills/<skill-name>/SKILL.md
```

不使用 `assets/` bundled skills。所有 skills 安装到 `filesDir/skills/`，shell 可直接访问。

- v1：通过 ADB push 安装
- 未来：接入 skill hub（如 Hermes Agent），过滤兼容性后复制到 filesDir

Discovery 仅扫描一层，不递归。

## Skill 格式

支持标准 agentskills.io 目录结构：

```text
skill-name/
  SKILL.md
  scripts/       # 可选
  references/    # 可选
  assets/        # 可选
```

MVP validation：

- Directory name 与 frontmatter `name` 必须匹配
- `name` 为 1-64 字符，lowercase-hyphen
- `description` 必须存在，1-1024 字符
- Invalid skills 跳过并记录日志，不暴露给 model

## Frontmatter Parsing

使用 SnakeYAML + lenient fallback：

- SnakeYAML parse 完整 YAML，包括嵌套 `metadata` map
- Malformed YAML（如 unquoted colons）catch exception 后用 regex fallback 提取 `name` + `description`
- Parse 所有字段（name、description、license、compatibility、allowed-tools、metadata）
- v1 behavior 仅依赖 name + description，其余字段 informational
- App skills 共用此 parser——`metadata.package` 存 Android package name

## Scripts 与 Resources

Skills 在 `filesDir/` 中，model 通过 `ShellTool` 直接访问所有文件：

- **简单 shell 脚本**：可通过 ShellTool 执行（Android mksh，非 bash）
- **Bash-specific 语法和 Python**：不可用
- **references/ 和 assets/**：model 可通过 `cat` 等 shell 命令读取
- **不需要专用 resource tool**——所有文件在 filesDir 文件系统路径上
- `allowed-tools` frontmatter 仅作参考，不改变 role allowlists

## 运行时模型

### Data Model

```kotlin
data class AgentSkillEntry(
    val name: String,
    val description: String,
    val filePath: String   // absolute path in filesDir
)
```

Catalog 在 session 内 immutable。Body 不预加载——activation 时从 filePath 读取。

### Trust

v1 中所有发现的 skills 均为 trusted。安装行为（ADB push）即为 consent gate。代码结构预留 trust-checking 层插入点，供未来 download/import 流程使用。

### Catalog

Skill catalog 作为 system prompt 的一部分，随每次 LLM call 自动发送。无需 per-turn 逻辑。

格式：

```text
## Available Skills
The following skills provide specialized instructions. Call activate_skill
with a skill's name to load its full instructions.

- calendar-date-math: Compute exact date ranges for calendar/task queries.
- image-table-reading: Extract tabular values from screenshots before entering forms.
```

无 skills 时省略此 section。

### Manager

添加 session-scoped `AgentSkillManager`：

```kotlin
class AgentSkillManager(
    private val skillsDir: File
) {
    fun catalogPrompt(): String?
    fun activate(name: String): ActivationResult
    fun activateExplicitMentions(text: String): List<ActivationResult>
}
```

职责：

- 输出紧凑 catalog text（供 system prompt）
- 以 LinkedHashSet 跟踪 active skill names
- Activation 去重（idempotent）
- 支持 `/skill-name` 显式激活——仅匹配已知 catalog names，在 word boundary 处匹配

存放在 `SessionServices` 中。`SessionServices.copy()` 保留 manager，使 delegated executor 消费 planner 激活的 skills。

### Activation Tool

添加 `activate_skill` tool，所有 roles 均可使用：

```text
activate_skill(name, agent_thought?)
```

Tool description 仅一行：`"Activate a skill by name. See the skill catalog in the system prompt for available skills."`

`activate_skill` 是普通 non-screen-changing tool，无特殊 turn policy。现有 `TurnToolPolicy` 已能正确处理它与其他 tools 的共存。

行为：

- 验证 skill name 是否在 catalog 中
- 对已激活的 skill 返回 "already active"
- 成功时读取 SKILL.md，strip frontmatter，返回完整 body 作为 tool result
- 不授予额外 tools 或 permissions

注册要求：

- `ToolName` 中标记为 non-screen-changing（`Unknown` 默认为 screen-changing）
- 添加到所有 `AgentRoleDef.allowedTools`（Standalone、Planner、Executor）
- 仅当 catalog 非空时注册

## Compaction

无特殊处理。Skill body 作为 tool result 进入 conversation history，正常参与 compaction。Catalog 在 system prompt 中始终可见，model 可随时重新调用 `activate_skill` 加载已被压缩的 skill。

## Activation 流程

### Session Start

1. `SessionServices.create` 扫描 `filesDir/skills/*/SKILL.md`，构建 catalog
2. Invalid skills 跳过记录。v1 中所有有效 skills 均为 trusted
3. `AgentSkillManager` 存储 immutable catalog
4. Catalog 追加到 system prompt
5. 仅当 catalog 非空时注册 `ActivateSkillTool`

### User-Explicit Activation

1. 用户目标中包含 `/skill-name`
2. `TurnPlanningPhaseRunner` 在构建 input 前调用 `activateExplicitMentions(goal)`
3. 已知 skill 在第一个 LLM turn 即为 active（body 作为额外 context 注入）
4. 未知 mentions 记录日志

仅支持 `/skill-name` 语法。在 word boundaries 处匹配已知 catalog names。

### Model-Driven Activation

1. Model 在 system prompt 中看到 catalog
2. Model 自主决定调用 `activate_skill(name=...)`
3. Tool 读取 SKILL.md，strip frontmatter，返回 body
4. Model 当 turn 获得指令，按指令使用现有 tools 执行

### Execution

没有独立的 skill executor。Skill 就是 prompt context。所有 tool calls 仍经过 `ToolRouter` 和 `PolicyEngine`。

## 共存与冲突优先级

App skills 与 Agent Skills 保持分离：

- App Skill：foreground package 指导，每 turn 自动加载
- Agent Skill：task/capability 指导，由用户或 model 激活

冲突优先级（高→低）：

1. System prompt、role allowlists、tool schemas、`PolicyEngine`、privacy gates
2. 当前用户目标和当前屏幕证据
3. Recalled memory
4. Foreground app skill
5. Active Agent Skills
6. Agent Skill catalog

## 代码变更

**新增：**

- `app/src/main/kotlin/ai/closepaw/agent/cognition/skills/**` — parser、catalog、manager
- `app/src/main/kotlin/ai/closepaw/tool/impl/ActivateSkillTool.kt`

**修改：**

- `ToolName.kt` — 添加 `ActivateSkill` variant，non-screen-changing
- `SessionServices.kt` — 添加 `AgentSkillManager`
- `SessionToolingBootstrapper.kt` — 条件注册 `ActivateSkillTool`
- `StandaloneAgentDef.kt`、`PlannerAgentDef.kt`、`ExecutorAgentDef.kt` — allowedTools 添加 `"activate_skill"`
- `TurnPlanningPhaseRunner.kt` — 调用 `activateExplicitMentions`，system prompt 追加 catalog

**不修改：**

- `PromptBuilder.kt` — 无变更
- `TurnToolPolicy.kt` — 无变更（`activate_skill` 是普通 non-screen-changing tool）
- `AppSkillRepository.kt` — 无变更（frontmatter 格式升级不影响 load 行为，loader 已 strip frontmatter）

## Implementation Tasks

### skill-frontmatter-parser

Scope: `agent/cognition/skills/SkillFrontmatterParser.kt`，tests

- SnakeYAML parse 完整 YAML，包括 metadata map
- Malformed YAML fallback 提取 name + description
- 返回 parsed frontmatter + body
- App skills 可复用此 parser

### skill-catalog

Scope: `agent/cognition/skills/AgentSkillCatalog.kt`，tests

- 扫描 `filesDir/skills/*/SKILL.md`
- Parse frontmatter，构建 name → AgentSkillEntry map
- Invalid skills 跳过记录
- `catalogPrompt()` 返回 system prompt section

### skill-manager

Scope: `agent/cognition/skills/AgentSkillManager.kt`，tests

- Activation 去重（idempotent）
- `/skill-name` mention 激活
- 跨 `SessionServices.copy()` 保留

### activation-tool

Scope: `tool/impl/ActivateSkillTool.kt`，`ToolName.kt`，role defs

- ToolSpec 实现，读取 SKILL.md body 返回
- `ToolName` non-screen-changing
- 所有 roles 的 allowedTools 添加

### session-integration

Scope: `SessionServices.kt`，`SessionToolingBootstrapper.kt`，`TurnPlanningPhaseRunner.kt`

- `SessionServices.create` 构建 manager
- Catalog 追加到 system prompt
- 条件注册 tool
- Explicit mention activation 在 first turn 前

### tests

- Parser：valid、malformed、missing fields、metadata map
- Catalog：discovery、invalid skills、empty
- Manager：activation、dedupe、mention parsing
- Tool：valid、unknown、duplicate
- 现有 app-skill tests 保持绿色

## 已决策项

1. **单一 skill root**：仅 `context.filesDir/skills/`，无 bundled assets
2. **Trust（v1）**：信任所有 discovered skills，预留 trust-checking 插入点
3. **Catalog 位置**：system prompt section（参考 Claude Code / Codex / pi 实现）
4. **Tool result**：立即返回完整 skill body
5. **无 activation barrier**：`activate_skill` 是普通 non-screen-changing tool，现有 TurnToolPolicy 足够
6. **无 budget enforcement**：v1 skill 数量极少，context window 是天然 budget
7. **Frontmatter**：SnakeYAML + lenient fallback，parse 所有字段包括 metadata map
8. **Tool name**：`activate_skill`
9. **Mention syntax**：`/skill-name`（用户显式）+ model 自主调用 `activate_skill`（model-driven）
10. **所有 roles**：均注册 `activate_skill`，不做 role 限制
11. **Compaction**：无特殊处理，skill body 正常 compaction，model 可重新 activate
12. **PromptBuilder**：不修改
13. **TurnToolPolicy**：不修改
14. **App skill 兼容**：frontmatter 升级，name 用 `app-<short-name>`（如 `app-chrome`、`app-tasks`），`metadata.package` 存 package name，loader 行为不变

---

## Appendix: Design Discussions

以下为设计讨论的存档，保留原始对话以追溯决策来源。

### D1: Activation 机制

> claude/codex load agent skill都是用的时候就load一下？这个load操作是不是类似一个tool call?

>> **[Claude]** Claude Code 用 SkillTool（tool call），Codex 用 `$skill-name` mention parsing，pi 用 `/skill:name` 命令展开。Tool call 方式可行，内容进入 history 后不需要每 turn 重新注入。

> sgtm。

### D2: App Skill 格式兼容

> agentskills.io里是不是允许加自定义的metadata，我们的app skill要不要也改成fully compatible格式？

>> **[Claude]** frontmatter 加 `name` 和 `metadata.package` 使格式 compatible，目录结构和 load 路径不动。两套格式共用 parser。

> sgtm。

### D3: Catalog 位置

> skill的catalog该放到system prompt里？

>> **[Claude]** 三家都放 system prompt / system-level message。没有人放 tool description。

> sgtm.

### D4: 目录命名

> 是不是不用叫agent_skills/，就叫skills/就行？

>> **[Claude]** `skills/` 更简洁，跟 agentskills.io convention `.agents/skills/` 一致。

> sgtm.

### D5: Scripts 执行

> scripts/ 不可执行？现在的shell tool无法执行bash script哈？

>> **[Claude]** Android mksh 能跑简单 shell 脚本，bash-specific 不行，Python 不行。

### D6: 取消 Bundled Skills

> 要不就没有bundled skill? 所有skill安装的时候就都到context.filesDir/下算了？可以直接接入一些已有的skill hub。

>> **[Claude]** 同意。单一 source 大幅简化，shell 能访问所有资源。

### D7: 简化 Data Model

> source是不是不用存在了？body也不用存了？

>> **[Claude]** 对。简化为 `AgentSkillEntry(name, description, filePath)`。Body lazy load on activation。

### D8: YAML Parser

> 不是用个标准yaml parser就搞定了？要能parse metadata。

>> **[Claude]** SnakeYAML + lenient fallback。Parse 所有字段包括嵌套 metadata。

> 行，反正这部分的code比较isolated，以后要改也简单。

### D9: Tool Description

> 这个available skills需要吗？对，skill catalog只存在于system prompt区域。tool description应该就一行。

>> **[Claude]** 同意。Catalog 在 system prompt，tool description 仅一行。

### D10: Executor Role

> executor role不要单独处理，就都有这个activate_skill这个tool就好了。

>> **[Claude]** 同意。所有 roles 均注册。

### D11: Compaction

> 有必要还单独额外注入一次吗？我们先KISS处理，让它可以被压缩？

>> **[Claude]** 完全同意。Skill body 正常 compaction，model 可从 catalog 重新 activate。

> "Catalog 在 system prompt 里，每 turn 重建" 这什么意思？

>> **[Claude]** 表述不准确。System prompt 每次 API call 都完整发送，无需 per-turn 逻辑。

### D12: Shell 读 Resources

> 不是有shell吗？用shell读写不就行了？不用额外加tool。

>> **[Claude]** 对。filesDir 在文件系统上，shell 可直接 `cat` 读取。不需要专用 tool。

### D13: 去掉 Activation Barrier 和 Budget

> Activation barrier 和 Budget 有必要吗，是不是可以去掉？

>> **[Claude]** 可以去掉。Activation barrier：`activate_skill` 标为 non-screen-changing，现有 TurnToolPolicy 已足够。Budget：v1 skill 数量极少，context window 是天然 budget。

### D14: Mention Syntax 与 Model 自主

> `/skill-name` 是确定load，但是agent也可以自己主动决定调用某个skill。

>> **[Claude]** 对。`/skill-name` 是用户显式快捷方式，model 可自主看 catalog 调用 `activate_skill`。两条路径并存。

### D15: App Skill Name 规则

> name就用app name就行？

>> **[Claude]** agentskills.io name 只能 lowercase-hyphen，不能用 package name。需要人类可读的短 name。

> ok，那每个就叫app-[short-name]吧，这个shortname可以是一般用户看到的app name（uber/ lyft/ chrome etc.）。
