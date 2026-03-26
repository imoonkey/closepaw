# Agent Security Policy — App-Aware Capability Escalation

## Goal

Add app-level security classification to the Android Agent so that sensitive apps (banking, crypto, payments, messaging) receive stricter approval requirements than ordinary apps — without breaking the agent's ability to automate normal apps, and without introducing a separate policy system.

## The Real Problem

The agent can do anything the user can do. Three threat vectors make this dangerous:

1. **Prompt injection** — malicious screen content tricks the agent into unintended actions
2. **Goal drift** — agent misinterprets the task and navigates into dangerous territory
3. **Cascade** — a sequence of individually-safe actions that compose into something catastrophic (navigate to transfer page → fill amount → confirm)

The common factor: **the agent takes an irreversible action in a context where consequences are severe.** Two variables determine the danger:

- **What** the agent does → already captured by `CapabilityClass` (OBSERVE / NAVIGATE / EDIT / COMMIT)
- **Where** the agent does it → NOT captured today. All apps are treated equally.

The existing three-axis design anticipated this. `PolicyCheckRequest` already has `packageName` and `sensitivityTags` fields. Phase 2 mentions "sensitivity tags owned by app/package metadata." This design fills that gap.

## The Core Insight

The right abstraction is NOT "app risk categories" (too many, too fuzzy) or "app risk scores" (too opaque).

The right abstraction is: **per-app, which CapabilityClasses get escalated?**

A banking app isn't generically "dangerous." It's dangerous because NAVIGATE can reach transfer flows, EDIT can fill amounts, and COMMIT can execute transfers. But OBSERVE is fine — reading your balance is harmless.

This composes directly with the existing three-axis model:

```
base_risk = CapabilityClass → RiskClass        (three-axis default)
app_risk  = AppPolicy[capabilityClass] ?? base  (app escalation)
floor     = SupervisionContext.minimumRisk       (supervision floor)
final     = max(app_risk, floor)                 (one max() call)
```

Three inputs. One `max()` operation. Deterministic. No special cases.

## Design

### AppSensitivity — The Only New Enum

```kotlin
enum class AppSensitivity {
    CRITICAL,    // Financial, crypto, payment — NAVIGATE+ escalated to HIGH
    SENSITIVE,   // Messaging, social, health — EDIT/COMMIT escalated to HIGH
    STANDARD     // Default — normal CapabilityClass rules apply
}
```

Three tiers. No more. Each tier defines which CapabilityClasses get escalated:

| CapabilityClass | STANDARD | SENSITIVE | CRITICAL |
|-----------------|----------|-----------|----------|
| OBSERVE         | SAFE     | SAFE      | SAFE     |
| NAVIGATE        | MODERATE | MODERATE  | **HIGH** |
| EDIT            | MODERATE | **HIGH**  | **HIGH** |
| COMMIT          | HIGH     | HIGH      | HIGH     |

In words:
- **CRITICAL** apps: everything above OBSERVE requires approval (because even navigation can reach dangerous flows)
- **SENSITIVE** apps: EDIT and COMMIT require approval (typing and committing are the danger, not navigation)
- **STANDARD** apps: normal three-axis rules

Note: COMMIT already requires approval in STANDARD. The escalation only matters for NAVIGATE and EDIT.

### Where Classification Data Lives

Three sources, resolved in priority order:

#### 1. User overrides (highest priority)

Persisted per-app in DataStore/SharedPreferences. Users can relax or tighten any app's classification.

```kotlin
data class UserAppOverride(
    val packageName: String,
    val sensitivity: AppSensitivity
)
```

Safety floor: user can override a CRITICAL app down to SENSITIVE, but not to STANDARD. This prevents accidentally removing all protection from known-dangerous apps while still respecting user agency.

```kotlin
fun resolveOverride(builtin: AppSensitivity, userOverride: AppSensitivity): AppSensitivity {
    // User can only relax by one tier
    return if (userOverride < builtin) {
        maxOf(userOverride, AppSensitivity.entries[builtin.ordinal - 1])
    } else {
        userOverride  // tightening is always allowed
    }
}
```

#### 2. App skill metadata (second priority)

SKILL.md files already exist per package. Add a `security` field to frontmatter:

```markdown
---
package: com.chase.sig.android
security: CRITICAL
---
```

This ships with the APK and is maintainable by the development team. Natural extension of the existing app skills system.

#### 3. Built-in defaults (lowest priority, catch-all)

Hardcoded in `AppSensitivityDefaults` — covers major known packages that may not have SKILL.md files:

