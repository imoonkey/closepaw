# OpenClaw 家族共性能力分析

## 目标

回答三个问题：

1. `.reference/claws/` 里这些 `openclaw and variants` 的真正共性能力是什么。
2. 哪些能力本质上是电脑端或云端 agent 更擅长、甚至近乎独占的。
3. 哪些能力你的 Android Agent 可以吸收，哪些反而是手机端天然更强、桌面系 agent 做不到或很难做好的。

## 分析范围

先把目录里的项目分层，否则很容易把“生态附件”误认为“runtime 本体”。

### A. 核心 runtime 变体

这些项目都在做“个人 agent 运行时”本身，只是执行介质和产品包装不同：

- `openclaw`
- `nanobot`
- `nano-claw`
- `picoclaw`
- `mimiclaw`
- `nextclaw`
- `CoPaw`
- `poco-agent`
- `LobsterAI`
- `lettabot`
- `hermes-agent`
- `ironclaw`
- `zeroclaw`

### B. 生态附件或局部子系统

这些不是完整 runtime，对判断“common capabilities”应降权：

- `ClawX`：OpenClaw 的桌面 GUI 外壳，不是新的 agent runtime
- `MemOS`：长时记忆子系统，不是完整 assistant runtime
- `leon`：更早期的个人 assistant 路线，和 OpenClaw 家族有交集，但不是同一架构代系

## 一句话结论

OpenClaw 家族的共性，不是“会聊天”，而是：

**一个常驻的、可被外界触发的、带持久状态的个人 agent runtime。**

它通常同时具备这六个核心层：

1. 多入口接入
2. 会话化 agent loop
3. 工具与执行层
4. 记忆与工作区
5. 自主调度
6. 控制平面与安全边界

不同项目主要只是在下面几个维度上变化：

- 运行介质：Mac / Linux / Windows / VPS / Docker / SBC / ESP32 / Android node
- 接入面：CLI、Web、IM、桌面 App、移动节点
- 执行方式：本机 shell、容器、WASM、浏览器、节点调用、硬件外设
- 产品包装：极简 runtime、桌面产品、云控平台、芯片固件

## 共性能力栈

## 1. 多入口接入，而不是单一 chat UI

这几乎是所有 OpenClaw 变体最稳定的共性。

它们不是把 agent 绑定在一个聊天窗口里，而是把 agent 暴露给多个入口：

- CLI
- Web 控制台
- 桌面 App
- Telegram / Discord / Slack / WhatsApp / Feishu / QQ / Email 等 IM 渠道
- 节点或设备端 companion app

所以它们的第一性原理不是“做一个聊天应用”，而是“做一个 ingress router”。

用户消息、定时任务、webhook、heartbeat、语音、节点事件，最终都会被归一化成同一种 agent 输入。

这点在 `openclaw`、`nanobot`、`nano-claw`、`nextclaw`、`CoPaw`、`LobsterAI`、`lettabot`、`hermes-agent` 里都非常明显。

### 真正的共性

- 输入源多样
- 运行时内部统一
- 同一个 agent 可以跨入口连续存在

### 对 Android 的启发

你的 Android Agent 后续如果做远程接入，应该把外部渠道当作 **intent source**，不是第二套 runtime。

## 2. 会话化 agent runtime，而不是 stateless 推理调用

OpenClaw 家族几乎都有明确的 session / conversation / chat state。

它们不是每次来一条消息就重新问一次模型，而是维持：

- 当前会话上下文
- 会话级工作记忆
- 会话级历史压缩
- 会话重置与恢复
- 某些情况下的跨渠道 continuity

这类系统的核心对象通常不是“请求”，而是：

`channel input -> session -> turn loop -> tool execution -> persisted state`

### 这类 session 通常承载的东西

- 历史消息
- 当前任务状态
- 临时 scratchpad / todos
- 工具调用轨迹
- token / cost / route 元数据
- 需要时的 resume / replay

### 为什么这是共性

因为只要 agent 真要干活，就一定要处理：

- 多轮任务
- 工具调用后的再决策
- 用户中途纠偏
- 后台任务与当前对话的连续性

这也是为什么轻量实现如 `nanobot`、`nano-claw`、`picoclaw`、`mimiclaw` 即使大砍 UI 和平台能力，也依然保留 session、memory、cron、heartbeat 这些骨架。

## 3. 工具调用不是插件点缀，而是主执行面

OpenClaw 家族的“agentic”不是靠 prompt 说出来的，而是靠工具层落地的。

共同模式是：

- LLM 负责决定做什么
- tool layer 负责把意图变成可执行动作
- 执行结果再回流给 LLM

工具内容各不相同，但结构高度相似：

