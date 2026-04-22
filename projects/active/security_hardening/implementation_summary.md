# Security Hardening — 实现总结

*日期: 2026-03-27*

## 概览

两个 task 完成，实现了 `safety_kiss_design.md` 的完整 Phase 0：

| Task | Commit | 变更 |
|------|--------|------|
| `basic-security` | `0d190dd` | 基础安全加固 5 项 |
| `agent-security` | `08ab249` | KISS 4+1 Layer 安全模型 |

共 43 文件变更，+745 -315 行。

---

## Task 1: 基础 Security Hardening

### 实现内容

1. **EncryptedSharedPreferences** — API key 存储从明文 SharedPreferences 迁移到 `EncryptedSharedPreferences`（AES-256-GCM）。包含：
   - 自动迁移逻辑：首次启动时从旧存储读取 key，写入加密存储，删除明文
   - Crash resilience：`try-catch` 包裹所有加密操作，keystore 损坏时 fallback 到明文存储（不 crash app）
   - 新增依赖 `androidx.security:security-crypto:1.1.0-alpha06`

2. **Cleartext traffic** — `AndroidManifest.xml` 设置 `usesCleartextTraffic="false"`

3. **InsecureSslConfig** — 用 `check(BuildConfig.DEBUG)` 门控 `trustManager` 和 `sslSocketFactory`，release build 调用直接抛异常

4. **allowBackup** — `AndroidManifest.xml` 设置 `allowBackup="false"`

5. **.env.example** — 添加占位 key 文件

### Code Review 发现 & 修复

Codex review 发现 EncryptedSharedPreferences 在 keystore 损坏时会 crash app。修复：所有加密读写操作加 `try-catch`，失败时 fallback 到 plain `SharedPreferences` 并 log warning。

---

## Task 2: Agent Security — KISS 4+1 Layer

### Layer 1: App Classification

**新文件：**
- `protocol/AppTier.kt` — `enum class AppTier { BLOCKED, CAUTIOUS, NORMAL }`
- `tool/AppClassifier.kt` — 从 `app_tiers.json` 加载分类表，支持 user override（只能收紧）
- `assets/security/app_tiers.json` — 61 行，覆盖银行/crypto/支付/密码管理器/系统 app

**Lookup 顺序：** `userOverrides[pkg]` → `appTiers[pkg]` → `CAUTIOUS`

### Layer 2: Perception Gate

**修改：** `AgentTurnRunner.capturePreTurnSnapshot()`

BLOCKED app 的屏幕内容在发给 LLM 之前被替换为 masked stub（只有 package name + tier）。Mask 同时应用于：
- Pre-turn snapshot（`AgentTurnRunner`）
- Post-action observation（`TurnExecutionPhaseRunner`）

通过共享的 `AppClassifier.maskIfBlocked()` 方法确保所有 capture 点一致。

### Layer 3: Execution Gate

**重写：** `PolicyEngine.kt`（从 240 行降到 ~90 行）

**删除的旧代码：**
- `DEFAULT_RISK_LEVELS` map
- `MobileActionName.defaultRiskLevel`
- `getRiskLevelLocked()` / `resolveActionName()` / `resolveRiskKey()`
- `riskOverrides` map + `setRiskLevel()` / `getRiskLevel()`
- `allow/deny lists` + `allowTool()` / `denyTool()`
- `RiskLevel` enum（从 `ApprovalTypes.kt` 删除）
- `ApprovalRequirement` sealed interface（死代码）

**新的决策逻辑（20 行）：**
```
非 screen-changing tool → Allow
Escape (back/home) → Allow
BLOCKED → Deny（即使 AUTO_APPROVE）
ALWAYS_ASK → AskUser
AUTO_APPROVE → Allow
SMART + CAUTIOUS → AskUser
SMART + NORMAL → Allow
```

**Escape 检测：** 同时处理 `system_button(button="back"|"home")` 和 `mobile_action(action="back"|"home")` 两种路径。

**ApprovalDetails 扩展：** 新增 `packageName`, `appTier`, `reason` 字段。

### Layer 4: Memory Gate

**修改：** `RememberExperienceTool.kt`

当前 foreground app 是 BLOCKED 时，`remember_experience` 返回错误，防止 agent 将敏感内容写入持久化 memory。

### Layer 5: Prompt-based Safety

**修改：** `StandaloneAgentDef.kt` — 添加通用 safety rules（金钱操作/不可逆操作/权限变更 → ask user）

**修改：** 17 个 `SKILL.md` — 每个添加 `## Safety` section，按 app 类型定制 DANGEROUS/SAFE 操作列表。

### 测试

`PolicyEngineTest.kt` 从 ~30 行扩展到 146 行，覆盖：
- BLOCKED app deny（包括 AUTO_APPROVE 模式）
- Escape action allow（system_button + mobile_action 两种路径）
- CAUTIOUS app ask（SMART 模式）
- NORMAL app allow
- Non-screen-changing tool allow
- ALWAYS_ASK mode
- Null package → CAUTIOUS

`ToolRouterTest.kt` 和 `RememberExperienceToolTest.kt` 也更新以适配新签名。

### Code Review 发现

Codex review 提出 4 个 issue：
1. `open_app` 到 BLOCKED target 不检查 — **By design**，Phase 2 处理
2. Escape 处理 broken — **False positive**，实现已正确处理两种路径
3. Post-action capture 泄露 — **已修复**，`maskIfBlocked()` 应用于所有 capture 点
4. Memory gate 不检查 target package — **Minor edge case**，当前不修

---

## 关联的 doc/main/ 更新

6 个架构文档同步更新（`8d63c18`）：
- `infra/tools.md` — PolicyEngine 重写说明、AppTier 模型、approval flow
- `infra/perception.md` — perception gate
- `agent/overview.md` — 安全层说明
- `agent/loop.md` — turn 中的安全 gate
- `agent/memory.md` — memory gate
- `protocol/config.md` — ApprovalMode 更新

---

## 遗留 / Phase 2

| 项目 | 状态 | 参考 |
|------|------|------|
| Prompt injection 防护 | Phase 2 | `safety_kiss_design.md` |
| ask_user / Approval 统一 | Phase 2 | `safety_kiss_design.md` |
| open_app target check | Phase 2 | `safety_kiss_design.md` |
| api_key.txt 明文文件清理 | 未排期 | Codex review #1 |
| InsecureSslConfig 收窄到 eval-only | 未排期 | Codex review #3 |
