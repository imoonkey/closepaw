# OpenClaw 安全模型分析

基于 `.reference/claws/openclaw/` 源码的深度分析。

## 项目概述

OpenClaw 是一个 personal AI assistant platform，运行在用户自己的设备上，通过多种 messaging channel（WhatsApp、Telegram、Slack、Discord 等）交互。核心架构：Gateway（control plane）+ Agent（execution）+ Plugin（扩展），TypeScript 实现。

关键定位：**single-user trusted-operator model**，即一个 gateway 实例服务一个受信用户（operator），不是 multi-tenant 系统。

## 安全模型

### 1. Tool Permission — 多层 Policy Pipeline

OpenClaw 的 tool 权限是整个安全模型的核心，采用 **pipeline 式多层过滤**：

```
Tool Profile → Provider Profile → Global Policy → Agent Policy → Group Policy
       ↓              ↓                ↓              ↓              ↓
    allow/deny      allow/deny       allow/deny     allow/deny     allow/deny
```

**Tool Profiles**（`tool-catalog.ts`）：4 个预定义级别
- `minimal`：仅 `session_status`
- `coding`：read/write/edit/exec/process/web_search 等开发工具
- `messaging`：sessions_list/history/send/message 等通信工具
- `full`：无限制

**Policy 数据结构**：
```typescript
type ToolPolicyLike = { allow?: string[]; deny?: string[] };
```

每层可独立设置 allow/deny list，pipeline 按顺序过滤，最终确定可用 tool 集合。

### 2. Exec Approval — 命令执行审批系统

这是 OpenClaw 最精细的安全机制（`exec-approvals.ts`）：

**三维配置空间**：
- `ExecSecurity`: `"deny"` | `"allowlist"` | `"full"` — 控制 exec 权限级别
- `ExecAsk`: `"off"` | `"on-miss"` | `"always"` — 控制是否需要人工审批
- `ExecHost`: `"sandbox"` | `"gateway"` | `"node"` — 控制执行位置

**审批流程**（`bash-tools.exec-approval-request.ts`）：
1. Agent 请求执行命令
2. 系统注册审批请求（two-phase：先注册 ID 再等待 decision，防止 race condition）
3. 审批请求转发给 operator（可通过 chat channel 或 UI）
4. Operator approve/deny
5. 有超时机制（默认 120s），超时 fallback 到 `askFallback` security level

**Allowlist 持久化**：已批准的命令模式持久存储，支持 pattern matching，避免重复审批。

**SafeBin 策略**（`exec-safe-bin-policy.ts`）：预定义安全二进制文件列表（如 `ls`、`cat`、`git`），这些工具在 trusted path 下执行时可跳过审批。包含参数验证（`validateSafeBinArgv`）防止通过安全命令执行危险操作。

### 3. Dangerous Tool Classification

**Gateway HTTP 默认拒绝列表**（`dangerous-tools.ts`）：
```typescript
DEFAULT_GATEWAY_HTTP_TOOL_DENY = [
  "sessions_spawn",  // 远程 RCE 风险
  "sessions_send",   // 跨 session 注入
  "cron",            // 持久化自动化
  "gateway",         // 控制面重配置
  "whatsapp_login",  // 需要交互式设置
]
```

**ACP（Automation Control Plane）危险 tool 列表**：
```typescript
DANGEROUS_ACP_TOOLS = [
  "exec", "spawn", "shell",        // 命令执行
  "sessions_spawn", "sessions_send", "gateway",  // 编排/控制面
  "fs_write", "fs_delete", "fs_move", "apply_patch",  // 文件变更
]
```
这些 tool 在自动化场景下始终需要显式审批。

**Owner-Only Tools**：`whatsapp_login`、`cron`、`gateway`、`nodes` 只有 owner 可以使用，non-owner sender 被直接过滤掉。

### 4. Sandbox — Docker 容器隔离

**Sandbox 模式**（`types.sandbox.ts`）：
- `off`（默认）：直接在 gateway host 执行
- `non-main`：非主 agent 使用 sandbox
- `all`：所有 agent 均使用 sandbox

