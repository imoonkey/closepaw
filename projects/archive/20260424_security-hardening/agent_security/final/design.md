# Agent Security Policy — Aligned Design

## Goal

Add app-level security classification to the Android Agent so that:

1. Sensitive apps (financial, auth, health) are not treated like normal apps — both for **observation** (what the LLM sees) and **execution** (what the agent can do)
2. The design stays centered on the existing `PolicyEngine → ToolRouter → approval UI` path
3. Unknown apps fail safe
4. The policy is deterministic, testable, and not owned by prompts

## Threat Model

An AI agent with accessibility service access can do anything the user can do. Three threat vectors:

1. **Prompt injection** — malicious screen content tricks the agent into unintended actions
2. **Goal drift** — agent misinterprets the task and navigates into dangerous territory
3. **Cascade** — sequence of individually-safe actions composing into something catastrophic

Two variables determine the danger:
- **What** the agent does → `CapabilityClass` (OBSERVE / NAVIGATE / EDIT / COMMIT)
- **Where** the agent does it → the foreground app

A critical additional insight: in this system, **observation is not free**. Screen capture happens in `AgentTurnRunner.capturePreTurnSnapshot()` *before* `PolicyEngine` runs. The a11y tree and screenshot go to the LLM (external API), history, and trace. For a banking app, that means account numbers, balances, and transaction history are exposed even if the agent never takes an action. The privacy boundary must exist before cognition, not just at execution time.

## Architecture Overview

Two enforcement points, one app profile:

```
                    ┌──────────────────┐
                    │ AppPolicyProfile │  ← resolved once per package
                    └────────┬─────────┘
                             │
          ┌──────────────────┼──────────────────┐
          ▼                                      ▼
┌─────────────────┐                    ┌─────────────────┐
│ Perception Gate │                    │  PolicyEngine   │
│ (pre-cognition) │                    │ (pre-execution) │
│                 │                    │                 │
│ FULL → pass     │                    │ Escalation      │
│ MASKED → stub   │                    │ table + escape  │
└─────────────────┘                    └─────────────────┘
```

Both gates use the same `AppPolicyProfile`. This is one policy model, not two subsystems.

## Design

### 1. AppSensitivity Enum

Four classification tiers. Phase 1 intentionally collapses them into three enforcement profiles.

```kotlin
enum class AppSensitivity {
    BLOCKED,       // Financial, auth, health, admin — masked + denied by default
    GUARDED,       // Unknown/unclassified apps — cautious defaults
    SENSITIVE,     // Messaging, social, email — EDIT/COMMIT escalated
    STANDARD       // Known-safe / general apps — normal rules
}
```

