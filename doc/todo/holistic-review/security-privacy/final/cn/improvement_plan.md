# 安全与隐私改进计划 (已对齐)

**日期:** 2026-04-08
**状态:** FINAL (经过 2 轮对齐 + owner review + ultrathink)
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
5. Debug build 中保留现有行为, 或将 debug extras 移到受 signature permission 保护的独立 Activity

**验收标准:**
- 外部 `ACTION_MAIN` 启动不能持久化 API key 或路由变更
- 外部启动不能强制 BASIC 模式
- 外部启动不能在没有用户确认的情况下自动启动 agent
- Debug build 的 debug workflow 不受影响

**文件:** `AndroidManifest.xml`, `MainActivityIntentPayload.kt`, `MainActivityIntentApplier.kt`, `MainActivity.kt`

### P0.2 将隐私拦截移入捕获层
**发现:** CRITICAL-2
**工作量:** L (2-3 天)
**实现方式:** /tdd (test-driven)

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
3. `loadApiKeyFromFile()` 从 `/sdcard/api_key.txt` 读取的路径用 `BuildConfig.DEBUG` 门控, 仅 debug build 可用
4. 删除相关 deadcode (`prefsFailed` flag、plaintext prefs 初始化等), 加 test

**验收标准:**
- 无长期 secret 写入普通 `SharedPreferences`
- 加密失败对用户可见
- 当前 session 凭据在用户显式输入后仍可在不持久化的情况下工作
- Production build 无路径从共享存储读取 `api_key.txt`

**文件:** `AppSettingsStore.kt`, `OAuthCredentialStore.kt`, `OnboardingStore.kt`

### P0.4 从日志中移除 auth PII
**发现:** HIGH-3
**工作量:** S (15 分钟)

1. 删除 `OpenAIOAuth.kt:201-210` 处的 id_token claims 日志
2. 移除 `OpenAiSignIn.kt:70-77` 中的 email 日志
3. 如需调试, 仅记录 token 的存在与长度, 受 `BuildConfig.DEBUG` 门控

注: `LlmLogger` 已有 `BuildConfig.DEBUG` 门控, release build 不输出, 无需改动。

**验收标准:**
- logcat 中无 token claims、email 或账户标识符

**文件:** `OpenAIOAuth.kt`, `OpenAiSignIn.kt`

---

## Priority 1: 遏制后的加固

### P1.1 密码字段脱敏
**发现:** HIGH-1
**工作量:** S (30 分钟)

仅脱敏 `isPassword` 字段。不做 editable 字段抑制、不拆分 serializer、不做 rule-based 分类。`AccessibilityNodeInfo.isPassword()` 是 OS 提供的 100% 准确信号。

1. 在 `Perceptor` 构建 `PerceptionElement` 时, 检查 `AccessibilityNodeInfo.isPassword()`, 如果为 true 则抑制 text/contentDescription
2. 所有下游路径 (prompt、history、trace) 自动继承, 无需拆分 serializer

**验收标准:**
- 密码字段在所有路径 (prompt/history/trace) 中不以原始文本出现
- 非密码字段 (包括 editable) 全部保持原样, 不做任何脱敏

**文件:** `Perceptor.kt`

### P1.2 Shell blocklist 精简
**发现:** MEDIUM-1
**工作量:** S (30 分钟)

功能优先。保留 shell 在 production, 精简 blocklist 到真正有系统级危险的命令。不限制元字符, 不添加 path denylist (在 `sh -c` + 元字符不限制前提下, path denylist 可被轻易绕过, 属于 security theater)。

1. 将 command blocklist 精简为: `am`, `pm`, `reboot`, `su`
2. 解除: `rm`, `mv`, `cp`, `chmod`, `chown`, `sh`, `bash`, `eval`, `exec`, `settings`
3. 不限制 shell 元字符

**验收标准:**
- Blocklist 仅包含 `am`, `pm`, `reboot`, `su`
- 其余命令和元字符不受限制

**文件:** `ShellTool.kt`

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

注: app_tiers.json 需要从当前 61 个 app 扩展到 100-1000 个, 作为独立设计任务。

**验收标准:**
- `app_tiers.json` 缺失或损坏时阻止正常 session 启动
- 用户看到解释该问题的错误信息
- 生产代码中没有空分类器 fallback 路径

**文件:** `AppClassifier.kt`, `SessionServices.kt`

---

## Priority 2: 架构后续

