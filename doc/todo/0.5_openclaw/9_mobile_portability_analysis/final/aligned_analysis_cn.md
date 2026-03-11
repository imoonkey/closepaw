# 移动端可移植性分析

## 目标

决定 OpenClaw 中哪些东西应该被 Android Agent 吸收，哪些应该在 Android 上做原生化重解释，哪些则应明确拒绝，因为它们本质上属于桌面范式。

总规则很简单：

- 不把 OpenClaw 当成运行时整体移植过来
- 吸收其中平台无关的设计模式
- 继续让 Android Agent 现有的 Kotlin-first session / tool / platform 架构作为 control plane

## 当前 repo 的基础盘

Android Agent 已经拥有大多数“可移植核心”：

- session / task / turn lifecycle、Hot Idle、checkpoint reload
- 通过 `TodoState` 和 `ScratchpadState` 持有 planning state
- 通过 `HistoryManager` 处理 context hygiene 和 compression
- tool registry、routing 与 approval policy
- planner / executor 多 agent 模式
- 通过 accessibility 与 virtual display 平台进行 Android 原生执行
- 通过 `app_skills/<package>/SKILL.md` 做 app-scoped skill injection

因此，这个项目不是为了做“功能对齐式移植”。它本质上是在为 Android 选择正确的 ownership boundary。

## 可移植性结论

### 直接移植（其实已具备）

这些概念已经存在，或者能非常自然地映射到当前设计中：

| OpenClaw 概念 | Android Agent 实现 |
|---|---|
| Session-oriented runtime | `AgentSession`、`SessionCoordinator`、`SessionServices` |
| Working memory / planning | `TodoState`（session-scoped）、`ScratchpadState`（session-scoped） |
| Prompt compaction / history hygiene | `HistoryManager` —— 三阶段压缩流水线（主动 screen downgrade、按组驱逐、硬性 budget guard），带 `COMPRESSION_DIGEST` breadcrumbs |
| Tool approval / risk classification | `PolicyEngine`（ALWAYS_ASK / AUTO_APPROVE / SMART）+ `ToolRouter` lifecycle |
| Multi-agent orchestration | 通过 `AgentDefRegistry` + `SubAgentRunner` 实现 Planner / Executor 模式 |
| Prompt ownership boundaries | system prompt + tool descriptions + `app_skills/<package>/SKILL.md` |
| Session persistence + resume | `SessionRecordingService`、`SessionStorage`、checkpoint reload、Hot Idle |

**关于 compaction：** Android Agent 当前的压缩其实已经比 OpenClaw 更复杂。它具备主动的 per-turn screen downgrade（把每 turn 增量控制在约 275 tokens）、对 KV cache 友好的深压缩（压到预算的 50%，不是 95%），以及 call / output 原子对驱逐。因此，不需要再从 OpenClaw 引入额外 compaction 模式。

### 需要原生化重解释

这些点值得借鉴，但桌面实现方式应该被替换：

- 长期 memory
- 外部 channel / relay ingress
- plugin / extension model
- capability / security model
- voice、notifications、sensors 等 device-native tools

### 明确非目标

不要移植：

| 桌面能力 | 为什么不要 | Android 上的替代 |
|---|---|---|
| Embedded Node.js runtime | Android 原生不适合 | Kotlin-first 架构 |
| Docker sandbox | Android 上没有容器运行时 | Android per-app sandbox + `PolicyEngine` |
| Puppeteer / Playwright | Android 上没有 headless browser | 通过 AccessibilityService 控制浏览器 app |
| WhatsApp Web automation | 依赖 headless Chrome（300-500MB） | 直接通过 AccessibilityService 操作 WhatsApp App |
| Inbound gateway server | NAT 阻断入站；后台进程容易被杀；电池代价高 | Outbound relay client 模式 |
| npm-compatible plugin runtime | Android 上没有 npm | Kotlin modules + asset-based `app_skills/` |
| Unrestricted shell / code execution | 无 root，CLI 能力也有限 | 限定作用域的 `ShellTool`（仅文件检查） |

