# OpenWeb Runtime Integration for Android Agent

## Goal

Give `androidagent` a first-class web-runtime tool so the agent can use site APIs from OpenWeb instead of driving Chrome UI when a supported site exists.

Success means:

- OpenWeb stays the source of truth for site capability: structural specs, primitives, manifests, examples.
- Android Agent gets a native, session-scoped runtime that fits its existing tool/session architecture.
- Auth works on Android without pretending desktop Chrome exists.
- The MVP is small, useful, and testable: no heroic parity work before the first real value lands.

## What OpenWeb Actually Gives Us

From the current OpenWeb runtime and docs, the valuable asset is **not** the Node CLI itself. The real asset is:

1. A site package model: `openapi.yaml`, `asyncapi.yaml`, `manifest.json`, examples, optional adapters.
2. A stable execution contract: `node` transport, `page` transport, extraction ops, WS ops, permission categories, failure classes.
3. A declarative auth model: `cookie_session`, `localStorage_jwt`, `page_global`, `exchange_chain`, CSRF, signing, extraction primitives.

The current implementation in `openweb` proves the model in:

- `src/runtime/http-executor.ts`
- `src/runtime/session-executor.ts`
- `src/runtime/browser-fetch-executor.ts`
- `src/runtime/browser-lifecycle.ts`
- `src/runtime/token-cache.ts`

The Android integration should preserve that model, not try to preserve the desktop runtime process shape.

## Recommendation

Build a **Kotlin-native OpenWeb runtime** inside `androidagent`, but keep OpenWeb site packages as the source of truth.

The key design choices are:

1. **Do not embed the Node runtime as-is.**
2. **Do not make CDP-over-Shizuku the whole architecture.** Treat it as an optional browser backend.
3. **Add a new session-scoped `OpenWebRuntime` service**, separate from `AndroidPlatform`.
4. **Precompile OpenWeb site packages into Android-friendly bundle JSON at build time** instead of parsing raw YAML and redoing AJV validation on-device.
5. **Use a pluggable browser backend abstraction**:
   - `ManagedWebViewBackend` as the default and product baseline
   - `ChromeCdpBackend` as an optional accelerator for hard sites
6. **Expose OpenWeb through two tools**, not hundreds of dynamic per-site tools:
   - `openweb_catalog`
   - `openweb_execute`

This is the only approach that fits both codebases cleanly.

## Why This Wins

### Why not bundle OpenWeb's Node runtime as-is

Rejected.

The current OpenWeb runtime is tied to desktop Node and desktop Chrome lifecycle assumptions:

- `browser-lifecycle.ts` manages a desktop browser process, watchdog, PID files, and system-browser login.
- `commands/browser.ts` copies desktop Chrome profiles, detects macOS/Linux/Windows Chrome paths, and spawns Chrome with `child_process`.
- The runtime expects `patchright` `Browser`/`Page` objects and Node filesystem/process APIs throughout.

Embedding Node on Android still leaves the hardest part unsolved: browser ownership, auth bootstrap, and browser-context JS execution. Once those are rewritten, the runtime is no longer "as-is".

### Why not a thin Kotlin wrapper around a Node sidecar

Rejected as the primary architecture.

It improves code reuse but creates two runtimes, two cancellation models, two storage models, and an IPC boundary around the most failure-prone part of the system. It also does not remove the desktop browser assumptions above.

It is acceptable only as a short-lived compatibility lab, not as the product architecture.

### Why not CDP-over-Shizuku only

Rejected as the only path.

CDP-over-Shizuku is a **browser bridge**, not a full Android runtime design. It is high-value for some sites because it can reuse the user's real Chrome session, but it has hard product risks:

- depends on Shizuku
- depends on Chrome internals and a working on-device CDP bridge
- may interfere with the user's live Chrome state
- is harder to recover/debug than an app-owned browser context

It should exist behind a backend interface, not underneath everything.

### Why not WebView-only forever

