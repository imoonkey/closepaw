# Review of `design_claude.md`

Reviewed against:

- [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md)
- [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md)
- [OpenWeb architecture.md](/Users/moonkey/workspace/openweb-workspace/openweb/doc/main/architecture.md)
- [OpenWeb runtime.md](/Users/moonkey/workspace/openweb-workspace/openweb/doc/main/runtime.md)
- [OpenWeb adapters.md](/Users/moonkey/workspace/openweb-workspace/openweb/doc/main/adapters.md)
- [Tool system docs](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/infra/tools.md)
- [Platform docs](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/infra/platform.md)
- [ToolName.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/ToolName.kt)
- [PolicyEngine.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt)

## Summary

Claude's draft is strong on ambition and on explaining why OpenWeb matters. It also correctly rejects embedded Node as the product architecture and correctly points toward a Kotlin-native runtime.

The main issue is that it makes the highest-risk path, Chrome CDP via Shizuku, the architectural center instead of an optional backend. That leads to several downstream overclaims: adapter reuse, primitive coverage, auth friction, rollout speed, and Android fit. As a result, the draft is directionally useful but too optimistic to use as the base MVP design without substantial correction.

## Findings

### High: current L3 adapters are not reusable "as-is" through raw CDP `Runtime.evaluate`

Claude claims that L3 adapters can be reused directly and run through CDP evaluation with minimal adaptation in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:191), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:629), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:799), and [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:905).

That is not what the current adapter ABI looks like. The actual adapter contract is `init(page)`, `isAuthenticated(page)`, and `execute(page, operation, params, helpers)`, with a Patchright `Page` object plus injected `pageFetch` and `graphqlFetch` helpers, as shown in [adapters.md](/Users/moonkey/workspace/openweb-workspace/openweb/doc/main/adapters.md:8), [adapters.md](/Users/moonkey/workspace/openweb-workspace/openweb/doc/main/adapters.md:18), [adapter.ts](/Users/moonkey/workspace/openweb-workspace/openweb/src/types/adapter.ts:18), and [adapter-helpers.ts](/Users/moonkey/workspace/openweb-workspace/openweb/src/lib/adapter-helpers.ts:1).

Sample adapters are not just serialized expressions. They use real page APIs such as `page.locator(...).click()`, `page.goto(...)`, and `page.context().cookies()` in [amazon.ts](/Users/moonkey/workspace/openweb-workspace/openweb/src/sites/amazon/adapters/amazon.ts:246) and [facebook-graphql.ts](/Users/moonkey/workspace/openweb-workspace/openweb/src/sites/facebook/adapters/facebook-graphql.ts:403). That means the current L3 package is not "bundle JS and send it to `Runtime.evaluate`". It needs either:

- an adapter ABI v2 designed for Android/browser-backend portability, or
- a real compatibility layer that emulates enough of the Patchright `Page` + helper surface.

This is the single largest correctness issue in the Claude draft.

### High: the design collapses too many browser concerns into one CDP pipe

Claude's architecture centers everything on "Kotlin runtime + CDP auth extraction" in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:145). That is too narrow for what OpenWeb actually dispatches.

The runtime has separate execution paths for:

- `node` transport
- `page` transport
- extraction operations
- L3 adapters
- WS / AsyncAPI

This separation is explicit in [runtime.md](/Users/moonkey/workspace/openweb-workspace/openweb/doc/main/runtime.md:21) and [architecture.md](/Users/moonkey/workspace/openweb-workspace/openweb/doc/main/architecture.md:20).

CDP can help with some of these, but "CDP auth extraction" is not a sufficient system architecture. `page` transport and extraction need a browser execution backend in the hot path. Managed sessions also need an app-owned browser context. Codex's browser-backend abstraction in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:38), [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:195), and [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:210) is the cleaner structure.

### High: tool and policy integration is underspecified and not aligned with the current androidagent seams

Claude proposes one `web_api` tool and says the tool will "report the operation's permission level to the PolicyEngine" in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:522) and [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:586).

The current tool stack does not already have that seam. Policy is driven by `(toolName, params, packageName)` and primarily reasons over tool identity and app tier, per [tools.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/infra/tools.md:73) and [PolicyEngine.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:45). Unknown tools are treated as screen-changing in [ToolName.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/ToolName.kt:11).

So this is not just a runtime detail. It requires an explicit policy extension. Codex calls that out directly in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:424), while Claude assumes it away.

### Medium: raw YAML asset loading recreates upstream validation complexity on-device

Claude keeps raw YAML specs in assets and builds a Kotlin `SpecLoader` to parse them on device in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:177), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:199), and [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:840).

OpenWeb today loads specs and validates `x-openweb` via AJV as part of the runtime pipeline in [runtime.md](/Users/moonkey/workspace/openweb-workspace/openweb/doc/main/runtime.md:8). Re-creating YAML parsing plus schema-validation parity on Android is real work with little product upside. The build-time export proposed in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:41) and [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:146) is a better MVP boundary.

### Medium: the draft overstates site count and readiness

Claude repeatedly frames the project as a 96-site runtime from the start in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:11), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:187), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:531), and [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:791).

That is too aggressive for launch planning. OpenWeb's own architecture doc still distinguishes verified site coverage and lists 55 total verified sites in [architecture.md](/Users/moonkey/workspace/openweb-workspace/openweb/doc/main/architecture.md:217). The first Android rollout should ship a curated allowlist of proven sites, not everything present under `src/sites/`. Codex explicitly scopes MVP that way in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:495).

### Medium: the design blurs L2 page primitives and L3 adapters

