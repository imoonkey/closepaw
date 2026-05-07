# OpenWeb Runtime Integration for Android Agent

Status: aligned draft v1

This document is the current self-contained aligned design for integrating OpenWeb into `androidagent`.

Maintenance rule:

- When an open question is resolved, update this document first.
- After the design text reflects the resolved decision, remove or revise the corresponding item in the final `Open Questions` section.

## Goal

Give `androidagent` a first-class web-runtime capability so the agent can use structured site APIs from OpenWeb instead of driving web UI when a supported site and operation exist.

Success means:

- OpenWeb remains the source of truth for site capability.
- Android Agent gets an Android-native runtime that fits its existing session, tool, and policy architecture.
- Auth works on Android without assuming the desktop OpenWeb process model.
- The first useful release is small, testable, and does not depend on the riskiest browser integration path.

## What OpenWeb Contributes

OpenWeb's core value is not the current Node CLI process. The reusable value is:

- site packages: `openapi.yaml`, `asyncapi.yaml`, `manifest.json`, examples, optional adapters
- execution semantics: `node` transport, `page` transport, extraction operations, permission categories, failure classes
- declarative primitives for auth, CSRF, signing, pagination, and extraction

The aligned Android design preserves those semantics while replacing the desktop runtime implementation details with Android-native ones.

## Aligned Decisions

The following points are current consensus:

- The runtime should be Kotlin-native inside `androidagent`.
- OpenWeb site packages remain the upstream source of truth.
- The project should not embed the existing Node runtime as-is.
- The project should not depend on Termux, embedded Node, or embedded JS runtimes as the product architecture.
- The runtime should be a session-scoped service, separate from `AndroidPlatform` and separate from `VirtualDisplayPlatform`.
- Android should consume validated, build-time exported site bundles instead of parsing raw YAML and redoing full schema validation on-device.
- The tool surface should separate discovery from execution.
- The execution path should use OpenWeb's permission categories and failure-class vocabulary.
- The MVP should ship a curated allowlist of verified sites and primitives rather than everything under upstream `src/sites/`.
- Current L3 adapter ABI parity, WebSocket parity, and package-update infrastructure are not MVP scope.

## Architectural Base

The aligned base inherits the structural choices from the Codex draft and folds in Claude's useful implementation detail where it does not increase architectural risk.

### Core shape

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

### Placement in androidagent

`OpenWebRuntime` belongs beside the existing session-scoped services in `SessionServices`, not inside:

- `AndroidPlatform`
- `VirtualDisplayPlatform`
- `MobileActionTool`

The current platform layer is for phone UI automation. OpenWeb is a separate capability with its own runtime, storage, and browser state.

## Package Pipeline

Android should not treat raw upstream YAML as the runtime contract.

Instead:

- OpenWeb validates upstream site packages in its own toolchain.
- A build/export step emits Android-friendly bundle artifacts.
- `androidagent` consumes those exported artifacts as assets for the shipped allowlist.

### Exported artifacts

The current aligned artifact set is:

- `site_bundle.json` per site
- `catalog.json` for discovery/indexing
- examples needed for discovery/help text
- optional raw adapter source only for later phases

### On-device layout

The current aligned storage model is:

- `assets/openweb/sites/...` for bundled reviewed packages
- `files/openweb/sites/...` for possible future downloaded packages
- `files/openweb/registry/...` only after signed updates exist

The first release ships bundled assets only.

## Browser Backend Model

The runtime needs an explicit browser-backend abstraction. The architecture must support more than one backend even if only one ships in the first useful release.

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

This interface is the critical seam that keeps browser strategy from becoming a load-bearing architecture decision.

Important constraint: `evaluateJs` returns `String?` (JSON-serialized), not `Any?`. Both WebView's `evaluateJavascript` and CDP's `Runtime.evaluate` return string results. Keeping the return type as `String?` avoids an impedance mismatch at the abstraction boundary.

### When the browser backend is involved

The browser backend is **not** in the hot path for most operations. The steady-state execution flow for an L1/L2 operation with cached auth is:

```text
openweb_execute → OpenWebExecutor → TokenVault (cache hit)
    → NodeTransportExecutor → OkHttp request → response
```

No browser, no WebView, no CDP. Pure HTTP.

The browser backend is invoked **only** for:

1. **Auth extraction** — when the token cache is empty or expired for a site
2. **Login bootstrap** — when the user must log in to a site for the first time
3. **Extraction operations** — `script_json`, `html_selector`, `page_global_data`, etc.
4. **`page` transport** — operations that must execute fetch inside browser context