```kotlin
object AppSensitivityDefaults {
    // Resolved at startup; immutable after init
    private val CRITICAL_PACKAGES = setOf(
        // Banking
        "com.chase.sig.android",
        "com.wf.wellsfargomobile",
        "com.citi.citimobile",
        "com.infonow.bofa",
        "com.usaa.mobile.android.usaa",
        "com.ally.MobileBanking",
        // Crypto
        "com.coinbase.android",
        "com.binance.dev",
        "com.kraken.trade",
        // Payments
        "com.venmo",
        "com.squareup.cash",
        "com.paypal.android.p2pmobile",
        "com.google.android.apps.walletnfcrel",  // Google Pay
        "com.zhelihua.zfb",  // Alipay
        "com.tencent.mm",    // WeChat Pay
    )

    private val SENSITIVE_PACKAGES = setOf(
        // Messaging
        "com.whatsapp",
        "org.telegram.messenger",
        "com.discord",
        "com.Slack",
        "com.facebook.orca",
        // Social
        "com.twitter.android",
        "com.instagram.android",
        "com.facebook.katana",
        "com.linkedin.android",
        "com.reddit.frontpage",
        // Email
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        // Health
        "com.google.android.apps.fitness",
        "com.myfitnesspal.android",
    )

    // Also match by package name patterns as heuristic fallback
    private val CRITICAL_PATTERNS = listOf(
        "bank", "banking", "crypto", "wallet", "trade", "invest",
        "brokerage", "forex", "payment"
    )

    private val SENSITIVE_PATTERNS = listOf(
        "messenger", "chat", "sms", "email", "mail",
        "social", "health"
    )

    fun classify(packageName: String): AppSensitivity {
        val pkg = packageName.lowercase()
        if (pkg in CRITICAL_PACKAGES) return AppSensitivity.CRITICAL
        if (pkg in SENSITIVE_PACKAGES) return AppSensitivity.SENSITIVE
        if (CRITICAL_PATTERNS.any { it in pkg }) return AppSensitivity.CRITICAL
        if (SENSITIVE_PATTERNS.any { it in pkg }) return AppSensitivity.SENSITIVE
        return AppSensitivity.STANDARD
    }
}
```

#### Resolution Order

```kotlin
fun resolve(packageName: String): AppSensitivity {
    // 1. User override (respecting safety floor)
    userOverrides[packageName]?.let { override ->
        val base = resolveWithoutUser(packageName)
        return resolveOverride(base, override)
    }
    return resolveWithoutUser(packageName)
}

private fun resolveWithoutUser(packageName: String): AppSensitivity {
    // 2. App skill metadata
    appSkillSensitivity[packageName]?.let { return it }
    // 3. Built-in defaults (exact + pattern)
    return AppSensitivityDefaults.classify(packageName)
}
```

### Integration with PolicyEngine

The change to `PolicyEngine` is minimal. Today it resolves risk as:

```kotlin
// Current: action-only
fun check(toolName, params) → PolicyDecision
```

After this design:

```kotlin
// New: action + app context
fun check(toolName, params, appContext: AppContext?) → PolicyDecision

data class AppContext(
    val packageName: String,
    val sensitivity: AppSensitivity
)
```

The `evaluateRiskLocked` method gains one new step:

```kotlin
private fun evaluateRiskLocked(
    toolName: String,
    params: JSONObject,
    appContext: AppContext?
): PolicyDecision {
    val capabilityClass = resolveCapabilityClass(toolName, params)
    val baseRisk = capabilityClass.defaultRiskClass()

    // NEW: apply app escalation
    val appRisk = appContext?.let { ctx ->
        ESCALATION_TABLE[ctx.sensitivity]?.get(capabilityClass) ?: baseRisk
    } ?: baseRisk

    val finalRisk = maxOf(baseRisk, appRisk)

    return when (finalRisk) {
        RiskClass.SAFE -> PolicyDecision.Allow
        RiskClass.MODERATE -> PolicyDecision.Allow  // configurable to ask
        RiskClass.HIGH -> PolicyDecision.AskUser(
            reason = buildApprovalReason(toolName, capabilityClass, appContext),
            riskLevel = RiskLevel.HIGH
        )
    }
}

// Static escalation table — the only new data structure
private val ESCALATION_TABLE: Map<AppSensitivity, Map<CapabilityClass, RiskClass>> = mapOf(
    AppSensitivity.CRITICAL to mapOf(
        CapabilityClass.NAVIGATE to RiskClass.HIGH,
        CapabilityClass.EDIT to RiskClass.HIGH,
        CapabilityClass.COMMIT to RiskClass.HIGH
    ),
    AppSensitivity.SENSITIVE to mapOf(
        CapabilityClass.EDIT to RiskClass.HIGH,
        CapabilityClass.COMMIT to RiskClass.HIGH
    )
    // STANDARD: no overrides, use defaults
)
```

### open_app Interaction

When the agent opens a CRITICAL or SENSITIVE app, `open_app` maps to CapabilityClass.NAVIGATE. In a CRITICAL app context, this escalates to HIGH → approval required.

