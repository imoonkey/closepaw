# OpenWeb Runtime 集成 Android Agent 设计

Status: aligned draft v1

本文档是将 OpenWeb 集成进 `androidagent` 的完整对齐设计。

维护规则：

- 当一个 open question 被解决时，先更新本文档正文。
- 正文反映了最终决策后，再删除或修改末尾 `Open Questions` 中对应的条目。

## 目标

让 `androidagent` 拥有一等公民级别的 web-runtime 能力：当目标站点和操作已被 OpenWeb 支持时，agent 直接调用结构化 API，而不是通过 UI 自动化操控网页。

成功标准：

- OpenWeb 仍然是站点能力的 source of truth。
- Android Agent 获得一个原生 Android runtime，与现有的 session、tool、policy 架构自然融合。
- Auth 在 Android 上可用，且不依赖桌面 OpenWeb 的进程模型。
- 首个有用的发布版本体量小、可测试，不依赖风险最高的 browser 集成路径。

## OpenWeb 提供了什么

OpenWeb 的核心价值不是当前的 Node CLI 进程，而是：

- site packages：`openapi.yaml`、`asyncapi.yaml`、`manifest.json`、examples、可选的 adapters
- 执行语义：`node` transport、`page` transport、extraction operations、permission categories、failure classes
- 声明式 primitives：auth、CSRF、signing、pagination、extraction

Android 设计保留这些语义，但用 Android 原生实现替换桌面端的 runtime 细节。

## 对齐决策

以下是当前共识：

- Runtime 用 Kotlin 原生实现，放在 `androidagent` 内部。
- OpenWeb site packages 仍为上游 source of truth。
- 不嵌入现有 Node runtime。
- 不依赖 Termux、嵌入式 Node 或嵌入式 JS runtime 作为产品架构。
- Runtime 是 session-scoped service，与 `AndroidPlatform`、`VirtualDisplayPlatform` 分离。
- Android 消费构建时导出的、已验证的 site bundles，而非在设备上解析原始 YAML 并重做 schema validation。
- Tool 层面将 discovery 与 execution 分开。
- 执行路径使用 OpenWeb 的 permission categories 和 failure-class 词汇。
- MVP 发布一个精选的 allowlist（已验证的站点和 primitives），而非上游 `src/sites/` 下的全部内容。
- 当前 L3 adapter ABI 兼容、WebSocket 兼容、package-update 基础设施不在 MVP 范围内。

## 架构基础

对齐方案继承 Codex 设计的结构选择，并在不增加架构风险的前提下融入 Claude 设计的实现细节。

### 核心结构

```text
LLM
  -> discovery tool
  -> execution tool
      -> OpenWebRuntime (session-scoped)
          -> OpenWebPackageRepository
          -> OpenWebCatalogIndex
          -> OpenWebTokenVault
          -> OpenWebExecutor
              -> NodeTransportExecutor
              -> ExtractionExecutor
              -> PageTransportExecutor
              -> PrimitiveRegistry
              -> ResultFormatter
          -> BrowserCoordinator
              -> Managed browser backend
              -> Optional Chrome CDP backend
```

### 在 androidagent 中的位置

`OpenWebRuntime` 与 `SessionServices` 中现有的 session-scoped services 并列，不放在：

- `AndroidPlatform`
- `VirtualDisplayPlatform`
- `MobileActionTool`

现有 platform 层负责手机 UI 自动化。OpenWeb 是独立的能力，拥有自己的 runtime、storage 和 browser state。

## Package Pipeline

Android 不应将上游原始 YAML 视为 runtime contract。

取而代之：

- OpenWeb 在自身工具链中验证上游 site packages。
- 构建/导出步骤生成 Android 友好的 bundle artifacts。
- `androidagent` 将导出产物作为 assets 消费。

### 导出产物

当前对齐的 artifact 集合：

