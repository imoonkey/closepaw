# OpenClaw 生态 Common Capabilities 深度分析

> 基于 `.reference/claws/` 下 16 个项目的横向对比
> 分析日期：2026-03-10

---

## 一、项目全景

| 项目 | 语言 | 平台 | Stars | 定位 |
|------|------|------|------:|------|
| **openclaw** | TS/Python | macOS/Linux/Win/iOS/Android | 297,939 | 旗舰全功能平台 |
| **nanobot** | Python | 跨平台 | 31,918 | 超轻量复刻 |
| **zeroclaw** | Rust | Desktop/Server/Embedded | 25,748 | Rust 低开销基础设施 |
| **picoclaw** | Go | IoT/Edge ($10 硬件) | 23,569 | 极致轻量边缘部署 |
| **leon** | TS/Node | Desktop/Server | 17,047 | 面向大众的个人助手 |
| **CoPaw** | Python | Desktop/Cloud | 10,454 | AgentScope 多渠道助手 |
| **ironclaw** | Rust | Desktop/Server | 9,023 | 安全优先的 Rust 重写 |
| **MemOS** | Python | Cloud/Plugin | 6,486 | Agent 记忆基础设施 |
| **mimiclaw** | C/FreeRTOS | ESP32-S3 ($5 芯片) | 4,233 | 嵌入式芯片级助手 |
| **LobsterAI** | Electron/React | Desktop + IM | 3,745 | 全场景产品化助理 |
| **hermes-agent** | Python | CLI/Gateway | 3,600 | 自学习 agent 系统 |
| **ClawX** | Electron/React | Desktop GUI | 3,412 | OpenClaw 桌面前端 |
| **poco-agent** | FastAPI/Next.js | Docker/Self-hosted | 1,132 | 沙箱安全替代品 |
| **lettabot** | TS/Node | Multi-channel | 257 | Letta SDK 记忆助手 |
| **nextclaw** | TS/Next.js | Browser UI | 127 | 浏览器配置的全能平台 |
| **nano-claw** | TS/Node | 跨平台 | 31 | 教学/研究轻量实现 |

---

## 二、Common Capabilities（所有/大多数项目共有）

### 2.1 Agent Loop（核心循环）

**100% 共有** — 这是定义"agent"的本质。

```
Perceive → Think (LLM) → Act (Tool Call) → Observe (Tool Result) → Loop
```

| 模式 | 采用项目 |
|------|---------|
| ReAct (Reason + Act) | openclaw, zeroclaw, ironclaw, leon, CoPaw, hermes, mimiclaw |
| Standard tool-calling loop | nanobot, picoclaw, nano-claw, nextclaw, LobsterAI, poco-agent |
| Letta SDK loop | lettabot |

**共性要素：**
- LLM 作为决策核心（multi-provider 支持是标配）
- Tool call → result → re-prompt 的迭代循环
- Max iteration guard（防 runaway，通常 10-25 轮）
- Streaming response（大多数支持 delta 流式输出）

### 2.2 Multi-Provider LLM 支持

**100% 共有** — 无一例外地支持多 LLM 后端。

| Provider | 支持率 |
|----------|--------|
| OpenAI / OpenAI-compatible | 16/16 |
| Anthropic Claude | 14/16 |
| OpenRouter (聚合网关) | 12/16 |
| Ollama (本地推理) | 8/16 |
| Gemini / DeepSeek / Groq | 8-10/16 |

**共性模式：**
- Provider trait/interface 抽象（Rust: `Provider` trait, TS: class, Python: adapter）
- 运行时可切换模型
- API key 配置与 provider 注册

### 2.3 Tool/Action 系统

**100% 共有** — 所有项目都有 tool registry + JSON schema + execution。

#### 通用 Tools（大多数项目都有）：

| Tool 类别 | 描述 | 覆盖率 |
|-----------|------|--------|
| **Shell/Terminal** | 执行系统命令 | 15/16 (mimiclaw 除外) |
| **File Read/Write/Edit** | 文件系统操作 | 14/16 |
| **Web Search** | 网络搜索 (Brave/Tavily/Perplexity) | 14/16 |
| **Web Fetch** | HTTP 请求 + HTML→Markdown | 12/16 |
| **Cron/Schedule** | 定时任务调度 | 13/16 |
| **Memory Store/Recall** | 持久化记忆读写 | 12/16 |
| **Send Message** | 跨渠道发消息 | 12/16 |