The approval prompt should say: **"Open [Chase Banking]? This is a financial app — subsequent actions will require individual approval."**

For SENSITIVE apps, `open_app` stays MODERATE (auto-approved in LOCAL_FOREGROUND), since the danger is in EDIT/COMMIT, not navigation.

### Approval Context Enrichment

Today's `ApprovalDetails` has `toolName`, `args`, `description`, `riskLevel`. Add app context:

```kotlin
data class ApprovalDetails(
    val callId: String,
    val toolName: String,
    val args: JSONObject,
    val description: String = "",
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    // NEW
    val appContext: AppContext? = null,
    val escalationReason: String? = null  // "Financial app", "Messaging app", etc.
)
```

The UI uses `escalationReason` to show WHY the prompt appeared, e.g.:

> **Approval Required**
> Click "Transfer" in Chase Banking
> *Reason: Financial app — actions may initiate money transfers*

### Data Flow

```
TurnExecutionPhaseRunner
  → platform.getCurrentPackageName()
  → AppSensitivityResolver.resolve(packageName)
  → AppContext(packageName, sensitivity)
  → toolRouter.execute(..., appContext)
    → policyEngine.check(toolName, params, appContext)
      → resolveCapabilityClass(toolName, params)
      → look up ESCALATION_TABLE[sensitivity][capabilityClass]
      → max(base, app, supervision floor)
      → Allow / AskUser / Deny
```

The change threads through existing infrastructure. No new event system. No new state machine. One new parameter flowing through the existing pipeline.

### Fail-Safe: Unclassified Apps

Unknown apps → STANDARD. No escalation. Why:

1. The agent exists to automate apps. Blocking unknown apps kills the product.
2. SupervisionContext already raises the floor for REMOTE/BACKGROUND sessions.
3. For LOCAL_FOREGROUND, the user is watching — STANDARD with normal COMMIT approval is sufficient.
4. The combination `SupervisionContext × CapabilityClass` already provides a safety net for unknown apps.

### Privacy Boundaries (Read vs. Act)

Already handled by CapabilityClass:
- OBSERVE = read-only (SAFE in all tiers)
- NAVIGATE/EDIT/COMMIT = acting

A CRITICAL app allows OBSERVE (read bank balance) but escalates everything else. No separate "read-only mode" needed — the existing abstraction captures it.

## Components

### New Files

1. **`AppSensitivity.kt`** — `AppSensitivity` enum, `AppContext` data class
2. **`AppSensitivityResolver.kt`** — resolution logic (user overrides → skill metadata → built-in defaults)
3. **`AppSensitivityDefaults.kt`** — hardcoded known-sensitive packages + pattern heuristics

### Modified Files

4. **`PolicyEngine.kt`** — `check()` gains `appContext` parameter; `evaluateRiskLocked()` adds escalation table lookup
5. **`ToolRouter.kt`** — `execute()` gains `appContext` parameter, passes to `policyEngine.check()`
6. **`TurnExecutionPhaseRunner.kt`** — resolves `AppContext` from current package name before calling `toolRouter.execute()`
7. **`ApprovalDetails.kt`** — gains `appContext` and `escalationReason` fields
8. **`SessionToolingBootstrapper.kt`** — creates `AppSensitivityResolver` and passes to `PolicyEngine`
9. **App skill SKILL.md files** — add `security:` frontmatter to existing skills for sensitive apps (if any are sensitive)

### Removed / Changed

- `DEFAULT_RISK_LEVELS` in PolicyEngine companion — replaced by CapabilityClass → RiskClass mapping (aligns with three-axis design)
- `MobileActionName.defaultRiskLevel` — risk level moves out of enum constructors into PolicyEngine's capability catalog (as three-axis design specified)

## Tasks

### T1: `app-sensitivity-enum`

**Scope:** `app/src/main/kotlin/com/moonkey/androidagent/protocol/AppSensitivity.kt`
**Work:** Create `AppSensitivity` enum (CRITICAL, SENSITIVE, STANDARD) and `AppContext` data class.
**Acceptance:** Compiles, unit test for enum ordering.
**Dependencies:** None.

### T2: `app-sensitivity-defaults`

**Scope:** `app/src/main/kotlin/com/moonkey/androidagent/tool/AppSensitivityDefaults.kt`
**Work:** Hardcoded known-sensitive packages + pattern heuristic classifier.
**Acceptance:** Unit tests: known packages classified correctly, pattern matching works, unknown → STANDARD.
**Dependencies:** T1.

### T3: `app-sensitivity-resolver`

**Scope:** `app/src/main/kotlin/com/moonkey/androidagent/tool/AppSensitivityResolver.kt`
**Work:** Resolution chain: user override → skill metadata → built-in defaults. Safety floor logic for overrides.
**Acceptance:** Unit tests: resolution priority correct, safety floor prevents skipping more than one tier.
**Dependencies:** T1, T2.

