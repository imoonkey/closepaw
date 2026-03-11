# Design Review: Codex Mobile Portability Analysis

> Reviewer: Claude | Reviewed: `design_codex.md`

## Overall Assessment

Strong doc. The Codex design arrives at the same core conclusion — "absorb, don't port" — and does it with good architectural discipline. The three-bucket classification (Port Directly / Reinterpret / Do Not Port) is clean and accurate. The emphasis on keeping one control plane and treating all inputs as session ops is exactly right.

**Verdict: Agree on direction. A few gaps and one design disagreement worth surfacing.**

---

## Correctness

### Accurate

- **Baseline inventory** (§Current Baseline) correctly identifies the existing Android Agent subsystems and links to the right files. No errors spotted.
- **"Do Not Port" list** is correct — Node.js runtime, Docker, Puppeteer, inbound WS gateway, unrestricted shell, npm plugins are all rightly rejected.
- **Key Rule** ("all inputs enter as session ops") is well-stated and matches the existing `Op` sealed class design. This should be codified as an architectural invariant.
- **Capability-based portability boundaries** (§2) correctly maps Android's permission model as the replacement for Docker sandbox isolation. The per-capability declaration schema (permission, foreground-only, risk level, data scope) is a solid framework.

### Minor Inaccuracy

- The doc says `ScratchpadState` is "focused on task-scoped working memory." In practice, `ScratchpadState` survives across tasks within a session (it persists through Hot Idle and is included in checkpoints — see `SessionCheckpointCoordinator`). It's session-scoped, not task-scoped. This matters when deciding where cross-session memory sits — it's a separate concern from scratchpad, not just a longer-lived version of it.

---

## Gaps

### 1. No Priority Matrix

The doc identifies what to do but not the order. The phased follow-up (§Phased Follow-Up) lists four steps but doesn't prioritize among them or tie them to the other OpenClaw design projects (1-8). Without priorities, this analysis doesn't help sequence work.

**Suggestion:** Add a priority matrix mapping each absorption target to urgency + dependency, and cross-reference the other project numbers.

### 2. Compression Pipeline Not Compared

The doc acknowledges `HistoryManager` handles compaction but doesn't compare Android Agent's 3-phase compression pipeline against OpenClaw's approach. This is relevant because:
- Android Agent's compression is arguably more sophisticated (proactive screen downgrade, KV cache-friendly deep compression, `COMPRESSION_DIGEST` breadcrumbs)
- OpenClaw may have patterns worth adopting (two-pass summarization verification)

Knowing whether there's anything to absorb from OpenClaw's compaction is part of the portability analysis.

### 3. App Skills Evolution Underexplored

The doc correctly identifies the two extension surfaces (Kotlin modules + asset/config files) but doesn't discuss the gap between current `app_skills/` (read-only, APK-bundled) and a user-writable or dynamically-loadable skill system. The current system requires an APK rebuild for any skill change — fine for development, but a limitation for user customization or runtime adaptation.

### 4. Offline/Disconnected Behavior Absent

Mobile agents lose network. OpenClaw's desktop gateway doesn't. The doc doesn't address what happens when the LLM API is unreachable — should the agent queue tasks? Degrade to cached responses? Notify the user? This is a mobile-specific concern that belongs in a portability analysis.

### 5. Performance/Token Budget Impact Not Discussed

Adding cross-session memory injection, capability declarations, and identity templates all consume tokens. The current `HistoryManager` has a 100K token budget with compression at 85%. How much headroom remains for these new context sources? This constrains the design of memory retrieval (how many memory entries can be injected per turn?).

---

## Design Trade-off Disagreement

### Capability Declaration Schema — Too Abstract?

The doc proposes each capability declare: backing permission, foreground/background, risk level, data scope. This is good as a mental model, but the doc doesn't show how it connects to the existing `PolicyEngine` or `ToolSpec` interface.

Currently, `PolicyEngine` makes approval decisions based on mode (ALWAYS_ASK/AUTO_APPROVE/SMART) and tool name. The proposed capability metadata (permissions, foreground constraint, data scope) would need to be wired into `PolicyEngine.assessRisk()` and potentially into `ToolRegistry` for filtering.

**Concern:** If this stays as a documentation-only contract without concrete integration points, it won't actually improve safety. The doc should show _where_ in the existing code these capability declarations would be checked.

**Suggestion:** Extend `ToolSpec` with optional capability metadata, and have `PolicyEngine` consume it.

```kotlin
interface ToolSpec {
    // ... existing members ...
    val capabilities: ToolCapabilities?  // Optional, for tools that declare them
}

data class ToolCapabilities(
    val requiredPermissions: Set<String>,
    val foregroundOnly: Boolean,
    val riskLevel: RiskLevel,
    val dataScope: DataScope,
)
```

---

## Structural Notes

- **Good:** The "Target Architecture" ASCII diagram cleanly separates Intent Sources → Session Control Plane → Agent Core → Capability Layer → Execution Substrate. This is a useful mental model.
- **Good:** The relay state machine (§Interaction State Machine) is well-scoped — minimal states, explicit backoff, transport-decoupled.
- **Could improve:** The doc is analysis-heavy but implementation-light. It tells you _what_ to do but not _how much work_ each piece is. Even rough T-shirt sizing (S/M/L) would help planning.

---

## Summary

| Aspect | Assessment |
|---|---|
| Core conclusion | ✅ Correct — absorb, don't port |
| Codebase grounding | ✅ Good — references correct files and patterns |
| Completeness | 🔶 Missing priorities, compression comparison, token budget analysis |
| Actionability | 🔶 Good direction, but needs concrete integration points for capability model |
| Disagreements | One: capability declarations need code-level integration plan, not just conceptual schema |
| Overall | Strong analysis doc. Gaps are refinement-level, not directional. |