- 文件 / 工作区读写
- shell / command execution
- Web 搜索或浏览器控制
- 渠道消息发送
- 定时任务管理
- 节点能力调用
- Skills / MCP / Plugin 扩展

### 真正的共性不是具体工具，而是工具所有权

这些项目几乎都把 agent 设计成：

- 可扩展的 tool host
- 带 schema / contract 的 action surface
- 带 approval / policy / sandbox 的执行边界

所以它们共同在做的其实是 **capability orchestration**。

## 4. 记忆不是附加特性，而是主产品价值

如果只看 README，很容易以为共性是“多渠道”和“工具”。但再往里看，真正被反复强调的是：

- 长时记忆
- 用户画像
- 项目上下文
- 历史任务沉淀
- skills / knowledge 的可复用积累

只不过不同项目把记忆拆成了不同层：

- 会话历史
- session-scoped 工作记忆
- durable long-term memory
- profile / identity files
- skill memory / procedural memory

`openclaw` 本体强调 workspace、identity、session、compaction；`hermes-agent` 强调 self-improving memory loop；`lettabot` 强调 unified memory across channels；`LobsterAI`、`poco-agent`、`CoPaw` 都把 memory 当成产品卖点；`MemOS` 则把这部分直接抽成独立 OS。

### 所以这里的真正共性是

这些项目都把 agent 看成“随时间积累状态的系统”，不是“每次都重新开始的问答器”。

## 5. 自主调度几乎是标配

另一个非常稳定的共性是：

- cron
- heartbeat
- scheduled task
- background job
- autonomous wake-up

也就是说，OpenClaw 家族默认认为 agent 不应该只在用户说话时存在，而应该在没有显式输入时也能周期性工作。

这直接把它和普通 chat assistant 分开了。

### 这类调度通常承载什么

- 定时提醒
- 周报 / 日报
- 邮件或消息巡检
- 监控类任务
- 周期性研究 / 汇总 / 推送

### 这件事的重要性

一旦有 cron / heartbeat，agent 就从“被动工具”变成“代理人”。

这也是为什么就算是极简实现，如 `nanobot`、`nano-claw`、`picoclaw`、`mimiclaw`，也没有砍掉 heartbeat / cron。

## 6. 都有某种工作区或可编辑上下文

OpenClaw 家族往往有一个“人类可读、可编辑、可备份”的上下文载体：

- Markdown workspace
- config files
- session store
- skill directory
- memory files
- SQLite / JSONL / local DB

这说明它们并不把 agent 完全当成黑盒模型，而是当成一个：

**由模型 + 文件系统状态 + 可编辑规则 + 执行记录** 共同构成的系统。

这一点非常关键，因为它决定了：

- 可调试
- 可迁移
- 可备份
- 可审计
- 可被人类直接修正

## 7. 都需要控制平面和可观测性

就算实现极简，最终也都会长出控制平面：

- gateway
- daemon
- web dashboard
- desktop console
- logs / replay / status
- pairing / approvals / health

因为一旦 agent 常驻、多入口、可调度、可执行，你就必须知道：

- 它现在在线吗
- 接收了什么
- 执行到了哪
- 哪个工具失败了
- 现在有哪些设备/节点
- 哪些权限已打开

所以共性并不是“有 Web UI”，而是 **有运维面**。

## 8. 安全边界不是补丁，而是架构层

越新的 OpenClaw 变体，越明显把安全视为一等公民：

- pairing / allowlist
- tool approvals
- exec approvals
- sandbox / container / WASM
- endpoint allowlist
- prompt injection defense
- 本地优先 / 数据留在自己设备

这不是因为大家“更谨慎”了，而是因为这类 agent 天生比聊天机器人更危险：

- 能读写文件
- 能执行命令
- 能发消息
- 能做自动化
- 能长期运行

所以这类系统的真正共性是：

**安全控制必须嵌在 capability boundary 上，而不是只靠系统提示词。**

## 一个更准确的抽象

把这些项目抽象到同一层，其实是这个结构：

```text
Ingress Surfaces
- CLI
- Web / Desktop UI
- IM channels
- Voice
- Webhook / schedule / heartbeat
        |
        v
Session Router
- session keying
- history
- context compaction
- routing / identity
        |
        v
Agent Loop
- think
- choose tool
- observe result
- continue / complete
        |
        v
Capability Layer
- local tools
- browser
- skills / MCP / plugins
- device / node commands
        |
        v
Execution Substrate
- desktop OS
- cloud VM
- container
- browser runtime
- mobile node
- embedded board
        |
        v
Persistence + Ops
- workspace
- memory
- schedules
- logs
- approvals
- health
```

