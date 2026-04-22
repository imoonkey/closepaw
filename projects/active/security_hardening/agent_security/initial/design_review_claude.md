# Review of Codex's Agent Security Policy Design

Reviewer: Claude

## Summary

Codex's design is more comprehensive than Claude's. It identifies a threat that Claude's design completely ignores (the perception privacy boundary) and it proposes a more granular app classification. But it over-specifies in areas that don't need Phase 1 granularity, and the 8-class enum violates KISS when 3-4 distinct policy profiles would suffice.

## What Codex Gets Right

### 1. The privacy boundary is the most important idea in either design

Claude's design treats OBSERVE as always SAFE. This is wrong.

In this system, "observe" means: capture the screen, feed the a11y tree and screenshot to the LLM, store it in history, persist it in traces. If the agent lands on a banking app, the LLM sees account balances, transaction history, and account numbers — even if it never clicks anything. That is a privacy leak. The data goes to an external API. It gets written to disk.

Codex's `MASKED` observation policy is the correct response: replace the raw observation with a stub that says "you're in a financial app; screen content hidden by policy." This must apply to prompt, history, AND trace.

Claude missed this entirely. This is the single biggest gap in Claude's design.

### 2. Default-deny for truly sensitive apps

Codex defaults FINANCIAL/SECRET_STORE/SENSITIVE_RECORDS/ADMIN_CONSOLE to blocked (`maxCapability = null`). Claude defaults everything to "ask for each action."

Codex is right. "Ask for every click in your banking app" is the wrong UX for a product that should obviously not be automating banking apps. The default should be "don't do this" with an explicit user override to opt in. The override should feel like a deliberate, informed decision, not a routine approval tap.

### 3. Escape actions in blocked apps

Codex's `allowEscapeActions` flag — keeping `back`, `home`, and `open_app` available even in blocked apps — is a smart detail. Without it, the agent gets stuck if it accidentally navigates into a banking app. This prevents a dead-end state without opening a security hole.

### 4. UNCLASSIFIED as a distinct category

Claude treats unknown apps as STANDARD (no escalation). Codex treats them as UNCLASSIFIED (OBSERVE and NAVIGATE allowed, EDIT and COMMIT ask). Codex is more conservative and more correct — the agent should not auto-approve typing and committing in apps it has never seen before. The cost of false caution (extra approval prompts) is low; the cost of false trust (automating an unknown sensitive app) is high.

### 5. "Read is not safe" framing

The explicit callout that "read is safe" is false in an LLM-agent context is valuable. This challenges an assumption that Claude's design built its entire OBSERVE tier on. In a traditional program, reading is safe because the data stays local. In an agent, reading means exfiltration to an external model and persistent storage. The correct default for financial/health/auth apps is "don't even look."

## Where Codex Over-Specifies

### 1. Eight app classes when three policy profiles exist

`AppSecurityClass` has 8 values, but only 3-4 produce distinct policies:

| Policy behavior | Classes that share it |
|---|---|
| MASKED + blocked | FINANCIAL, SECRET_STORE, SENSITIVE_RECORDS, ADMIN_CONSOLE |
| FULL + ask on COMMIT | GENERAL, COMMUNICATION |
| FULL + ask on EDIT/COMMIT | UNCLASSIFIED |
| FULL + ask on NAVIGATE+ | SYSTEM_CONTROL |

Four of those 8 enum values produce identical behavior. Why differentiate FINANCIAL from SECRET_STORE at the policy level if the policy is the same? If you need the label for the approval UI ("This is a financial app" vs. "This is a password manager"), that's a display concern, not a policy concern.

The right design keeps the label (for UX) separate from the policy tier (for enforcement). Claude's 3-tier model (CRITICAL, SENSITIVE, STANDARD) is closer to the correct abstraction, but it needs the UNCLASSIFIED category from Codex added as a fourth tier.

### 2. Action sensitivity tags are Phase 2+ material

Detecting "Send" or "Pay" from UI element text/description/resource-id is:
- **Locale-dependent**: "Envoyer", "送信", "Enviar" all mean "Send"
- **Fragile**: apps rename buttons, use icons, use custom views
- **False-positive-prone**: "Send feedback" is not "Send money"
- **Hard to test**: requires real app UI state

This is valuable long-term but it's a separate subsystem with its own design challenges. Including it in Phase 1 makes the implementation scope 2-3x larger without proportional safety gain. The app-level classification (CRITICAL apps ask for everything) already provides the safety floor; action-level detection is a refinement on top.

Codex acknowledges tags come from "deterministic" sources, but the examples (matching button text like "Send", "Transfer", "Pay") are inherently heuristic and app-specific. This needs its own design round, not a section in this document.

