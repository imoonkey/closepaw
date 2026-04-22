# Cross-Review: Codex Performance Design

Reviewer: CLAUDE
Date: 2026-04-08

## Overall Assessment

Codex's review is focused, well-structured, and surfaces several findings that Claude's review either missed or under-weighted. The narrower scope (targeted files only) allowed deeper analysis of specific hotspots.

**Better base: CLAUDE** — broader coverage (26 findings vs 16) with positive findings that confirm correct patterns, plus build config and UI overlay analysis that Codex omitted. However, Codex has unique insights that must be merged.

## Findings Codex Got Right (Overlap)

| Codex ID | Claude ID | Agreement |
|----------|-----------|-----------|
| CPU-1 | A1 | Same finding. Codex rated HIGH; Claude rated MEDIUM. **Codex's rating is more appropriate** — this is the hottest path. |
| CPU-3 | A2 | Same O(n^2) indexOf finding. Both MEDIUM. Agree. |
| CPU-4 | A6 | Same O(n^2) token recomputation. Codex rated HIGH; Claude MEDIUM. **Codex's HIGH is justified** — this scales with session length. |
| MEM-1 | A4 | Same bitmap peak memory finding. Codex HIGH; Claude MEDIUM. Both valid — Codex emphasizes exception-safety angle more (RES-1). |
| IO-1 | B5 | Same per-line flush. Codex rated HIGH; Claude LOW. **Codex's HIGH is more appropriate** — battery impact during traced runs is significant. |
| IO-2 | A9 | Same 3-capture retry. Codex HIGH; Claude MEDIUM. Both valid. |
| RES-4 | A12 | Same R8/minification. Codex MEDIUM; Claude HIGH. **Claude's HIGH is correct** — this affects every user, not just traced runs. |

## Unique Codex Findings (Not in Claude's Review)

### CPU-2: Quadratic text enrichment — ACCEPT
Codex correctly identified that `enrichEmptyTextElements` has a quadratic pattern that Claude only rated LOW (A3). Codex's deeper analysis showing it applies to ALL interactive elements (not just edge cases) and includes `mergedText()` recomputation makes this more impactful than Claude acknowledged. **Upgrade from LOW to MEDIUM.**

### CPU-5: Screen downgrading rescans full history — ACCEPT
Claude missed this entirely. `downgradeOldScreens()` rebuilds screen indices on every new screen observation. With growing sessions, this is O(n) per screen addition, leading to O(n^2) total work across a session. **Good catch, MEDIUM impact.**

### MEM-2: Streaming clients accumulate full responses for unused logging — ACCEPT
Claude noted this in A8 as "fine" because the accumulators are small. Codex correctly points out the accumulators are unnecessary in release builds since `LlmLogger` returns immediately. The fix is trivial (guard with `isVerboseEnabled`) and eliminates dead allocations. **ACCEPT, LOW-MEDIUM impact.**

### RES-1: Bitmap cleanup not exception-safe — ACCEPT WITH CAVEAT
Claude noted (B1) that bitmap lifecycle is "correct and thorough" but acknowledged a theoretical exception gap in B2 for accessibility nodes. Codex identifies the same gap specifically for screenshot bitmaps. **Codex is correct** — only `HardwareBuffer` is in the `finally` block. While exceptions are unlikely in practice (bitmap operations rarely throw), this is a correctness issue worth fixing. **ACCEPT, LOW impact (unlikely but easy to fix).**

### RES-2: `flush()` doesn't actually flush the writer — ACCEPT
Claude missed this. The `WriteOp.Flush` handler only completes the deferred without calling `writer.flush()`. This is a semantic bug, not just a performance issue. **ACCEPT, MEDIUM impact.**

### RES-3: No explicit cancellation for streaming flows — ACCEPT WITH CAVEAT
Codex notes `awaitClose` only logs. In practice, Kotlin's `callbackFlow` cancellation does cancel the coroutine running inside the `produce` block, which would interrupt the blocking network call if it's properly suspending. For OkHttp (used by CodexResponseClient), the call needs explicit `call.cancel()` on close. For the OpenAI SDK client, cancellation depends on SDK internals. **ACCEPT, LOW impact — worth fixing but not critical.**

## Codex Improvement Plan Assessment

Codex's priority ordering has some issues:

1. **Trace batching as P1 item #1 is wrong.** This only affects traced runs (debug mode), not normal operation. R8 minification (Codex P2, item #5) or history compression (Codex P2, item #4) should be higher.

2. **MEM-2 (streaming accumulators) as P1 item #3 is slightly high.** The memory cost is small (a few StringBuilders per LLM call). Correct priority but LOW effort makes it fine as a quick win.

3. **Single-pass Perceptor as P3 item #7 is too low.** Given CPU-1 was rated HIGH, the fix should be P2 at least.

4. **Text enrichment as P3 item #8 is correctly placed** — product-sensitive and needs careful design.

## Impact Rating Disagreements

| Finding | Claude | Codex | Correct |
|---------|--------|-------|---------|
| Tree traversal (CPU-1/A1) | MEDIUM | HIGH | **HIGH** — Codex right |
| Token recomp (CPU-4/A6) | MEDIUM | HIGH | **HIGH** — Codex right |
| Per-line flush (IO-1/B5) | LOW | HIGH | **MEDIUM** — only during traces |
| R8 minification (RES-4/A12) | HIGH | MEDIUM | **HIGH** — Claude right |
| Post-action retries (IO-2/A9) | MEDIUM | HIGH | **MEDIUM** — by design, adaptive fix is P3 |

## Missing from Both Reviews

1. **Coroutine dispatcher usage** — Neither review systematically checked whether heavy operations are on appropriate dispatchers (IO vs Default vs Main).
2. **ProGuard/R8 keep rules complexity** — Both mention enabling R8 but neither estimates the effort of writing keep rules for OpenAI SDK, Shizuku, kotlinx.serialization, etc.
3. **Memory pressure callbacks** — Neither checked if the app registers `onTrimMemory` handlers to release caches under memory pressure.

## Recommended Merged Priority

1. R8 minification (affects all users, every session)
2. History compression O(n^2) fix (affects long sessions)
3. Single-pass Perceptor traversal (affects every capture)
4. Truncation O(n^2) indexOf fix (quick win)
5. FileTraceRecorder flush() bug fix + batching
6. Streaming accumulator guard (quick win)
7. ByteArrayOutputStream pre-sizing (trivial)
8. Screen downgrading incremental tracking
9. Text enrichment optimization
10. Bitmap exception safety
11. Post-action adaptive retries
12. Streaming cancellation hooks