Close, but incomplete.

A managed WebView is the best Android-native baseline because it is app-owned and easy to instrument. But some hard sites will want real Chrome session reuse or Chrome's network/fingerprint behavior. So WebView should be the default backend, not the only backend.

## Architecture

```text
LLM
  -> openweb_catalog / openweb_execute
      -> OpenWebRuntime (session-scoped service)
          -> OpenWebPackageRepository
          -> OpenWebCatalogIndex
          -> OpenWebPermissionEngine
          -> OpenWebTokenVault
          -> OpenWebExecutor
              -> NodeTransportExecutor (OkHttp)
              -> PageTransportExecutor (browser backend fetch)
              -> ExtractionExecutor (browser backend evaluate)
              -> PrimitiveRegistry
              -> ResultFormatter
          -> BrowserCoordinator
              -> ManagedWebViewBackend
              -> ChromeCdpBackend (optional/experimental)
```

### Core rule

`AndroidPlatform` remains the abstraction for phone UI automation only.

OpenWeb does **not** belong in:

- `AndroidPlatform.performAction()`
- `VirtualDisplayPlatform`
- `MobileActionTool`

Instead, it is a sibling session service, the same way LLM/history/memory are separate services in `SessionServices.kt`.

This matches the current androidagent architecture:

- tools are registered in `SessionToolingBootstrapper.kt`
- tools can capture their own dependencies in constructors
- `ToolExecutionContext` is intentionally small

## Component Design

### 1. OpenWeb Package Pipeline

**Decision:** OpenWeb packages stay upstream; Android consumes a precompiled bundle.

#### Source of truth

Reuse from `openweb`:

- `src/sites/*/openapi.yaml`
- `src/sites/*/asyncapi.yaml`
- `src/sites/*/manifest.json`
- `src/sites/*/examples/*.json`

#### Android build artifact

Add an exporter in the OpenWeb toolchain that emits:

- `site_bundle.json` per site
- `catalog.json` across all shipped sites
- raw examples
- optional raw adapter source for later phases

The exporter runs **after** upstream validation, so Android does not need to reimplement raw YAML parsing + AJV parity in the MVP.

#### On-device repository layout

Mirror OpenWeb's logical package layout under app-owned storage:

- `assets/openweb/sites/...` for bundled, reviewed packages
- `files/openweb/sites/...` for future app-downloaded updates
- `files/openweb/registry/...` only after signed updates exist

MVP ships bundled assets only.

### 2. OpenWeb Runtime Core

Create a new package, e.g. `app/src/main/kotlin/com/moonkey/androidagent/openweb/`.

Main pieces:

- `OpenWebPackageRepository`
- `OpenWebCatalogIndex`
- `OpenWebExecutor`
- `OpenWebPermissionEngine`
- `OpenWebTokenVault`
- `OpenWebResultFormatter`
- `primitives/*`
- `browser/*`

The runtime preserves OpenWeb semantics:

- transport resolution
- operation-level overrides
- permission categories: `read`, `write`, `delete`, `transact`
- failure classes: `needs_login`, `needs_page`, `bot_blocked`, `retriable`, `fatal`

But the implementation is Android-native:

- OkHttp instead of Node `fetch`
- app-private encrypted storage instead of `vault.json`
- browser backend interface instead of Patchright-owned browser lifecycle

### 3. Browser Backend Abstraction

```kotlin
interface OpenWebBrowserBackend {
    suspend fun ensureContext(site: String, url: String): BrowserContextRef
    suspend fun extractCookies(url: String): List<OwCookie>
    suspend fun evaluate(context: BrowserContextRef, expression: String): Any?
    suspend fun fetch(context: BrowserContextRef, request: BrowserFetchRequest): BrowserFetchResult
    suspend fun openLogin(site: SiteBundle): LoginResult
    suspend fun clearContext(site: String)
}
```

The runtime owns the policy for **which** backend to use. Site packages do not change for this.

