# Design Review: CLAUDE vs CODEX

**Reviewer:** Claude
**Date:** 2026-04-11

---

## Agreement

Both designs converge on the same core thesis: **Kotlin-native runtime, reuse site packages as data, don't embed Node.js.** Both correctly identify that the value is in the 96 site packages (specs + primitives), not the TypeScript runtime. Both reject embedded Node.js, sidecar, and QuickJS. The convergence here is strong and correct.

Both designs also agree on: OkHttp for HTTP, EncryptedSharedPreferences for token cache, same failure-class vocabulary, same permission taxonomy, L3 adapters deferred past MVP, WebSocket deferred.

The disagreements are architectural — and consequential.

---

## 1. Browser Backend: CDP-First (CLAUDE) vs WebView-First (CODEX)

**This is the central disagreement.**

### CLAUDE's position

CDP to Chrome is the primary auth extraction path. The user is already logged into 20+ sites in Chrome. Extract those credentials via Shizuku + Chrome debug port. WebView is a degraded fallback (Tier 4).

CLAUDE explicitly rejects WebView for auth: "Fatal flaw — WebView and Chrome have separate cookie stores since Android 7+."

### CODEX's position

ManagedWebView is the default and MVP baseline. The user logs in once per site inside the app's WebView. Chrome CDP is optional, feature-flagged, not in MVP. If CDP doesn't work, the whole runtime still stands.

### Assessment

**CODEX is right on architecture. CLAUDE is right on vision.**

CLAUDE's dismissal of WebView is a logical error. The separate cookie store is not a "fatal flaw" — it's a design feature. The managed WebView owns its own sessions. The user logs in once per site in the app-controlled browser. Those credentials are reliable, app-owned, and don't depend on Shizuku or Chrome state. CLAUDE correctly identified the cookie separation fact but drew the wrong conclusion from it.

CLAUDE's CDP-first approach has real product risks that the design underestimates:

- **Chrome debug port on Android is uncertain.** The `--remote-debugging-port` flag via command-line file works on some devices/Chrome versions, not all. Basing the MVP on this is risky.
- **Chrome restart disrupts the user.** CLAUDE acknowledges this ("Warn user before restart") but the mitigation is inadequate. Killing Chrome to enable debugging is a hostile UX.
- **Shizuku is a hard dependency.** Many users won't have Shizuku. A runtime that only shines with Shizuku is a runtime that only shines for power users.
- **No clean degradation path.** CLAUDE's tiers are sequential (1→2→3→4), but tiers 2+3 both require Shizuku. If Shizuku is unavailable, you fall all the way to Tier 4 (WebView), which CLAUDE treats as the worst case.

CODEX's ManagedWebView-first approach is more pragmatic:

- Ships working auth without Shizuku
- Fully app-controlled lifecycle
- Testable in CI (no device-specific Chrome version dependencies)
- CDP becomes a "make it better" optimization, not a "make it work" requirement

However, CLAUDE is right that the "magic moment" — zero-friction auth from existing Chrome sessions — is the compelling product story. Making users re-login to 20 sites in a WebView is real friction. This should be the Phase 3 goal, not dismissed.

**Verdict:** CODEX's browser backend abstraction with WebView as default is the right architecture. CLAUDE's CDP-based Chrome session reuse should be folded in as the Phase 3 accelerator behind CODEX's `OpenWebBrowserBackend` interface.

---

## 2. Spec Packaging: Raw YAML (CLAUDE) vs Build-Time Precompilation (CODEX)

### CLAUDE's position

Bundle raw YAML as Android assets. Parse with SnakeYAML at runtime.

### CODEX's position

Build-time export step: compile OpenAPI YAML + manifests into `site_bundle.json` per site + `catalog.json` index. No YAML parsing or schema validation on device.

### Assessment

**CODEX is clearly right.**

