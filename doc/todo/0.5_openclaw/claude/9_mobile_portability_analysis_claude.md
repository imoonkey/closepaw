# OpenClaw 移植到手机端的可行性分析

## 核心问题

OpenClaw 有什么必须跑在电脑上的原因？能否把能力完全移植到手机端？

## 硬限制（手机上做不了或代价极高）

### 1. WhatsApp Web 渠道
OpenClaw 的 WhatsApp 支持是跑 Puppeteer 控制完整的 Chromium 实例，模拟 WhatsApp Web 登录：
- 需要常驻 ~300-500MB 内存的 headless Chrome
- 需要持久化的 browser session + cookie
- Android 上没有 Puppeteer/Playwright 等价物
- **结论：不可移植**。但手机上可以用无障碍服务直接操作 WhatsApp App，反而比 OpenClaw 的方案更原生

### 2. Docker Sandbox
OpenClaw 的三轴安全模型里，Sandbox 维度依赖 Docker 隔离执行环境：
- Android 上没有 Docker
- **结论：维度不适用**。但手机天然有 App Sandbox，Android 的权限模型本身就是隔离的。需要的是 tool 层面的权限控制，不需要容器

### 3. Gateway 作为常驻服务器
OpenClaw 的 Gateway 是接受 WebSocket 入站连接的 Node.js 服务：
- 手机上跑 server 被 NAT/防火墙挡住，外部无法主动连入
- Android 激进杀后台进程，常驻 WS server 会被系统干掉
- 电池消耗不可接受
- **结论：架构上不能照搬**。手机端做 client 连外部 server 是可行的（反转 C/S 关系）

### 4. Browser Automation
OpenClaw 用 Playwright 控制浏览器页面做自动化：
- 手机上没有 Playwright
- **结论：不可移植**。但无障碍服务可以操作手机上的浏览器 App，覆盖大部分场景

### 5. 多渠道 Bot 长连接
Telegram Bot、Discord Bot、Slack Bot 都需要持久的 WebSocket/HTTP 长连接：
- Android 杀后台后连接断开
- 用 Foreground Service 可以勉强维持，但耗电
- **结论：可以做但体验差**。更实际的方案是用外部轻量 relay server 接收消息，push 到手机端

## 软限制（能做，但需要改造）

### 6. Node.js/TypeScript 运行时
OpenClaw 整个 Gateway + 插件 + 渠道 ~37k LOC TypeScript：
- Android 上不能直接跑 Node.js
- 嵌入 V8/QuickJS 可以跑 JS，但性能和集成成本高
- **结论：Kotlin 重写更实际**。不需要全部重写 — 很多模块（渠道集成、浏览器控制）在手机上本来就不需要

### 7. Shell/Exec Tool
OpenClaw 可以让 agent 执行 shell 命令：
- Android 上无 root 下能执行的命令非常有限
- 没有通用的包管理器、编译器等
- **结论：极度缩水**。能做基本的文件读写，但不能指望像桌面一样 `apt install && python script.py`

### 8. 插件生态（npm 分发）
OpenClaw 的 extensions 是 npm workspace packages：
- 手机上没有 npm
- **结论：需要自己的插件机制**。skill/plugin 可以是 Kotlin 模块或 prompt + tool 定义文件，不需要 npm

## 没有限制（手机上同样好或更好）

| 能力 | 手机上的情况 |
|------|------------|
| LLM API 调用 | 完全一样，HTTP 请求 |
| Session 管理 + JSONL 存储 | SQLite/文件系统，Android 原生支持 |
| Memory 系统 (Markdown + FTS) | SQLite FTS 在 Android 上是一等公民 |
| 语音 (STT/TTS) | Android SpeechRecognizer + TextToSpeech，原生免费 |
| 屏幕感知 | 无障碍服务 > OpenClaw 的桌面方案 |
| App 操作 | 无障碍服务 > OpenClaw 的桌面方案 |
| 摄像头/GPS/传感器 | 手机的核心优势 |
| 通知读取/拦截 | NotificationListenerService，手机独有能力 |
| Compaction/Pruning | 纯算法，平台无关 |
| Tool 风险分级 | 纯逻辑，平台无关 |
| Agent Identity 模板 | 文件配置，平台无关 |

## 结论

OpenClaw 里**真正必须跑在电脑上的**只有 3 件事：
1. **WhatsApp Web (Puppeteer)** — 但用无障碍操作 WhatsApp App 反而更好
2. **Docker Sandbox** — 但 Android 自带 App Sandbox
3. **Gateway 做入站 Server** — 但反转成 client 模式就行

其他所有核心能力（session 体系、memory、compaction、pruning、voice、tool 系统、agent 编排）都是平台无关的逻辑，完全可以在 Kotlin 上实现。

## 最关键的洞察

OpenClaw 用 "电脑 Gateway + 手机 Node" 的架构，本质上是因为桌面能力强、手机只是传感器。但 Android Agent 恰好反过来 — 手机是主战场（无障碍服务、App 操作、传感器），不需要桌面做 Gateway。

要做的不是"把 OpenClaw 搬到手机上"，而是**把 OpenClaw 里平台无关的好设计（session、memory、compaction、security model）吸收进来，架构保持手机原生**。