### 3. PolicyCheckRequest includes ScreenSnapshot

Codex's `PolicyCheckRequest` includes `currentSnapshot: ScreenSnapshot?`. This means the policy engine would need to inspect screen content to derive sensitivity tags. Policy engines should be fast, deterministic, and side-effect-free. Passing a full snapshot into the policy check mixes concerns — classification logic (reading screen elements) should happen before the policy check, not inside it.

### 4. Package-prefix matching

Codex mentions "package prefix" matching as a classification source. This is fragile — `com.chase.*` catches Chase banking, but `com.chase.signalfire` (hypothetical non-banking app from Chase) would be a false positive. Exact package + keyword heuristic (Claude's approach) is more honest about the precision of this mechanism.

## Gaps in Codex's Design

### 1. No user override safety floor

Codex allows overrides to any value per dimension. There's no mention of preventing a user from accidentally overriding a FINANCIAL app to have FULL observation + no approval floor. Claude's "one tier down" floor is too rigid, but some floor is needed. At minimum: overriding a blocked app should require explicit acknowledgment, not just a settings toggle.

### 2. No explicit composition formula

Claude's `final = max(base, app, floor)` is a single, testable formula. Codex describes the policy flow as a sequence of prose steps without a clean algebraic expression. The max-based composition is more auditable.

### 3. Interaction with three-axis SessionSecurityConfig migration

Codex says "ApprovalMode stays as a coarse global override until the broader SessionSecurityConfig migration happens." This is pragmatic but doesn't specify HOW it stays. Will `approvalMode` and the new policy coexist? Who wins on conflict? Claude's design has the same gap but is more explicit about integrating with the three-axis `PolicyCheckRequest`.

### 4. No task dependency graph

Codex's tasks are ordered but dependencies aren't explicit. The masked-observation-gate depends on package-classification-catalog, but can it be built independently? Claude's T1-T7 with explicit dependency edges are more implementable.

## Comparison Table

| Dimension | Claude | Codex |
|---|---|---|
| Core mechanism | Escalation table: (AppSensitivity × CapabilityClass) → RiskClass | Policy profile per app class: observation + maxCapability + floor |
| App categories | 3 (CRITICAL, SENSITIVE, STANDARD) | 8 (GENERAL through ADMIN_CONSOLE) |
| Privacy boundary | None — OBSERVE always SAFE | MASKED observation for sensitive apps |
| Default for financial apps | Ask for every action (CRITICAL) | Block automation + mask screen |
| Default for unknown apps | STANDARD (normal rules) | UNCLASSIFIED (ask for EDIT/COMMIT) |
| Action-level detection | Not in scope | ActionSensitivityTag enum (7 tags) |
| Hard blocks | No — user owns device | Yes — maxCapability = null |
| User override model | Per-app tier with one-tier-down floor | Per-app override on 3 dimensions |
| Composition rule | `max(base, app, floor)` — algebraic | Prose steps — sequential |
| Escape from blocked app | Not addressed (no blocks) | `allowEscapeActions` flag |
| Integration complexity | Low — one new param through pipeline | Medium-high — perception gate + policy rewrite |
| Phase 1 scope | Small, implementable | Large, includes action sensitivity |

## Verdict: Which Design is the Better Base?

**Claude's design** is the better base for the aligned first draft, but it must incorporate three critical elements from Codex:

1. **Privacy boundary (MASKED observation)**. Claude's blind spot. A CRITICAL/blocked app must not expose screen content to the LLM, history, or trace. This is non-negotiable.

2. **Default-deny for financial/auth/health apps**. "Ask for every click" is the wrong default when the right default is "don't automate this." User can opt in with deliberate override.

3. **UNCLASSIFIED tier**. Unknown apps should not get the same freedom as known-safe apps. Ask for EDIT/COMMIT in apps with no classification.

Why Claude's is the better base:
- The escalation table composition (`max(base, app, floor)`) is the correct core mechanism — clean, testable, algebraic
- 3 tiers (expanded to 4 with UNCLASSIFIED) is the right granularity for policy; app category labels are a UX concern
- Integration with existing infrastructure is minimal and incremental
- Phase 1 scope is realistic
- Action sensitivity tags belong in a separate Phase 2 design, not in this document

Codex's design is the better *analysis* — it identified real threats that Claude missed. But its implementation surface is too large for one design, and its 8-class enum conflates policy tiers with display labels.

The aligned draft should use Claude's escalation table as the policy engine, add Codex's observation gate as a pre-cognition filter, add a 4th tier (BLOCKED/UNCLASSIFIED), and defer action sensitivity tags to Phase 2.