This distinction matters for performance, testability, and battery. The majority of agent interactions with OpenWeb are cache-hit HTTP calls.

### Managed browser backend (WebView)

The aligned base uses Android **WebView** as the managed browser backend.

WebView is the correct default because:

- app-owned session state via `CookieManager` and `evaluateJavascript`
- built into Android — zero extra dependencies
- no Shizuku or Chrome debugging required
- testable in CI

Known WebView limitations the implementation must handle:

- **Site detection:** Some sites detect WebView (User-Agent contains `wv`, different TLS fingerprint) and may degrade or block. The WebView compatibility spike (open question #4) should quantify this.
- **Process-global CookieManager:** Android's `CookieManager` is shared across all WebViews in the process. Cookies set for site A are visible to site B if domains overlap (e.g., Meta properties). The implementation should scope cookie extraction to the target site's domain, not dump the entire cookie jar.
- **No service workers or extensions:** Some extraction primitives that depend on SPA state may require navigation + wait before evaluating JS.

The managed WebView host must be isolated from the existing app-automation display. It must not hijack the surface the agent uses to automate other apps.

### Optional Chrome CDP backend

Chrome CDP via Shizuku is valuable, especially for:

- reusing existing Chrome sessions
- harder sites that prefer or require real Chrome behavior
- cases where Chrome fingerprint or session reuse materially improves reliability

But it is not the aligned architectural foundation. It is an optional later backend behind the same interface.

That keeps the runtime viable even if:

- Shizuku is unavailable
- Chrome debugging is unstable on a subset of devices
- enabling CDP creates too much user disruption

## Auth Model

The aligned auth model keeps the same high-level OpenWeb cascade shape while making the steps backend-owned.

### Auth cascade flow

```text
NodeTransportExecutor receives operation request
│
├─ Step 1: TokenVault.read(site)
│   ├─ hit + not expired → resolve auth from cache → execute HTTP → return
│   │   └─ if 401/403 → invalidate cache → fall to step 2
│   └─ miss or expired → fall to step 2
│
├─ Step 2: BrowserCoordinator.extractAuth(site, authPrimitive)
│   ├─ backend has active session for site → extract cookies/tokens
│   │   └─ success → write to TokenVault → execute HTTP → return
│   │   └─ 401/403 → session is stale → fall to step 3
│   └─ no active session → fall to step 3
│
├─ Step 3: BrowserCoordinator.openLogin(site)
│   ├─ present login UI to user (WebView or Chrome depending on backend)
│   ├─ poll/detect login completion
│   │   └─ success → extract auth → write to TokenVault → execute HTTP → return
│   └─ timeout or user cancels → throw needs_login
│
└─ All steps exhausted → throw needs_login with guidance
```

Key behaviors:

- **Cache invalidation is per-site.** A 401 from Instagram does not invalidate Reddit tokens.
- **Step 2 is skipped if the operation does not require auth** (`requires_auth: false` or no auth primitive in spec).
- **Step 3 is interactive.** The agent should inform the LLM that user login is needed. The LLM should relay this to the user via `ask_user` or equivalent.
- **Concurrency guard:** If step 2 or 3 is already in progress for a site (e.g., login UI is open), subsequent requests for the same site should wait for the in-flight auth operation, not start a second one. A per-site mutex or `CompletableDeferred` handles this.

### Token vault

Token storage reuses OpenWeb's logical token schema with Android-native secure storage.

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

Expiry check: if `jwtExp` is present, use it. Otherwise use `capturedAt + ttlSeconds`. Default TTL is 1 hour, matching desktop.

Implementation: `EncryptedSharedPreferences` for index/metadata, encrypted files for larger payloads if needed. Aligns with existing patterns in `OAuthCredentialStore.kt` and `AppSettingsStore.kt`.

### Backend-owned extraction

Auth extraction is backend-specific:

- **WebView backend:** `CookieManager.getCookie(url)` for cookies, `evaluateJavascript` for localStorage/sessionStorage/page globals
- **Chrome CDP backend (future):** `Network.getCookies`, `Runtime.evaluate` for storage/globals, `DOMStorage.getDOMStorageItems` for storage

The aligned design does not assume a generic one-size-fits-all extraction bridge through Custom Tabs or arbitrary postMessage flows.

### Login bootstrap

Login is backend-specific:

- **WebView backend:** login occurs inside the managed WebView. The backend navigates to the site's login URL, the user authenticates, the backend detects successful login (e.g., cookie presence, URL change, page content).
- **Chrome CDP backend (future):** login occurs in Chrome. The backend monitors page state via CDP until auth tokens appear.

Custom Tabs or Auth Tab may later be used as UX helpers, but they are not the core architecture contract.

## Execution Model

The Android runtime should preserve OpenWeb's dispatch model:

- L3 adapter operations override transport dispatch
- extraction operations dispatch before HTTP transports
- transport resolution remains operation-level override, then server-level override, then default

### `node` transport

This is the primary first implementation path.

Android-native implementation:

- parameter binding
- request construction
- auth / CSRF / signing primitive resolution
- SSRF validation
- redirect handling
- response parsing
- response unwrap
- failure classification

Implementation direction:

- OkHttp
- explicit redirect policy
- explicit SSRF checks on each hop
- no dependency on the desktop Node fetch path

### extraction operations

These should be implemented through browser backend evaluation for the supported extraction primitives required by the allowlist.

Likely early extraction support includes:

- `script_json`
- `ssr_next_data`
- `html_selector`
- `page_global_data`

### `page` transport

The architecture must reserve a first-class seam for `page` transport.

The current aligned draft does not require full `page` transport parity in the earliest allowlist release. It should be added when:

- the curated allowlist requires it, or
- browser compatibility results show it is needed earlier

### L3 adapters

Current L3 adapters are not portable as-is.

The existing adapter ABI depends on:

- Patchright `Page`
- injected helper functions such as `pageFetch` and `graphqlFetch`
- adapter lifecycle methods such as `init`, `isAuthenticated`, and `execute`

The aligned MVP therefore does not promise current L3 adapter parity. Later work must choose between:

- adapter ABI v2 designed for backend portability, or
- a compatibility layer that emulates enough of the current ABI

### WebSocket / AsyncAPI

Not MVP.

Later implementation can reuse AsyncAPI package semantics with Android-native WebSocket execution.

## Tool and Policy Integration

The aligned tool shape separates discovery from execution.

Current working names:

- `openweb_catalog`
- `openweb_execute`

Exact naming is not architecture-critical, but the split is.

### Discovery tool

Purpose:

- search supported sites
- list operations for a site
- describe operation params, permission class, and examples

This tool is always safe and should remain cheap for the model to call.

### Execution tool

Purpose:

- execute one OpenWeb operation using site, operation, and nested params

This tool must expose or resolve the operation's permission category so approval remains inside the normal `ToolRouter` and `PolicyEngine` flow.

### Permission mapping

Current aligned mapping:

- `read` -> allow
- `write` -> ask
- `delete` -> ask
- `transact` -> deny

The runtime should not bury these decisions inside tool execution. The approval seam needs an explicit policy extension for remote operation categories.

## Result Handling

The runtime must not dump raw large JSON payloads into the LLM context.

It needs a result formatter that:

- summarizes objects compactly
- truncates list results to first N items with a total count
- strips verbose metadata the LLM doesn't need (pagination tokens, internal IDs, etc.)
- includes site, operation, status, and item count in every formatted result
- preserves the full structured payload outside the prompt context for trace/debug/UI use

Target: formatted results should stay under ~500 tokens for typical single-object responses, ~1000 tokens for list responses. These budgets should be tunable per-operation via the bundle metadata if needed.

Full payloads should be stored alongside the existing trace system (`TraceRecorder`) so they are available for debugging and UI rendering without polluting LLM history.

This is a required runtime component, not a presentation nicety.

## Security Model

The aligned security base is:

- app-private encrypted token storage
- SSRF validation for direct HTTP execution
- redirect validation and auth-header stripping where appropriate
- OpenWeb permission-category enforcement
- hard deny for `transact` operations in the initial design
- bundled reviewed packages only in the first release

The runtime should preserve OpenWeb's failure-class vocabulary so the agent can react correctly:

- `needs_login`
- `needs_page`
- `needs_browser`
- `bot_blocked`
- `retriable`
- `fatal`

## Reuse vs Rewrite

### Reuse directly

- site packages as source input
- manifests, operation IDs, examples, summaries
- permission taxonomy
- failure-class vocabulary
- primitive semantics

### Reuse with adaptation

- token schema
- dispatch semantics
- site lookup and catalog concepts
- auth-cascade structure

### Rewrite for Android

- package loading on device
- HTTP executor
- token storage implementation
- browser lifecycle and browser hosting
- tool integration
- result formatting
- browser-backed primitive execution
- concurrency and lifecycle ownership

### Deferred beyond MVP

- current L3 adapter ABI parity
- full WebSocket parity
- package-update system
- unrestricted user-installed packages

## Implementation Estimates

Rough sizing based on analysis of both the openweb TypeScript runtime and the androidagent codebase.

### New Kotlin code

| Component | Estimated LOC |
|-----------|--------------|
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

### Build-time export tooling (in openweb repo)

| Component | Estimated LOC |
|-----------|--------------|
| Bundle exporter script | ~200-400 |

### Bundled assets

~2-5 MB for curated site packages (JSON bundles, compressed).

### Key Kotlin types

These are directional sketches, not final APIs. They show the shape of the model layer that the bundle JSON deserializes into.

```kotlin
// Operation entry from exported bundle
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

// Resolved auth ready for injection into OkHttp request
data class ResolvedAuth(
    val headers: Map<String, String>,
    val cookieString: String?
)

// Bound request ready for OkHttp
data class BoundRequest(
    val url: String,
    val method: HttpMethod,
    val headers: Map<String, String>,
    val body: RequestBody?
)

// Execution result
data class ExecuteResult(
    val status: Int,
    val body: Any?,     // deserialized JSON
    val failureClass: FailureClass? // null on success
)
```

## Phased Rollout

The aligned phase plan is conservative.

### Phase 1: Export and runtime foundations

Deliver:

- upstream export pipeline for Android bundle artifacts
- package repository and catalog index
- token-vault foundation
- discovery tool
- policy seam design for remote permissions

### Phase 2: First useful release

Deliver:

- Kotlin `node` transport executor
- managed browser backend sufficient for login/bootstrap and the allowlist's required primitives
- execution tool
- result formatter
- curated allowlist of verified sites

The first useful release should prioritize authenticated value, not just public demo sites.

### Phase 3: Browser-backed expansion

Deliver:

- WebView compatibility matrix for high-value sites
- any required expansion of browser-backed primitives
- `page` transport where needed
- warm-session equivalent where needed
- Chrome CDP backend spike behind a feature flag

### Phase 4: Advanced parity

Deliver:

- selected CDP-backed hard-site support if the spike justifies it
- L3 adapter strategy
- WebSocket / AsyncAPI support
- package update and signing work

## Open Questions

1. What is the exact export and handoff mechanism from the `openweb` repo into `androidagent` builds: checked-in exported assets, local copy script, published artifact, or another path?
2. What is the exact Android bundle schema for `site_bundle.json` and `catalog.json`, and how much upstream metadata should be preserved verbatim versus normalized?
3. Which specific sites and primitives make up the MVP allowlist, and what acceptance criteria will be used to admit or remove a site from that allowlist?
4. How many high-value authenticated sites actually work in WebView on real devices? Some sites detect WebView (User-Agent `wv` flag, TLS fingerprint) and degrade or block. A compatibility spike against 10-15 top auth-required sites should run early in Phase 2 to validate the WebView-first strategy.
5. Does the managed browser backend need full `page` transport in the first useful release, or can the initial allowlist stay on `node` transport plus a minimal extraction set?
6. What is the exact implementation of the dedicated managed browser host: hidden virtual display host, user-visible internal activity fallback, or another isolated surface? How does it reuse existing VD primitives without overloading `VirtualDisplayPlatform`?
7. What is the exact `ToolSpec` / `ToolRouter` / `PolicyEngine` extension point for per-invocation OpenWeb permission categories?
8. Where should full OpenWeb response payloads live after execution, and how should those payloads be exposed to trace tooling and UI without polluting LLM context?
9. What is the exact Shizuku and Chrome CDP feasibility envelope across supported devices and Chrome versions, including cookie access, storage access, evaluation, and page targeting?
10. Can Chrome CDP be enabled and used without unacceptable disruption to the user's active Chrome session, tabs, and profile state?
11. If Chrome CDP is viable, how should account and profile selection work when the user has multiple Chrome profiles or multiple site accounts?
12. Should tokens and sessions remain backend-local, or is there a safe and useful migration/sync path between a managed backend and an optional Chrome backend?
13. What role, if any, should Custom Tabs or Auth Tab play in login bootstrap once backend-owned extraction is the primary contract?
14. For L3 parity, should Android define an adapter ABI v2 for backend portability, or build a compatibility layer for the current Patchright-oriented adapter ABI?
15. When and how should site-package freshness move beyond bundled reviewed assets into signed package updates?
16. How should WebView cookie isolation work across sites? Android's `CookieManager` is process-global — cookies set for one site are visible when loading another if domains overlap (e.g., Meta properties). Should the implementation clear cookies between site contexts, use separate WebView instances, or scope extraction to target domains?
17. How should the auth cascade handle concurrent requests? If the agent calls `openweb_execute` for site A while site A's login bootstrap is already in progress (from a prior call), the second request should wait for the in-flight auth, not start a parallel login. What is the coordination primitive — per-site mutex, `CompletableDeferred`, or something else?