#### 3a. ManagedWebViewBackend

This is the default backend and the MVP baseline.

Properties:

- app-owned session
- direct access to `CookieManager`
- direct JS execution via `evaluateJavascript`
- direct access to `localStorage` / `sessionStorage`
- no Shizuku/CDP requirement for core value

Host it on a **dedicated OpenWeb browser surface**, not inside the existing `VirtualDisplayPlatform`.

Reason:

- `VirtualDisplayPlatform` is the phone-automation platform
- it may already be busy automating another app
- an OpenWeb browser host must not steal the agent's app-automation display

So OpenWeb gets its own host:

- preferred: dedicated hidden virtual display host
- fallback: user-visible internal host activity when hidden hosting is unavailable

Implementation note:

Reuse the existing Shizuku/VD primitives and lifecycle patterns (`ShizukuClient.kt`, `VdLifecycleArbiter.kt`, `VirtualDisplayConfig`, launch-on-display flow), but **do not** overload `VirtualDisplayPlatform` itself.

#### 3b. ChromeCdpBackend

Optional, feature-flagged, not in MVP.

Use when:

- a site needs the user's real Chrome session
- a site is known to be Chrome-fingerprint-sensitive
- a site needs page-global or module-walk behavior that is materially better in Chrome

This backend should remain behind an explicit backend interface because the transport risk is higher:

- it needs a reliable on-device CDP bridge
- it depends on Shizuku
- it depends on Chrome availability/state

If the spike fails, the whole runtime must still stand on `ManagedWebViewBackend`.

## Transport Mapping

### `node` transport

Implement natively in Kotlin:

- request binding
- SSRF validation
- redirect handling
- auth/CSRF/signing primitive resolution
- response parsing
- schema-aware result formatting

This is the cheapest and most important path.

### `page` transport

Implemented by the browser backend:

- run `fetch()` inside WebView or Chrome page context
- let browser cookies/CORS/network stack do the work

This is **not** required for phase 1, but the architecture must reserve the seam now.

### extraction ops

Implemented through browser backend `evaluate(...)`.

This maps cleanly for:

- `script_json`
- `ssr_next_data`
- `html_selector`
- `page_global_data`

### L3 adapters

Not in MVP.

Current adapters are tied to the Patchright/Node adapter ABI (`adapter-executor.ts`, injected helpers, `Page` object). That ABI does not transfer cleanly to Android.

Future plan:

- define an Android/browser-safe adapter ABI v2
- or keep a separate compatibility layer only for a small allowlist of adapters

Do **not** promise current L3 adapters as-is in the first Android version.

### WS / AsyncAPI

Not in MVP.

Future path:

- OkHttp WebSocket client
- Android-native WS auth primitive registry
- reuse AsyncAPI specs and operation indexing

## Auth Story

### Canonical model

Keep the same OpenWeb auth cascade shape, but make it backend-owned:

```text
1. TokenVault cache
2. Browser-backed extraction
3. Login bootstrap
4. Retry execution
```

### TokenVault

Store normalized tokens in Android encrypted storage.

Reuse the logical schema from `CachedTokens` in `openweb/src/runtime/token-cache.ts`:

- cookies
- localStorage
- sessionStorage
- capturedAt
- ttlSeconds
- jwtExp

Recommended implementation:

- `EncryptedSharedPreferences` for metadata/index
- encrypted file payloads for larger token bodies if needed
- app-private root under `files/openweb/`

This aligns with patterns already used in:

- `OAuthCredentialStore.kt`
- `AppSettingsStore.kt`

### Cookie/token extraction by backend

#### ManagedWebViewBackend

- cookies: `CookieManager`
- local/session storage: `evaluateJavascript`
- page globals / DOM extraction: `evaluateJavascript`

This is straightforward and reliable because the app owns the browser context.

#### ChromeCdpBackend

- cookies: CDP
- storage/global/module walk: CDP `Runtime.evaluate`

This is valuable, but optional.

