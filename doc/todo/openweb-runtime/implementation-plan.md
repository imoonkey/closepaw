# OpenWeb Runtime: 实现计划

## 背景

Design doc 已在 `doc/todo/openweb-runtime/final/design.md` 对齐。目标是先独立实现 runtime、跑通测试，再与 agent 结合。核心问题：怎样的开发/调试工作流最快？

## 决策：在 `:app` debug source set 中加 Debug Activity

**不** 单独建 app。**不** 拆模块。原因：

- androidagent 是 single-module（`:app`），无需重构
- Debug source set 已存在（`app/src/debug/`），VD viewer 就是这种模式
- OkHttp 4.12.0 和 kotlinx-serialization 已在依赖中
- Phase 1 runtime 90% 是纯 Kotlin——JVM unit tests 覆盖，不需要设备
- Debug Activity 是实机集成测试的视觉化面板
- 等 API 表面稳定后再提取成 `:openweb` module

## Step 0: Site Package Export（在 openweb repo 中）

创建 `openweb/scripts/export-android.ts`——将 YAML site packages 转为 Android 友好的 JSON bundles：

- 输入：`dist/sites/{name}/manifest.json` + `openapi.yaml`
- 输出：`dist/android/sites/{name}/site_bundle.json` + `dist/android/catalog.json`
- 将 server-level 的 `x-openweb` 继承（transport, auth, csrf）解析到每个 operation 中
- 将 params、request body、examples 扁平化到一个 JSON 里
- Android 端不需要 YAML parsing

在 androidagent repo 中放一个 copy script `scripts/sync-openweb-bundles.sh`，将 `dist/android/` 拷入 `app/src/main/assets/openweb/`。

**先手动转 2-3 个站点**（hackernews, reddit），解锁 Android 端开发，export script 可以并行建设。

## Step 1: Model Layer（纯 Kotlin）

```
openweb/model/
  SiteBundle.kt          — @Serializable data classes: SiteBundle, ServerEntry, OperationEntry, ParamSpec, RequestBodySpec
  CatalogIndex.kt        — @Serializable: CatalogIndex, CatalogSiteEntry
  AuthPrimitive.kt       — Sealed class: CookieSession, LocalStorageJwt, PageGlobal, ExchangeChain, ...
  FailureClass.kt        — Enum: needs_login, needs_page, bot_blocked, retriable, fatal
  PermissionCategory.kt  — Enum: read, write, delete, transact
```

测试：`test/.../openweb/model/SiteBundleTest.kt`——解析 sample JSON、可选字段缺失、enum 映射。

## Step 2: Package Repository + Catalog

```
openweb/repo/
  OpenWebPackageRepository.kt  — AssetManager 驱动，ConcurrentHashMap 缓存
  OpenWebCatalogIndex.kt       — 加载 catalog.json，按站点名/关键词搜索
```

模式与现有 `AssetAppSkillRepository` 和 `ModelCatalog.fromJson()` 一致。

## Step 3: ParamBinder（纯 Kotlin，测试最密集）

```
openweb/exec/ParamBinder.kt
```

- 解析 `actual_path`（优先于 spec path）
- 替换 `{name}` path params
- 从 `in: query` params 构建 query string（含 defaults）
- 从 `in: header` params 构建 headers
- 构建 JSON 或 form-encoded request body
- 返回 `BoundRequest(url, method, headers, body)`

目标：`ParamBinderTest.kt` 中 15+ test cases。

## Step 4: HttpExecutor（OkHttp）

```
openweb/exec/HttpExecutor.kt
```

- OkHttp 设置 `followRedirects(false)`——手动 redirect 循环
- 每一跳做 SSRF validation（block private IPs、link-local、cloud metadata）
- 最多 5 次 redirect；跨域时 strip auth headers
- 301/302/303 重写为 GET；307/308 保留 method
- 可配置 timeouts（connect 10s, read 30s）

测试：`MockWebServer` 测 redirects、SSRF block、status codes。需添加 `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`。

## Step 5: ResponseHandler + ResultFormatter（纯 Kotlin）

```
openweb/exec/ResponseHandler.kt    — JSON 解析、unwrap（dot-path）、failure classification
openweb/exec/ResultFormatter.kt    — 给 LLM 的紧凑文本、截断列表、去除冗余 metadata
```

Failure classification：401/403 -> `needs_login`，429/5xx -> `retriable`，400/404 -> `fatal`。

## Step 6: OpenWebRuntime Facade

```
openweb/OpenWebRuntime.kt
```