Why four, not three or eight:
- BLOCKED, GUARDED, and STANDARD produce distinct enforcement
- SENSITIVE is kept as a separate classification label even though it shares Phase 1 enforcement with GUARDED
- BLOCKED covers all apps that are both confidentiality-sensitive AND high-consequence (Codex's FINANCIAL, SECRET_STORE, SENSITIVE_RECORDS, ADMIN_CONSOLE all collapse to the same policy)
- GUARDED is the fail-safe for unknown apps (Codex's UNCLASSIFIED insight — unknown ≠ safe)
- SENSITIVE covers communication/social apps where EDIT/COMMIT are dangerous but observation is fine
- STANDARD is for apps where normal CapabilityClass rules apply

The important separation is:
- `AppSensitivity` is the classification label the user and policy reason about
- `AppPolicyProfile` is the enforcement profile used at runtime

### 2. ObservationPolicy

```kotlin
enum class ObservationPolicy {
    FULL,      // Normal: a11y tree + screenshot sent to LLM/history/trace
    MASKED     // Stub: package name + sensitivity tier + recovery instructions only
}
```

Masked observation replaces the raw a11y tree and screenshot with:

```
Foreground app: com.chase.sig.android
Security tier: BLOCKED (financial app)
Screen content hidden by security policy. Use back/home to leave this app, or ask the user for an override.
```

This must apply to:
- Prompt construction (PromptBuilder)
- History recording (TurnPlanningPhaseRunner)
- Trace artifacts (`AgentTrace` / trace recorder outputs)
- Any persisted screen payloads

### 3. AppPolicyProfile

Phase 1 uses one static profile per enforcement behavior:

```kotlin
data class AppPolicyProfile(
    val sensitivity: AppSensitivity,
    val observationPolicy: ObservationPolicy,
    val maxCapability: CapabilityClass?,     // null = in-app actions denied
    val allowEscapeActions: Boolean = true   // back/home always available
)
```

Default profiles:

| Tier | Observation | Max Capability | Escape | Behavior |
|------|-------------|---------------|--------|----------|
| BLOCKED | MASKED | `null` (denied) | yes | No observation, no in-app actions. Agent sees only app name + tier. Can leave. |
| GUARDED | FULL | COMMIT | yes | Full observation. OBSERVE and NAVIGATE auto. EDIT and COMMIT ask. |
| SENSITIVE | FULL | COMMIT | yes | Full observation. OBSERVE and NAVIGATE auto. EDIT and COMMIT ask. |
| STANDARD | FULL | COMMIT | yes | Full observation. OBSERVE, NAVIGATE, EDIT auto. COMMIT ask. |

Escalation table (CapabilityClass × AppSensitivity → RiskClass):

| CapabilityClass | STANDARD | SENSITIVE | GUARDED | BLOCKED |
|-----------------|----------|-----------|---------|---------|
| OBSERVE | SAFE | SAFE | SAFE | — (masked) |
| NAVIGATE | MODERATE | MODERATE | MODERATE | DENY (escape only) |
| EDIT | MODERATE | **HIGH** | **HIGH** | DENY |
| COMMIT | HIGH | HIGH | HIGH | DENY |

Composition formula:

```
profile      = AppSensitivityResolver.resolve(packageName)
observation  = profile.observationPolicy          → gate before cognition
capClass     = resolveCapabilityClass(tool, params)
baseRisk     = capClass.defaultRiskClass()
appRisk      = ESCALATION_TABLE[profile.sensitivity][capClass]
floor        = supervisionContext.minimumRisk()
finalRisk    = max(appRisk, floor)

if profile.maxCapability == null && !isEscapeAction(tool):
    return DENY
if finalRisk == SAFE:     return ALLOW
if finalRisk == MODERATE: return ALLOW
if finalRisk == HIGH:     return ASK_USER(reason)
```

GUARDED and SENSITIVE intentionally share the same Phase 1 enforcement row. The difference is provenance and user messaging:
- GUARDED means "unknown app, cautious default"
- SENSITIVE means "known communication/personal-content app"

Do not add a second enforcement difference in Phase 1 just because the labels differ. If future evidence shows that unknown apps need stricter navigation rules, add that in a later phase.

### 4. Escape Actions

Even in BLOCKED apps, the agent must not get stuck. Escape actions are always allowed regardless of `maxCapability`:

- `back`
- `home`
- `open_app` (to navigate away)

These map to CapabilityClass.NAVIGATE but bypass the `maxCapability` check. They do NOT bypass observation masking — the agent still cannot see the screen content.

### 5. Classification Sources

Resolved in priority order:

#### a) User overrides (highest priority)

Persisted per-app. Can tighten any app. Can relax any app, but relaxing a BLOCKED app or changing it from `MASKED` to `FULL` must require explicit acknowledgement in the UI.

```kotlin
data class UserAppOverride(
    val packageName: String,
    val sensitivity: AppSensitivity
)
```

Rules:
- `AUTO_APPROVE` never changes app classification
- a per-app override is the only way to unmask or unblock a BLOCKED app
- relaxing a BLOCKED app must be a deliberate override, not a side effect of a global mode

#### b) App skill metadata (second priority)

SKILL.md frontmatter. Requires new parsing (current `AppSkillRepository.stripFrontmatter()` discards it — needs a parallel `parseFrontmatter()` method).

```markdown
---
package: com.example.app
security: SENSITIVE
---
```

#### c) Built-in defaults (third priority)

Hardcoded known packages (exact match) + keyword heuristic (substring match on lowercased package name, only escalates, never de-escalates).

```kotlin
object AppSensitivityDefaults {
    private val BLOCKED_PACKAGES = setOf(
        // Banking
        "com.chase.sig.android", "com.wf.wellsfargomobile",
        "com.citi.citimobile", "com.infonow.bofa",
        // Crypto
        "com.coinbase.android", "com.binance.dev",
        // Payments
        "com.venmo", "com.squareup.cash",
        "com.paypal.android.p2pmobile",
        "com.google.android.apps.walletnfcrel",
        // Auth/secrets
        "com.onepassword.android", "com.authy.authy",
        // ...
    )

    private val SENSITIVE_PACKAGES = setOf(
        "com.whatsapp", "org.telegram.messenger",
        "com.google.android.gm", "com.microsoft.office.outlook",
        "com.twitter.android", "com.instagram.android",
        // ...
    )

    // Heuristic patterns — only escalate, never de-escalate
    private val BLOCKED_PATTERNS = listOf(
        "bank", "crypto", "wallet", "brokerage", "payment"
    )
    private val SENSITIVE_PATTERNS = listOf(
        "messenger", "chat", "email", "social"
    )

    fun classify(packageName: String): AppSensitivity {
        val pkg = packageName.lowercase()
        if (pkg in BLOCKED_PACKAGES) return AppSensitivity.BLOCKED
        if (pkg in SENSITIVE_PACKAGES) return AppSensitivity.SENSITIVE
        if (BLOCKED_PATTERNS.any { it in pkg }) return AppSensitivity.BLOCKED
        if (SENSITIVE_PATTERNS.any { it in pkg }) return AppSensitivity.SENSITIVE
        return AppSensitivity.GUARDED  // unknown → cautious, not standard
    }
}
```

#### d) Fail-safe default

If no source matches: `GUARDED`. Unknown apps get full observation but EDIT/COMMIT require approval. This is more conservative than STANDARD but does not block the agent from being useful.

If multiple non-fallback rules match, the highest explicit sensitivity wins:

`BLOCKED > SENSITIVE > STANDARD`

`GUARDED` is only the fallback when nothing else matches. This resolves dual-purpose apps deterministically. A package that is both messaging and payments is `BLOCKED`.

### 6. Integration with PolicyEngine

Today:
```kotlin
fun check(toolName: String, params: JSONObject): PolicyDecision
```

After:
```kotlin
fun check(toolName: String, params: JSONObject, appContext: AppContext?): PolicyDecision

data class AppContext(
    val packageName: String,
    val profile: AppPolicyProfile
)
```

Changes to `evaluateRiskLocked`:

1. If `appContext.profile.maxCapability == null` and action is not an escape action → DENY
2. Resolve `CapabilityClass` from tool/action
3. Look up escalation table for `appContext.profile.sensitivity`
4. Apply supervision floor via `max()`
5. Return Allow / AskUser / Deny

`ApprovalMode` stays as the Phase 1 global override:
- `ALWAYS_ASK` asks for every action that is otherwise allowed
- `SMART` uses the escalation table result
- `AUTO_APPROVE` can bypass `ASK_USER`, but never `DENY`, never unmask a BLOCKED app, and never change classification

The later `SessionSecurityConfig` migration is separate. This design only requires that the global mode continue to act as a final approval floor, not as a source of app trust.

### 7. Integration with Perception Pipeline

New gate in `AgentTurnRunner.capturePreTurnSnapshot()`:

```kotlin
val snapshot = platform.captureScreen()
val packageName = platform.getCurrentPackageName()
val profile = appSensitivityResolver.resolve(packageName)

val observation = if (profile.observationPolicy == ObservationPolicy.MASKED) {
    MaskedObservation(packageName, profile.sensitivity)
} else {
    FullObservation(snapshot)
}
```

This masked observation propagates to:
- `TurnPlanningPhaseRunner` (prompt construction)
- History manager
- Trace recording

### 8. open_app Interaction

`open_app` is special because the policy-relevant package is the **target**, not the current foreground.

For `open_app`, the target package must be resolved before policy check using the same resolution logic that execution uses.

Phase 1 design:
- extract a pure shared resolver from `OpenAppTool` name-to-package matching logic
- `TurnExecutionPhaseRunner` uses that resolver before calling `ToolRouter`
- `OpenAppTool` reuses the same resolver during execution

Do not duplicate name-resolution logic in policy code, and do not split `open_app` into a two-phase tool lifecycle just for this case.

If the target resolves to BLOCKED:
- `open_app` itself returns `AskUser` with reason: "This is a financial app. Automation is blocked by default. Approve to open (screen will remain masked)."
- If approved, the app opens but observation remains MASKED and in-app actions remain DENIED until user overrides.

If target resolution fails before policy check, treat `open_app` like the current tool path: return the normal "app not found" failure, not a policy denial.

### 9. Reserved Phase 2 Hook: Action Sensitivity Tags

Phase 1 does **not** ship a target-level classifier that upgrades generic `click` into `COMMIT`.

Phase 1 **does** reserve the hook so the policy API does not need another redesign later:

```kotlin
enum class ActionSensitivityTag {
    MONEY_MOVEMENT,
    PUBLIC_POST,
    DESTRUCTIVE,
    SECRET_ENTRY,
    PERMISSION_CHANGE
}
```

Rules for Phase 1:
- `PolicyCheckRequest` and `ApprovalDetails` may carry `actionSensitivityTags`
- Phase 1 always leaves that set empty
- enforcement correctness must not depend on those tags yet

This keeps the first implementation small while acknowledging the real gap: app-level classification is the Phase 1 safety floor, not the complete long-term answer

### 10. Approval Context Enrichment

```kotlin
data class ApprovalDetails(
    val callId: String,
    val toolName: String,
    val args: JSONObject,
    val description: String = "",
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    // NEW
    val appContext: AppContext? = null,
    val escalationReason: String? = null,
    val actionSensitivityTags: Set<ActionSensitivityTag> = emptySet()
)
```

The UI uses `escalationReason` to explain WHY the prompt appeared:
- "Financial app — automation blocked by default"
- "Unknown app — text input requires approval"
- "Messaging app — sending messages requires approval"

## Data Flow

```
AgentTurnRunner.capturePreTurnSnapshot()
  → platform.getCurrentPackageName()
  → AppSensitivityResolver.resolve(packageName) → AppPolicyProfile
  → if MASKED: replace snapshot with stub observation
  → pass observation to planning phase

TurnExecutionPhaseRunner (per tool call)
  → resolve AppContext (foreground pkg, or target pkg for open_app)
  → toolRouter.execute(toolName, params, context, callId, appContext, ...)
    → policyEngine.check(toolName, params, appContext)
      → check maxCapability / escape action
      → resolve CapabilityClass
      → look up ESCALATION_TABLE
      → max(appRisk, supervisionFloor)
      → Allow / AskUser / Deny
```

## Tasks

### T1: `app-sensitivity-types`
**Scope:** `protocol/AppSensitivity.kt`
**Work:** `AppSensitivity` enum, `ObservationPolicy` enum, `AppPolicyProfile` data class, `AppContext` data class, static profile table.
**Acceptance:** Compiles, unit tests for profile lookup.
**Dependencies:** None.

### T2: `app-sensitivity-defaults`
**Scope:** `tool/AppSensitivityDefaults.kt`
**Work:** Hardcoded known packages + keyword heuristic. Unknown → GUARDED.
**Acceptance:** Unit tests: known financial → BLOCKED, known messenger → SENSITIVE, unknown → GUARDED, pattern match works.
**Dependencies:** T1.

### T3: `app-sensitivity-resolver`
**Scope:** `tool/AppSensitivityResolver.kt`
**Work:** Resolution chain: user override → skill metadata → built-in defaults → GUARDED. Highest-sensitivity-wins conflict handling. User override application with explicit-acknowledgment requirement for relaxing BLOCKED.
**Acceptance:** Unit tests: priority order correct, user overrides applied.
**Dependencies:** T1, T2.

### T4: `perception-gate`
**Scope:** `agent/AgentTurnRunner.kt`, `agent/TurnPlanningPhaseRunner.kt`, `trace/*`
**Work:** Gate in `capturePreTurnSnapshot()` that replaces observation with masked stub when profile is MASKED. Ensure stub propagates to prompt, history, and trace.
**Acceptance:** When foreground app is BLOCKED, LLM input contains masked stub, not raw a11y tree or screenshot. Trace files also masked.
**Dependencies:** T3.

### T5: `policy-engine-app-context`
**Scope:** `tool/PolicyEngine.kt`
**Work:** Add `appContext` to `check()`. Add escalation table. Add `maxCapability` + escape action logic. Compose with supervision floor via `max()`.
**Acceptance:** Unit tests: BLOCKED app + click → DENY; BLOCKED app + back → ALLOW; GUARDED app + type → ASK; STANDARD app + scroll → ALLOW.
**Dependencies:** T1, T3.

### T6: `tool-router-app-context`
**Scope:** `tool/ToolRouter.kt`, `protocol/ApprovalTypes.kt`
**Work:** Thread `appContext` through `execute()` → `policyEngine.check()`. Enrich `ApprovalDetails`. Add reserved `actionSensitivityTags` field defaulting empty.
**Acceptance:** Approval details include app context. Existing tests pass (appContext defaults to null).
**Dependencies:** T5.

### T7: `turn-runner-integration`
**Scope:** `agent/TurnExecutionPhaseRunner.kt`, `session/SessionToolingBootstrapper.kt`
**Work:** Resolve `AppContext` before each tool call. Extract and reuse a shared `open_app` target resolver before policy and during execution. Wire resolver into session bootstrap.
**Acceptance:** End-to-end: BLOCKED package triggers deny; GUARDED package triggers ask on type; `open_app` to a BLOCKED target asks before launch without duplicating app-resolution logic.
**Dependencies:** T3, T6.

### T8: `skill-metadata-security`
**Scope:** `agent/cognition/prompt/AppSkillRepository.kt`, `app/src/main/assets/app_skills/*/SKILL.md`
**Work:** Parse `security:` from SKILL.md frontmatter (separate from existing `stripFrontmatter`). Feed into resolver.
**Acceptance:** A SKILL.md with `security: SENSITIVE` causes that package to be classified as SENSITIVE.
**Dependencies:** T3.

## Phase 2, Not Phase 1

Target-level action sensitivity detection remains future work.

That future work should:
- stay deterministic and local
- populate `actionSensitivityTags` before policy check
- handle locale/app-specific semantics without moving decision ownership into the prompt

Phase 1 is still complete without it because BLOCKED, GUARDED, and SENSITIVE app classification already establish the safety floor.