**Docker sandbox 安全配置**：
- `readOnlyRoot`：只读根文件系统
- `capDrop`：丢弃 Linux capabilities
- `pidsLimit` / `memory` / `cpus`：资源限制
- `seccompProfile` / `apparmorProfile`：内核级安全策略
- `network`：网络隔离（bridge/none/custom）
- `user`：非 root 用户运行

**Workspace 隔离**：sandbox 有独立的 workspace 目录，通过 `workspaceAccess` 控制读写（`rw` 或只读 copy）。

**路径安全**（`path-policy.ts`、`boundary-file-read.ts`）：
- `toRelativeWorkspacePath()` / `toRelativeSandboxPath()` — 防止路径遍历（path traversal）
- `workspaceOnly` 模式限制文件操作在 workspace 内
- 符号链接检查、hard link 检查、TOCTOU 防护

### 5. Sub-Agent 权限限制

Sub-agent 有独立的 tool 限制策略（`pi-tools.policy.ts`）：

**始终拒绝**（所有 sub-agent）：
```
gateway, agents_list, whatsapp_login, session_status,
cron, memory_search, memory_get, sessions_send
```

**叶子节点额外拒绝**（不能再 spawn 子 agent）：
```
subagents, sessions_list, sessions_history, sessions_spawn
```

这形成了一个 **depth-based permission degradation** 模型：越深层的 sub-agent 权限越少。

### 6. External Content 安全 — Prompt Injection 防护

**外部内容标记**（`external-content.ts`）：
- 所有外部来源（email、webhook、web_fetch 等）的内容用随机 ID 的 boundary marker 包裹
- 注入安全警告提示 LLM 不要执行外部内容中的指令
- **Marker spoofing 防护**：检测并替换 Unicode 同形字符（fullwidth、CJK angle brackets 等）伪造的 boundary marker
- 对 invisible format characters（zero-width space 等）进行 strip

**可疑 pattern 检测**：
```
"ignore previous instructions", "you are now a", "system: override",
"rm -rf", "delete all emails", "<system>", "[System Message]"
```

### 7. DM Policy — 发送者身份验证

多层 access control for messaging（`dm-policy-shared.ts`）：

- `dmPolicy`: `"open"` | `"pairing"` | `"allowlist"` | `"disabled"` — 控制 DM 接入
- `groupPolicy`: `"open"` | `"allowlist"` | `"disabled"` — 控制群聊接入
- `allowFrom` 白名单 + pairing store（动态授权）
- Owner/non-owner 区分：owner-only commands 对非 owner 不可见

### 8. Security Audit 系统

内置安全审计工具（`audit.ts`）：
- `openclaw security audit` / `openclaw security audit --deep`
- 检查：配置权限、dangerous flag、exec approval 设置、gateway 绑定地址、sandbox 配置
- Severity 分级：`info` | `warn` | `critical`
- 自动修复建议（`fix.ts`）

**Dangerous Config Flags 检测**（`dangerous-config-flags.ts`）：
```
gateway.controlUi.allowInsecureAuth
gateway.controlUi.dangerouslyDisableDeviceAuth
hooks.gmail.allowUnsafeExternalContent
tools.exec.applyPatch.workspaceOnly=false
```

### 9. Skill Scanner — 第三方代码审计

对安装的 skill/plugin 进行静态分析（`skill-scanner.ts`）：
- `dangerous-exec`：child_process 调用检测
- `dynamic-code-execution`：eval/new Function 检测
- `crypto-mining`：挖矿特征检测
- `potential-exfiltration`：文件读取 + 网络发送组合检测
- `env-harvesting`：环境变量 + 网络发送检测
- `obfuscated-code`：hex/base64 混淆检测

### 10. SSRF 防护

网络请求安全（`ssrf.ts`）：
- 阻止对 `localhost`、`metadata.google.internal` 等的请求
- IP 地址分类检查（private、special-use、loopback）
- DNS rebinding 防护（解析后再次验证 IP）
- Hostname allowlist 机制

### 11. Secret 管理

- 配置中的 secret 支持 `SecretInput` 类型（可引用 env var 或 secret store）
- `detect-secrets` CI 集成
- Config snapshot redaction（`redact-snapshot.ts`）
- 日志 redaction patterns