这才是 OpenClaw 家族真正稳定的“common capabilities model”。

## 哪些能力是“高频共性”，哪些只是“富实现加成”

## 高频共性

这些几乎已经是 OpenClaw 家族的最低配：

- 多入口接入
- 会话化上下文
- 工具调用循环
- 工作区 / 配置 / 持久状态
- 记忆或至少记忆接口
- cron / heartbeat
- 安全边界

## 富实现加成

这些很常见，但不是每个变体都有：

- 浏览器自动化
- Web 仪表盘或桌面 GUI
- 丰富的插件市场
- 多设备节点网络
- 语音唤醒 / Talk Mode
- Live Canvas / agent-driven UI
- 容器 / WASM / VM 沙箱
- 云端托管和 serverless 执行

它们是产品差异化来源，不是家族最低公约数。

## 电脑端或云端 agent 特有的能力

这里的“特有”，不是字面上永远不可能搬到手机，而是指：

**它们天然依赖桌面 / 服务器执行环境，在 Android 上要么不适合，要么成本和收益严重失衡。**

## 1. 无约束本机执行环境

典型能力：

- `system.run`
- 任意 shell 命令
- git / package manager / build toolchain
- 仓库读写与代码修改
- 长命名路径和完整文件树操作

原因很直接：

- 桌面和云端天然拥有稳定文件系统与进程模型
- 可以安装依赖、启动子进程、运行服务
- 可以访问开发工具链、浏览器、Docker、SSH

这类能力是 `openclaw`、`poco-agent`、`LobsterAI`、`hermes-agent`、`ironclaw`、`zeroclaw` 的核心差异化来源之一。

### Android 不适合直接复制

- 没有通用 package manager 语义
- 没有稳定后台进程生态
- 受沙箱和权限限制
- 用户真实价值也不在“手机上跑 npm install”

## 2. 入站网关与常驻 server

典型能力：

- WebSocket / HTTP server
- webhook ingress
- remote dashboard
- public endpoint / tailnet / tunnel
- always-on daemon

这类能力在桌面和云端非常自然，因为：

- 网络可达性更好
- 长驻进程更可靠
- 不怕电池
- 可以持续监听端口

### Android 上更合理的形态

不是 inbound gateway，而是：

- outbound relay client
- push 唤醒
- 前台服务加有限后台能力

## 3. 浏览器与容器型执行

典型能力：

- Playwright / Chromium / CDP
- openclaw-managed browser profile
- Docker / VM / WASM sandbox
- headless automation

这是电脑/云端的典型优势，因为它们有：

- 稳定浏览器进程
- 大内存
- 完整 OS API
- 合理的多进程与隔离手段

Android 上做这类复制通常是错误方向。手机更适合直接操作真实 App，而不是再造一个桌面浏览器执行层。

## 4. 高并发、多租户、远程 worker

典型能力：

- 多用户 / 多实例
- serverless persistence
- isolated workers
- job queue
- orchestrator / worker split

这类能力属于云端 runtime 的地盘，不是手机端的自然重心。

## 5. 宽屏控制台与重运维面

典型能力：

- dashboard
- artifact viewer
- replay console
- ops panel
- admin panel

它们适合桌面和云端，但不应该成为 Android Agent 的中心设计。

## 你的 Android Agent 可以有的能力

这里不是“理论上能有”，而是“与你当前 repo 的 owner boundary 相容，且值得做”的能力。

## 1. OpenClaw 家族的 runtime 核心，你已经有了一半以上

当前 repo 已经具备：

- `AgentSession` / `SessionCoordinator` / `SessionServices`
- planner / executor / subagent
- `ToolRegistry` / `ToolRouter` / `PolicyEngine`
- `HistoryManager` / `SessionRecordingService` / checkpoint
- `TodoState` / `ScratchpadState`
- Accessibility / VirtualDisplay 双执行基座
- app-scoped `SKILL.md`

所以你的 Android Agent 本质上已经是一个 **sessioned personal agent runtime**，不是单纯的 UI automation demo。

## 2. 可以直接吸收的 OpenClaw 家族共性

最值得继续补齐的是：

- 长时 memory 子系统
- heartbeat / scheduled task
- 外部 intent source 接入
- 设备能力广告与可用性快照
- 更明确的 capability metadata
- native voice loop
- 可恢复的 relay client

### 这些都和当前架构兼容

因为它们都可以继续挂在你现有的 owner 上：

- session 归 `AgentSession`
- tool 执行归 `ToolRouter`
- 历史归 `HistoryManager` / recording
- 平台动作归 `AndroidPlatform`

不需要引入第二套 runtime。

## 3. Android 上应该做“原生重解释”，而不是桌面平移