## 核心架构决策

保持一条控制平面：

```text
Intent Sources
- Chat UI
- Optional relay client
- Optional native triggers (voice, notifications, sensors)
          |
          v
Session Control Plane
- SessionCoordinator
- AgentSession
- SessionServices
          |
          v
Agent Core
- Agent / Turn runners
- Prompt assembly
- HistoryManager
- TodoState / ScratchpadState
- Planner / Executor delegation
          |
          v
Capability Layer
- UI tools
- planning tools
- future native tools
          |
          v
Execution Substrate
- AccessibilityPlatform
- VirtualDisplayPlatform
- Android storage / Android APIs
```

所有进入系统的工作，无论来自哪里，都必须经过现有 session-op 模型。Channel transport 不能变成第二个 runtime owner。

## 已解决的设计规则

### 1. Session continuity 继续归现有 owner 所有

Repo 中已经有：

- 通过 Hot Idle 实现的 follow-up continuity
- 通过 checkpoints 实现的 process-death recovery
- 通过 `SessionRecordingService` 与 `SessionStorage` 实现的持久化 session history

因此：

- 不重新设计 `SessionId`
- 不把持久化迁进 `HistoryManager`
- 不为这个项目再发明第二套 session storage model

如果未来确实需要改进 session continuity，也应扩展现有 recording / checkpoint 栈，而不是替换它。

### 2. 长期 Memory 是新子系统，必须与 Session History 分离

三个层面必须保持分离：

- `HistoryManager`：运行时 prompt history 与 compression
- `ScratchpadState` / `TodoState`：session-scoped 的工作记忆
- 新的 durable memory store：跨 session 的 retrieval 与 writes

推荐形态：

- 存储：以 SQLite 为主，可选地导出人类可读的 Markdown 视图
- 检索：有边界的 FTS query
- 写入：显式 memory write / reflect 流程，而不是隐式自动持久化

Prompt ownership 规则：

- 检索出的长期 memory 必须作为独立 prompt block 注入
- 不能把它合并进 app-skill block
- app skills 继续表示人为编写的 package guidance；memory 则是可变的检索上下文

这样既保留可调试性，也让 ownership 明确。

### 3. Capability Metadata 取代桌面式 sandbox 思维

在 Android 上，正确边界不是 Docker，而是 capability ownership。

每个 tool-capability 都应声明：

- 所需权限
- 仅前台可用还是后台也安全
- 风险等级
- 数据作用域

但这不能只是文档概念。它必须落到具体集成点，例如挂在 `ToolSpec` 上的 metadata，或者一个相邻的 capability descriptor，由 policy 与 availability filtering 共同消费。

### 4. Tool availability 应按 task 或 session 快照，而不是 mid-turn 热切换

动态 capability discovery 是有价值的，但在当前架构下，执行中热变更 registry 不是正确模型。

规则：

- 在 task 开始或 session 开始时确定 active tool availability
- 在那次运行中只把这个 snapshot 暴露给 LLM
- 如果权限或运行时能力有实质变化，则在下一个任务时刷新，或重启 agent / session

这样既避免 nondeterministic tool exposure，又能防止把明显不可用的工具宣传给模型。

### 5. 当前 Shell 边界继续保留

现有 shell tool 是有意限制成 file-inspection-only 的。

因此：

- 不要把它逐渐扩成桌面式 package-manager / intent control
- 如果确实需要包检查、app 管理等设备动作，应新增专门的 native tools

这样安全边界与工具语义都更清晰。

### 6. 外部 Channels 是 Intent Sources，不是 Runtime Owners

如果未来引入 remote channels：

- 手机仍然是执行宿主
- relay 应以 outbound 或 push-wake 为主
- remote input 应转换成与本地 UI 相同的 session ops

一个最小 transport state machine 就够：

```text
DISABLED -> CONNECTING -> READY -> DELIVERING -> BACKOFF -> READY
```

这属于 relay client 的状态，不属于 `AgentSession`。