#### 进阶 Tools（部分项目有）：

| Tool 类别 | 描述 | 采用项目 |
|-----------|------|---------|
| **Browser Automation** | CDP/Playwright 浏览器控制 | openclaw, zeroclaw, ironclaw, hermes, CoPaw, LobsterAI, poco-agent |
| **Delegate/Spawn** | 子 agent 派遣 | openclaw, zeroclaw, ironclaw, hermes, lettabot |
| **Code Execution** | 沙箱代码执行 | hermes, poco-agent, LobsterAI |
| **Image Generation** | 图像生成 | hermes, LobsterAI |
| **Vision/OCR** | 图像理解 | openclaw, zeroclaw, hermes, LobsterAI |
| **MCP Protocol** | 外部工具扩展 | ironclaw, nanobot, CoPaw, poco-agent |
| **Canvas/A2UI** | Agent 驱动 UI | openclaw |

### 2.4 Memory 系统

**15/16 共有**（MemOS 本身就是记忆系统）

| 记忆类型 | 描述 | 覆盖率 |
|----------|------|--------|
| **Session History** | 对话历史持久化 | 16/16 |
| **Personality/Soul** | Agent 身份定义 (SOUL.md) | 10/16 |
| **User Profile** | 用户偏好记录 (USER.md) | 10/16 |
| **Long-term Memory** | 跨会话事实记忆 (MEMORY.md) | 12/16 |
| **Daily Notes** | 每日笔记/日志 | 6/16 |
| **Embeddings/Vector** | 向量检索 | 7/16 |
| **Compaction** | 上下文压缩/摘要 | 10/16 |

**共性模式：**
- 文件系统存储是最低公约数（MEMORY.md / USER.md / SOUL.md 三件套）
- 会话开始时加载 snapshot 到 system prompt
- 自动提取 vs 显式指令写入

### 2.5 Multi-Channel 消息接入

**14/16 共有**（mimiclaw 仅 Telegram，ClawX 间接通过 gateway）

| 渠道 | 支持项目数 |
|------|-----------|
| Telegram | 14 |
| Discord | 13 |
| Slack | 10 |
| WhatsApp | 8 |
| Signal | 5 |
| DingTalk/Feishu | 6 |
| Email | 4 |
| Matrix | 3 |
| IRC/MQTT/Nostr | 2-3 |

### 2.6 Security 模型

| 安全能力 | 描述 | 覆盖率 |
|----------|------|--------|
| **API Key 管理** | 密钥不硬编码 | 16/16 |
| **Command 审批** | 危险命令需确认 | 10/16 |
| **Exec 沙箱** | 命令执行隔离 | 7/16 |
| **SSRF 防护** | 阻止内网请求 | 3/16 |
| **Prompt Injection 检测** | 注入攻击防御 | 3/16 |
| **Secret Scrubbing** | 输出中敏感信息脱敏 | 3/16 |
| **WASM Sandbox** | WebAssembly 级隔离 | 1/16 |

**安全梯度：** ironclaw > openclaw > zeroclaw > 其他

### 2.7 Skill/Plugin 系统

**12/16 共有**

- 以 Markdown 文件定义 skill prompt（SKILL.md 模式）
- 动态加载目录下的 skill 文件
- 运行时注入到 system prompt
- 部分项目有 marketplace/hub（ironclaw ClawHub, openclaw agentskills.io）

### 2.8 Heartbeat（主动唤醒）

**10/16 共有**

- 定期（通常 30 分钟）自动触发 agent 运行
- 检查待办事项 (HEARTBEAT.md)
- 执行到期的 cron 任务
- 主动通知用户

---

## 三、Desktop/Cloud Agent 特有（Android Agent 难以具备）

这些是电脑端/云端 agent 的天然优势，手机端实现困难或不可能：

### 3.1 Shell/Terminal 全权访问

