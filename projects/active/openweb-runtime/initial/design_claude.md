# OpenWeb Runtime Integration: Architectural Design

**Author:** Claude (ultra-think)
**Date:** 2026-04-11
**Status:** Draft

---

## 1. Vision

Give the Android Agent structured, authenticated access to 96+ web services — Instagram, YouTube, Reddit, GitHub, Telegram, etc. — without scraping UIs. The user's existing browser sessions provide auth. The agent calls typed APIs.

Today the agent navigates web UIs pixel-by-pixel through the accessibility tree. With OpenWeb integration, it calls `web_api("instagram", "getUserProfile", {"username": "elonmusk"})` and gets structured JSON in one turn instead of 8-12 turns of scrolling and text extraction.

**10x reduction in turns. 50x reduction in tokens. Structured data instead of OCR.**

---

## 2. What OpenWeb Gives Us

OpenWeb is a Node.js/TypeScript runtime that provides structured API access to 96 websites via a 3-layer model:

| Layer | Coverage | Mechanism | Browser Needed? |
|-------|----------|-----------|-----------------|
| L1 — Structural Spec | ~40% of sites | Pure OpenAPI HTTP calls | No |
| L2 — Interaction Primitives | ~50% of sites | Declarative auth/CSRF/signing config | Only for auth extraction |
| L3 — Code Adapters | ~10% of sites | Arbitrary JS in browser context | Yes (page.evaluate) |

**L1+L2 = 90% of sites work as pure HTTP once auth tokens are extracted.** This is the key insight.

### 2.1 The Runtime (what it actually does)

```
Load spec → Validate params → Resolve auth → Make HTTP request → Parse response
```

The runtime is ~12K LOC of TypeScript, but conceptually it's:
1. **YAML parser** (OpenAPI 3.1 with `x-openweb` extensions)
2. **Parameter binder** (path substitution, query params, headers, JSON/form body)
3. **Auth resolver** (16 primitive types across 5 categories)
4. **HTTP client** (fetch with redirect following, SSRF validation)
5. **Token cache** (AES-256-GCM encrypted vault)
6. **Browser lifecycle** (Patchright/Playwright for CDP)

### 2.2 Site Packages (the real value)

Each site is a self-contained package:
```
sites/instagram/
  manifest.json       # metadata (name, version, requires_auth, stats)
  openapi.yaml        # L1 spec + L2 primitives in x-openweb extensions
  adapters/           # L3 code (optional, ~10% of sites)
  examples/           # Example responses per operation
```

**These packages are pure data.** They transfer to Android unchanged.

### 2.3 Auth Primitive Catalog

| Category | Types | Purpose |
|----------|-------|---------|
| Auth (6) | cookie_session, localStorage_jwt, sessionStorage_msal, page_global, webpack_module_walk, exchange_chain | Token/cookie extraction |
| CSRF (3) | cookie_to_header, meta_tag, api_response | CSRF token injection |
| Signing (1) | sapisidhash | Per-request signing (YouTube) |
| Pagination (2) | cursor, link_header | Multi-page fetches |
| Extraction (4) | ssr_next_data, html_selector, script_json, page_global_data | DOM data extraction |

---

## 3. Architecture Options Evaluated

### 3.1 Embedded Node.js Runtime

**Idea:** Ship Node.js binary with the APK. Run openweb TypeScript directly.

**Verdict: Dead on arrival.**
- Node.js binary = 30-50MB APK bloat
- Patchright (Playwright fork) doesn't run on Android — it launches desktop Chrome
- No official Node.js Android builds; community projects (node-on-android) are unmaintained
- Memory pressure unacceptable on mobile
- Cold start latency (2-5s for Node.js process)

