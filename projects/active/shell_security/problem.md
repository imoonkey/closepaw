# Shell Tool Security — Problem Statement

## 现状

ClosePaw 有两个 shell tool，都缺乏充分的安全加固：

### `shell`（现有 native toybox shell）

- 有 metacharacter blocklist（`;|&`><$\n\r`）和 command blocklist（`am`, `pm`, `reboot`, `su`, `env`, `xargs`, `find`）
- 但 blocklist 是 porous 的：full path 绕过（`/system/bin/am`）、`command am`、通过脚本间接调用
- 10s timeout、4KB output cap 客观上限制了危害面
- 标记为 `isScreenChanging = false`，走 auto-allow 路径

### `termux_shell`（新增，via Termux bridge daemon）

- v1 同样走 auto-allow 路径（`isScreenChanging = false`）
- 完整 bash + 完整 toolchain（python/node/git 等）
- 120s timeout、64KB output cap
- 能力面远大于 native shell：pipe/redirect、任意脚本执行、网络访问（curl/wget/pip/npm）、进程管理
- Bridge 侧暂无 command blocklist 或 workspace boundary enforcement
- Bridge v1 无 token auth——loopback-only binding，设备上其他 app 理论可连

## 需要解决的问题

### 1. Bridge Auth

v1 的 bridge daemon 监听 `127.0.0.1:18422`，无 token auth。Loopback binding 阻止了网络访问，但设备上任何 app 都能连 localhost。

**加固方案**：Bridge daemon 启动时读 token 文件（`~/.closepaw/token`），所有 endpoint 验证 `Authorization: Bearer <token>`。

**Token 生命周期**：
- Setup 时生成：`python3 -c "import secrets; print(secrets.token_hex(32))"` → 写入 `~/.closepaw/token`
- Bridge 启动时一次性读取
- ClosePaw 侧存入 EncryptedSharedPreferences，每个 OkHttp 请求带 Authorization header
- Health check 返回 wrong token 时为 401（用于检测 token mismatch）

**Token Rotation（re-setup 场景）**：
- Step 1: 生成 candidate token → `~/.closepaw/token.candidate`
- Step 2: 原子 `mv token.candidate token`
- Step 3: 启动新 bridge（读取新 token）
- Step 4: ClosePaw 用 candidate token 调 `/v1/health`
- Step 5: Health 200 → promote candidate 到 EncryptedSharedPreferences
- Health 失败 → 报告错误，旧 bridge 已被 kill，需重新 setup

**Two-phase commit**：candidate token 只在 health check 成功后才 promote。失败的 re-setup 不 break 之前能用的 bridge（旧 bridge 已 kill 但旧 token 仍在 ClosePaw 侧作为 fallback）。

**存储**：现有 `AuthStore` API 按 `LLMProvider` 键存储，不是通用 secret store。需要：
- 新建 `TermuxSecretStore`（基于 EncryptedSharedPreferences，namespaced key），或
- 扩展 `AuthStore` 加通用 secret API

### 2. Android UI Policy Bypass

`termux_shell` 可以跑 `am start`、`pm install`、`input tap`、`settings put` 等命令，绕过 ClosePaw 的 app-tier policy（NORMAL/CAUTIOUS/BLOCKED）。现有 `shell` 的 blocklist 也覆盖不全。

Blocklist 是 porous 的：full path（`/system/bin/am`）、shell function、`command am`、alias、`toybox input`、scripts、`python -c` subprocess。这是 best-effort policy，不是 hard sandbox。

### 3. Command Risk Classification

不同命令的风险完全不同：`ls` vs `rm -rf`，`git status` vs `git push --force`，`cat file` vs `curl -X POST`。目前没有 risk classification——全部 auto-allow。

需要的分类维度：
- READ（无副作用）
- WORKSPACE_WRITE（本地文件变更）
- COMMAND_SAFE（build/test）
- COMMAND_MUTATE（git commit、mv、chmod）
- NETWORK（curl、pip install、npm install）
- PUBLISH（git push、npm publish）
- DESTRUCTIVE（rm -rf、git reset --hard）
- SYSTEM（am、pm、su、settings、input）
- SECRET_ACCESS（.env、*.pem、credentials）

PolicyEngine 集成方式两个选项：
- **Option A**：在现有 `PolicyEngine` 中加 `checkWorkspace()`，按 tool name dispatch
- **Option B**：在 invocation 上引入 `ToolPolicyMetadata`，支持参数级别 risk classification

关键问题：`isScreenChanging = false` 的 auto-allow 逻辑需要加 workspace 分支。`termux_shell` 不该走 auto-allow，但也不是 screen-changing。需要第三个类别或显式 bypass。

### 4. Workspace Boundary

`termux_shell` 的 cwd 可以设为 workspace root，但 bash 命令可以访问任意路径。File tools（如果后续实现）可以做硬边界（bridge canonicalize 路径），但 shell 命令是软边界。

### 5. Secrets Exposure

Shell 输出可能包含 API keys、tokens、passwords、SSH keys。这些进入 LLM history、trace、UI 显示。

加固方案：
- 扫描 `(?i)(api[_-]?key|secret|password|token|credential)\s*[=:]\s*\S+`
- 已知 secret 文件（.env、.npmrc、*.pem、~/.ssh/*）触发 approval 或 redact
- Bridge log redact secret patterns
- `ToolObservation.TextOutput` 持久化前 redact
- 这是 usability guard，不是 security boundary

## 涉及的代码

- `tool/impl/ShellTool.kt` — 现有 blocklist 和 metacharacter 检查
- `tool/PolicyEngine.kt` — `isScreenChanging = false` 的 auto-allow 逻辑
- `tool/ToolRouter.kt` — tool execution lifecycle
- `tool/ToolName.kt` — tool metadata
- 新增 `tool/impl/TermuxShellTool.kt` — 无安全检查
- 新增 bridge daemon — 无 auth、无 command filtering
- `AuthStore` 或新建 `TermuxSecretStore` — token 存储

## 约束

- 这不是一个 hard sandbox 问题。任意 bash 无法通过 blocklist 完全限制。
- 目标是 policy-controlled best-effort：classify commands → auto-allow/ask/deny。
- 与 PolicyEngine 的现有 approval flow（AskUser → deferred → user responds）集成。
- 两个 shell tool 的安全模型应统一设计，不分别处理。

## 状态

Deferred。v1 先发布 `termux_shell` 功能（无 auth、auto-allow）。安全加固作为后续独立项目。