- 每个站点一个 `site_bundle.json`
- 一个 `catalog.json` 用于 discovery/indexing
- discovery/help text 所需的 examples
- 后续阶段才需要的原始 adapter source（可选）

### 设备端布局

当前对齐的存储模型：

- `assets/openweb/sites/...` — 打包的、已审核的 packages
- `files/openweb/sites/...` — 未来可能的下载 packages
- `files/openweb/registry/...` — 等签名更新机制就绪后再用

首个发布版本仅包含 bundled assets。

## Browser Backend 模型

Runtime 需要一个显式的 browser-backend 抽象。架构必须支持多个 backend，即使首个发布版本只搭载一个。

```kotlin
interface OpenWebBrowserBackend {
    suspend fun ensureContext(site: String, url: String): BrowserContextRef
    suspend fun extractCookies(url: String): List<OwCookie>
    suspend fun evaluateJs(context: BrowserContextRef, expression: String): String?
    suspend fun fetch(context: BrowserContextRef, request: BrowserFetchRequest): BrowserFetchResult
    suspend fun openLogin(site: SiteBundle): LoginResult
    suspend fun clearContext(site: String)
}
```

这个 interface 是关键的架构接缝（seam）——它使 browser 策略不会成为承重的架构决策。

重要约束：`evaluateJs` 返回 `String?`（JSON-serialized），不是 `Any?`。WebView 的 `evaluateJavascript` 和 CDP 的 `Runtime.evaluate` 都返回字符串结果。保持 `String?` 可避免抽象边界的阻抗失配。

### Browser backend 何时介入

对大多数操作而言，browser backend **不在** hot path 上。L1/L2 operation 在 auth 已缓存时的稳态执行流：

```text
openweb_execute → OpenWebExecutor → TokenVault (cache hit)
    → NodeTransportExecutor → OkHttp request → response
```

不涉及 browser、WebView 或 CDP。纯 HTTP。

Browser backend 仅在以下场景被调用：

1. **Auth extraction** — token cache 为空或过期
2. **Login bootstrap** — 用户首次登录某站点
3. **Extraction operations** — `script_json`、`html_selector`、`page_global_data` 等
4. **`page` transport** — 必须在 browser context 内执行 fetch 的操作

这个区分对性能、可测试性和电池续航都很重要。Agent 与 OpenWeb 的大多数交互都是 cache-hit 的 HTTP 调用。

### Managed browser backend (WebView)

对齐方案使用 Android **WebView** 作为 managed browser backend。

WebView 是正确的默认选择，因为：

- 通过 `CookieManager` 和 `evaluateJavascript` 拥有 app-owned session state
- Android 内置——零额外依赖
- 不需要 Shizuku 或 Chrome debugging
- CI 中可测试

WebView 的已知限制（实现时必须处理）：

- **站点检测：** 部分站点会检测 WebView（User-Agent 包含 `wv`、TLS fingerprint 不同）并降级或阻断。WebView compatibility spike（open question #4）应量化此问题。
- **进程全局的 CookieManager：** Android 的 `CookieManager` 在进程内所有 WebView 间共享。站点 A 设置的 cookies 若域名重叠（如 Meta 系属性），站点 B 也能看到。实现应将 cookie extraction 限定在目标站点的域名范围内。
- **无 service workers 或 extensions：** 某些依赖 SPA state 的 extraction primitives 可能需要先导航 + 等待再执行 JS。

Managed WebView host 必须与现有的 app-automation display 隔离，不能占用 agent 自动化其他 app 时使用的 surface。

### Optional Chrome CDP backend

Chrome CDP via Shizuku 是有价值的，特别适用于：

- 复用用户现有的 Chrome sessions
- 更难对付的站点（偏好或需要真实 Chrome 行为）
- Chrome fingerprint 或 session 复用能实质提升可靠性的场景

但它不是对齐方案的架构基础，而是藏在同一 interface 后面的可选 backend。

这保证了即使以下情况发生，runtime 仍然可用：