### Login bootstrap

Backend-specific:

- `ManagedWebViewBackend`: login must happen in the managed WebView session itself
- `ChromeCdpBackend`: login should happen in Chrome, then CDP extracts

Do not treat Custom Tabs/Auth Tab as the generic answer for all backends. They are only a fit when the backend session is Chrome-owned.

### Site-specific account providers

Do not add AccountManager/OAuth-provider shortcuts in MVP.

They can come later as site-specific optimizations for Google/Microsoft ecosystems, after the generic runtime works.

## Tool Integration

### New tools

#### `openweb_catalog`

Purpose:

- search sites
- list operations for a site
- describe an operation's params, permission, and example payload

Why it exists:

- we cannot expose hundreds of dynamic OpenWeb operations as tool schemas without blowing prompt budget
- we still need the model to discover what is supported

#### `openweb_execute`

Purpose:

- execute a single OpenWeb operation using `site`, `operation`, and free-form `params`

Tool contract:

- accepts nested JSON params
- returns concise text for the LLM
- stores fuller structured payload in `data` and/or a trace artifact

### Prompt rule

Update the agent instructions so the model prefers OpenWeb when:

- the task is about a supported site/service
- a direct site API is better than driving Chrome UI

Fallback rule:

- if OpenWeb cannot satisfy the task, the agent may fall back to normal mobile/web UI automation

### Approval / policy

`openweb_catalog` is always allowed.

`openweb_execute` uses OpenWeb permission categories:

- `read` -> allow
- `write` -> ask
- `delete` -> ask
- `transact` -> deny

MVP implementation should use a small extension to the tool policy seam instead of burying this in prompt text.

Minimal design:

- add an optional policy hint from `ToolSpec`
- `OpenWebExecuteTool` resolves the operation category from the catalog/runtime
- `PolicyEngine` applies the remote-op rule above

This keeps approval in the normal ToolRouter flow and avoids special login/approval hacks inside the tool body.

### Result formatting

Never dump raw JSON directly into history.

Add `OpenWebResultFormatter`:

- small object -> compact JSON-ish text
- list result -> count + first `N` items
- large payload -> summary + truncation marker
- always include site/op/status

Persist the full payload outside the LLM context for debugging/UI later.

## Reuse vs Rewrite

### Reuse as-is

- site packages as source-of-truth input
- manifests, examples, operation IDs, summaries
- permission taxonomy
- failure-class vocabulary
- primitive semantics
- logical package-repository layout

### Reuse with adaptation

- token schema from `CachedTokens`
- transport semantics (`node`, `page`, extraction, WS)
- site lookup/indexing concepts from `site-resolver.ts` and `site-package.ts`

### Must be rewritten for Android

- browser lifecycle
- auth bootstrap
- token storage implementation
- HTTP executors
- redirect/SSRF implementation
- tool integration
- result formatting for LLM budgets
- browser-backed primitive execution

### Explicitly deferred

- current L3 adapter ABI
- WS parity
- user-installed packages
- signed package update system

## Rollout

### Phase 1: Foundations

- Export validated Android site bundles from OpenWeb
- Add `OpenWebPackageRepository`, `OpenWebCatalogIndex`, `OpenWebTokenVault`
- Add `openweb_catalog`

### Phase 2: MVP

- Add Kotlin `node` transport executor
- Add `ManagedWebViewBackend`
- Implement MVP primitives required by the curated allowlist:
  - `cookie_session`
  - `localStorage_jwt`
  - `page_global`
  - `cookie_to_header`
  - `meta_tag`
  - extraction primitives
- Add `openweb_execute`
- Add remote permission gating and result formatter
- Ship a curated allowlist of verified sites, not the whole repo
- Exclude `page` transport, adapter, and WS dependent sites from this first shipment

### Phase 3: Hard-site expansion

- Add `page` transport
- add warm-session equivalent
- add feature-flagged `ChromeCdpBackend`
- add site-level backend overrides

