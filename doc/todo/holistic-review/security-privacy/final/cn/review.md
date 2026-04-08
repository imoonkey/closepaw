# 安全与隐私审查 (已对齐)

**日期:** 2026-04-08
**作者:** Claude (初始 + 交叉审查), Codex (初始 + 交叉审查)
**状态:** FINAL (经过 2 轮对齐)
**基础:** Codex 架构框架与 Claude 细粒度发现合并

---

## 总体评估

代码库具备良好的安全基础原语: `allowBackup="false"`、release 构建禁用明文传输、EncryptedSharedPreferences 作为默认凭据存储、blocked/cautious/normal 应用分类器且用户覆盖只能收紧策略、审批系统中的 TOCTOU 重检、OAuth 中的 PKCE+CSRF、以及 UI 输入的密码遮罩。

问题不在于缺少基础原语, 而在于**边界放置与权限组合**:

1. 对导出的 launcher intent 给予了过多信任
2. 隐私遮罩发生在捕获之后而非之前
3. 同一个 accessibility payload 被复用于 prompting、history、traces 和 debug, 却没有字段级隐私策略
4. Secret 存储在失败时静默降级为明文
5. 基于屏幕的审批模型管控的却是文件/系统级工具

当前设计属于开发阶段水平, 尚未达到分发级别的加固标准。

---

## 架构与信任边界

### 控制平面
`MainActivity` 作为 launcher 被导出。它同时解析并应用安全敏感的 intent extras: API key、base URL 覆盖、agent 模式、backend、debug/trace 标志、排除的工具、最大轮次和目标文本。其中多项会被持久化; 目标文本会触发自动执行。

### 特权自动化平面
Accessibility service 获取窗口内容、截取屏幕截图并执行手势。虚拟显示模式通过 Shizuku 中介增加了显示和 shell 级能力。

### 感知与 Prompting 平面
`AccessibilityPlatform.captureScreen()` 和虚拟显示栈收集 accessibility tree 和截图, 转换为 `ScreenSnapshot` 和 `Perceptor.toPromptJson()`。该数据流向: 实时 LLM 请求、屏幕观察历史、action 后观察、trace 制品和 debug 制品。

### 持久化平面
- **Secrets:** `AppSettingsStore`、`OAuthCredentialStore`、`OnboardingStore` (均为 encrypted prefs, 带明文 fallback)
- **Session 状态:** 内部应用存储下的 JSON 和 context snapshots; 内部存储下的 memory markdown
- **Traces/debug:** `getExternalFilesDir()` 下的 JSONL、原始 tree、sanitized tree、截图

### 网络平面
Release 构建禁用明文传输。云端流量发往 OpenAI/OpenRouter/Novita 或 base URL 覆盖。OAuth 使用端口 1455 上的本地 HTTP callback listener。Debug 构建可禁用 TLS 验证。

---

## 发现

### CRITICAL-1: 导出的 Launcher 作为未认证的控制平面

**来源:** Codex Finding 1 + Claude Finding 6
**文件:** `AndroidManifest.xml:29-37`, `MainActivityIntentPayload.kt:28-147`, `MainActivityIntentApplier.kt:17-86`, `MainActivity.kt:280-317`, `SessionConfig.kt:71-77`, `AgentDefRegistry.kt:5-10`, `StandaloneAgentDef.kt:8-20`

任何共安装的应用都可以向导出的 `MainActivity` 发送 intent, 从而:
- 覆盖 API key 和 base URL (将 LLM 流量路由至攻击者)
- 切换到 BASIC 模式 (启用 shell tool)
- 启用 debug/trace 标志
- 通过 `handleIntent()` 自动下发目标
- 持久化 backend、model、agent 模式及其他安全敏感设置

这不仅仅是一个 URL 投毒漏洞。它是一个完整的未认证本地控制平面, 并与已授予的 accessibility 权限相结合。

**影响:** 不受信任的本地调用方可完全重配 LLM 路由和 agent 行为。通过重定向的 API 流量实现下游数据外泄。

### CRITICAL-2: Blocked App 隐私拦截发生在捕获之后

**来源:** Codex Finding 2 (Claude 完全遗漏了此项)
**文件:** `AccessibilityPlatform.kt:61-102,157-187`, `AccessibilityScreenshotCapturer.kt:157-196`, `TraceRecorderFactory.kt:12-22`, `AgentTurnRunner.kt:143-160`, `OpenAppTool.kt:203-220`, `UIActionInvocation.kt:74-84`, `ObservationBuilder.kt:13-28`, `PostActionAnalysis.kt:17-73`