| 能力 | 为什么手机端困难 |
|------|----------------|
| 任意 shell 命令执行 | Android 无 root 无法访问其他 app 数据，沙箱限制严格 |
| Docker 容器沙箱 | Android 无 Docker 运行时 |
| SSH 远程执行 | 可以有但不是原生优势 |
| 进程管理/系统监控 | 无 root 权限受限 |

### 3.2 Browser Automation (CDP/Playwright)

| 能力 | 为什么手机端困难 |
|------|----------------|
| Chrome DevTools Protocol | Android 无法控制其他 app 的 WebView |
| 完整 DOM 快照 | Accessibility tree 只有 UI 结构，无 DOM |
| JavaScript 注入/执行 | 跨 app 不可能 |
| 专用浏览器 profile | 无法创建隔离浏览器实例 |

### 3.3 文件系统全权访问

| 能力 | 为什么手机端困难 |
|------|----------------|
| 读写任意路径 | Android scoped storage，只能访问自己 app 数据 |
| 代码仓库操作 | 手机上没有 dev 环境 |
| Git 操作 | 理论可行但非典型用例 |
| 工作区管理 | 受限于 scoped storage |

### 3.4 多渠道消息网关

| 能力 | 为什么手机端困难 |
|------|----------------|
| 作为 bot server 长驻 | Android 后台限制，电池优化会杀进程 |
| WebSocket 长连接 | 后台保活困难 |
| 多平台同时在线 | 资源受限 |
| Webhook 接收 | 需要公网 IP / 反向代理 |

### 3.5 Hardware/Embedded 外设控制

| 能力 | 为什么手机端困难 |
|------|----------------|
| GPIO/SPI (zeroclaw, picoclaw) | 手机无通用 GPIO |
| USB 串口 (STM32/Arduino) | 需要 USB Host + 驱动，非标准 |
| probe-rs 调试 | 桌面级工具链 |

### 3.6 Canvas / A2UI（Agent 驱动 UI）

| 能力 | 为什么手机端困难 |
|------|----------------|
| 实时 HTML/CSS/JS 画布 | 可以嵌 WebView 但不是原生渲染 |
| Agent 推送 UI 更新 | 可行但需要自己实现 host |
| 屏幕录制回放 | 权限限制 + 后台限制 |

---

## 四、Android Agent 特有优势（Desktop/Cloud Agent 不具备）

这是你最核心的差异化。它们不仅仅是"也能做"，而是**只有手机端才能做好的事**：

### 4.1 跨 App UI 自动化（Accessibility Service）

**这是最大的独占优势。**

| 能力 | Desktop Agent 对应 | 为什么 Android 更强 |
|------|-------------------|-------------------|
| 读取任意 app 的 UI 树 | 只能控制浏览器 (CDP) | Android a11y service 看到**所有 app** 的完整 UI 结构 |
| 点击/滑动/输入任意 app | 仅限浏览器内 | 系统级权限，不需要 app 配合 |
| 跨 app 工作流 | 需要每个 app 的 API | 统一的 accessibility action 接口 |
| 操作没有 API 的 app | 不可能 | a11y 不需要 app 提供 API |

**关键洞察：** Desktop agent 操作 app 的主要方式是 API/SDK（需要每个 app 适配），而 Android agent 通过 a11y service 获得了**通用的 app 操控能力**，这是一个根本性的架构优势。

### 4.2 真实移动传感器

| 能力 | Desktop Agent | Android Agent |
|------|--------------|--------------|
| **GPS 定位** | 无 (需外部 API) | 原生高精度 GPS |
| **摄像头** | 需要权限 + 外设 | 随身携带，随时可拍 |
| **麦克风** | 有但不随身 | 随身 + 语音优先交互 |
| **加速度/陀螺仪** | 无 | 姿态感知、运动检测 |
| **指纹/面部** | 有但不便 | 原生生物识别 |
| **NFC** | 需外设 | 原生支持 |
| **蓝牙** | 有 | 更自然（耳机、穿戴设备） |

### 4.3 通知系统深度集成

