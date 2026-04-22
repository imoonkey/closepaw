Round 2 review of the aligned draft. The structure and consensus are solid. I made targeted edits to close gaps, not to change direction.

## Changes made to `final/design.md`

### 1. Browser backend hot-path clarification
Added "When the browser backend is involved" section. The draft correctly architectured this but didn't make it explicit: most steady-state operations (L1/L2 with cached auth) are pure OkHttp. The browser backend is only invoked for auth extraction, login, extraction ops, and page transport. This distinction matters for performance reasoning and testability.

### 2. Named WebView explicitly
The managed backend is WebView. Both designs knew this. The draft said "app-owned managed browser backend" without naming it. I named it and documented the three known limitations: site detection (UA `wv` flag), process-global CookieManager, no service workers. These need to be visible in the design so the WebView compatibility spike (open question #4) tests for them.

### 3. Expanded auth cascade with concrete flow
Replaced the 4-line abstract cascade with a state-machine-style flow showing triggers, success/failure paths, and cache invalidation behavior. Added concurrency guard requirement (per-site mutex for in-flight auth).

### 4. Added CachedTokens type and expiry logic
Made the token vault concrete: `CachedTokens` data class, expiry check (JWT exp > TTL > default), EncryptedSharedPreferences alignment.

### 5. Refined BrowserBackend interface
Changed `evaluate(...): Any?` → `evaluateJs(...): String?`. Both WebView's `evaluateJavascript` and CDP's `Runtime.evaluate` return JSON strings. `Any?` creates an impedance mismatch at the abstraction boundary.

### 6. Added implementation estimates
~2,500 LOC new Kotlin code + ~200-400 LOC export tooling + ~2-5MB bundled assets. These help with planning.

### 7. Added key Kotlin type sketches
`OperationEntry`, `AuthPrimitive` sealed class, `ResolvedAuth`, `BoundRequest`, `ExecuteResult`. Directional, not final. Shows the model shape that bundle JSON deserializes into.

### 8. Strengthened result formatter spec
Added target token budgets (~500 for objects, ~1000 for lists) and TraceRecorder integration for full payload persistence.

### 9. Added two open questions (#16, #17)
- #16: WebView cookie isolation across sites (CookieManager is process-global)
- #17: Auth cascade concurrency (per-site mutex for parallel requests)

### 10. Sharpened existing open questions
#4 now names specific WebView detection vectors and recommends a concrete spike scope (10-15 top sites). #6 now asks how VD primitives are reused without overloading VirtualDisplayPlatform.

## What I did NOT change

- Architecture direction (Kotlin-native, WebView-first, CDP optional) — consensus holds
- Phase plan — already correct
- Two-tool design — already correct
- Build-time export — already correct
- Session scoping — already correct

Vote: CHANGES