Phase 5 groups Discord with Telegram and WhatsApp as "complex L3 sites" in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:793).

But OpenWeb's architecture doc classifies:

- Discord as L2 using `webpack_module_walk` with `page` transport
- Telegram and WhatsApp as L3 adapters

See [architecture.md](/Users/moonkey/workspace/openweb-workspace/openweb/doc/main/architecture.md:210).

This matters because the Android plan should prioritize browser primitive parity before adapter ABI parity. Discord belongs in the former bucket, not the latter.

### Medium: "not needed on Android" removes too much

Claude marks browser lifecycle, warm session, bot detection, and filesystem locks as unnecessary in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:662).

That is too strong. Even if the desktop implementations are not reused, the concepts still exist:

- `warmSession()` is central for browser-backed requests on protected sites in [runtime.md](/Users/moonkey/workspace/openweb-workspace/openweb/doc/main/runtime.md:164) and [warm-session.ts](/Users/moonkey/workspace/openweb-workspace/openweb/src/runtime/warm-session.ts:1)
- bot-block handling is part of browser-fetch and adapter execution in [browser-fetch-executor.ts](/Users/moonkey/workspace/openweb-workspace/openweb/src/runtime/browser-fetch-executor.ts:24) and [adapter-executor.ts](/Users/moonkey/workspace/openweb-workspace/openweb/src/runtime/adapter-executor.ts:149)
- concurrency control still matters for cache and browser/session ownership, even if Android uses coroutines and session-scoped services instead of filesystem PID locks

This should be reframed as "rewrite for Android", not "not needed".

### Medium: the CDP-first auth cascade puts the riskiest dependency on the MVP critical path

Claude's auth cascade makes CDP via Shizuku the main path to the "magic moment" in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:463) and [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:760).

The problem is that the unresolved pieces are not secondary:

- enabling and persisting Chrome debug access
- minimizing Chrome restart/tab disruption
- reliably targeting the right page/origin/profile
- surviving devices without Shizuku

Claude does list these as open questions in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:876), but if CDP is the default architecture, those questions are blocking. Codex makes CDP an optional later backend in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:39), which is the safer dependency order.

### Medium: WebView is rejected too hard, then underused

Claude is right that WebView should not be assumed to read the user's existing Chrome sessions in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:126).

But the draft then treats WebView mostly as a login fallback. That misses its strongest role on Android: an app-owned browser backend for managed sessions, `page` transport, extraction, and controlled auth flows. The current androidagent platform abstraction is for phone UI automation, not arbitrary browser embedding, per [platform.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/infra/platform.md:6). Codex handles this by creating a separate OpenWeb browser host rather than overloading `AndroidPlatform` or the existing virtual display in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:113) and [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:222).

### Medium: the Custom Tab login story does not close the extraction loop

Tier 3 in Claude's auth cascade assumes a Custom Tab can open the site and "postMessage extracts tokens back to app" in [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_claude.md:485).

That is not a complete generic design. OpenWeb auth requires arbitrary site cookies, localStorage, sessionStorage, globals, and sometimes page/module introspection. A generic third-party site will not cooperate with an app-specific `postMessage` bridge. Custom Tabs may help as a user-visible login bootstrap, but the draft still needs a separate controlled extraction backend after login.

## Gaps

- The draft does not specify a browser-backend interface that can cleanly host both managed WebView sessions and optional Chrome/CDP sessions.
- It does not define how full OpenWeb results are summarized into the LLM context versus persisted out-of-band for debugging and UI.
- It does not explicitly place the runtime into `SessionServices` as a session-scoped sibling service instead of leaking it into `AndroidPlatform`.
- It does not narrow MVP scope to a specific primitive/site allowlist, which is necessary if the runtime is to be testable and shippable.

## Trade-off Assessment

- Claude optimizes for the best eventual auth story: real Chrome sessions, real Chrome fingerprint, minimal user setup. That is attractive, especially for harder sites.
- The cost of that choice is dependency inversion: the least certain subsystem becomes the foundation.
- Codex optimizes for an implementable base: validated exported site bundles, Kotlin runtime core, managed WebView backend, and CDP only where it demonstrably adds value.
- The cost of the Codex path is that "use the user's existing Chrome session automatically" is not the day-one story for every site. That is a real product trade-off, but it is the right one for the first aligned draft.

## What To Keep From Claude

- Keep the strong explanation of why OpenWeb matters versus UI automation.
- Keep the rejection of embedded Node, Termux, and JS-runtime polyfill stacks.
- Keep the useful breakdown of primitive categories and auth-cascade thinking.
- Keep the emphasis that CDP/Shizuku is strategically valuable for zero-friction auth and some hard sites.

## What To Prefer From Codex

- Prefer a Kotlin-native runtime with build-time exported site bundles in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:34) and [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:148).
- Prefer a browser-backend abstraction with `ManagedWebViewBackend` first and `ChromeCdpBackend` second in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:42) and [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:197).
- Prefer two-tool integration, `openweb_catalog` and `openweb_execute`, because it better matches current tool/policy seams and prompt-budget constraints in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:45) and [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:386).
- Prefer a curated MVP allowlist and explicit deferral of current adapter ABI, WS parity, and signed updates in [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:486) and [design_codex.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/openweb-runtime/initial/design_codex.md:495).

## Recommendation

Use Claude's draft as input, not as the base document. The best aligned first draft should keep Claude's product framing and CDP upside discussion, but it should inherit Codex's structural choices: exported bundles, session-scoped runtime, browser-backend abstraction, split tool surface, curated MVP, and CDP as optional later backend.

CODEX is the better base for the first aligned draft.
