# Tooling + Platform Review

## Summary

Tool definitions, validation, routing, policy/approval gating, and Android accessibility execution. This layer translates LLM intent into real UI actions.

## High-risk issues (must-fix)

### AccessibilityNodeInfo objects are retained without recycling
- Why it matters: storing `AccessibilityNodeInfo` instances in `ScreenSnapshot` and `rawMap` without recycling can leak memory and keep stale nodes alive, eventually degrading performance or crashing long sessions.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/data/perception/Perceptor.kt` — `snapshot()` stores `rootOriginal` and `nodeMap`; `traverse()` collects nodes.
- Fix: avoid storing raw nodes long-term. Store stable selectors (resource id + bounds + class) and re-resolve at execution time, or use `AccessibilityNodeInfo.obtain()` + `recycle()` for snapshots and release nodes after actions.

### Approval requests can block forever
- Why it matters: `ToolRouter.execute()` waits indefinitely on `deferred.await()`. If the UI never replies, the agent loop stalls permanently.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/infra/tools/ToolRouter.kt` — `ApprovalDecision` await path.
- Fix: add a timeout with a default policy (auto-deny or auto-abort) and emit an approval timeout event.

## Medium issues (should-fix)

### Tool observation is captured but never surfaced
- Why it matters: `BaseTool` captures a post-action observation, but `ToolCallResult` drops it and `Agent` captures a second snapshot, wasting time and tokens.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/tools/base/BaseTool.kt` (`capturePostActionObservation()`), `app/src/main/kotlin/com/moonkey/androidagent/infra/tools/ToolCallResult.kt`.
- Fix: add `observation` to `ToolCallResult.Success`, propagate it through `ToolRouter`, and use it in `Agent.formatToolResult()`.

### Call IDs are short and inconsistent
- Why it matters: ToolRouter generates 8-char IDs, increasing collision risk and making it hard to correlate with LLM tool call ids.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/infra/tools/ToolRouter.kt` — `generateCallId()`.
- Fix: use full UUIDs, or accept caller-provided IDs for correlation.

### Optional numeric parameters silently coerce invalid types
- Why it matters: `optLong/optInt` will return defaults for non-numeric input, allowing invalid tool calls through validation.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/tools/impl/WaitTool.kt` and `SwipeTool.kt`.
- Fix: explicitly validate type when the parameter is present (e.g., `has(...)` + `isNumber` check).

## Low-risk suggestions (nice-to-have)

### PolicyEngine SMART mode always allows medium risk
- Why it matters: medium-risk actions (typing, home) may still require user approval depending on use case.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/infra/policy/PolicyEngine.kt` — `evaluateRisk()`.
- Fix: add a configuration toggle for medium-risk approvals, or defer to `approvalMode`.

### Scroll gesture heuristics are fixed to screen ratios
- Why it matters: devices with unusual aspect ratios or gesture insets may not scroll reliably.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt` — `performScroll()`.
- Fix: incorporate window insets or allow configurable scroll margins.