`captureScreen()` 在任何 blocked app 遮罩之前就捕获 tree 和截图并写入原始/sanitized trace 制品。`AgentTurnRunner` 仅遮罩返回的 `ScreenSnapshot`。`OpenAppTool`、`UIActionInvocation` 和 `PostActionAnalysis` 中的 action 后观察从原始 snapshot 捕获, 未经遮罩。

一个被标记为 blocked 的银行/密码管理器屏幕会通过以下途径泄漏: 原始 tree 制品、sanitized tree 制品、截图以及 action 后观察。

**影响:** 用户可见的策略声称 blocked app 内容已隐藏, 但实际上仍可被持久化和处理。

### HIGH-1: Accessibility 数据未进行隐私清洗

**来源:** Codex Finding 3 (Claude 承认此为差距)
**文件:** `agent_accessibility_config.xml:2-10`, `Perceptor.kt:243-317`, `Models.kt:108-127`, `TurnPlanningPhaseRunner.kt:181-205`, `SessionRuntimeSnapshot.kt:6-54`

`PerceptionElement` 携带原始 text、contentDescription、hintText、resourceId 和 range 值, 但没有 `isPassword` 或敏感度元数据。`Perceptor.toPromptJson()` 将同一模型序列化用于: 实时 LLM prompt、屏幕观察历史、checkpoint 持久化和 trace 制品。

这意味着密码字段、OTP 验证码、已输入但未发送的消息、联系人以及非 blocked 应用中的金融数据, 都会流向云端 LLM 并在没有任何字段级隐私策略的情况下被持久化。

**影响:** 敏感屏幕数据在没有隐私控制的情况下离开设备或被持久存储。

### HIGH-2: Secret 存储失败时静默降级为明文

**来源:** Claude Finding 1 + Codex Finding 4
**文件:** `AppSettingsStore.kt:75-112,133-138`, `OAuthCredentialStore.kt:31-66`, `OnboardingStore.kt:43-61`

所有凭据存储在 `EncryptedSharedPreferences` 失败时, 都会静默降级为普通 `SharedPreferences`。OAuth refresh token、access token、id token 和 API key 以明文持久化, 且不通知用户。

**影响:** 在物理访问或 root exploit 的情况下, 凭据完全暴露。

### HIGH-3: id_token Claims 和 PII 输出到 Logcat

**来源:** Claude Finding 2 + Codex Finding 7
**文件:** `OpenAIOAuth.kt:198-210`, `OpenAiSignIn.kt:70-77`, `LlmLogger.kt:12-96`

OAuth 代码记录了解码后的 id_token claims (email、account ID、组织成员信息)。`OpenAiSignIn` 记录用户 email。`LlmLogger` 在 debug 构建中记录 prompt、input items、tool call 参数和 response。

**影响:** PII 通过 logcat 暴露。任何具有 READ_LOGS 权限的进程或运行 adb logcat 的开发者都可以获取账户详情和屏幕内容。

### HIGH-4: API Key 从全局可读的外部存储加载

**来源:** Claude Finding 3 + Codex 确认
**文件:** `AppSettingsStore.kt:299-317`

`loadApiKeyFromFile()` 从 `/sdcard/api_key.txt` 读取 (在 Android 10 及以下版本全局可读)。读取的 key 被静默持久化到同一个失败时降级为明文的加密存储中。

**影响:** 任何共安装的应用都可以窃取 API key 或注入恶意 key。

### MEDIUM-1: Shell Tool 能力与审批模型不匹配

**来源:** Codex Finding 5 + Claude Finding 5
**文件:** `StandaloneAgentDef.kt:8-20`, `ShellTool.kt:38-120`, `PolicyEngine.kt:43-79`

Shell 在 BASIC 模式下暴露。blocklist 仅验证第一个 token; 命令通过 `sh -c` 执行, 具有完整的 shell 解释能力。但即使输入验证做到完美, 前台应用审批模型对于一个能够访问应用私有文件、memory、traces 和 preferences 的工具来说, 本身就是错误的。

**影响:** 通过 prompt injection 实现任意命令执行。策略模型无法约束真实的能力边界。

### MEDIUM-2: InsecureSslConfig 应当仅在编译时用于 Debug

**来源:** Claude Finding 4 + Codex Finding 6
**文件:** `InsecureSslConfig.kt:20-48`, `ChatCompletionClient.kt:42-43`, `OpenAIResponseClient.kt:46-47`, `CodexResponseClient.kt:229`

