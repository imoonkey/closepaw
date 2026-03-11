# Cross-Review of Codex Design (by Claude)

## Overall Assessment

The Codex design is architecturally stronger than Claude's in one key respect: it identifies that the repo's real gap is **runtime truth propagation**, not feature addition, and proposes a single unifying abstraction (`RuntimeCapabilityContract`) instead of parallel workstreams. This is a better framing.

However, the design has correctness issues regarding current codebase state, and the unifying abstraction may over-couple things that are better left independent.

---

## Correctness Issues

### 1. "Session persistence already exists" — partially true, partially misleading

The design claims session persistence is solved and demotes it. This is accurate for **process-death recovery** (checkpoint + reload), but misses the product gap: sessions are not browsable, resumable by the user, or shareable across entry points. `SessionCheckpointCoordinator` saves a `SessionRuntimeSnapshot` for crash recovery; it does not provide session-as-a-first-class-product-object.

The distinction matters because the Codex design later proposes "workspace unification" (P5) as a separate item — but that work *is* session persistence in the product sense. Calling it solved and then re-introducing it under a different name creates confusion.

**Recommendation:** Acknowledge that infrastructure persistence exists but product-level session identity does not. Keep it as a distinct item, not a renamed P5.

### 2. "PolicyEngine has approval modes plus low/medium/high risk" — needs verification

The design states risk levels already exist in `PolicyEngine`. From codebase exploration, `PolicyEngine` makes allow/deny/ask decisions based on approval mode configuration, but **tool risk level as a typed property on `ToolSpec` does not exist yet**. The policy decisions are mode-based (auto-approve all, ask for all, etc.), not risk-based per tool.

If the design is counting on refactoring an existing risk classification, there isn't one to refactor — it needs to be built.

**Recommendation:** Treat tool risk levels as new work, not a refactor of existing risk tables.

### 3. "AgentDef" as persona target — accurate but incomplete

The design proposes replacing hardcoded `AgentDef` objects with asset-backed manifests. This is directionally correct — `AgentDefRegistry` does hand-build `AgentDef` instances. But `AgentDef` currently carries more than persona: it carries tool allowlists, delegation config, and execution constraints. Asset-backing all of this at once is a larger change than the design acknowledges.

**Recommendation:** Separate concerns: externalize prompt content first (small), then tool/delegation config (medium). Don't try to asset-back the entire `AgentDef` in one step.

---

## Design Trade-off Disagreements

### 4. RuntimeCapabilityContract as a single abstraction — over-coupling risk

The proposed contract bundles three orthogonal concerns:
- **capabilities** (what the platform can do)
- **toolAvailability** (which tools are registered)
- **policyProfile** (what risk level each tool carries)

These change at different rates and for different reasons:
- Capabilities change when permissions are granted/revoked (rare, platform-driven)
- Tool availability changes when services connect/disconnect (moderate, system-driven)
- Policy changes when the user adjusts settings or a remote entry point connects (user-driven)

Bundling them into one contract means any change triggers a full contract rebuild. The state machine (`Unprobed → Probed → Advertised → Enforced → Updated`) makes this explicit — `Updated` implies full re-derivation.

**Counter-proposal:** Keep these as three independent, lightweight mechanisms:
1. `ToolSpec.isAvailable()` — each tool checks its own preconditions (Claude T1-2)
2. `ToolSpec.riskLevel` — static property per tool, policy engine reads it (Claude T1-3)
3. `SessionConfig` already carries platform mode and feature flags

This achieves the same runtime-truth goal with less coupling and no new abstraction. The contract is implicit in the existing seams rather than explicit as a new object.

### 5. Prompt externalization "too small alone" — disagree on timing

The Codex design argues prompt externalization alone "mostly improves editing convenience, not execution quality" and should wait for the broader persona asset system. I disagree with the timing, not the direction.

Prompt externalization is a 4-hour change that immediately accelerates eval iteration. Every autotune round that runs against hardcoded prompts is slower than it needs to be. The Codex design's P3 (full persona manifests) is a multi-day project that depends on P1 and P2.

Doing the simple extraction now doesn't conflict with the broader persona vision later — it's strictly additive. Waiting for the full persona system to be designed before touching prompts is premature coupling.

### 6. Memory ordering — agree with Codex's reasoning

The Codex design places memory (P4) after capability/policy stabilization, arguing that memory trained against an unstable tool surface will overfit. This is a better argument than Claude's original ordering, which placed memory in Tier 2 without this dependency analysis.

---

## Gaps in the Codex Design

### 7. No concrete file-level changes for P1

Claude's Tier-1 items list specific files to modify and specific interfaces to change. The Codex design stays at component level ("SessionToolingBootstrapper", "SessionServices") without specifying the actual interface changes. For a design doc that's supposed to be "pick up and start coding," this is too abstract.

### 8. Onboarding wizard is missing

The Codex product analysis (#3) identified onboarding as critical for first-run success. The Codex design doesn't mention it at all. This is a product gap, not an architecture gap, but the design claims to be a complete roadmap.

### 9. Voice input is under-specified

P6 is three bullet points. The Claude design at least identifies the specific Android API (`SpeechRecognizer`), the integration point (`SessionCoordinator`), and the phasing (push-to-talk → TTS → wake word). The Codex design treats it as self-evident.

### 10. No cost estimates

The Codex design provides no time estimates for any item. This makes it harder to evaluate ROI trade-offs. The `RuntimeCapabilityContract` in particular could be anywhere from 2 days (thin wrapper) to 2 weeks (full state machine with reactive updates), depending on scope.

---

## Summary: Where Each Design is Stronger

| Aspect | Claude | Codex |
|--------|--------|-------|
| Unifying thesis | Weaker (parallel items) | Stronger (single contract) |
| Codebase accuracy | More grounded | Some overstatements |
| Actionability | Specific files + interfaces | Component-level only |
| Priority rationale | ROI-based | Dependency-based |
| Memory ordering | Less justified | Better justified |
| Scope realism | Smaller, shippable pieces | Larger, more coupled |
| Product completeness | Includes onboarding, voice detail | Omits onboarding |

---

## Recommendation for Alignment

The best roadmap takes Codex's dependency ordering but Claude's granularity:

1. **T1-1 Prompt externalization** — do it now, it's 4 hours and unblocks eval (Claude's scope)
2. **T1-2 Dynamic tool availability** — `isAvailable()` on `ToolSpec` (Claude's interface, Codex's motivation)
3. **T1-3 Tool risk levels** — typed `riskLevel` property + `PolicyEngine` integration (Claude's scope)
4. **T2-1 Session-as-product-object** — extend existing checkpoint into user-facing session (both agree, different framing)
5. **T2-2 Experience memory** — after tool surface stabilizes (Codex's ordering rationale)
6. Rest follows naturally

Skip the `RuntimeCapabilityContract` as a formal abstraction. Achieve the same runtime truth through the three independent mechanisms (availability, risk, config) — less coupling, same outcome.