- SnakeYAML is a heavy dependency for Android (~300KB, reflection-heavy, slow on mobile)
- Parsing 96 YAML files on cold start is wasteful
- AJV-equivalent schema validation on Android is unnecessary if validation ran at build time
- JSON parsing (JSONObject/kotlinx.serialization) is already in the app and is 10-50x faster than YAML parsing
- Build-time validation catches spec errors before they reach the device

The cross-repo coupling concern (exporter lives in openweb, consumed by androidagent build) is real but manageable — it's a build script, not a runtime dependency.

**Verdict:** Build-time precompilation is the right call. CLAUDE should adopt this.

---

## 3. Tool Design: Single Tool (CLAUDE) vs Two Tools (CODEX)

### CLAUDE's position

Single `web_api` tool with `action` enum: `list_sites`, `list_operations`, `execute`.

### CODEX's position

Two separate tools: `openweb_catalog` (discovery) and `openweb_execute` (execution).

### Assessment

**CODEX's two-tool design is better.**

- `openweb_catalog` is always safe — PolicyEngine can auto-allow it
- `openweb_execute` carries the permission hint from the operation's category
- Clean separation: discovery is free, execution may need approval
- LLMs handle distinct tools better than action-enum dispatch (less ambiguity in tool selection)
- Matches the existing androidagent tool architecture pattern (one tool = one concern)

Minor concern: the names `openweb_catalog` and `openweb_execute` expose implementation details. Something like `web_lookup` and `web_api` might be cleaner for the LLM. But this is a naming bikeshed, not an architecture issue.

**Verdict:** Two tools. CLAUDE should adopt this.

---

## 4. Result Formatting

### CLAUDE's position

Not addressed beyond "parse response" and "validate against schema."

### CODEX's position

Explicit `OpenWebResultFormatter` — never dump raw JSON into history. Summarize, truncate, persist full payload separately for debugging/UI.

### Assessment

**This is a significant gap in CLAUDE's design.**

For an LLM agent with token budgets, result formatting is not optional. A raw Instagram feed response can be 50KB+ of JSON. Dumping that into the LLM context is both wasteful and confusing. The existing androidagent already manages observation formatting carefully (e.g., ScreenSnapshot → text representation for the LLM, full data persisted in trace).

CODEX correctly identifies this as a first-class component. The formatter should:
- Truncate lists to first N items with count
- Compact small objects to inline text
- Strip verbose metadata the LLM doesn't need
- Persist full response in trace artifacts

**Verdict:** CLAUDE must add result formatting. This is a correctness gap.

---

## 5. Session Scoping and Platform Isolation

### CLAUDE's position

Mentions tool registration in ToolRegistry but doesn't explicitly discuss session scoping or the relationship to existing platform abstractions.

### CODEX's position

Explicitly states: OpenWebRuntime is a session-scoped service, NOT part of AndroidPlatform. OpenWeb browser context gets its own host surface — it must not share VirtualDisplayPlatform.

### Assessment

**CODEX correctly identifies an architectural trap that CLAUDE misses.**

If OpenWeb's browser needs a WebView (or CDP connection), that browser context must be isolated from the agent's app-automation display. The VirtualDisplay is the agent's "phone screen" for navigating apps. If OpenWeb's WebView runs on the same display, it steals the agent's workspace. If it runs on the real display, it's visible to the user during API calls.

CODEX's solution — dedicated hidden browser surface, reusing VD primitives but not VirtualDisplayPlatform itself — is correct.

**Verdict:** CLAUDE's design needs an explicit browser surface isolation section.

---

## 6. Policy Integration

### CLAUDE's position

Maps OpenWeb permissions to PolicyEngine decisions in a table. Doesn't specify the mechanism.

### CODEX's position

Proposes extending ToolSpec with an optional policy hint. OpenWebExecuteTool resolves the operation's permission category at validation time and passes it to PolicyEngine through the normal ToolRouter flow.

### Assessment