### P2.2 添加数据留存控制
**工作量:** M (1 天)

放在 Settings → "Permissions & Advanced" 页面, 新增 "Data & Storage" section。

1. 为 session history、checkpoints、memory 和 trace 留存提供用户可见设置
2. 一键安全擦除 trace 和已存储 session
3. 默认关闭 trace, 启用时给出清晰提示

注: Settings bottom sheet → full page 的改造作为独立 UI 改进 item (已记录到 ui-ux-quality notes)。

### P2.3 添加安全回归测试
**工作量:** M (1-2 天)

绝大部分为 unit test (JVM), 仅 #2 需要 instrumentation test。

| # | 测试项 | 类型 |
|---|--------|------|
| 1 | 外部 intent 安全敏感 extras 在 production 中被忽略 | Unit test |
| 2 | Blocked app 不产生 trace 截图/tree | Instrumentation test |
| 3 | 密码字段从 prompt/history/trace 中被脱敏 | Unit test |
| 4 | 加密存储失败时不以明文持久化 refresh token | Unit test |
| 5 | Shell blocklist 只含 am/pm/reboot/su, 其余命令可执行 | Unit test |
| 6 | AppClassifier 加载失败阻止 session 启动 | Unit test |

---

## WONTFIX 项

| ID | 发现 | 原因 |
|----|------|------|
| P2.1 | OAuth localhost 加固 | state 参数 (16 字节 SecureRandom) 已防住 code theft; 绑定 loopback 和 Host 验证对本地攻击者无效; 唯一剩余风险 (DoS) 影响极小且 proposed fixes 都防不了 |
| P2.4 | Debug 制品清理 | LlmLogger 已有 DEBUG 门控; debug receiver 已有 DEBUG 门控; debug 制品已有显式开关; getExternalFilesDir() 在 Android 10+ scoped storage 下其他 app 读不到 |
| MEDIUM-5 | Storage 权限移除 | Agent shell 需要操作文件, 保留 |

---

## 交付顺序

### Milestone A (下次发布之前)
- P0.1 Intent 锁定
- P0.3 关闭式 secret 失败 + /sdcard key 门控
- P0.4 从日志中移除 auth PII

### Milestone B
- P0.2 捕获层隐私拦截 (TDD)
- P1.2 Shell blocklist 精简
- P1.3 仅 Debug 的 InsecureSsl
- P1.4 AppClassifier 关闭式失败

### Milestone C
- P1.1 密码字段脱敏
- P2.2 数据留存控制
- P2.3 回归测试

---

## 总结表

| ID | 发现 | 严重度 | 工作量 | 优先级 | 状态 |
|----|------|--------|--------|--------|------|
| P0.1 | 导出 intent 控制平面 | CRITICAL | M | P0 | 待实现 |
| P0.2 | 捕获层隐私拦截 | CRITICAL | L | P0 | 待实现 (TDD) |
| P0.3 | 关闭式 secret 失败 + /sdcard 门控 | HIGH | M | P0 | 待实现 |
| P0.4 | 日志中的 auth PII | HIGH | S | P0 | 待实现 |
| P1.1 | 密码字段脱敏 (isPassword only) | HIGH | S | P1 | 待实现 |
| P1.2 | Shell blocklist 精简 (am/pm/reboot/su) | MEDIUM | S | P1 | 待实现 |
| P1.3 | 仅 Debug 的 InsecureSsl | MEDIUM | S | P1 | 待实现 |
| P1.4 | AppClassifier 关闭式失败 | MEDIUM | S | P1 | 待实现 |
| P2.2 | 数据留存控制 | MEDIUM | M | P2 | 待实现 |
| P2.3 | 安全回归测试 | - | M | P2 | 待实现 |
| ~~P2.1~~ | ~~OAuth localhost 加固~~ | - | - | - | WONTFIX |
| ~~P2.4~~ | ~~Debug 制品清理~~ | - | - | - | WONTFIX |
| ~~MEDIUM-5~~ | ~~Storage 权限移除~~ | - | - | - | WONTFIX |
| NEW | app_tiers.json 扩展到 100-1000 app | - | L | 独立 | 待设计 |

---
---

## Appendix: 讨论记录

以下是 owner review 过程中的完整讨论, 保留作为决策上下文。

### P0.1 讨论

> 这主要是为了debug的时候用的都。debug的这些flag该怎么处理最好？