### T4: `policy-engine-app-context`

**Scope:** `app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt`
**Work:** Add `appContext` parameter to `check()`. Add escalation table. Modify `evaluateRiskLocked()` to compose app risk with base risk.
**Acceptance:** Unit tests: CRITICAL app + NAVIGATE → HIGH; SENSITIVE app + OBSERVE → SAFE; STANDARD app unchanged from today.
**Dependencies:** T1, T3.

### T5: `tool-router-app-context`

**Scope:** `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt`, `ApprovalTypes.kt`
**Work:** Thread `appContext` through `execute()` → `policyEngine.check()`. Enrich `ApprovalDetails` with `appContext` + `escalationReason`.
**Acceptance:** Approval details include app context when present. Existing tests still pass (appContext defaults to null).
**Dependencies:** T4.

### T6: `turn-runner-integration`

**Scope:** `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt`, `SessionToolingBootstrapper.kt`
**Work:** Resolve `AppContext` from `platform.getCurrentPackageName()` + `AppSensitivityResolver` before each tool execution. Wire resolver into session bootstrap.
**Acceptance:** End-to-end: agent automating a known-CRITICAL package triggers approval for NAVIGATE actions.
**Dependencies:** T3, T5.

### T7: `skill-metadata-security`

**Scope:** `app/src/main/assets/app_skills/*/SKILL.md`, `AppSkillRepository.kt`
**Work:** Parse `security:` frontmatter from SKILL.md. Feed into `AppSensitivityResolver`.
**Acceptance:** A SKILL.md with `security: SENSITIVE` causes that package to be classified as SENSITIVE.
**Dependencies:** T3.

## Trade-offs

### Why not app "categories" (BANKING, CRYPTO, MESSAGING, ...)?

Categories proliferate. Every new app type needs a new category, and you need a mapping from category → policy. AppSensitivity captures the EFFECT (how much escalation) not the CAUSE (what kind of app). The cause can be logged for the approval prompt without being a policy input.

### Why not a numeric risk score per app?

Opaque. What does "risk score 7" mean? AppSensitivity is legible: you either escalate everything (CRITICAL), escalate edits (SENSITIVE), or don't escalate (STANDARD). Three values, each with a clear mental model.

### Why not hard-block CRITICAL apps entirely?

The user owns the device. If they want to automate their banking app with explicit per-action approval, that's their choice. The policy makes it safe (every action approved) without making it impossible. Hard blocks reduce trust in the system and invite workarounds.

### Why pattern matching on package names?

It's a heuristic fallback, not the primary mechanism. Known packages are exact-matched first. Patterns catch the long tail (small regional banks, new crypto apps). False positives only mean extra approval prompts (annoying, not dangerous). False negatives fall through to STANDARD, which still has COMMIT approval.

### Why allow user override with a safety floor?

Pure user freedom means one accidental toggle removes all protection from their banking app. Pure lockdown means the agent can't help with legitimate banking tasks. The one-tier-down floor is the compromise: CRITICAL → SENSITIVE (still ask for edits), but not CRITICAL → STANDARD (all protection removed).

### Why not a separate "read-only mode" for apps?

CapabilityClass already captures this. OBSERVE = read-only, automatically SAFE in all tiers. The abstraction already exists — adding a separate read-only concept would be redundant.

## Self-Review

**Does it fully cover the goal?**
- App classification: Yes — three tiers, three data sources, deterministic resolution.
- Default policy per category: Yes — ESCALATION_TABLE maps tier × capability → risk.
- Action-level risk in sensitive apps: Yes — same escalation table.
- User override: Yes — per-app with safety floor.
- Fail-safe: Yes — unknown → STANDARD, supervised by SupervisionContext.
- Privacy boundaries: Yes — OBSERVE always SAFE.
- Integration with existing infra: Yes — one new parameter through the existing pipeline.
- Integration with three-axis: Yes — app sensitivity is a modifier on CapabilityClass → RiskClass, not a new axis.

**Unnecessary complexity?**
- Pattern matching is arguably unnecessary if the known-package list is comprehensive enough. Keep it — low cost, catches the long tail, false positives are benign.
- Safety floor for overrides adds complexity. Keep it — the alternative (no floor) is a real safety risk.

**Edge cases handled through design?**
- Unknown app: → STANDARD. Handled by resolution default.
- App with no SKILL.md: → built-in defaults or STANDARD. No special case.
- User opens banking app for the first time: → CRITICAL from built-in defaults. open_app triggers approval. No onboarding flow needed.
- App changes package name (rare): → pattern heuristic may catch it, or falls to STANDARD. Acceptable.
- Agent navigates away from sensitive app mid-task: → packageName changes, next check uses new app's classification. Automatic.