**CODEX's approach is cleaner.** It keeps approval in the ToolRouter state machine (VALIDATING → POLICY_CHECK → ...) instead of burying permission logic inside the tool body. The existing PolicyEngine already supports this pattern — it just needs a way for a tool to declare its per-invocation permission level.

CLAUDE's table mapping is correct but the "how" is missing.

**Verdict:** CODEX's policy-hint-on-ToolSpec mechanism should be adopted.

---

## 7. Auth Cascade Design

### CLAUDE's position

Detailed 4-tier cascade with clear flow diagram, specific CDP commands, and concrete Kotlin interface sketches.

### CODEX's position

Higher-level cascade shape (cache → extract → login → retry), backend-specific login bootstrap. Less detailed on the mechanics.

### Assessment

**CLAUDE's auth cascade is better specified.** The 4-tier model with concrete CDP commands (`Network.getCookies`, `Runtime.evaluate`, `DOMStorage.getDOMStorageItems`), the CdpClient interface, and the ChromeDebugPortManager are useful design artifacts even if CDP moves to Phase 3.

CODEX's auth section is architecturally sound but underspecified for implementation. The aligned draft should adopt CLAUDE's cascade detail within CODEX's backend abstraction.

**Verdict:** CLAUDE's auth detail should be folded into CODEX's framework.

---

## 8. Phase Plan

### CLAUDE's position

7 phases. Phase 1 is read-only public APIs (no auth). Auth doesn't arrive until Phase 3 (CDP).

### CODEX's position

4 phases. Phase 2 (MVP) includes node transport + ManagedWebView + auth primitives. Auth is available from the first useful release.

### Assessment

**CODEX's phasing is better.**

CLAUDE's Phase 1 (read-only, no-auth sites) is a proof of concept, not an MVP. HackerNews top stories is not a compelling agent capability. The real value proposition is authenticated access — "show my Instagram feed," "check my GitHub notifications." If auth doesn't land until Phase 3 (~6-8 weeks in), the feature is stuck in demo-ware territory for too long.

CODEX ships auth in the MVP by using ManagedWebView. The user logs into a site once, and from then on the agent has structured API access. That's a usable product.

However, CODEX's Phase 1 (Foundations: export pipeline + catalog tool only) ships zero execution capability. Consider merging CODEX's Phases 1+2 into a single MVP that includes both catalog and execute.

**Verdict:** CODEX's auth-inclusive MVP is right. CLAUDE's 7-phase plan should compress.

---

## 9. Concrete Implementation Detail

### CLAUDE's position

Provides: sealed class hierarchies for auth primitives, data class sketches for BoundRequest/CachedTokens/ExecuteResult, LOC estimates per component (~3,200 total), concrete file layout tree, CdpClient interface.

### CODEX's position

Provides: `OpenWebBrowserBackend` interface, task breakdown with scope + acceptance criteria + dependencies, component listing without type sketches.

### Assessment

**Both bring different strengths.** CLAUDE has more implementation-ready type designs. CODEX has better project management artifacts (task decomposition, acceptance criteria). The aligned draft should have both.

---

## 10. Factual Errors and Gaps

### CLAUDE

1. **WebView dismissal is wrong.** "Fatal flaw" is an overstatement. Separate cookie stores mean separate sessions, which is a valid (and simpler) operating model.
2. **"96 sites" is the current count** but CLAUDE says it in the context of "bundle all 96 as assets." Some sites may not have Android-compatible operations (L3-only sites, page-transport-only sites). The bundle should be a curated allowlist, not the full repo. CODEX correctly calls this out.
3. **Chrome restart mitigation is inadequate.** "Save and restore tabs" via CDP before killing Chrome is fragile and complex. CODEX's approach of avoiding Chrome dependence in MVP is more honest.
4. **Anti-bot section overstates Android advantages.** "CDP detection is rare on mobile" — this is true today but may not remain so. It's not a durable architectural advantage to build on.