当前的 `BuildConfig.DEBUG` 检查是运行时守卫, 而非编译时排除。问题不在于 release 流量目前已经绕过了 TLS, 而在于不安全的辅助类仍然在主 source set 中, 未来的意外链接或误用可能使其在 release 中可达。这应当在编译时就不可能发生。

**影响:** 如果不安全的辅助类被接入 release 客户端, 所有 LLM 流量都将可被 MITM。

### MEDIUM-3: AppClassifier 在资源缺失时开放式失败

**来源:** Claude Finding 8
**文件:** `AppClassifier.kt:66-73`

如果 `app_tiers.json` 加载失败, `fromAssets()` 返回一个空分类器。所有应用变为 `CAUTIOUS`。在 `AUTO_APPROVE` 模式下, 银行类应用会被自动批准。

**影响:** Agent 在没有安全门控的情况下操作金融类应用。

### MEDIUM-4: OAuth Callback 使用本地 HTTP Listener 而非 Android 原生重定向

**来源:** Claude Finding 7 + Codex P2.1
**文件:** `OpenAIOAuth.kt:87-88,99-150`

OAuth callback 使用 `ServerSocket(1455)` 并接受第一个入站连接。高熵的 `state` 参数使 auth code 窃取变得困难, 但单次接受的 HTTP listener 仍可被本地攻击者消耗以拒绝登录。该模式也弱于标准的 Android OAuth 边界。如果暂时保留, socket 应当显式绑定到 loopback 并在接受 callback 前验证请求形状和 host。

**影响:** 本地登录拒绝服务, 以及弱于标准的 Android OAuth 边界。代码窃取风险受 state 验证限制。

### MEDIUM-5: 权限模型范围超出必要

**来源:** Codex Finding 8
**文件:** `AndroidManifest.xml:4-10`

遗留的 `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` 权限仍然存在, 尽管大部分使用的是应用私有存储。写入到 `getExternalFilesDir()` 的 trace 比内部存储的保障等级更低。

**影响:** 权限面令人困惑; 在旧版 Android 上, trace 可通过备份或其他应用访问。

### LOW-1: 外部存储上的 Trace 文件未完全脱敏

**来源:** Claude Finding 10
**文件:** `FileTraceRecorder.kt:47-48`, `AgentTraceArtifacts.kt:181-193`, `CognitionTraceRedactor.kt`

截图以原始 JPEG 存储, 未进行脱敏。电话号码和金融金额未被文本脱敏器覆盖。Trace 目录可能位于外部存储。

### LOW-2: Debug Broadcast Receiver 被导出

**来源:** Claude Finding 12
**文件:** `AgentServiceReceiverHelpers.kt:10-18,30-38`

`STOP_AGENT` 和 `ACTION_DEBUG_EXEC` receiver 在 debug 构建中被导出。任何应用都可以停止 agent 或触发 debug action。

### LOW-3: Shizuku Provider 被导出

**来源:** Claude Finding 13
**文件:** `AndroidManifest.xml:59-65`

标准 Shizuku 集成模式。`INTERACT_ACROSS_USERS_FULL` signature 权限限制了攻击面。

---

## 积极发现

1. **网络安全配置正确** - 明文传输已禁用, 无 debug 覆盖
2. **ToolRouter 中的 TOCTOU 守卫** - 在审批等待后重新检查前台 package
3. **Trace 脱敏器覆盖面良好** - email、bearer token、JWT、敏感键值对
4. **API key 字段使用密码遮罩** - `PasswordVisualTransformation()` 带切换功能
5. **AppClassifier 只能收紧** - 用户不能弱化内置的层级分类
6. **PKCE 和 CSRF state 正确实现** - S256 + 16 字节 SecureRandom state
7. **allowBackup=false** - 减少基于备份的泄漏

---

## 设计决定 (对齐过程中解决)

1. **Shell 处置:** 从生产 agent 模式中移除。如保留则仅限 debug/dev, 并采用 argv-only 执行、binary allowlist、filesystem allowlist 和独立的数据访问策略。
2. **可编辑字段抑制:** 按受众拆分 serializer。密码字段始终脱敏。非密码可编辑文本从 history/checkpoints/traces 中脱敏。在实时 prompt 中, 仅对当前聚焦的可编辑字段保留原始文本; 其他所有可编辑字段使用结构 + non-empty/length 标记。
3. **AppClassifier 关闭式失败:** 加载失败时终止 session 启动。不设特殊 escape 路径。运行时 back/home 例外保持不变。
4. **降级存储:** 仅在当前 session 内使用内存存储。绝不明文持久化。重启需要重新输入或重新认证。