```kotlin
class OpenWebRuntime(
    private val packageRepo: OpenWebPackageRepository,
    private val catalogIndex: OpenWebCatalogIndex,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    suspend fun search(query: String): String
    suspend fun listOps(site: String): String
    suspend fun describeOp(site: String, op: String): String
    suspend fun execute(site: String, op: String, params: Map<String, Any?>): ExecuteResult
    fun formatResult(site: String, op: String, result: ExecuteResult): String
}
```

## Step 7: Debug Activity

```
app/src/debug/kotlin/com/moonkey/androidagent/openweb/OpenWebDebugActivity.kt
app/src/debug/AndroidManifest.xml  (添加 activity entry)
```

**UI 设计：** Site dropdown -> Operation dropdown -> 根据 params 自动生成表单字段 -> Execute 按钮 -> Result viewer（status + formatted text + raw JSON + 耗时）

**ADB 启动：**
```bash
# 交互模式
adb shell am start -n com.moonkey.androidagent/com.moonkey.androidagent.openweb.OpenWebDebugActivity

# 直接执行（脚本化测试用）
adb shell am start ... --es site hackernews --es operation getTopStories --es params '{"tags":"front_page"}'

# Deep link
adb shell am start -a android.intent.action.VIEW -d "openweb://debug?site=hackernews&op=getTopStories"
```

不依赖 AgentService、LLM、SessionServices。纯 runtime 调试。

## 文件布局

```
app/src/main/kotlin/.../openweb/
  model/SiteBundle.kt, CatalogIndex.kt, AuthPrimitive.kt, FailureClass.kt, PermissionCategory.kt
  repo/OpenWebPackageRepository.kt, OpenWebCatalogIndex.kt
  exec/ParamBinder.kt, HttpExecutor.kt, ResponseHandler.kt, ResultFormatter.kt
  OpenWebRuntime.kt

app/src/debug/kotlin/.../openweb/
  OpenWebDebugActivity.kt

app/src/main/assets/openweb/
  catalog.json
  sites/hackernews/site_bundle.json
  sites/reddit/site_bundle.json

app/src/test/kotlin/.../openweb/
  model/SiteBundleTest.kt
  repo/OpenWebCatalogIndexTest.kt, OpenWebPackageRepositoryTest.kt
  exec/ParamBinderTest.kt, HttpExecutorTest.kt, ResponseHandlerTest.kt, ResultFormatterTest.kt
  OpenWebRuntimeTest.kt
  test/OpenWebTestFixtures.kt
```

## 估算

| 类型 | LOC |
|------|-----|
| Runtime 代码 | ~1,500 |
| JVM Unit Tests | ~600 |
| Debug Activity | ~300 |
| Export Script（openweb repo） | ~300 |
| **总计** | **~2,700** |

## Build 变更

`app/build.gradle.kts` 新增一行：
```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

## 验证方式

1. `./gradlew :app:testDebugUnitTest --tests "com.moonkey.androidagent.openweb.*"` — 所有 JVM tests 通过
2. `./gradlew assembleDebug` — 构建无报错
3. ADB 启动 debug Activity -> 选 hackernews -> getTopStories -> Execute -> 看到结构化 JSON 结果
4. ADB intent extras 启动做脚本化测试

## MVP 首批站点（建议）

| 站点 | 为什么选 |
|------|---------|
| hackernews | 无 auth，有 `actual_path` + `unwrap`，多 server，干净 JSON |
| reddit | 无 auth（读操作），有 pagination，`.json` suffix 惯例 |
| wikipedia | 无 auth，REST API，JSON 简洁 |
| github | 需 auth——Phase 2 的 canary |

从 hackernews 开始。它覆盖：path params、query params、`actual_path`、`unwrap`、多 servers、干净 JSON 响应。是 end-to-end 测试的理想首选站点。

## 本计划不包含的内容（后续 phases）

- Auth（WebView backend, TokenVault, auth primitive resolvers）——Phase 2
- Chrome CDP backend——Phase 3
- Agent tool 集成（openweb_catalog, openweb_execute）——runtime 验证通过后再做
- L3 adapters、WebSocket——Phase 4

## 开发节奏

```
主循环：写代码 → JVM unit test → 快速迭代（秒级反馈）
集成循环：assembleDebug → 安装 → Debug Activity → 真实 HTTP（分钟级反馈）
```

纯逻辑的 ParamBinder、ResponseHandler、ResultFormatter 全部在 JVM tests 中验证。只有 HttpExecutor 的真实网络行为和 AssetManager 加载需要走设备。