- Shizuku 不可用
- Chrome debugging 在部分设备上不稳定
- 启用 CDP 对用户造成过多干扰

## Auth 模型

对齐方案保留 OpenWeb 高层的 cascade 结构，同时让每个步骤由 backend 持有。

### Auth cascade 流程

```text
NodeTransportExecutor 收到 operation request
│
├─ Step 1: TokenVault.read(site)
│   ├─ hit + 未过期 → 从缓存解析 auth → 执行 HTTP → 返回
│   │   └─ 若 401/403 → 清除缓存 → 进入 step 2
│   └─ miss 或已过期 → 进入 step 2
│
├─ Step 2: BrowserCoordinator.extractAuth(site, authPrimitive)
│   ├─ backend 对该站点有活跃 session → 提取 cookies/tokens
│   │   └─ 成功 → 写入 TokenVault → 执行 HTTP → 返回
│   │   └─ 401/403 → session 已过时 → 进入 step 3
│   └─ 无活跃 session → 进入 step 3
│
├─ Step 3: BrowserCoordinator.openLogin(site)
│   ├─ 向用户展示 login UI（WebView 或 Chrome，取决于 backend）
│   ├─ poll/检测登录完成
│   │   └─ 成功 → 提取 auth → 写入 TokenVault → 执行 HTTP → 返回
│   └─ 超时或用户取消 → 抛出 needs_login
│
└─ 所有步骤穷尽 → 抛出 needs_login 并附带指引
```

关键行为：

- **Cache invalidation 按站点隔离。** Instagram 的 401 不会使 Reddit 的 tokens 失效。
- **若 operation 不需要 auth**（`requires_auth: false` 或 spec 中无 auth primitive），跳过 Step 2。
- **Step 3 是交互式的。** Agent 应告知 LLM 需要用户登录，LLM 通过 `ask_user` 等方式转达给用户。
- **并发保护：** 若某站点的 step 2 或 3 正在进行（如 login UI 已打开），同站点的后续请求应等待正在进行的 auth 操作完成，而非启动第二个。用 per-site mutex 或 `CompletableDeferred` 处理。

### Token vault

Token 存储复用 OpenWeb 的逻辑 token schema，搭配 Android 原生安全存储。

```kotlin
data class CachedTokens(
    val cookies: List<SerializedCookie>,
    val localStorage: Map<String, String>,
    val sessionStorage: Map<String, String>,
    val capturedAt: Instant,
    val ttlSeconds: Long = 3600,
    val jwtExp: Long? = null
)
```

过期检查：若 `jwtExp` 存在则使用它，否则使用 `capturedAt + ttlSeconds`。默认 TTL 1 小时，与桌面端一致。

实现：`EncryptedSharedPreferences` 存 index/metadata，较大的 payload 用 encrypted files。与现有 `OAuthCredentialStore.kt` 和 `AppSettingsStore.kt` 的模式对齐。

### Backend-owned extraction

Auth extraction 因 backend 而异：

- **WebView backend：** `CookieManager.getCookie(url)` 取 cookies，`evaluateJavascript` 取 localStorage/sessionStorage/page globals
- **Chrome CDP backend（未来）：** `Network.getCookies`、`Runtime.evaluate` 取 storage/globals、`DOMStorage.getDOMStorageItems` 取 storage

对齐方案不假设通过 Custom Tabs 或任意 postMessage 流做通用的一刀切 extraction bridge。

### Login bootstrap

Login 因 backend 而异：

- **WebView backend：** 登录在 managed WebView 内进行。Backend 导航到站点的 login URL，用户认证，backend 检测登录成功（如 cookie 出现、URL 变化、页面内容变化）。
- **Chrome CDP backend（未来）：** 登录在 Chrome 中进行。Backend 通过 CDP 监控页面状态直到 auth tokens 出现。

Custom Tabs 或 Auth Tab 后续可作为 UX 辅助手段使用，但它们不是核心架构合约。

## 执行模型