>> **回复:** 建议分两层处理: (1) 在 production build 中, `MainActivity` 完全忽略来自外部 caller 的 debug/trace/mode 等 extras — 只接受 goal text (且需用户确认); (2) debug build 中保留现有行为, 或者更好的做法是把这些 debug extras 移到一个独立的 Activity, 用 signature permission 保护。具体来说就是在 `MainActivityIntentApplier` 中加一个 `BuildConfig.DEBUG` 或 caller UID 检查。

### P0.2 讨论

> SGTM. 需要使用 /tdd test-driven-dev，make sure lifecycle is solid.

>> **回复:** 收到。实现时用 /tdd, 先写 test 覆盖 blocked app capture 的各条路径 (直接 capture、post-action observation、trace artifact 写入), 再改代码。

### P0.3 讨论

> SGTM. deadcode 记得删除干净。 add tests.

>> **回复:** 收到。明文 fallback 路径删除后, 相关的 `prefsFailed` flag 和 plaintext prefs 初始化代码一并清理。test 覆盖: mock EncryptedSharedPreferences 失败, 验证不写入 plaintext, 验证降级 banner 状态。

### P0.4 讨论

> Ok, SGTM. auth相关PII可以删除。Llmlogger是dev-only.

>> **回复:** 收到。auth PII 日志直接删除。LlmLogger 已经有 `BuildConfig.DEBUG` 门控 (line 10: `VERBOSE_LOGGING = BuildConfig.DEBUG`), release build 不会输出任何内容, 当前状态 OK, 不需要额外改动。

### P1.1 讨论 (大幅简化)

> 按之前讨论的， isEditable不是不动吗？底下的 1/4要改。3也可以不做，这个是不是除了用户自己或者开发者，别人也拿不到？那就谁分享之前自己脱敏？我主要觉得这玩意也不好做啊，做个差劲的rule-based classifier不如不做。

>> **回复:** 同意, 大幅简化。isEditable 不动, trace 也不自动脱敏 (on-device 存储, 其他 app 拿不到, 分享前自行处理)。整个 P1.1 简化为一件事: **只脱敏 `isPassword` 字段**。`AccessibilityNodeInfo.isPassword()` 是 OS 明确告诉你的, 不需要做任何 rule-based 猜测, 100% 准确。一个 `if` 就够了, 不需要拆分 serializer, 不需要敏感度枚举。

> 可编辑字段全抑制掉是不是too much? history也该全保留吧？不然reload session有信息丢失，行为不一致。

>> **回复:** 同意, history 确实需要保留原文。最终方案: 只做 isPassword, 其余全不动。

### P1.2 讨论 (多轮精简)

> 能不能coding agent允许什么我们就允许什么？先宽松点。别功能性还没整好，限制加一堆。元字符先不限制？

>> **回复:** 同意, 功能优先。当前 `ShellTool.kt` 的 blocklist 已经 block 了 `rm, mv, cp, chmod, chown, pm, am, settings, reboot, su, sh, bash, eval, exec`。元字符不限制。

> 是不是还可以往下减一减限制？mv/cp没必要限制吧？am,pm,settings,eval,exec干什么的？

>> **回复:** 可以减。逐个分析:
>>
>> | 命令 | 作用 | 建议 |
>> |------|------|------|
>> | `mv` | 移动/重命名文件 | **解除** — 基本文件操作 |
>> | `cp` | 复制文件 | **解除** — 同上 |
>> | `chmod`/`chown` | 修改权限/所有者 | **解除** — 非 root 下影响有限 |
>> | `rm` | 删除文件 | **解除** — agent 需要, 且 `find -delete` 同样可用 |
>> | `am` | Activity Manager | **保留** — `am broadcast` 可触发 debug receiver |
>> | `pm` | Package Manager | **保留** — `pm install` 可侧载 APK |
>> | `settings` | 系统设置 | **保留** — 可改系统代理等 |
>> | `reboot`/`su` | 重启/root | **保留** |
>> | `sh`/`bash`/`eval`/`exec` | shell 相关 | **解除** — 元字符不限制时冗余 |

> settings能不能也解除？这不是开关个wifi等等的快捷方式吗？这个打开吧。

>> **回复:** 行, `settings` 解除。最终 blocklist: `am`, `pm`, `reboot`, `su` — 4 个。

