# open_app CAUTIOUS Destination Approval Bypass

## Problem

In SMART mode, `PolicyEngine.check()` decides approval based solely on the **current foreground app's tier**. When the agent calls `open_app` to launch an unclassified (CAUTIOUS) app from a NORMAL app (e.g., launcher), the policy sees NORMAL and returns `Allow` — bypassing the approval gate entirely.

The destination app's tier is never considered during the policy check.

## Root Cause

`PolicyEngine.check()` takes `packageName` = current foreground. For `open_app`, the security-relevant package is the **destination**, not the origin. The destination is resolved later inside `OpenAppInvocation.execute()`, after the policy check has already passed.

```
ToolRouter.execute()
  -> policyEngine.check(toolName, params, packageName=currentForeground)  // sees NORMAL -> Allow
  -> invocation.execute()
       -> resolves appName -> targetPackage
       -> checks BLOCKED (line 191-197)          // catches BLOCKED only
       -> launches app                            // CAUTIOUS slips through
```

## Fix: Two-Site Check

Principle: **use the stricter of current tier and destination tier** when both are known.

### Change 1: PolicyEngine.check() — accept optional destination

**File:** `PolicyEngine.kt`, `check()` signature and SMART branch.

```kotlin
fun check(
    toolName: String,
    params: JSONObject = JSONObject(),
    packageName: String? = null,
    destinationPackage: String? = null       // +1 param
): PolicyDecision {
    val currentTier = appClassifier.classify(packageName)
    val destTier = destinationPackage?.let { appClassifier.classify(it) }

    // Effective tier = stricter of the two (lower ordinal = stricter)
    val tier = if (destTier != null) minOf(currentTier, destTier) else currentTier

    // ... rest unchanged (use `tier` as before)
}
```

`minOf` works because `AppTier` ordinal: `BLOCKED(0) < CAUTIOUS(1) < NORMAL(2)`.

No behavioral change when `destinationPackage` is null (all existing callers).

### Change 2: ToolRouter — resolve destination for open_app before policy check

**File:** `ToolRouter.kt`, inside `execute()`, before the `policyEngine.check()` call (line 103).

```kotlin
// Pre-resolve destination package for open_app policy check
val destinationPackage = if (toolName == "open_app") {
    resolveOpenAppDestination(params, context.platform)
} else null

val policyDecision = policyEngine.check(toolName, params, packageName, destinationPackage)
```

Add a private helper:

```kotlin
private suspend fun resolveOpenAppDestination(
    params: JSONObject,
    platform: AndroidPlatform
): String? {
    val appName = params.optString("app_name", "").trim().lowercase()
    if (appName.isEmpty()) return null

    // 1. Well-known alias (cheap, no I/O)
    AppAliases.PACKAGE_MAP[appName]?.let { return it }

    // 2. Installed apps lookup (same data OpenAppInvocation uses)
    val apps = platform.getInstalledApps()
    apps.find { it.label.equals(appName, ignoreCase = true) }?.let { return it.packageName }
    apps.find { it.label.contains(appName, ignoreCase = true) }?.let { return it.packageName }

    return null  // unresolved -> policy falls back to current-tier-only
}
```

This duplicates a subset of OpenAppInvocation's resolution logic, but intentionally: it's a best-effort pre-flight check, not the authoritative resolver. If resolution fails here, the existing BLOCKED check in OpenAppInvocation remains as defense-in-depth.

### Change 3: None needed in OpenAppTool

The existing BLOCKED check in `OpenAppInvocation.execute()` (lines 191-197) stays as defense-in-depth. No changes needed.

## Why This Is the Simplest Approach

| Alternative | Why not |
|---|---|
| Handle entirely in OpenAppInvocation | Invocation has no access to approval flow — would need new abstraction (ToolExecutionResult.NeedsApproval or similar) |
| Make ToolRouter special-case CAUTIOUS after execution | Approval must happen BEFORE launch, not after |
| Always require approval for open_app in SMART mode | Too aggressive — most destinations are NORMAL |
| Extract shared resolution logic | Premature abstraction for one call site |

## Behavior Matrix (SMART mode)

| Current app | Destination | Effective tier | Decision |
|---|---|---|---|
| NORMAL | NORMAL | NORMAL | Allow |
| NORMAL | CAUTIOUS | CAUTIOUS | AskUser |
| NORMAL | BLOCKED | BLOCKED | Deny |
| CAUTIOUS | NORMAL | CAUTIOUS | AskUser |
| CAUTIOUS | CAUTIOUS | CAUTIOUS | AskUser |
| launcher (null) | CAUTIOUS | CAUTIOUS | AskUser |
| any | unresolved | current tier | (no change) |

## Scope

- **Files changed:** 2 (PolicyEngine.kt, ToolRouter.kt)
- **Lines added:** ~20
- **Lines modified:** ~3 (check() signature, policy check call)
- **New abstractions:** 0
- **Risk:** Low. Null destinationPackage = current behavior. Resolution failure = current behavior. Only net-new behavior is: resolved CAUTIOUS destination triggers approval.