Android runtime 应保留 OpenWeb 的 dispatch 模型：

- L3 adapter operations 覆盖 transport dispatch
- extraction operations 在 HTTP transports 之前 dispatch
- transport resolution 仍然是 operation-level override → server-level override → default

### `node` transport

这是第一优先级的实现路径。

Android 原生实现：

- parameter binding
- request construction
- auth / CSRF / signing primitive resolution
- SSRF validation
- redirect handling
- response parsing
- response unwrap
- failure classification

实现方向：

- OkHttp
- 显式的 redirect policy
- 每一跳都做显式 SSRF 检查
- 不依赖桌面端 Node fetch 路径

### extraction operations

通过 browser backend evaluation 实现 allowlist 所需的 extraction primitives。

早期大概率支持：

- `script_json`
- `ssr_next_data`
- `html_selector`
- `page_global_data`

### `page` transport

架构必须为 `page` transport 预留一等公民级别的接缝。

当前对齐方案不要求在最早的 allowlist 发布中完整支持 `page` transport。应在以下情况时加入：

- 精选 allowlist 需要它，或
- browser 兼容性测试结果表明需要提前加入

### L3 adapters

当前 L3 adapters 无法原样移植。

现有 adapter ABI 依赖：

- Patchright `Page`
- 注入的 helper functions（如 `pageFetch`、`graphqlFetch`）
- adapter 生命周期方法（`init`、`isAuthenticated`、`execute`）

因此对齐方案的 MVP 不承诺当前 L3 adapter 兼容。后续工作需在以下两者中选择：

- 为 backend 可移植性设计 adapter ABI v2，或
- 构建一个兼容层，模拟当前 ABI 的足够子集

### WebSocket / AsyncAPI

不在 MVP 范围。

后续可复用 AsyncAPI package 语义，配合 Android 原生 WebSocket 执行。

## Tool 与 Policy 集成

对齐方案的 tool 形态将 discovery 与 execution 分开。

当前工作名称：

- `openweb_catalog`
- `openweb_execute`

具体命名不是架构关键点，但拆分本身是。

### Discovery tool

用途：

- 搜索已支持的站点
- 列出某站点的 operations
- 描述 operation 的 params、permission class 和 examples

此 tool 始终安全，应保持 model 调用的低成本。

### Execution tool

用途：

- 使用 site、operation 和嵌套 params 执行一个 OpenWeb operation

此 tool 必须暴露或解析 operation 的 permission category，使审批流程留在 `ToolRouter` 和 `PolicyEngine` 的正常流程中。

### Permission 映射

当前对齐映射：

- `read` -> allow
- `write` -> ask
- `delete` -> ask
- `transact` -> deny

Runtime 不应将这些决策埋在 tool execution 内部。审批接缝需要为 remote operation categories 做显式的 policy extension。

## 结果处理

Runtime 不能将原始的大型 JSON payload 直接灌入 LLM context。

需要一个 result formatter：

- 紧凑地摘要 objects
- 将列表结果截断为前 N 条，附带总数
- 去除 LLM 不需要的冗余 metadata（pagination tokens、internal IDs 等）
- 每个格式化结果都包含 site、operation、status 和 item count
- 将完整的结构化 payload 保存在 prompt context 之外，供 trace/debug/UI 使用

目标：单对象响应的格式化结果应控制在 ~500 tokens 以内，列表响应 ~1000 tokens 以内。这些 budget 可通过 bundle metadata 按 operation 调节。

完整 payload 应与现有 trace system（`TraceRecorder`）一同存储，供调试和 UI 渲染使用，不污染 LLM history。

这是必需的 runtime 组件，不是可有可无的展示层细节。

## 安全模型

对齐方案的安全基线：

- app-private encrypted token storage
- 直接 HTTP 执行的 SSRF validation
- redirect validation 和跨域时的 auth-header stripping
- OpenWeb permission-category enforcement
- 初始设计中对 `transact` operations 硬性 deny
- 首个发布版本仅包含 bundled reviewed packages