> **[ultrathink] P1.2 path denylist 是 security theater:**
> 当前 shell 用 `sh -c` 执行且元字符不限制。path denylist 可被轻易绕过:
> - `base64 /data/data/.../shared_prefs/prefs.xml` (换 binary)
> - `cd shared_prefs && cat prefs.xml` (不含完整路径)
> - `P=shared_prefs; cat $P/prefs.xml` (变量展开)
> - `ln -s shared_prefs /tmp/sp && cat /tmp/sp/prefs.xml` (符号链接)
>
> 要做可靠 path 保护必须放弃 `sh -c` 改 argv-only (像 Codex 那样)。但用户明确要功能优先。
> **结论: 删除 path denylist。**

> ok，可以。

### P1.3 讨论

> OK，没问题

>> **回复:** 收到, 按原 plan 执行。

### P1.4 讨论

> sgtm。
> app_tiers.json 应该把世界常用的100-1000 app涵盖进去。这个要单独item去设计和做。

>> **回复:** 收到, fail-closed 按原 plan。app_tiers.json 扩展作为独立 item — 当前只有 61 个 app (36 blocked + 25 normal)。数据来源: Google Play top charts、AppBrain 排名等。用 LLM 批量评估 tier。放到 task graph 里作为独立设计任务。

### P2.1 讨论 (WONTFIX)

> **[ultrathink] P2.1 proposed fixes 不解决实际威胁:**
>
> 1. **绑定 127.0.0.1:** 防网络远程攻击者, 但威胁是本地 app, 绑定 loopback 无效
> 2. **验证 Host header:** 本地攻击者控制 HTTP 请求, 零保护
> 3. **实际保护已存在:** state 参数 16 字节 SecureRandom, 其他 app 读不到浏览器 URL
> 4. **唯一剩余风险:** DoS (消耗 accept), 影响极小, proposed fixes 都防不了
>
> **结论: WONTFIX。**

> ok，可以。

### P2.2 讨论

> 这个在setting page的三个顶级目录里加到哪？还是第四个？
> setting要不要从现在底部浮上来的，改成一个正常的完整的页面？

>> **回复:** 放 "Permissions & Advanced" 里, 加个 "Data & Storage" section。Bottom sheet → full page 作为独立 UI 改进 item (已记录到 `doc/todo/holistic-review/ui-ux-quality/final/note.md`)。

### P2.3 讨论

> 这些可以写unittest/integration test吗？还是要写个process doc？

>> **回复:** 绝大部分可以写 unit test:
>>
>> | # | 测试项 | 类型 |
>> |---|--------|------|
>> | 1 | Intent extras 被忽略 | Unit test |
>> | 2 | Blocked app 不产 trace | Instrumentation test |
>> | 3 | 密码字段脱敏 | Unit test |
>> | 4 | 加密失败不写明文 | Unit test |
>> | 5 | Shell blocklist | Unit test |
>> | 6 | AppClassifier 加载失败 | Unit test |
>>
>> 只有 #2 需要 instrumentation test。不需要 process doc。

### P2.4 讨论 (WONTFIX)

> "将 LlmLogger 的详细输出置于显式的开发者模式或私有本地文件 sink 之后"。这会增加开发看log的难度吗？logcat是人人可见吗？debug开关开了就能打到logcat行吗？

>> **回复:** LlmLogger 当前已有 `BuildConfig.DEBUG` 门控, debug build 打到 logcat, release 不输出。Android 4.1+ 第三方 app 不能读 logcat, 只有 ADB 可以。当前行为已正确, 无需改动。

> "用 signature 级权限限制 debug receiver" 这个怎么做？

>> **回复:** 在 manifest 声明 `protectionLevel="signature"` 自定义权限, 注册 receiver 时指定。但这些 receiver 已有 `BuildConfig.DEBUG` 门控, 加 signature permission 属于 defense-in-depth, 优先级不高。

> 那不做了，剩下的还需要做吗？

>> **回复:** 逐项看:
>> 1. signature 权限 — 不做, DEBUG 门控够了
>> 2. 显式开发者开关 — 已经是 (debug build / trace 开关)
>> 3. trace 存储限内部存储 — `getExternalFilesDir()` 在 Android 10+ scoped storage 下其他 app 读不到
>> 4. LlmLogger — 已确认无需改
>>
>> **结论: P2.4 整个 WONTFIX。**

### Storage 权限讨论 (WONTFIX)

> agent可能用shell操作any file。所以要保留？

>> **回复:** 如果 shell 保留在 production, storage 权限需要保留。

> 不移除。

>> **回复:** 了解, WONTFIX。
