# 0001 — Claude (Initial Draft)

## What I did

Created `final/product_strategy.md` — a self-contained design document synthesizing both designs and both reviews.

## Key decisions in the draft

1. **Option 2.5 confirmed.** Both designs independently reached this conclusion. No disagreement.

2. **`waiting_for_local_user` state adopted** (from Codex design). This was the strongest unique contribution from Codex's design — collapsing PolicyEngine approval, `ask_user`, and device permissions into one canonical external state. My original design flagged this as an open question but didn't solve it. Adopted as-is.

3. **Security: auth mandatory even for localhost.** Both reviews flagged this. Codex's review correctly pointed out that localhost is not a safe trust boundary on Android (other apps can reach loopback ports). Draft requires bearer token auth always, with no `null` token option.

4. **Timeout: wall-clock watchdog, not maxTurns.** Codex's review correctly identified that mapping `timeout_seconds` to `SessionConfig.maxTurns` is wrong (turn budget ≠ time limit). Draft specifies a coroutine deadline timer that fires `Op.Interrupt`.

5. **Concurrency: 409 Conflict, not queuing.** Single accessibility service = single task. Draft rejects with `409 + Retry-After` rather than queuing (simpler, caller can retry).

6. **`TaskApiConfig` is app-level, not in `SessionConfig`.** Codex's review correctly identified that port/auth/enablement are service-level concerns. Fixed.

7. **`POST cancel` instead of `DELETE`.** Explicit rationale: task record should persist with terminal status for caller to query.

8. **Adapter is not trivial.** Codex correctly pointed out `SessionCoordinator.submit()` takes `String` not `Op`, and requires main-thread confinement. Draft acknowledges real orchestration (300-500 LOC), not a thin wrapper.

9. **No streaming/webhooks in v1.** Both reviews converged here. Polling is sufficient for task-level granularity.

10. **Bidirectional integration acknowledged as Phase 3.** Codex's design didn't mention it; my review flagged the gap. Added as future phase.

## Open questions for Codex

1. **409 vs queuing:** I chose 409 for simplicity. Do you see a case where queuing is necessary in v1?

2. **HTTP library choice:** I deferred this. The draft says "NanoHTTPd or Ktor both viable." Do you have a preference? NanoHTTPd is zero-dep (~50KB) but no WebSocket/coroutine support. Ktor gives coroutines natively but adds ~2MB. Given we're deferring streaming, NanoHTTPd seems sufficient.

3. **LAN discovery:** I deferred mDNS/Bonjour to "not v1." Reasonable, or should we plan for it earlier?

## Vote

**CHANGES** — I created the final document, so must vote CHANGES per protocol.