| 能力 | Desktop Agent | Android Agent |
|------|--------------|--------------|
| **读取所有 app 通知** | 有限 (macOS 需 TCC) | NotificationListenerService 读全部通知 |
| **通知驱动的自动化** | 有限 | 可以基于任何 app 的通知触发 agent 行动 |
| **推送到用户** | 需要渠道 (Telegram/Email) | 原生系统通知，零配置 |
| **通知交互** | 不可能 | 直接 action 通知按钮 |

### 4.4 Always-With-You（随身性）

| 场景 | Desktop Agent | Android Agent |
|------|--------------|--------------|
| **出门在外** | 不可用（除非远程） | 随身可用 |
| **实时位置感知** | 无 | GPS + 网络定位 |
| **即时拍照/录音** | 不便 | 一句话触发 |
| **运动/健康数据** | 无 | 与 Health Connect 集成 |
| **车载/出行** | 不适合 | 自然场景 |

### 4.5 App 间数据桥接

| 能力 | 描述 |
|------|------|
| **Intent 系统** | Android 原生的 app 间通信，agent 可以发起 Intent |
| **Content Provider** | 访问联系人、日历、短信等系统数据 |
| **Share Sheet** | 通过分享机制接收来自任何 app 的数据 |
| **Deep Link** | 直接跳转到任何 app 的特定页面 |

### 4.6 语音优先交互

| 能力 | 描述 |
|------|------|
| **随时唤醒** | 无需打开电脑，口袋里就能交互 |
| **TTS 回复** | 边走边听 agent 的回复 |
| **多模态输入** | 语音 + 拍照 + 文字混合 |
| **耳机/手表触发** | 蓝牙配件一键触发 |

---

## 五、三层能力对照矩阵

| 能力 | Desktop/Cloud | Android Agent | 谁更强 |
|------|:------------:|:-------------:|:------:|
| **Agent Loop (LLM + Tools)** | ✅ | ✅ | 平手 |
| **Multi-Provider LLM** | ✅ | ✅ | 平手 |
| **Memory System** | ✅ | ✅ | Desktop 略强 (向量DB, 图数据库) |
| **Tool Registry + JSON Schema** | ✅ | ✅ | 平手 |
| **Cron/Heartbeat** | ✅ | ✅ (需 foreground service) | Desktop 更稳定 |
| **Shell 命令执行** | ✅✅ | ❌/有限 | Desktop 完胜 |
| **Browser Automation** | ✅✅ | ❌ | Desktop 完胜 |
| **文件系统全权访问** | ✅✅ | ❌ | Desktop 完胜 |
| **消息网关 (bot server)** | ✅✅ | ❌/有限 | Desktop 完胜 |
| **Docker 沙箱** | ✅✅ | ❌ | Desktop 完胜 |
| **跨 App UI 自动化** | ❌ | ✅✅ | **Android 独占** |
| **真实传感器 (GPS/Camera/IMU)** | ❌/有限 | ✅✅ | **Android 独占** |
| **系统通知读写** | 有限 | ✅✅ | **Android 完胜** |
| **随身可用性** | ❌ | ✅✅ | **Android 独占** |
| **App 间数据桥接** | ❌ | ✅✅ | **Android 独占** |
| **语音优先交互** | 有限 | ✅✅ | **Android 完胜** |
| **Skill/Plugin 生态** | ✅✅ | ✅ (app skill) | Desktop 更成熟 |
| **安全模型深度** | ✅✅ | ✅ | Desktop 更深 (WASM, Landlock) |
| **Hardware 外设** | ✅ (GPIO, USB) | ✅ (BLE, NFC) | 各有领域 |

---

## 六、Android Agent 应该从 OpenClaw 生态吸收的能力

### 6.1 立即可用（已有基础或易实现）

| 能力 | 来源 | 优先级 | 备注 |
|------|------|:------:|------|
| **Structured Memory (MEMORY.md/USER.md/SOUL.md)** | 全生态共识 | P0 | 已在规划中，参考 hermes/ironclaw 的 bounded memory |
| **Heartbeat 主动唤醒** | 10/16 项目 | P0 | 前台服务 + 定时触发 |
| **Cron 调度** | 13/16 项目 | P1 | AlarmManager / WorkManager |
| **Session Compaction** | 10/16 项目 | P1 | 长对话摘要压缩 |
| **Skill 系统 (SKILL.md)** | 12/16 项目 | P0 | 已有 app_skills，可扩展 |
| **Web Search tool** | 14/16 项目 | P1 | HTTP 请求即可 |
| **Provider 热切换** | 16/16 项目 | P1 | 运行时切换 LLM |