## 与 Android Agent 的对比/启发

| 维度 | OpenClaw | Android Agent | 差距/启发 |
|------|----------|---------------|-----------|
| Tool 权限 | 多层 pipeline + profile 预设 | 无（全部 tool 可用） | 需要 tool policy 机制 |
| 执行审批 | 精细的 exec approval + allowlist | 无 | 高风险操作需人工确认 |
| 风险分类 | dangerous tools + owner-only | 无 | 需要 action risk tier |
| Sandbox | Docker 容器隔离 | N/A（在 Android 内） | Android sandbox = accessibility service 边界 |
| External content | boundary marker + injection 检测 | 无 | LLM 输入需要安全标记 |
| Sub-agent | depth-based permission degradation | 无 sub-agent | 未来扩展方向 |
| 审计 | 内置 security audit CLI | 无 | 需要配置安全检查 |
| SSRF | DNS + IP + hostname 检查 | N/A | 如有网络工具需考虑 |

## 可借鉴的设计

### P0 — 立即可用

1. **Tool Risk Tier**：将现有 tool 按风险分级
   - Tier 0（观察）：`perceive_screen`、`wait`
   - Tier 1（导航）：`tap`、`swipe`、`scroll`、`go_back`、`press_home`
   - Tier 2（输入）：`type_text`、`set_text`
   - Tier 3（系统）：`open_app`、`shell`
   - Tier 4（敏感）：涉及金融/认证/隐私 app 的操作

2. **App Risk Classification**：按包名/类别对 app 分级
   - 低风险：Settings、Calculator 等
   - 中风险：浏览器、文件管理器
   - 高风险：银行、支付、密码管理器
   - 禁止：企业 MDM、安全相关 app

3. **Action Confirmation**：高风险操作前请求用户确认
   - 参考 OpenClaw 的 `ExecAsk` 三档设计：`off` / `on-miss` / `always`
   - 金融操作、密码输入、权限授予默认 `always`

### P1 — 短期可实现

4. **Dangerous Action Detection**：参考 OpenClaw 的 suspicious pattern 检测
   - 检测 LLM 输出中的危险意图（如 "卸载应用"、"清除数据"、"授予权限"）
   - 在执行前 intercept

5. **External Content Wrapping**：来自屏幕的文本（可能含恶意内容）传给 LLM 时加安全标记
   - 参考 OpenClaw 的 `wrapExternalContent()` 模式
   - 防止屏幕上的 prompt injection 内容被 LLM 当作指令执行

6. **Security Audit 配置检查**：启动时检查配置安全性
   - Accessibility service 权限状态
   - API key 来源（env vs hardcode）
   - 高风险 app 交互策略

### P2 — 中期方向

7. **Observation Gating**：参考 OpenClaw 的 path boundary 概念
   - 在感知层过滤敏感信息（密码字段、银行余额等）
   - Accessibility tree 节点根据 `isPassword`、app context 做 redaction
   - 防止 LLM 观察到不该看到的数据

8. **Policy Engine**：声明式策略配置
   ```yaml
   tool_policy:
     profile: "standard"  # minimal | standard | full
     app_overrides:
       com.bank.app:
         tier: "restricted"
         require_confirmation: always
         observation_gating: redact_sensitive
   ```

9. **Session Transcript Redaction**：参考 OpenClaw 的 `redact-snapshot.ts`
   - 会话日志中自动 redact 密码、token、金融数据
   - 防止敏感数据泄露到日志/history

## 调研日期

2026-03-24

## 源码路径

- Security 核心：`src/security/`
- Tool policy：`src/agents/tool-policy*.ts`、`src/agents/tool-catalog.ts`
- Exec approval：`src/infra/exec-approvals*.ts`、`src/agents/bash-tools.exec-approval*.ts`
- Sandbox：`src/agents/sandbox/`
- External content：`src/security/external-content.ts`
- DM policy：`src/security/dm-policy-shared.ts`
- SSRF：`src/infra/net/ssrf.ts`
- Skill scanner：`src/security/skill-scanner.ts`
