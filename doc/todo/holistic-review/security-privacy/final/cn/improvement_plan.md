# 安全与隐私改进计划 (已对齐)

**日期:** 2026-04-08
**状态:** FINAL (经过 2 轮对齐)
**配套文档:** `review.md`

---

## Priority 0: 遏制当前暴露

### P0.1 关闭导出 Intent 控制平面
**发现:** CRITICAL-1
**工作量:** M (1-2 小时)

1. 保持 `MainActivity` 仅为 launcher 语义导出
2. 在生产环境中: 忽略外部调用方的所有安全敏感 extras (API key、base URL、backend、mode、debug/trace 标志、排除的工具)
3. 永不持久化 intent 提供的凭据或路由配置
4. 在执行外部提供的目标之前, 要求显式的应用内确认
5. 如果内部/测试自动化需要覆盖: 使用受 signature permission 或 `BuildConfig.DEBUG` 保护的独立 activity

**验收标准:**
- 外部 `ACTION_MAIN` 启动不能持久化 API key 或路由变更
- 外部启动不能强制 BASIC 模式
- 外部启动不能在没有用户确认的情况下自动启动 agent

**文件:** `AndroidManifest.xml`, `MainActivityIntentPayload.kt`, `MainActivityIntentApplier.kt`, `MainActivity.kt`

### P0.2 将隐私拦截移入捕获层
**发现:** CRITICAL-2
**工作量:** L (2-3 天)

1. 在捕获栈内部、任何制品创建之前, 确定当前 package 和层级
2. 如果前台应用为 BLOCKED: 立即返回遮罩后的 snapshot, 不写入原始/sanitized tree 或截图, 不发出包含捕获内容的 action 后观察
3. 移除临时的 action 后捕获点; 替换为一个始终返回策略过滤后观察的共享 helper

**验收标准:**
- 在 blocked app 上启用 tracing 时, 不写入截图或 tree 制品
- `open_app` 和 `UIActionInvocation` 不能返回原始的 blocked app 观察
- Blocked app session 仅产生遮罩后的 snapshot 和策略警告

**文件:** `AccessibilityPlatform.kt`, `VirtualDisplayCaptureCoordinator.kt`, `AccessibilityScreenshotCapturer.kt`, `VirtualDisplayScreenshotProcessor.kt`, `OpenAppTool.kt`, `UIActionInvocation.kt`, `ObservationBuilder.kt`

### P0.3 对长期 Secret 实行关闭式失败
**发现:** HIGH-2, HIGH-4
**工作量:** M (2-3 小时)

1. 移除 OAuth refresh token、id token 和手动 API key 的明文 fallback
2. 如果加密存储失败: 仅在当前进程中将 access token 和用户输入的 API key 保持在内存中, 重启时要求重新输入或重新认证, 显示醒目的安全降级横幅
3. 完全移除从 `/sdcard/api_key.txt` 读取的 `loadApiKeyFromFile()` (如果 eval 需要则通过 `BuildConfig.DEBUG` 门控)
4. 移除过时的 `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` 权限

**验收标准:**
- 无长期 secret 写入普通 `SharedPreferences`
- 加密失败对用户可见
- 当前 session 凭据在用户显式输入后仍可在不持久化的情况下工作
- 无生产路径从共享存储读取 `api_key.txt`

**文件:** `AppSettingsStore.kt`, `OAuthCredentialStore.kt`, `OnboardingStore.kt`, `AndroidManifest.xml`

### P0.4 从日志中移除 PII
**发现:** HIGH-3
**工作量:** S (15 分钟)

1. 删除 `OpenAIOAuth.kt:201-210` 处的 id_token claims 日志
2. 移除 `OpenAiSignIn.kt:70-77` 中的 email 日志
3. 如需调试, 仅记录 token 的存在与长度, 受 `BuildConfig.DEBUG` 门控
4. 将 `LlmLogger` 的详细输出视为 P2.4 下单独的开发者专用加固项, 而非可接受的默认日志行为

**验收标准:**
- logcat 中无 token claims、email 或账户标识符
- 无生产/默认流程依赖 `LlmLogger` 的详细输出

**文件:** `OpenAIOAuth.kt`, `OpenAiSignIn.kt`

---

## Priority 1: 遏制后的加固

### P1.1 添加源头级 Accessibility 清洗
**发现:** HIGH-1
**工作量:** L (3-5 天)

1. 在感知模型中扩展敏感度元数据 (`isPassword`、`isEditable` 及可选的敏感度枚举)
2. 在捕获时抑制密码字段的文本
3. 从 history、checkpoint 和 trace serializer 中无条件脱敏可编辑字段文本
4. 在实时 LLM prompt 中, 仅对当前聚焦的可编辑字段保留原始文本; 对所有其他可编辑字段, 发出结构加脱敏标记 (如 non-empty/length 状态) 而非原始文本
5. 按受众拆分 serializer: action grounding、LLM prompt、history、trace/debug
6. 除非有明确理由, 不将高保真 prompt JSON 持久化到 history/checkpoints

**验收标准:**
- 密码字段在 prompt/history/trace 中永远不以原始文本出现
- History、checkpoints 和 traces 永远不存储原始可编辑文本
- 非聚焦可编辑字段在实时 prompt 输出中永远不以原始文本出现
- 聚焦可编辑文本的暴露仅限于实时 prompt 路径
- `PerceptionElement` 为下游 serializer 携带敏感度上下文

**文件:** `Perceptor.kt`, `Models.kt`, `TurnPlanningPhaseRunner.kt`, `ObservationBuilder.kt`, trace 和 history 相关 package

### P1.2 从生产 Agent 模式中移除 `shell`
**发现:** MEDIUM-1
**工作量:** M (1-2 小时)