你应该吸收的是模式，不是桌面实现形式。

例如：

- `system.run` -> 不做桌面 shell，对应到更窄的 native capability tools
- gateway server -> 不做入站服务，对应到 relay / push ingress
- browser automation -> 不做 CDP，对应到直接操作浏览器 App
- plugin runtime -> 不做 npm 插件宿主，对应到 Kotlin tools + asset skills
- Canvas host -> 不照搬 macOS WebView canvas，可改为 Android overlay / panel / in-app surface

## 4. Android 上完全值得拥有的 OpenClaw 式产品能力

这些不是桌面专属，反而和 Android 很匹配：

- 语音对话
- 前台服务常驻
- 通知驱动任务启动
- 相机、位置、联系人、日历、短信等原生工具
- 设备状态感知
- 本地记忆
- 随手唤起的 overlay / capsule
- 与真实用户 App 状态共享的 agent 上下文

## 它们不能有，而你在手机端能有的能力

这里的“不能有”，更准确地说是：

**桌面/云端可以模拟，但无法像手机端那样天然、低摩擦、第一人称地拥有。**

## 1. 对“真实个人设备上下文”的原位控制

你的 Android Agent 可以直接处在用户实际生活发生的设备上：

- 真正的通知流
- 当前前台 App
- 最近任务
- 系统分享入口
- 锁屏外的真实使用上下文
- 用户此刻正在看的页面

桌面或云端 agent 即使能远程发消息，也不在这个一手现场里。

这意味着手机端 agent 可以做：

- 在用户刚收到通知时接入
- 在用户打开某个 App 的当下协助
- 基于当前屏幕状态给出帮助
- 在操作现场直接接管或补完动作

这是一种 **in-situ assistance**，桌面/云端很难复制。

## 2. 传感器和身体化上下文

手机天然拥有：

- 位置
- 移动状态
- 步数 / 活动
- 相机
- 麦克风
- 通讯录
- 短信
- 日历
- 电量、网络、蓝牙、设备姿态

桌面和云端通常只有其中很小一部分，且不是第一手来源。

所以手机 agent 的独特价值不是“也能跑一个 agent”，而是：

**它是带身体和环境感知的 agent。**

## 3. 真实移动 App 生态内的操作能力

OpenClaw 桌面系变体很多都还在做：

- IM bot
- 浏览器自动化
- remote control

而你的 Android Agent 可以直接操作：

- WhatsApp / Telegram / Settings / Maps / Calendar / Shopping / Banking 这类真实移动 App
- 真实的 touch-first UI
- 用户已经登录且已经在用的账号环境

这和桌面浏览器里的自动化不是一回事。

手机端最强的，不是通用 shell，而是 **真实 App 世界里的原位执行权**。

## 4. 微时刻触发与低心智负担交互

手机端还有一个桌面和云端难以做到的优势：

- 用户总在身边
- 交互时长短
- 触发频率高
- 可以靠通知、语音、悬浮入口、分享菜单、快捷动作进入

因此手机 agent 更适合：

- 微自动化
- 临门一脚协助
- 场景化提醒
- 半自动接管
- 一屏内完成的任务

而不是长时间的 IDE / terminal 风格工作流。

## 对当前 Android Agent 的具体结论

## 应该吸收的

- OpenClaw 家族对 sessioned runtime 的产品理解
- long-term memory
- heartbeat / scheduled tasks
- device capability advertising
- external ingress as intent source
- richer native tool families
- capability-scoped security

## 不该照搬的

- gateway-first 架构
- 桌面 shell parity
- browser/CDP automation
- npm/plugin host
- Docker / WASM / VM sandbox 作为主安全模型
- 宽屏 dashboard-first 产品假设

## 应该强化成自己路线的

- accessibility / virtual display 双基座
- app-local skill guidance
- overlay / capsule 交互
- 真实移动 App 内自动化
- 通知、位置、相机、短信、联系人等 device-native tools
- voice-first 和 opportunistic interaction

## 最终判断

如果把 OpenClaw 家族压缩成一句产品定义，它不是“电脑上的 AI 助手”，而是：

**一个能长期存在、能被多入口触发、能持久积累状态、能调用外部能力并代表你行动的个人 agent runtime。**

你的 Android Agent 完全可以继承这个定义。

但它不应该去追桌面/云端 agent 的实现外壳，而应该把这套 runtime 核心落到手机自己的优势上：

- 原位 App 操作
- 真实个人设备上下文
- 传感器和通知
- 语音与悬浮入口
- 低摩擦、随身、第一人称协助

从这个角度看，手机端不是 OpenClaw 的降级版。

它更像是这条路线里最接近“个人助理”本义的一种形态。