### CODEX

1. **WebView anti-bot risk is underspecified.** Some sites actively detect and block WebView (Instagram, LinkedIn). CODEX acknowledges Chrome CDP for "fingerprint-sensitive" sites but doesn't quantify the impact. If 30% of top sites block WebView, the MVP's site coverage shrinks significantly.
2. **Build-time export coupling is acknowledged but not designed.** How does the androidagent build pull from the openweb repo? Git submodule? Published npm artifact? Copy script? This needs a concrete answer.
3. **No LOC estimates or timeline guidance.** Harder to evaluate feasibility.
4. **L3 adapter future path is vague.** "Define an Android/browser-safe adapter ABI v2" is hand-waving. CLAUDE's approach (send adapter JS via CDP Runtime.evaluate) is at least concrete, even if complex.

---

## 11. Shared Blind Spot: WebView Site Compatibility

Neither design adequately addresses which sites work in WebView vs. which require Chrome.

Some sites aggressively detect WebView:
- Different User-Agent (contains "wv" token)
- Different TLS fingerprint (BoringSSL version may differ)
- Missing Chrome extensions/APIs
- Service worker limitations

A compatibility matrix (site × backend) should be produced during Phase 2. If WebView compatibility is poor for high-value sites, CDP moves from "nice to have" to "required for MVP."

---

## Verdict: CODEX is the better base for the aligned draft.

### Why

1. **Browser backend abstraction** — CODEX's `OpenWebBrowserBackend` interface is the right architectural seam. CLAUDE welds CDP into the foundation with no clean escape hatch.

2. **MVP ships auth without Shizuku** — CODEX's ManagedWebView approach delivers the core value proposition (authenticated web API access) without depending on uncertain Chrome debugging capabilities. CLAUDE's MVP is auth-less until Phase 3.

3. **Build-time precompilation** — CODEX correctly avoids YAML parsing and schema validation on device. CLAUDE's raw-YAML approach is simpler but wasteful.

4. **Two-tool design** — cleaner separation of discovery vs execution, better policy integration.

5. **Result formatting** — CODEX identifies a critical LLM-agent concern that CLAUDE misses entirely.

6. **Session and surface isolation** — CODEX explicitly addresses how OpenWebRuntime fits into the existing SessionServices architecture and why the browser surface must be separate from VirtualDisplayPlatform.

7. **Risk management** — CDP is correctly positioned as an optional optimization, not a load-bearing dependency.

### What CLAUDE contributes to the aligned draft

CODEX is the skeleton. CLAUDE provides the muscle:

- **4-tier auth cascade detail** — concrete CDP commands, CdpClient interface, ChromeDebugPortManager design. This becomes the Phase 3 specification within CODEX's `ChromeCdpBackend`.
- **Kotlin type system** — sealed classes for auth primitives, data class sketches for BoundRequest/CachedTokens/ExecuteResult. CODEX's design lacks this implementation-level detail.
- **LOC estimates** — ~3,200 LOC total Kotlin, ~2-5MB bundled assets. Useful for planning.
- **Concrete file layout** — the `openweb/` package tree under `app/src/main/kotlin/`.
- **Anti-bot analysis** — Android's natural advantages (real device, real Chrome, mobile UA) are worth documenting even if not architectural load-bearing.
- **Chrome session reuse vision** — "the magic moment" narrative should be preserved as the Phase 3 north star. The aligned draft should make clear that WebView-first is a pragmatic MVP choice, not the end state.

### Risk to validate early

Both designs defer the question: **how many high-value sites actually work in WebView?** If the answer is "most of them," CODEX's phasing is perfect. If the answer is "half of them are blocked," CDP moves from Phase 3 to Phase 2, and CLAUDE's instinct to lead with CDP is vindicated.

**Recommendation:** Run a WebView compatibility spike (10 top auth-required sites) before committing to the final phase plan.