Runtime 应保留 OpenWeb 的 failure-class 词汇，使 agent 能正确响应：

- `needs_login`
- `needs_page`
- `needs_browser`
- `bot_blocked`
- `retriable`
- `fatal`

## 复用 vs 重写

### 直接复用

- site packages 作为源输入
- manifests、operation IDs、examples、summaries
- permission taxonomy
- failure-class vocabulary
- primitive 语义

### 适配后复用

- token schema
- dispatch 语义
- site lookup 和 catalog 概念
- auth-cascade 结构

### 需为 Android 重写

- 设备端 package loading
- HTTP executor
- token storage 实现
- browser lifecycle 和 browser hosting
- tool integration
- result formatting
- browser-backed primitive execution
- concurrency 和 lifecycle ownership

### MVP 之后再做

- 当前 L3 adapter ABI 兼容
- 完整 WebSocket 兼容
- package-update system
- 不受限的用户自装 packages

## 实现估算

基于对 openweb TypeScript runtime 和 androidagent codebase 两端的分析，粗略估算。

### 新增 Kotlin 代码

| 组件 | 估算 LOC |
|------|---------|
| Package repository + catalog index | ~300 |
| Node transport executor (OkHttp, redirects, SSRF) | ~400 |
| Parameter binder | ~200 |
| Auth/CSRF/signing primitive resolvers | ~500 |
| Token vault | ~150 |
| Result formatter | ~150 |
| Browser backend interface + WebView impl | ~400 |
| Tools (catalog + execute) | ~250 |
| Policy extension | ~100 |
| Error model + failure classes | ~80 |
| **Total** | **~2,500** |

### 构建时导出工具（在 openweb repo 中）

| 组件 | 估算 LOC |
|------|---------|
| Bundle exporter script | ~200-400 |

### Bundled assets

精选 site packages（JSON bundles, compressed）约 2-5 MB。

### 核心 Kotlin 类型

以下为方向性草图，非最终 API。展示 bundle JSON 反序列化后的 model 层形态。

```kotlin
// 从导出 bundle 中加载的 operation entry
data class OperationEntry(
    val operationId: String,
    val method: HttpMethod,
    val path: String,
    val serverUrl: String,
    val summary: String?,
    val permission: PermissionCategory,
    val transport: Transport,           // NODE, PAGE
    val requiresAuth: Boolean,
    val auth: AuthPrimitive?,
    val csrf: CsrfPrimitive?,
    val signing: SigningPrimitive?,
    val pagination: PaginationConfig?,
    val extraction: ExtractionConfig?,
    val adapter: AdapterRef?,
    val actualPath: String?,
    val unwrap: String?,
    val parameters: List<ParamSpec>,
    val requestBody: RequestBodySpec?
)

// Auth primitive discriminated union
sealed class AuthPrimitive {
    data class CookieSession(/* no config */) : AuthPrimitive()
    data class LocalStorageJwt(
        val key: String, val path: String, val inject: Inject
    ) : AuthPrimitive()
    data class PageGlobal(
        val expression: String, val inject: Inject
    ) : AuthPrimitive()
    data class ExchangeChain(
        val steps: List<ExchangeStep>, val inject: Inject
    ) : AuthPrimitive()
    // ... remaining types
}

// 解析完毕、可注入 OkHttp request 的 auth
data class ResolvedAuth(
    val headers: Map<String, String>,
    val cookieString: String?
)

// 构建完毕、可交给 OkHttp 的 request
data class BoundRequest(
    val url: String,
    val method: HttpMethod,
    val headers: Map<String, String>,
    val body: RequestBody?
)

// 执行结果
data class ExecuteResult(
    val status: Int,
    val body: Any?,     // deserialized JSON
    val failureClass: FailureClass? // null on success
)
```

## 分阶段 Rollout

对齐方案的 phase plan 偏保守。