The Patchright dependency alone kills this. And even if we solved that, running a V8-in-V8 (Node inside Android's ART) is architectural absurdity.

### 3.2 Full Kotlin Rewrite

**Idea:** Rewrite the entire runtime in Kotlin. OkHttp for HTTP, custom auth via CDP.

**Verdict: Right long-term direction, too expensive for MVP.**
- 12K LOC of well-tested TypeScript → Kotlin is ~3-4 months of work
- 16 primitive resolvers, each with edge cases
- Must maintain two codebases or abandon desktop
- L3 adapters (Telegram, WhatsApp) can't be rewritten — they depend on browser page.evaluate()
- Testing burden doubles

But: the core HTTP path (spec → params → auth → request → response) is conceptually simple in Kotlin. OkHttp is arguably a better HTTP client than Node's fetch.

### 3.3 Hybrid: Kotlin + Node.js Sidecar for L3

**Idea:** Kotlin for L1+L2 (90%). Node.js sidecar for L3 adapters only.

**Verdict: Interesting but still has the Node.js-on-Android problem.**
Even a minimal Node.js sidecar needs Patchright for page.evaluate(). Termux could host it, but Termux-as-dependency is fragile. Not production-viable.

### 3.4 CDP-over-Shizuku (from existing doc)

**Idea:** Shizuku forwards Chrome's DevTools Protocol socket. Kotlin CDP client extracts auth. OkHttp makes requests.

**Verdict: Viable and powerful, but CDP client in Kotlin is significant work.**
- Chrome's debug port gives full access: cookies, localStorage, page.evaluate()
- Shizuku can enable Chrome's `--remote-debugging-port` flag
- CDP protocol is well-documented but the client is complex (~2K LOC)
- This IS the right auth extraction mechanism — just not the whole story

### 3.5 QuickJS / Hermes Embedded JS Runtime

**Idea:** Embed lightweight JS runtime (QuickJS ~700KB). Transpile openweb to it.

**Verdict: Polyfill hell negates code reuse benefit.**
- No Node.js APIs (fs, crypto, http) — need massive polyfill layer
- No Patchright — still need CDP client for browser interaction
- Bridge layer (Kotlin ↔ QuickJS) adds complexity
- Debugging is painful
- For L1+L2 (which is pure HTTP), a Kotlin implementation is simpler than a polyfill stack

### 3.6 WebView-Based Auth

**Idea:** Use Android WebView for auth extraction. WebView shares Chrome's cookies.

**Verdict: Fatal flaw — WebView and Chrome have separate cookie stores since Android 7+.**
CookieManager is shared among WebViews within the app, but NOT with Chrome browser. User logged into Instagram in Chrome → WebView has no access to those cookies. This kills the "zero-friction auth" story.

WebView is useful as a **login fallback** (user logs in within our WebView), but not for extracting existing Chrome sessions.

### 3.7 Termux + openweb CLI

**Idea:** Run openweb in Termux. Agent communicates via local socket.

**Verdict: Too fragile for production.** Two-app dependency, no in-APK bundling, poor UX. Interesting for development/testing only.

---

## 4. Recommended Architecture

### Kotlin-Native Runtime + CDP Auth Extraction

**Core principle:** The value is in the site packages (data), not the runtime (code). Rewrite the runtime in Kotlin. Reuse site packages verbatim.

```
┌───────────────────────────────────────────────────────┐
│ Agent (ReAct Loop)                                    │
│   └── web_api tool (ToolSpec implementation)          │
├───────────────────────────────────────────────────────┤
│ OpenWeb Runtime (Kotlin)                              │
│   ├── SpecLoader        YAML → typed model            │
│   ├── OperationResolver find op by ID, merge x-openweb│
│   ├── ParamBinder       path + query + headers + body │
│   ├── AuthResolver      primitive type dispatch       │
│   ├── CsrfResolver      mutation-only CSRF injection  │
│   ├── SigningResolver   per-request signing            │
│   ├── HttpExecutor      OkHttp + redirects + SSRF     │
│   ├── ResponseHandler   parse + validate + unwrap     │
│   └── Paginator         cursor + link_header          │
├───────────────────────────────────────────────────────┤
│ Auth Layer                                            │
│   ├── TokenCache        EncryptedSharedPreferences    │
│   ├── CdpAuthExtractor  Chrome cookies/storage via CDP│
│   ├── CdpPageEvaluator  JS eval for page_global etc.  │
│   └── WebViewLogin      Fallback: user logs in here   │
├───────────────────────────────────────────────────────┤
│ Chrome Bridge (via Shizuku)                           │
│   ├── ChromeDebugPortManager  enable/detect debug port│
│   ├── CdpClient               WebSocket to CDP        │
│   └── CdpSession              page targeting + eval   │
├───────────────────────────────────────────────────────┤
│ Assets                                                │
│   └── openweb_sites/   bundled site packages (YAML)   │
└───────────────────────────────────────────────────────┘
```

### Why This Architecture

1. **90% of operations are pure HTTP.** Once auth tokens are cached, the browser is never touched. OkHttp is faster and more reliable than Node.js fetch on Android.

2. **Auth extraction is a one-time event per site.** Connect to Chrome via CDP, extract cookies/tokens, cache them. Then it's all HTTP. The browser is the auth oracle, not the execution engine.

3. **Site packages transfer 1:1.** 96 sites × OpenAPI YAML + manifests = pure data. Bundle as Android assets. No rewrite.

4. **Kotlin is the right language.** The runtime is fundamentally: parse YAML, build URLs, set headers, make HTTP requests, parse JSON. All of this is idiomatic Kotlin with well-supported libraries.

5. **CDP via Shizuku gives us everything.** Chrome's DevTools Protocol provides: cookies (`Network.getCookies`), localStorage/sessionStorage (`DOMStorage.getDOMStorageItems`), JavaScript evaluation (`Runtime.evaluate`), page navigation. This covers ALL 16 auth primitive types.

6. **L3 adapters work via CDP too.** `Runtime.evaluate` on the Chrome page = equivalent to Patchright's `page.evaluate()`. We can run the same adapter JavaScript on Android.

---

## 5. Component Design

### 5.1 SpecLoader

**Responsibility:** Parse OpenAPI YAML + x-openweb extensions into typed Kotlin models.

**Library:** `org.yaml.snakeyaml` (already widely used in Android) or `com.charleskorn.kaml` (Kotlin-native YAML).

**Key types (mirroring openweb's TypeScript):**

```kotlin
data class SitePackage(
    val name: String,
    val root: String,       // asset path
    val manifest: Manifest,
    val spec: OpenApiSpec,
    val operations: Map<String, OperationEntry>
)

data class OperationEntry(
    val operationId: String,
    val method: HttpMethod,
    val path: String,
    val summary: String?,
    val permission: PermissionCategory,  // read, write, delete, transact
    val xOpenweb: XOpenWebOperation?
)

data class XOpenWebOperation(
    val transport: Transport?,           // node or page
    val auth: AuthPrimitive?,
    val csrf: CsrfPrimitive?,
    val signing: SigningPrimitive?,
    val pagination: PaginationPrimitive?,
    val extraction: ExtractionPrimitive?,
    val adapter: AdapterRef?,
    val permission: PermissionCategory?,
    val actualPath: String?,             // for virtual paths
    val unwrap: String?,                 // response path extraction
    val requiresAuth: Boolean?
)

sealed class AuthPrimitive {
    data class CookieSession(...) : AuthPrimitive()
    data class LocalStorageJwt(val key: String, val path: String, val inject: Inject) : AuthPrimitive()
    data class SessionStorageMsal(...) : AuthPrimitive()
    data class PageGlobal(val expression: String, val inject: Inject, ...) : AuthPrimitive()
    data class WebpackModuleWalk(...) : AuthPrimitive()
    data class ExchangeChain(val steps: List<ExchangeStep>, val inject: Inject) : AuthPrimitive()
}
```

**Spec caching:** Parse once on first access, cache in memory per site. Specs are immutable at runtime.

### 5.2 ParamBinder

**Responsibility:** Given an operation spec + user params, produce: final URL, headers map, request body.

Direct port of openweb's `request-builder.ts` logic:

```kotlin
class ParamBinder {
    fun bind(
        serverUrl: String,
        operation: OperationEntry,
        params: Map<String, Any?>
    ): BoundRequest {
        val allParams = resolveAllParameters(operation)
        validateParams(allParams, params)
        val path = substitutePath(operation.path, allParams, params)
        val url = buildQueryUrl(serverUrl, path, allParams, params)
        val headers = buildHeaderParams(allParams, params)
        val body = buildRequestBody(operation, allParams, params)
        return BoundRequest(url, headers, body)
    }
}

data class BoundRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: RequestBody?   // OkHttp RequestBody (JSON or form-encoded)
)
```

### 5.3 AuthResolver

**Responsibility:** Given an auth primitive config + cached tokens (or CDP handle), produce auth headers.

```kotlin
interface AuthPrimitiveResolver {
    suspend fun resolve(
        config: AuthPrimitive,
        cachedTokens: CachedTokens?,
        cdpSession: CdpSession?     // null if tokens are cached
    ): ResolvedAuth
}

data class ResolvedAuth(
    val headers: Map<String, String>,
    val cookieString: String?
)
```

**Resolution strategy (mirroring openweb's 4-tier cascade):**

```
1. Check TokenCache → if valid, resolve from cached cookies/localStorage
2. If cache miss/expired → connect CDP to Chrome → extract via primitive
3. If Chrome not available → present WebView login
4. Cache extracted tokens → resolve from cache
```

**Primitive-specific resolvers (each ~20-50 LOC in Kotlin):**

| Primitive | Cached Resolution | CDP Resolution |
|-----------|------------------|----------------|
| cookie_session | Inject cached cookies as header | `Network.getCookies({urls: [serverUrl]})` |
| localStorage_jwt | Read cached localStorage[key], traverse path | `Runtime.evaluate("localStorage.getItem('key')")` |
| sessionStorage_msal | Read cached sessionStorage, filter by scope | `Runtime.evaluate("sessionStorage.getItem('key')")` |
| page_global | Read cached value (if deterministic) | `Runtime.evaluate("window.ytcfg.data_")` |
| webpack_module_walk | Cannot cache — always CDP | `Runtime.evaluate(walkScript)` |
| exchange_chain | Cannot cache result — re-execute chain | OkHttp calls with cached cookies |

### 5.4 HttpExecutor

**Responsibility:** Execute HTTP request with auth, CSRF, signing. Handle redirects and SSRF.

```kotlin
class HttpExecutor(
    private val client: OkHttpClient,
    private val ssrfValidator: SsrfValidator
) {
    suspend fun execute(
        request: BoundRequest,
        auth: ResolvedAuth?,
        csrf: ResolvedCsrf?,
        signing: ResolvedSigning?,
        method: HttpMethod
    ): ExecuteResult {
        val finalHeaders = mergeHeaders(request.headers, auth, csrf, signing)
        val okHttpRequest = buildOkHttpRequest(request.url, method, finalHeaders, request.body)
        ssrfValidator.validate(request.url)
        val response = withRedirectFollowing(okHttpRequest, maxRedirects = 5)
        return parseResponse(response)
    }
}
```

**OkHttp configuration:**
- Disable automatic redirect following (we handle it manually for SSRF + header stripping)
- Connection pool with keep-alive (reuse across operations)
- Timeouts: connect 10s, read 30s (configurable per-site)
- No cookie jar (cookies injected via headers, not OkHttp's CookieJar)

**Redirect handling (mirrors openweb's redirect.ts):**
- Max 5 redirects
- SSRF validate each hop
- Strip Authorization, Cookie, X-CSRF-* on cross-origin redirect
- 301/302/303 → rewrite to GET, drop body
- 307/308 → preserve method + body

### 5.5 TokenCache

**Responsibility:** Encrypted per-site token storage.

```kotlin
class TokenCache(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        "openweb_token_cache",
        MasterKey.DEFAULT_MASTER_KEY_ALIAS,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun read(site: String): CachedTokens?
    fun write(site: String, tokens: CachedTokens)
    fun clear(site: String)
    fun isExpired(tokens: CachedTokens): Boolean
}

data class CachedTokens(
    val cookies: List<SerializedCookie>,
    val localStorage: Map<String, String>,
    val sessionStorage: Map<String, String>,
    val capturedAt: Instant,
    val ttlSeconds: Long = 3600,
    val jwtExp: Long? = null
)
```

**vs. openweb's vault:** Desktop uses AES-256-GCM with PBKDF2 machine binding. Android uses `EncryptedSharedPreferences` backed by Android Keystore — hardware-backed encryption, no machine fingerprint needed. Simpler and more secure.

### 5.6 CdpClient

**Responsibility:** WebSocket-based Chrome DevTools Protocol client.

```kotlin
class CdpClient(
    private val debugUrl: String   // ws://localhost:9222/devtools/page/...
) {
    suspend fun send(method: String, params: JsonObject? = null): JsonObject
    suspend fun getCookies(urls: List<String>): List<CdpCookie>
    suspend fun evaluate(expression: String): JsonElement?
    suspend fun getLocalStorage(origin: String): Map<String, String>
    suspend fun getSessionStorage(origin: String): Map<String, String>
    suspend fun navigateTo(url: String)
    fun close()
}
```

**Implementation:** OkHttp WebSocket → JSON-RPC over WebSocket. CDP is a simple request/response protocol with notifications.

**Key CDP commands used:**

| Command | Purpose |
|---------|---------|
| `Network.getCookies` | Extract cookies for URLs |
| `Runtime.evaluate` | Execute JavaScript in page context |
| `DOMStorage.getDOMStorageItems` | Read localStorage/sessionStorage |
| `Page.navigate` | Navigate to URL for auth extraction |
| `Target.getTargets` | List open tabs/pages |
| `Target.attachToTarget` | Connect to specific tab |

**Size estimate:** ~500-800 LOC for a minimal CDP client sufficient for auth extraction.

### 5.7 ChromeDebugPortManager

**Responsibility:** Enable and manage Chrome's remote debugging port via Shizuku.

```kotlin
class ChromeDebugPortManager(
    private val shizukuClient: ShizukuClient  // existing in androidagent
) {
    /** Check if Chrome has debug port open */
    suspend fun isDebugPortActive(): Boolean

    /** Start Chrome with debug port (kills and restarts) */
    suspend fun enableDebugPort(port: Int = 9222): Boolean

    /** Get WebSocket debug URL for a page matching origin */
    suspend fun getPageTarget(origin: String): String?

    /** List all open Chrome tabs */
    suspend fun listTargets(): List<CdpTarget>
}
```

**How Chrome debug port is enabled:**
1. Check if Chrome is already running with `--remote-debugging-port`
2. If not: use Shizuku shell to set Chrome's command-line flags file:
   ```
   echo "chrome --remote-debugging-port=9222" > /data/local/tmp/chrome-command-line
   ```
   Then restart Chrome via `am force-stop com.android.chrome && am start ...`
3. Alternative: Use Chrome Beta / Chrome Canary which are more debug-friendly
4. Connect via `http://localhost:9222/json` to discover pages

**Risk:** Restarting Chrome disrupts user's browsing. Mitigations:
- Prefer Chrome Beta/Dev/Canary if installed (separate process)
- Only restart when auth extraction is needed (not for every API call)
- Warn user before restart
- Save and restore Chrome tabs (via CDP `Target.getTargets` before restart)

---

## 6. Auth Strategy — The 4-Tier Cascade (Android Edition)

```
┌─ Tier 1: Token Cache ─────────────────────────────────┐
│ EncryptedSharedPreferences                             │
│ Cached cookies + localStorage + sessionStorage         │
│ → Pure OkHttp request (no browser, no Shizuku)        │
│ If 401/403 → clear cache, fall to Tier 2              │
└───────────────────────────────────────────────────────┘
        │ miss / expired / 401
        ▼
┌─ Tier 2: CDP Auth Extraction ─────────────────────────┐
│ Shizuku → Chrome debug port → CDP WebSocket           │
│ Extract cookies, localStorage, sessionStorage, globals │
│ Write to TokenCache → retry request                    │
│ If Chrome not running / no matching page → Tier 3     │
└───────────────────────────────────────────────────────┘
        │ Chrome unavailable / not logged in
        ▼
┌─ Tier 3: Agent-Assisted Login ─────────────────────────┐
│ Agent opens Chrome → navigates to site login page      │
│ User logs in manually (or agent assists)               │
│ Agent detects login success via CDP polling             │
│ → Extract auth via Tier 2 → cache → execute           │
└───────────────────────────────────────────────────────┘
        │ Shizuku unavailable
        ▼
┌─ Tier 4: In-App WebView Login ─────────────────────────┐
│ Present WebView with site's login page                 │
│ User logs in (separate session from Chrome)            │
│ Capture WebView cookies → write to TokenCache          │
│ → Execute with cached tokens                           │
└───────────────────────────────────────────────────────┘
```

### Why This Order

**Tier 1 (cache)** handles the steady state. Once tokens are extracted, 99% of requests never touch the browser. Fast, reliable, zero user disruption.

**Tier 2 (CDP)** leverages the user's EXISTING Chrome sessions. They're already logged into Instagram, GitHub, YouTube. We extract those credentials with zero friction. This is the "magic" moment — the agent just works because the user is already logged in.

**Tier 3 (agent-assisted login)** uses the agent's existing superpower — navigating UIs. If the user isn't logged into a site, the agent can open Chrome, navigate to the login page, and ask the user to log in. Once they do, the agent extracts auth via CDP. This is uniquely powerful on Android — desktop openweb can't do this.

**Tier 4 (WebView fallback)** is for devices without Shizuku. Separate cookie jar from Chrome, but still functional. User logs in once per site within our controlled WebView.

### Anti-Bot Advantages on Android

Android has BETTER anti-bot posture than desktop openweb:

1. **Real Chrome, real device** — genuine TLS fingerprint, no Patchright patches needed
2. **User's actual sessions** — cookies and tokens are from real human browsing
3. **Mobile user agents** — anti-bot vendors focus on desktop automation
4. **CDP detection is rare on mobile** — Puppeteer/Playwright detection targets desktop Chrome
5. **No Blink feature hacks needed** — `--disable-blink-features=AutomationControlled` is a desktop concern

---

## 7. Tool Integration

### 7.1 WebApiTool

```kotlin
class WebApiTool(
    private val runtime: OpenWebRuntime
) : ToolSpec {
    override val name = "web_api"
    override val description = """
        Access web service APIs with the user's existing authentication.
        Provides structured data from 96+ sites including social media, 
        news, shopping, and productivity services.
        
        Usage: Specify site name, operation ID, and operation parameters.
        Call with action="list_sites" to see available sites.
        Call with action="list_operations" and site name to see operations.
    """.trimIndent()

    override val parameterSchema = JSONObject("""{
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["execute", "list_sites", "list_operations"],
                "description": "Action to perform"
            },
            "site": {
                "type": "string",
                "description": "Site name (e.g., 'instagram', 'youtube', 'reddit')"
            },
            "operation": {
                "type": "string",
                "description": "Operation ID from the site's API"
            },
            "params": {
                "type": "object",
                "description": "Operation-specific parameters"
            }
        },
        "required": ["action"]
    }""")
}
```

### 7.2 How the LLM Discovers Operations

**Problem:** The LLM needs to know what APIs are available without bloating the context.

**Solution: Two-phase discovery**

1. **System prompt injection:** A brief catalog of available sites and their capabilities (generated from manifests). ~200 tokens for 20 sites.

```
Available web APIs (use web_api tool):
- instagram: getUserProfile, getUserPosts, getFeed, searchUsers [auth required]
- youtube: searchVideos, getVideoDetails, getChannelInfo, getComments [auth for some]
- reddit: getSubreddit, getPost, getComments, searchPosts [auth for writes]
- hackernews: getTopStories, getStoryDetail, getUserProfile [no auth]
...
```

2. **On-demand detail:** When the LLM calls `web_api(action="list_operations", site="instagram")`, return the full operation catalog with parameter schemas.

3. **App skills integration (future):** Generate per-site skill files from OpenAPI specs. Injected into context when the user's goal mentions a specific service.

### 7.3 Permission Mapping

OpenWeb permissions map to AndroidAgent's PolicyEngine:

| OpenWeb Permission | PolicyEngine Decision | Rationale |
|-------------------|----------------------|-----------|
| `read` (GET/HEAD) | ALLOW | Safe, idempotent |
| `write` (POST/PUT/PATCH) | ASK_USER (SMART mode) | Modifies state |
| `delete` (DELETE) | ASK_USER (always) | Destructive |
| `transact` (checkout/payment) | DENY | Financial — never auto-approve |

The `web_api` tool reports the operation's permission level to the PolicyEngine, which applies the same approval workflow as other tools.

### 7.4 Error Handling

OpenWeb's 10 failure classes map to tool results:

| Failure Class | Tool Result | Agent Action |
|---------------|-------------|--------------|
| `retriable` | Failure (retriable) | Auto-retry with backoff |
| `needs_login` | Failure + guidance | Trigger Tier 3/4 auth |
| `needs_browser` | Failure + guidance | Start Chrome via Shizuku |
| `needs_page` | Failure + guidance | Open site URL in Chrome |
| `permission_denied` | Failure | Report to user |
| `permission_required` | Failure | Request user approval |
| `bot_blocked` | Failure + guidance | Suggest manual action |
| `fatal` | Failure | Stop + report |

---

## 8. What's Reused vs. Rewritten

### 8.1 Reused As-Is (Zero Changes)

| Component | Format | Notes |
|-----------|--------|-------|
| Site packages (96 sites) | YAML + JSON | Bundled as Android assets |
| OpenAPI specs | YAML | Parsed by Kotlin YAML library |
| Manifest files | JSON | Parsed by JSONObject/kotlinx |
| x-openweb extension schema | Embedded in specs | Same semantics, Kotlin types |
| Permission categories | Config | Same 4-tier model |
| Error classification | Concept | Same 10 failure classes |
| Auth primitive taxonomy | Config in specs | Same 16 types, Kotlin resolvers |
| L3 adapter JavaScript | JS files | Sent via CDP Runtime.evaluate |

### 8.2 Rewritten in Kotlin

| Component | Desktop (TS) | Android (Kotlin) | LOC Estimate |
|-----------|-------------|------------------|--------------|
| Spec loader | spec-loader.ts (234) | SpecLoader.kt | ~200 |
| Site package | site-package.ts (127) | SitePackage.kt | ~120 |
| Param binder | request-builder.ts (200) | ParamBinder.kt | ~180 |
| HTTP executor | session-executor.ts (302) | HttpExecutor.kt | ~250 |
| Redirect handler | redirect.ts (85) | RedirectHandler.kt | ~80 |
| SSRF validator | ssrf.ts (60) | SsrfValidator.kt | ~60 |
| Auth resolvers (6) | primitives/*.ts (~400) | auth/*.kt | ~350 |
| CSRF resolvers (3) | primitives/*.ts (~120) | csrf/*.kt | ~100 |
| Signing resolver (1) | primitives/*.ts (~60) | signing/*.kt | ~50 |
| Paginator | paginator.ts (~100) | Paginator.kt | ~80 |
| Token cache | token-cache.ts (286) | TokenCache.kt | ~100 |
| Response handler | response-parser.ts + unwrap | ResponseHandler.kt | ~80 |
| Error model | errors.ts (80) | OpenWebError.kt | ~60 |
| **Total** | **~2,054** | | **~1,710** |

### 8.3 New Android-Specific Code

| Component | Purpose | LOC Estimate |
|-----------|---------|--------------|
| CdpClient | WebSocket CDP client | ~600 |
| ChromeDebugPortManager | Shizuku Chrome management | ~200 |
| WebApiTool | ToolSpec implementation | ~150 |
| WebViewLoginActivity | Fallback login UI | ~200 |
| SiteCatalog | Asset-based site discovery | ~100 |
| AuthCascade | 4-tier auth orchestration | ~200 |
| **Total** | | **~1,450** |

### 8.4 Not Needed on Android

| Component | Why Not |
|-----------|---------|
| Browser lifecycle (browser-lifecycle.ts) | Chrome managed by OS / Shizuku |
| Patchright integration | Using CDP directly |
| Session warm-up (warm-session.ts) | Real Chrome on real device |
| Bot detection (bot-detect.ts) | Mobile = minimal bot detection |
| CLI (cli.ts) | Agent tool, not CLI |
| File-based config | Android SharedPreferences |
| PBKDF2 vault encryption | Android Keystore via EncryptedSharedPreferences |
| Filesystem locks | Android process model handles this |

---

## 9. Security Model

### 9.1 SSRF Protection

Same as desktop — validate every URL before request:
- Block private IPs (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8)
- Block link-local (169.254.0.0/16, fe80::/10)
- Block cloud metadata (169.254.169.254)
- HTTPS only
- Per-hop validation on redirects

### 9.2 Token Security

- Tokens encrypted at rest via Android Keystore (hardware-backed on supported devices)
- Tokens scoped per-site (no cross-site leakage)
- TTL enforcement (default 1 hour, JWT exp respected)
- Clear on app uninstall (SharedPreferences lifecycle)

### 9.3 Permission Gating

- OpenWeb permission categories (read/write/delete/transact) enforced
- Integrated with PolicyEngine approval workflow
- `transact` operations blocked by default (financial safety)
- User prompted for write/delete operations

### 9.4 CDP Security

- CDP connection is local only (localhost:9222)
- Shizuku permission required (user must grant)
- Chrome debug port only enabled when needed
- CDP session scoped to specific page/tab
- No exfiltration of credentials to network (tokens stay on-device)

### 9.5 L3 Adapter Safety

- Adapter JavaScript runs in Chrome's page context (same origin, same permissions)
- Adapters are bundled with the app (not downloaded at runtime)
- No arbitrary code execution — only pre-packaged adapter scripts
- Adapter output validated before use

---

## 10. Phased Rollout

### Phase 1: MVP — Read-Only, Public APIs (2-3 weeks)

**Goal:** Prove the architecture works end-to-end with zero auth complexity.

**Scope:**
- SpecLoader: Parse OpenAPI YAML from bundled assets
- ParamBinder: Path substitution, query params, headers, JSON body
- HttpExecutor: OkHttp with redirect handling and SSRF validation
- ResponseHandler: JSON parsing, unwrap, basic validation
- WebApiTool: Registered in ToolRegistry with list_sites / list_operations / execute
- Bundle 5-10 no-auth sites: hackernews, wikipedia, etc.

**What the agent can do after Phase 1:**
```
User: "What's trending on Hacker News?"
Agent: web_api(action="execute", site="hackernews", operation="getTopStories", params={})
→ Returns structured JSON with top 30 stories, scores, comment counts
```

**Validates:** Spec loading, param binding, HTTP execution, tool integration, LLM discovery.

### Phase 2: Token Cache + Manual Auth (1-2 weeks)

**Goal:** Support authenticated sites with user-provided tokens.

**Scope:**
- TokenCache: EncryptedSharedPreferences storage
- Auth resolvers: cookie_session, localStorage_jwt (the two most common)
- Settings UI: "Add API token" / "Paste cookies" for power users
- Cache invalidation on 401/403 responses
- Expand to auth-required read-only: reddit (read), github (public repos)

**What the agent can do after Phase 2:**
```
User: "Show my GitHub notifications"
Agent: web_api(site="github", operation="getNotifications", params={})
→ (uses cached auth) Returns structured notification list
```

### Phase 3: CDP Auth Extraction (2-3 weeks)

**Goal:** Zero-friction auth from Chrome's existing sessions.

**Scope:**
- CdpClient: WebSocket CDP protocol implementation
- ChromeDebugPortManager: Shizuku-based Chrome debug port management
- CDP auth extractors: cookies, localStorage, sessionStorage, page globals
- 4-tier auth cascade orchestration
- Expand to all L2 auth-required sites

**What the agent can do after Phase 3:**
```
User: "What's my Instagram feed?"
Agent: web_api(site="instagram", operation="getFeed", params={})
→ (extracts auth from Chrome automatically) Returns feed items
```

**This is the "magic" moment — the agent accesses web services using the user's existing sessions with zero setup.**

### Phase 4: Full L2 Coverage (2-3 weeks)

**Goal:** Complete L2 primitive support.

**Scope:**
- CSRF resolvers: cookie_to_header, meta_tag, api_response
- Signing resolver: sapisidhash (YouTube)
- Pagination: cursor + link_header
- exchange_chain auth
- webpack_module_walk (via CDP Runtime.evaluate)
- sessionStorage_msal
- Expand to all 96 sites with L1+L2 support

### Phase 5: L3 Adapter Support (2-3 weeks)

**Goal:** Support the remaining ~10% of sites that need browser-context code.

**Scope:**
- Adapter loading from bundled JS assets
- CDP-based execution: send adapter JavaScript via Runtime.evaluate
- Adapter helpers bridge (pageFetch via OkHttp, error helpers)
- Support Telegram, WhatsApp, Discord (complex L3 sites)

### Phase 6: WebSocket + Advanced (2-3 weeks)

**Goal:** Real-time messaging and advanced features.

**Scope:**
- OkHttp WebSocket transport
- AsyncAPI spec support
- WebSocket auth primitives
- Real-time message streaming
- Connection pooling and lifecycle

### Phase 7: Polish + Scale

- Per-site app skill generation (auto-generate from specs)
- Offline operation catalog (precomputed for LLM context)
- Site package updates (OTA without APK update)
- Performance optimization (spec precompilation, connection pooling)
- Analytics: success rates per site, auth cascade metrics

---

## 11. Key Design Decisions

### 11.1 Single `web_api` Tool vs. Per-Site Tools

**Decision: Single tool.**

Per-site tools (96 tools) would overwhelm the LLM's tool selection. A single `web_api` tool with `list_sites` / `list_operations` / `execute` actions is cleaner. The LLM discovers capabilities on-demand.

Future optimization: inject per-site operation summaries into the system prompt based on the user's detected intent (like existing app skills).

### 11.2 Site Packages as Assets vs. Downloaded

**Decision: Bundled as assets for MVP. OTA updates for future.**

Bundling guarantees offline availability and version consistency. Phase 7 adds OTA site package updates so new sites can be added without APK updates.

Asset path: `assets/openweb_sites/{site}/openapi.yaml`

### 11.3 CDP Client: Build vs. Library

**Decision: Build minimal client.**

Existing Kotlin CDP libraries (e.g., chrome-devtools-kotlin) are heavy and target desktop automation. We need only ~10 CDP commands for auth extraction. A minimal WebSocket-based client (~600 LOC) is lighter and more maintainable than pulling in a full CDP automation library.

### 11.4 Shizuku Dependency for Auth

**Decision: Shizuku required for Tier 2+3 auth, but not for basic operation.**

Phase 1-2 work without Shizuku (public APIs + manual auth). Phase 3+ uses Shizuku for CDP access. Tier 4 (WebView login) provides a Shizuku-free fallback for all phases.

This means the feature degrades gracefully:
- **With Shizuku:** Full 4-tier auth cascade, zero-friction auth from Chrome
- **Without Shizuku:** Manual token entry or WebView login per site

### 11.5 Auth Cache vs. Proxy Cookie Jar

**Decision: Cache-based, not proxy-based.**

Two possible models:
1. **Cache:** Extract tokens once, inject via headers on every request
2. **Proxy:** Route requests through Chrome (via CDP fetch or page.evaluate(fetch))

Cache is better because:
- Requests go directly from OkHttp to the server (no Chrome in the hot path)
- Works even when Chrome is closed
- Lower latency (no CDP round-trip per request)
- Simpler error model (OkHttp errors, not CDP+network errors)

The proxy model is only needed for L3 adapters (Phase 5), where the code must run in browser context.

---

## 12. Open Questions

### 12.1 Chrome Debug Port Persistence

Does Chrome maintain the debug port across restarts? If the user closes Chrome, do we need to re-enable debug port? Research needed on Chrome's command-line flag behavior on Android.

**Mitigation:** Detect Chrome state before Tier 2 auth. If debug port is down, re-enable via Shizuku.

### 12.2 Chrome Tab Disruption

Enabling debug port may require restarting Chrome. How do we minimize user disruption?

**Options:**
- Use Chrome Beta/Dev/Canary (separate process, separate debug port)
- Save and restore tabs via CDP before/after restart
- Only restart when auth extraction is actually needed (cache handles steady state)

### 12.3 Spec Freshness

Web APIs change. Specs bundled in the APK may become stale.

**Mitigation:** Ship verified specs (openweb's verify command validates against live sites). Add OTA update mechanism in Phase 7. Response schema validation warns on drift but doesn't fail.

### 12.4 Multi-Profile Chrome

Some users have multiple Chrome profiles. Which profile's auth do we extract?

**Mitigation:** Default to the active/foreground profile. Potentially list profiles via CDP and let the user choose.

### 12.5 L3 Adapter Compatibility

Desktop L3 adapters assume Patchright's page API. CDP's Runtime.evaluate is similar but not identical. What's the gap?

**Key differences:**
- Patchright: `page.evaluate(fn, args)` — serializes function + args, evaluates in page context
- CDP: `Runtime.evaluate({expression: "..."})` — evaluates string expression

The adapters are already written as string expressions (template literals for page.evaluate). They should work via CDP with minimal adaptation. Needs validation per-adapter.

### 12.6 WebView Login Session Isolation

When a user logs into a site via our WebView (Tier 4), those credentials live in our app's WebView cookie store, separate from Chrome. Should we try to sync them?

**Decision:** No sync. Keep them separate. WebView login creates a parallel session that the agent uses. The user's Chrome sessions are unaffected.

---

## 13. Success Metrics

| Metric | Phase 1 | Phase 3 | Phase 5 |
|--------|---------|---------|---------|
| Sites supported | 5-10 (no auth) | 80+ (L1+L2) | 96 (all) |
| Avg turns to get data | 1-2 | 1-2 | 1-2 |
| Token cost vs UI nav | 50x reduction | 50x reduction | 50x reduction |
| Auth success rate | N/A | >90% (cached) | >90% |
| Cold auth (first time) | N/A | <30s (CDP) | <30s |
| Request latency (cached) | <1s | <1s | <1s |

---

## 14. Appendix: Concrete File Layout

```
app/src/main/
├── kotlin/.../openweb/
│   ├── runtime/
│   │   ├── OpenWebRuntime.kt          # Top-level entry point
│   │   ├── SpecLoader.kt              # YAML → typed models
│   │   ├── SitePackage.kt             # Package loading + operation index
│   │   ├── SiteCatalog.kt             # Asset-based site discovery
│   │   ├── ParamBinder.kt             # URL + headers + body building
│   │   ├── HttpExecutor.kt            # OkHttp execution + redirects
│   │   ├── RedirectHandler.kt         # Redirect following + SSRF
│   │   ├── ResponseHandler.kt         # Parse + validate + unwrap
│   │   ├── Paginator.kt              # Cursor + link_header pagination
│   │   └── OpenWebError.kt           # Error model + failure classes
│   ├── auth/
│   │   ├── AuthCascade.kt            # 4-tier auth orchestration
│   │   ├── TokenCache.kt             # EncryptedSharedPreferences cache
│   │   ├── AuthResolver.kt           # Primitive type dispatch
│   │   ├── CookieSessionResolver.kt  # cookie_session primitive
│   │   ├── LocalStorageJwtResolver.kt # localStorage_jwt primitive
│   │   ├── PageGlobalResolver.kt     # page_global primitive
│   │   ├── WebpackModuleWalkResolver.kt
│   │   ├── ExchangeChainResolver.kt
│   │   ├── CsrfResolver.kt           # CSRF primitive dispatch
│   │   ├── SigningResolver.kt         # sapisidhash
│   │   └── SsrfValidator.kt          # IP blocklist
│   ├── cdp/
│   │   ├── CdpClient.kt              # WebSocket CDP protocol
│   │   ├── CdpSession.kt             # Page targeting + eval
│   │   └── ChromeDebugPortManager.kt # Shizuku Chrome management
│   ├── model/
│   │   ├── OpenApiSpec.kt             # Typed spec model
│   │   ├── XOpenWebExtensions.kt      # x-openweb extension types
│   │   ├── AuthPrimitive.kt          # Sealed class hierarchy
│   │   ├── CsrfPrimitive.kt
│   │   ├── PermissionCategory.kt
│   │   └── ExecuteResult.kt
│   └── tool/
│       └── WebApiTool.kt             # ToolSpec implementation
├── assets/
│   └── openweb_sites/                # Bundled site packages
│       ├── hackernews/
│       │   ├── manifest.json
│       │   └── openapi.yaml
│       ├── instagram/
│       │   ├── manifest.json
│       │   ├── openapi.yaml
│       │   └── adapters/
│       │       └── instagram-api.js
│       └── ... (96 sites)
└── res/
    └── layout/
        └── activity_webview_login.xml  # Fallback login UI
```

**Estimated total new Kotlin code: ~3,200 LOC**
**Estimated bundled assets: ~2-5MB (96 site packages compressed)**