### Phase 4: Full vision

- adapter ABI v2 or constrained adapter host
- AsyncAPI / WS support
- signed package updates
- account selection and per-site backend preferences

## Tasks

### 1. `openweb-android-export`

- Scope: `openweb/scripts/**`, `openweb/src/sites/**`, `androidagent/scripts/**`, `androidagent/app/src/main/assets/openweb/**`
- Acceptance:
  - validated Android bundle JSON is generated from upstream site packages
  - catalog index is emitted
  - androidagent can consume a curated asset bundle without sibling-repo runtime coupling
- Dependencies: none

### 2. `openweb-runtime-core`

- Scope: `app/src/main/kotlin/com/moonkey/androidagent/openweb/**`, `app/build.gradle.kts`
- Acceptance:
  - package repository loads bundles
  - catalog search/describe works
  - executor resolves operations, params, permissions, and error classes
  - encrypted token vault works
- Dependencies: `openweb-android-export`

### 3. `openweb-browser-webview`

- Scope: `app/src/main/kotlin/com/moonkey/androidagent/openweb/browser/**`, shared VD helpers if needed
- Acceptance:
  - managed browser context exists independent of `AndroidPlatform`
  - cookies/storage/global extraction work
  - extraction primitives run against the backend
  - login bootstrap path is defined and testable
- Dependencies: `openweb-runtime-core`

### 4. `openweb-tools-and-prompt`

- Scope: `tool/impl/**`, `tool/ToolName.kt`, `tool/ToolSpec.kt`, `session/SessionToolingBootstrapper.kt`, `session/SessionServices.kt`, `agent/definition/**`, `ui/common/ToolUi.kt`
- Acceptance:
  - `openweb_catalog` and `openweb_execute` are registered
  - the agent prompt prefers OpenWeb over Chrome UI for supported sites
  - results are summarized safely into history
- Dependencies: `openweb-runtime-core`, `openweb-browser-webview`

### 5. `openweb-policy-security`

- Scope: `tool/PolicyEngine.kt`, `tool/ToolRouter.kt` if needed, `openweb/security/**`, settings storage
- Acceptance:
  - remote permission categories are enforced
  - SSRF parity exists for Kotlin HTTP paths
  - MVP ships bundled reviewed packages only
  - transact ops are hard-denied
- Dependencies: `openweb-tools-and-prompt`

### 6. `openweb-chrome-cdp-spike`

- Scope: `app/src/main/kotlin/com/moonkey/androidagent/openweb/cdp/**`, possible Shizuku bridge helpers
- Acceptance:
  - real-device spike proves or disproves stable Chrome cookie/eval/fetch access
  - if proven, backend stays feature-flagged and site-override-driven
  - if not proven, the rest of the runtime remains unaffected
- Dependencies: `openweb-runtime-core`

### 7. `openweb-parity-later`

- Scope: adapters, WS, package updates
- Acceptance:
  - clear follow-on work after MVP without contaminating phase 1/2 scope
- Dependencies: `openweb-tools-and-prompt`, `openweb-chrome-cdp-spike`

## Trade-offs

- We give up maximal code reuse from the current Node runtime to get a runtime that actually fits Android.
- We choose a build-time export step to avoid reimplementing YAML/AJV complexity on-device.
- We choose discovery + execute tools because token budget matters more than perfect one-op-per-tool purity.
- We keep Chrome CDP as an optional backend so the project is not hostage to Shizuku/CDP feasibility.
- We deliberately exclude current L3 adapters and WS from MVP because they are real parity work, not foundations work.

## Final Decision

Integrate OpenWeb into Android Agent as a **Kotlin-native runtime with precompiled site bundles and a pluggable browser backend**.

Default backend: **managed WebView**.

Optional backend: **Chrome CDP via Shizuku**, only after a device spike proves it is stable.

Anything that tries to preserve the current desktop Node runtime shape is complexity without payoff.