### Phase 1: 导出与 runtime 基础

交付：

- 上游 export pipeline，生成 Android bundle artifacts
- package repository 和 catalog index
- token-vault 基础
- discovery tool
- remote permissions 的 policy seam 设计

### Phase 2: 首个有用的发布

交付：

- Kotlin `node` transport executor
- managed browser backend，足以支持 login/bootstrap 和 allowlist 所需的 primitives
- execution tool
- result formatter
- 精选的、已验证的站点 allowlist

首个有用的发布应优先提供 authenticated 价值，而非仅限公开 demo 站点。

### Phase 3: Browser-backed 扩展

交付：

- 高价值站点的 WebView compatibility matrix
- 按需扩展 browser-backed primitives
- 按需加入 `page` transport
- 按需加入 warm-session 等效机制
- Chrome CDP backend spike（feature flag 保护）

### Phase 4: 高级特性对齐

交付：

- 若 spike 验证可行，选择性支持 CDP-backed 困难站点
- L3 adapter 策略
- WebSocket / AsyncAPI 支持
- package update 与签名机制

## Open Questions

1. 从 `openweb` repo 到 `androidagent` build 的确切导出和交接机制是什么：checked-in exported assets、local copy script、published artifact，还是其他路径？
2. `site_bundle.json` 和 `catalog.json` 的确切 Android bundle schema 是什么，上游 metadata 应原样保留多少 vs 做归一化？
3. MVP allowlist 由哪些具体站点和 primitives 组成，准入和退出标准是什么？
4. 有多少高价值 authenticated 站点在真实设备的 WebView 中能正常工作？部分站点会检测 WebView（User-Agent `wv` flag、TLS fingerprint）并降级或阻断。应在 Phase 2 早期对 10-15 个热门 auth-required 站点做 compatibility spike，以验证 WebView-first 策略。
5. Managed browser backend 在首个发布中是否需要完整的 `page` transport，还是初始 allowlist 可以仅用 `node` transport 加最小 extraction 集？
6. Dedicated managed browser host 的确切实现是什么：hidden virtual display host、user-visible internal activity fallback，还是其他隔离 surface？如何复用现有 VD primitives 而不过载 `VirtualDisplayPlatform`？
7. 针对 per-invocation OpenWeb permission categories 的 `ToolSpec` / `ToolRouter` / `PolicyEngine` 确切扩展点是什么？
8. 完整的 OpenWeb response payload 在执行后存放在哪里，如何暴露给 trace tooling 和 UI 而不污染 LLM context？
9. Shizuku 和 Chrome CDP 在各设备和 Chrome 版本上的可行性边界是什么，包括 cookie access、storage access、evaluation 和 page targeting？
10. Chrome CDP 能否在不对用户活跃的 Chrome session、tabs 和 profile state 造成不可接受干扰的情况下启用和使用？
11. 若 Chrome CDP 可行，当用户有多个 Chrome profiles 或多个站点账户时，如何处理 account 和 profile selection？
12. Tokens 和 sessions 是否应保持 backend-local，还是在 managed backend 和 optional Chrome backend 之间存在安全且有用的 migration/sync 路径？
13. 一旦 backend-owned extraction 成为主要合约，Custom Tabs 或 Auth Tab 在 login bootstrap 中应扮演什么角色（如果有的话）？
14. 为实现 L3 兼容，Android 应定义一个 adapter ABI v2 以实现 backend 可移植性，还是为当前面向 Patchright 的 adapter ABI 构建兼容层？
15. Site-package 的新鲜度何时以及如何从 bundled reviewed assets 发展到 signed package updates？
16. WebView cookie isolation 如何跨站点工作？Android 的 `CookieManager` 是进程全局的——一个站点设置的 cookies 在加载另一个站点时若域名重叠（如 Meta 系属性）也可见。实现应清除站点间 cookies、使用独立 WebView 实例，还是将 extraction 限定到目标域？