### 6.2 需要适配但值得做

| 能力 | 来源 | 挑战 | 价值 |
|------|------|------|------|
| **Context Compaction** | ironclaw, hermes | Token 计数 + 摘要模型 | 长任务不断上下文 |
| **Memory Auto-Extraction** | LobsterAI, nanobot | 需要 extraction prompt | 无感积累用户偏好 |
| **Sub-agent Delegation** | openclaw, zeroclaw, hermes | 并发管理 + 资源控制 | 复杂任务分解 |
| **Security Policy Pipeline** | ironclaw | 权限模型适配 | 生产级安全 |

### 6.3 不应照搬

| 能力 | 原因 |
|------|------|
| Browser CDP 自动化 | 手机端用 a11y 操控浏览器更自然 |
| Docker 沙箱 | Android 无 Docker，改用 Android 沙箱 (isolated process) |
| Shell 全权执行 | 无意义，Android 沙箱不允许 |
| Multi-channel gateway | 手机不适合做 bot server，应改为消费者模式 |
| 文件系统全权访问 | scoped storage 限制，不应绕过 |

---

## 七、战略定位建议

```
Desktop/Cloud Agent = 开发者工具 + 自动化后端 + Bot 服务器
Android Agent     = 个人智能助手 + 真实世界传感器 + App 操控入口
```

**Android Agent 的护城河不在于复制 desktop 能力，而在于：**

1. **跨 App 操控** — 唯一能操作没有 API 的 app 的方式
2. **随身感知** — GPS、Camera、IMU 让 agent 理解物理世界
3. **通知枢纽** — 读取/响应所有 app 的通知，成为信息中枢
4. **语音优先** — 无需看屏幕就能与 agent 交互
5. **Intent 桥接** — 利用 Android Intent 系统串联 app 工作流

**最终形态：手机是 agent 的"身体"，Desktop/Cloud 是 agent 的"大脑扩展"。**
两者不是竞争关系，而是互补。Android Agent 应该能作为 OpenClaw 生态的 node（已有先例：openclaw 支持 Android 作为 node），同时保持独立运作的能力。

---

## 附录：各项目 Tool 覆盖对比

| Tool | openclaw | zeroclaw | ironclaw | hermes | leon | CoPaw | nanobot | picoclaw | LobsterAI | poco | lettabot | mimiclaw |
|------|:--------:|:--------:|:--------:|:------:|:----:|:-----:|:-------:|:--------:|:---------:|:----:|:--------:|:--------:|
| Shell | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | - |
| File R/W | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Web Search | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Web Fetch | ✅ | ✅ | ✅ | ✅ | - | ✅ | ✅ | ✅ | ✅ | ✅ | - | - |
| Browser | ✅ | ✅ | - | ✅ | - | ✅ | - | - | ✅ | ✅ | - | - |
| Memory | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | - | ✅ |
| Cron | ✅ | ✅ | ✅ | ✅ | - | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Delegate | ✅ | ✅ | ✅ | ✅ | - | - | - | - | - | - | - | - |
| Vision | ✅ | ✅ | - | ✅ | - | ✅ | - | - | - | - | - | - |
| MCP | bridge | - | ✅ | ✅ | - | ✅ | ✅ | - | - | ✅ | - | - |
| Canvas | ✅ | - | - | - | - | - | - | - | - | - | - | - |
| Screenshot | ✅ | ✅ | - | - | - | ✅ | - | - | - | - | - | - |
| PDF | ✅ | ✅ | - | - | - | - | - | - | ✅ | - | ✅ | - |
| Image Gen | - | - | - | ✅ | - | - | - | - | ✅ | - | - | - |
| Code Exec | - | - | - | ✅ | - | - | - | - | ✅ | ✅ | - | - |