### 7. Prompt Externalization 必须遵守 Prompt 生命周期

把 agent identity / rules 外置是合理方向，但必须尊重当前 prompt lifecycle：

- system prompt 只在 agent 启动时解析一次
- per-turn context 继续属于 `PromptBuilder` / planning-phase assembly

因此：

- identity / rules / tool-guidance assets 应归 startup-time prompt construction
- 检索得到的 memory 与 app / package guidance 应归 per-turn context injection

不要把这两种生命周期混在一起。

## 优先级矩阵

| 领域 | Repo 当前状态 | 优先级 | 关联项目 | 说明 |
|---|---|---|---|---|
| Portable-core framing | 大体已具备 | Done | — | 这是本分析的主要结论 |
| Long-term memory subsystem | 缺失 | P0 | Project 1 | 与 OpenClaw 差距最大的真实模式 |
| Voice-first interaction | 部分具备 | P0 | Project 2 | Android 原生已有 STT / TTS API |
| Capability metadata + context-aware policy | 部分具备 | P1 | Project 5 | 在现有 approval / risk 模型上继续扩展 |
| Tool availability snapshotting | 部分具备 | P1 | — | 在 task / session 启动时过滤不可用工具 |
| Agent identity externalization | 部分具备 | P2 | Project 4 | 有价值，但杠杆低于 memory |
| Device capability advertising | 缺失 | P2 | Project 6 | 向 LLM 宣告可用 tools / sensors |
| Relay-based remote ingress | 缺失 | P3 | Project 3 | 更偏未来，不是初期核心 |
| Dynamic runtime plugin system | 拒绝 | N/A | — | 保持 Kotlin modules + asset files 即可 |
| Session persistence redesign | 拒绝 | N/A | — | 当前 ownership 已经足够 |

## Trade-Offs

### 为什么这个结论成立

- 复用了 Android Agent 当前最强的系统部件
- 让 runtime boundaries 保持简单
- 避免把不属于 Android 的桌面约束强行带进来
- 让 prompt ownership、persistence ownership 和 capability ownership 各自保持独立

### 我们放弃了什么

- 直接移植桌面集成的能力
- 仅靠手机端实现 server-style 长连接通道架构
- npm 风格扩展分发
- 与桌面几乎等价的 shell 与 browser automation

这个取舍是正确的。Android Agent 的优势是原生手机控制，不是桌面模拟。

## 开放问题

以下问题都是真正值得继续研究的，但它们并不影响当前可移植性结论：

1. 长期 memory 应该只用 SQLite，还是 SQLite + 导出 Markdown 镜像便于人工检查？
2. 每个 turn 注入多少 retrieved memory，才不会明显恶化 token budget 与 prompt 稳定性？
3. 当云端 LLM 在移动端不可用时，离线 / 断网行为应该是什么？
4. 如果 session 空闲期间 runtime capabilities 发生变化，下一次任务是复用旧 session 并刷新工具快照，还是强制新建 session？

## 最终建议

OpenClaw 的架构反映的是一种桌面中心视角：电脑是“大脑”，手机是“传感器”。Android Agent 正好相反：手机既是大脑，也是身体，并且直接连接 app、传感器、通知和用户所处的物理环境。

OpenClaw 做得更好的部分，全是平台无关的算法性设计：memory retrieval、identity templates、security classification。这些都可以作为纯逻辑很自然地迁移过来。Android Agent 做得更好的部分，则全是平台原生能力：通过 accessibility 与 app 交互、传感器访问、始终随用户存在。

正确的设计不是“把 OpenClaw 搬到 Android 上”。正确的设计是：

- 保持 Android Agent 当前的 session / tool / platform 架构
- 在仍然适配的地方吸收 OpenClaw 的可移植设计模式
- 补上 Android 原生缺失的子系统，例如长期 memory 与 capability metadata
- 把桌面 runtime 部分明确当作非目标拒绝掉

这样得到的系统会比“移植版”更干净，而且与现有代码库方向一致。