1. 从生产 agent 定义和 prompt 中移除 `shell`
2. 如果 eval/dev 仍然需要, 仅在 debug/developer 模式下注册, 且需显式启用
3. 对任何保留的 dev shell 路径: 执行 argv-only (不使用 `sh -c`), 强制 binary allowlist, 强制 filesystem path allowlist
4. 用独立于前台应用的数据访问策略管控任何保留的 shell 路径

**验收标准:**
- 生产 agent 模式不能调用 `shell`
- 除非显式启用, debug/eval shell 不可用
- 在任何保留的 dev shell 路径中, 没有 `sh -c`、元字符解释或不受限的路径访问

**文件:** `StandaloneAgentDef.kt`, `SessionToolingBootstrapper.kt`, `ShellTool.kt`, `PolicyEngine.kt`, `ToolName.kt`

### P1.3 使 InsecureSsl 仅在编译时用于 Debug
**发现:** MEDIUM-2
**工作量:** S (30 分钟)

1. 将 `InsecureSslConfig.kt` 移至 `debug/` source set
2. Release source set 提供 no-op stub
3. 验证 base URL 覆盖在 debug 构建之外要求 HTTPS

**验收标准:**
- Release 构建不能编译任何不安全的 TLS helper
- 生产 base URL 拒绝非 HTTPS URL

**文件:** `InsecureSslConfig.kt`, `ChatCompletionClient.kt`, `OpenAIResponseClient.kt`, `CodexResponseClient.kt`

### P1.4 加固 AppClassifier 失败模式
**发现:** MEDIUM-3
**工作量:** S (30 分钟)

1. `fromAssets()` 抛出异常或返回显式失败, 而非返回空分类器
2. Session 创建在 agent 启动前中止, 并向用户展示错误信息
3. 不为分类器加载失败添加特殊 escape action 路径; 当前的 back/home 例外仅在分类器可用时的正常运行时策略下保留

**验收标准:**
- `app_tiers.json` 缺失或损坏时阻止正常 session 启动
- 用户看到解释该问题的错误信息
- 生产代码中没有空分类器 fallback 路径

**文件:** `AppClassifier.kt`, `SessionServices.kt`

---

## Priority 2: 架构后续

### P2.1 用 Deep Link 重定向替换 OAuth HTTP Callback Listener
**发现:** MEDIUM-4
**工作量:** L (1-2 天)

1. 注册 `androidagent://oauth/callback` URI scheme
2. 使用 Custom Tab 打开授权 URL
3. 通过 Activity intent filter 接收 callback
4. 移除 `OAuthCallbackServer`
5. 过渡期: 显式绑定到 loopback 并在接受 callback 前验证请求形状/host

**文件:** `OpenAIOAuth.kt`, `AndroidManifest.xml`

### P2.2 添加数据留存控制
**工作量:** M (1 天)

1. 为 session history、checkpoints、memory 和 trace 留存提供用户可见设置
2. 一键安全擦除 trace 和已存储 session
3. 默认关闭 trace, 启用时给出清晰提示

### P2.3 添加安全回归测试
**工作量:** M (1-2 天)

必需测试:
1. 带安全敏感 extras 的外部 intent 在生产中被忽略
2. Blocked app 不产生 trace 截图/tree
3. 密码/可编辑字段从 prompt/history serializer 中被脱敏
4. 加密存储失败时不以明文持久化 refresh token
5. Shell 验证器拒绝链式命令/元字符和禁止的路径
6. AppClassifier 加载失败阻止 session 启动

### P2.4 最小化 Debug 制品暴露
**工作量:** S (1 小时)

1. 用 signature 级权限限制 debug receiver
2. 要求显式的开发者开关才能启用外部 debug 制品
3. 将 trace 存储仅限于内部存储
4. 将 `LlmLogger` 的详细输出置于显式的开发者模式或私有本地文件 sink 之后, 而非默认输出到 logcat

---

## 交付顺序

### Milestone A (下次发布之前)
- P0.1 Intent 锁定
- P0.3 关闭式 secret 失败 + 移除 /sdcard key 路径
- P0.4 从日志中移除 PII

### Milestone B
- P0.2 捕获层隐私拦截
- P1.2 Shell 加固/移除
- P1.3 仅 Debug 的 InsecureSsl
- P1.4 AppClassifier 关闭式失败

### Milestone C
- P1.1 Accessibility 字段清洗
- P2.1 OAuth deep link
- P2.2 数据留存控制
- P2.3 回归测试
- P2.4 Debug 制品清理

---

## 总结表

| ID | 发现 | 严重度 | 工作量 | 优先级 |
|----|------|--------|--------|--------|
| P0.1 | 导出 intent 控制平面 | CRITICAL | M | P0 |
| P0.2 | 捕获层隐私拦截 | CRITICAL | L | P0 |
| P0.3 | 关闭式 secret 失败 + /sdcard 移除 | HIGH | M | P0 |
| P0.4 | 日志中的 PII | HIGH | S | P0 |
| P1.1 | Accessibility 字段清洗 | HIGH | L | P1 |
| P1.2 | Shell 加固/移除 | MEDIUM | M | P1 |
| P1.3 | 仅 Debug 的 InsecureSsl | MEDIUM | S | P1 |
| P1.4 | AppClassifier 关闭式失败 | MEDIUM | S | P1 |
| P2.1 | OAuth deep link callback | MEDIUM | L | P2 |
| P2.2 | 数据留存控制 | MEDIUM | M | P2 |
| P2.3 | 安全回归测试 | - | M | P2 |
| P2.4 | Debug 制品清理 | LOW | S | P2 |
