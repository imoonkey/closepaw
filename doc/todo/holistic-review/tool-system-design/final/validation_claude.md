# Tool System Design — Independent Validation (Claude)

**Date:** 2026-04-10
**Method:** Read every source file cited in review.md, verified claims line-by-line against current HEAD (c9be69fc)
**Rule:** No reading of validation_codex.md — fully independent assessment

---

## Per-Finding Verdicts

### C1. Blocked-App Boundary Not Enforced End-to-End

**Verdict: CONFIRMED — but narrower than described**

The review claims raw `captureScreen()` leaks unmasked data in UIActionInvocation, PostActionAnalysis, and OpenAppTool. Two of these three are wrong:

| Path | appClassifier passed? | Evidence |
|------|-----------------------|----------|
| UIActionInvocation.capturePostActionObservation | YES | `UIActionInvocation.kt:80` calls `buildObservation(snapshot, context.platform, context.appClassifier)` |
| OpenAppInvocation.execute | YES | `OpenAppTool.kt:214-215` calls `buildObservation(it, context.platform, context.appClassifier)` |
| PostActionAnalysis.capturePostActionAnalysis | **NO** | `PostActionAnalysis.kt:45` calls `buildObservation(it, platform)` — no appClassifier |
| ToolRouter post-approval refresh | YES | `ToolRouter.kt:254-256` calls `maskIfBlocked()` directly |

**The real gap:** PostActionAnalysis is used by ALL mobile_action executors — ClickExecutor, LongPressExecutor, TypeExecutor, SwipeExecutor, ScrollExecutor (via `capturePostActionAnalysis()`). None of these paths have access to `appClassifier`.

**Attack scenario:** Agent clicks a link/notification on a NORMAL app. PolicyEngine allows the click (foreground is NORMAL). The click opens a BLOCKED banking app. PostActionAnalysis captures the banking app's screen and builds an unmasked observation sent to the cloud LLM.

**The open_app destination-unaware claim is real but less severe:** open_app resolves target package during execution after policy check (`OpenAppTool.kt:151-188`), but the observation IS masked because OpenAppInvocation passes appClassifier. The user lands on a masked screen and PolicyEngine denies subsequent actions.

**Fix:** Pass `appClassifier` through to `capturePostActionAnalysis`. ~15 min, one parameter threading change through 5 executor files.

---

### C2. ToolName Is Not Canonical — Omissions Change Runtime Behavior

**Verdict: CONFIRMED — but one claimed consumer is fabricated**

`ask_user` and `shell` are not in `ToolName` (`ToolName.kt:72-84`). They resolve to `Unknown`, which defaults `isScreenChanging = true` (`ToolName.kt:17`).

Real consumers affected:
1. **PolicyEngine.kt:49** — `if (!ToolName.from(toolName).isScreenChanging) return PolicyDecision.Allow` — ask_user/shell skip this early-return, hitting full tier-based policy. On CAUTIOUS apps, ask_user triggers unnecessary approval prompts.
2. **TurnToolPolicy.kt:59,64** — shell/ask_user classified as "screen actions". Line 68: `if (!hasScreenAction) completionCall` — when shell or ask_user is present, `complete_task` is dropped from the turn. This is a real arbitration bug.

**Invalid claim:** The review states `ActionSignature` (loop detection) is also affected. No `ActionSignature` class exists in the codebase. `LoopDetectionPolicy.kt` does not reference `ToolName` or `isScreenChanging`. The review inflated C2 from 2 consumers to 3.

**Fix:** Add two entries to ToolName + one `when` branch. 15 min. Exactly what Phase 1a of the improvement plan proposes.

---

### H1. Shell Bypasses Declarative Tool Model

**Verdict: CONFIRMED**

`ShellTool.kt:44`: `command.split(Regex("\\s+"), limit = 2).first()` extracts the first whitespace-delimited token.
`ShellTool.kt:46`: Checks only this token against `BLOCKED_COMMANDS = setOf("am", "pm", "reboot", "su")`.
`ShellTool.kt:79`: `ProcessBuilder("sh", "-c", command)` executes the full command via shell.

Trivial bypass: `cat /dev/null; am start -n com.bank.app/.MainActivity` — first token is `cat`, passes validation, `am` executes after `;`.

Blast radius IS limited by Android sandbox (no root, no cross-app private data access), but the blocklist is security theater as implemented. Worth hardening — either reject metacharacters (`; | & $ \` `) or switch to command whitelist.

---

### H2. Explicit-Target Scroll Silently Degrades

**Verdict: CONFIRMED**

`ScrollExecutor.kt:111-127`:
```kotlin
private fun resolveScrollArea(target: Target?, snapshot: ScreenSnapshot?, platform: AndroidPlatform): Bounds {
    if (target != null) {
        val resolved = targetResolver.resolve(target, snapshot)
        if (resolved is TargetResolver.ResolveResult.Resolved) { /* ... return bounds */ }
    }
    // Silent fallback — no error, no warning
    val display = platform.getDisplayInfo()
    return Bounds(0, 0, display.widthPixels, display.heightPixels)
}
```

When caller specifies `element_index=42` and element 42 doesn't exist, the scroll silently becomes a full-screen scroll instead of failing. The agent receives no signal that targeting failed. This can cause infinite scroll loops where the agent keeps trying to scroll a specific container but is actually scrolling the whole screen.

**Fix:** Return error when explicit target fails. ~15 min.

---

### M1. Cancellation Semantics Inconsistent Across Executors

**Verdict: CONFIRMED**

| Executor | ActionResult.Cancelled handling | Correct? |
|----------|-------------------------------|----------|
| PointActionExecutorCore.kt:116 | → `ActionOutcome.Cancelled(...)` | YES |
| SwipeExecutor.kt:49-53 | → `ActionOutcome.Failed(reason="...cancelled...")` | **NO** |
| TypeExecutor.kt | No explicit Cancelled handling | **NO** |
| ScrollExecutor.kt:61,91 | → `ActionOutcome.Cancelled(...)` | YES |

SwipeExecutor maps platform cancellation to Failed, losing the cancellation signal. TypeExecutor's `performAction` calls (lines 57, 87, 98) don't have explicit Cancelled branches — Cancelled would fall through to the failure path.

Router-level: `cancel()`/`cancelAll()` (`ToolRouter.kt:334-349`) only abort pending approvals. No mechanism to cancel an already-executing tool (no CancellationToken or Job reference).

Real but low practical impact — the agent session has its own cancellation via `isCancelled()` checks.

---

### M2. ToolSpec Standardizes Inputs But Not Capabilities or Outputs

**Verdict: CONFIRMED — design observation, not a bug**

`ToolSpec` interface has no capability metadata. `ToolName.isScreenChanging` is the only capability signal, lives in a parallel enum that drifts from registered tools (proven by C2). Tool outputs are `data: Any?`. Post-action timing constants are scattered.

Real architectural debt, but the improvement plan's Phase 1b (add 3 booleans to ToolSpec, build ToolCapabilitiesResolver) is over-engineered for 2 consumers. The stopgap fix (Phase 1a: add entries to ToolName) is sufficient.

---

### M3. Point-Action Retargeting Is Hidden Policy

**Verdict: CONFIRMED — low practical impact**

`PointActionExecutorCore.kt:185-217`: `refinePointActionTarget()` silently promotes non-clickable elements to clickable containers or nearby children. `Log.d` at line 200-202 logs the change, but the refinement is NOT included in the attempt trail or the success message returned to the LLM.

The `warnings` field at line 216 carries through the ORIGINAL resolved warnings, no new warning is added for the promotion itself.

Real observation, but this retargeting IMPROVES click success rates on real Android UIs (where text labels inside clickable containers are common). Making it observable in the attempt trail would be good for debugging but is low priority.

---

### M4. TOCTOU After Approval Is Asymmetric

**Verdict: CONFIRMED — extremely narrow**

`ToolRouter.kt:196-228`:
- `packageName != null && currentPkg != packageName` → CANCEL (any change detected)
- `packageName == null && currentPkg != null && BLOCKED` → CANCEL
- `packageName == null && currentPkg != null && CAUTIOUS` → **PROCEED** (no re-check)

`AppClassifier.classify(null)` returns `CAUTIOUS` (`AppClassifier.kt:21`). So when packageName was unknown at policy check time, the initial policy ran on CAUTIOUS. After approval wait, if a different CAUTIOUS app is now foreground, the check passes without re-evaluating whether this specific CAUTIOUS app needs separate approval.

Real but requires: (1) packageName null at tool call time, (2) app changes to a different CAUTIOUS app during the 60s approval window. Very unlikely in practice.

---

### L1. Dead Scroll-Boundary Code

**Verdict: CONFIRMED**

- `UIActionInvocation.kt:53-58,87-110`: `detectScrollBoundary()` guarded by `uiAction is UIAction.Swipe`. UIActionInvocation is only instantiated by `SystemButtonTool.kt:73` and `WaitTool.kt:64` — neither produces Swipe. Dead code.
- `UiChangeDetector.kt:31-49`: `detectScrollBoundary()` — grep finds zero production callers. Dead code.

~30 lines removable.

---

### L2. MobileActionName Vestigial Members

**Verdict: CONFIRMED**

`PolicyEngine.kt:133-137`: `isEscape()` checks `MobileActionName.Back` and `MobileActionName.Home`. But:
- `MobileActionTool.kt:60`: validates action ∈ {click, long_press, scroll, swipe, type} — rejects "back"/"home"
- No standalone tool with name "back" or "home" exists in the registry
- `MobileActionName.fromOrNull(toolName)` with toolName="mobile_action" returns null (not Back/Home)

The entire `MobileActionName.Back/Home` escape path in `isEscape()` is unreachable. Only the `ToolName.SystemButton` path (lines 128-132) works for back/home escape detection.

Also: `MobileActionName.Wait` and `MobileActionName.SystemButton` members are vestigial — there are no mobile_action calls with action="wait" or action="system_button".

---

### L3. Duplicate Constants in OpenAppTool

**Verdict: CONFIRMED**

- `OpenAppTool` companion (line 74-75): `UI_SETTLE_DELAY_MS = 800L`, `SUGGESTION_LIMIT = 5`
- `OpenAppInvocation` companion (line 135-136): `UI_SETTLE_DELAY_MS = 800L`, `SUGGESTION_LIMIT = 5`
- Only OpenAppInvocation's constants are referenced. OpenAppTool companion constants are dead.

---

### L4. SystemButtonTool Unreachable Fallback

**Verdict: CONFIRMED**

`SystemButtonTool.kt:66-72`: `else -> SystemButtonType.BACK` in the `when` block. `validate()` at line 57-59 rejects any button not in `VALID_BUTTONS = listOf("back", "home", "enter", "recents")`. The `else` branch is unreachable.

Should be `else -> error("Unreachable: validated in validate()")`.

---

### L5. DataQueryInvocation Appears Unused

**Verdict: CONFIRMED**

`DataQueryInvocation.kt` — grep across all .kt files finds zero production callers. Only the class definition itself and doc references. 52 lines of dead code.

---

### L6. Shell Output Truncation Is Silent

**Verdict: CONFIRMED**

`ShellTool.kt:91-98`: Read loop stops at `MAX_OUTPUT_CHARS` (4096). Line 114: `textToolSuccess(output = "exit=$exitCode\n$output")` — no truncation indicator appended. The LLM receives a cleanly-ended partial output and has no way to know data was cut off.

**Fix:** 5 min — append `\n[output truncated at 4096 chars]` when output reaches limit.

---

### L7. Executor Per-Call Allocation

**Verdict: NOT_WORTH_IT**

`MobileActionTool.kt:73,77,85,89,93`: `ClickExecutor()`, `LongPressExecutor()`, `TypeExecutor()`, `ScrollExecutor()`, `SwipeExecutor()` instantiated per `createInvocation()` call. All are stateless.

JVM allocates small objects in ~10ns. These executors have no fields beyond `targetResolver: TargetResolver = TargetResolver` (a singleton default). Making them singletons saves nothing measurable. The current pattern is clearer: no shared mutable state concerns.

---

### L8. Scheduled State Is Ephemeral

**Verdict: NOT_WORTH_IT**

`ToolRouter.kt:234`: `Scheduled` state is created, then execution immediately continues to `Executing` (line 248). No external code queries for `Scheduled` state specifically. It exists as a lifecycle-completeness notification to `onStateChange` callbacks.

Removing it would save nothing and break the state model's symmetry with the documented lifecycle at `ToolCallState.kt:11-20`. Harmless.

---

## Summary Table

| ID | Verdict | Severity | Fix Effort | Worth Doing? |
|----|---------|----------|------------|-------------|
| C1 | CONFIRMED (narrowed) | Critical | 30 min | **YES** — thread appClassifier through PostActionAnalysis |
| C2 | CONFIRMED (inflated) | High | 15 min | **YES** — add ask_user/shell to ToolName |
| H1 | CONFIRMED | High | 30 min | **YES** — harden shell validation |
| H2 | CONFIRMED | High | 15 min | **YES** — fail on explicit target miss |
| M1 | CONFIRMED | Medium | 30 min | Yes — fix SwipeExecutor/TypeExecutor cancellation |
| M2 | CONFIRMED | Medium | N/A | No — design observation, Phase 1a covers the real issue |
| M3 | CONFIRMED | Low | 15 min | Maybe — add retargeting note to attempt trail |
| M4 | CONFIRMED | Low | 15 min | No — extremely narrow window |
| L1 | CONFIRMED | Low | 10 min | Yes — dead code, safe delete |
| L2 | CONFIRMED | Low | 10 min | Yes — dead code, safe delete |
| L3 | CONFIRMED | Low | 5 min | Yes — dead constants, safe delete |
| L4 | CONFIRMED | Low | 5 min | Yes — replace with error() |
| L5 | CONFIRMED | Low | 5 min | Yes — dead file, safe delete |
| L6 | CONFIRMED | Low | 5 min | Yes — 1-line fix |
| L7 | NOT_WORTH_IT | Low | 15 min | No — negligible allocation cost |
| L8 | NOT_WORTH_IT | Low | 15 min | No — harmless, preserves model symmetry |

---

## Review Quality Assessment

**Accuracy: 12/16 fully correct, 2 partially correct, 2 not worth fixing**

The review found real issues. The architecture analysis is sound: the pipeline design is good, the security gap in PostActionAnalysis is real and worth fixing, and the ToolName drift causes actual bugs.

**Where the review overreaches:**

1. **C1 inflates the attack surface.** UIActionInvocation and OpenAppTool both pass `appClassifier` to `buildObservation`. The review lists them as unprotected alongside PostActionAnalysis. Only PostActionAnalysis is actually unprotected. The finding is real but the evidence is sloppy.

2. **C2 fabricates a consumer.** "ActionSignature (loop detection)" does not exist. `LoopDetectionPolicy` doesn't use `isScreenChanging`. The finding has 2 real consumers (PolicyEngine, TurnToolPolicy), not 3.

3. **L7/L8 are observations, not problems.** Per-call allocation of stateless objects is a non-issue. Ephemeral Scheduled state is harmless by design.

**Where the review is strong:**

1. **H1 (Shell validation)** — Correctly identifies that first-token-only blocklist is security theater against shell metacharacters.
2. **H2 (Silent scroll degradation)** — Correctly identifies that explicit targets silently falling back to full-screen breaks the tool contract.
3. **Dead code findings (L1-L5)** — All confirmed with zero false positives.
4. **Improvement plan structure** — Independently-shippable phases, Phase 0 correctly prioritized.

---

## Recommended Fix Priority

**Do now (< 1 hour, high ROI):**
1. C2: Add `ask_user`/`shell` to ToolName with `isScreenChanging = false`
2. L6: Shell truncation indicator
3. L1-L5: Dead code cleanup batch

**Do soon (< 2 hours, security):**
4. C1: Thread `appClassifier` through `capturePostActionAnalysis` → all 5 executors
5. H1: Harden shell validation (reject metacharacters or whitelist)
6. H2: Fail on explicit target miss in ScrollExecutor

**Do when convenient:**
7. M1: Fix SwipeExecutor/TypeExecutor cancellation mapping
8. M3: Add retargeting note to attempt trail

**Skip:**
- L7 (allocation), L8 (Scheduled), M2 (full metadata migration), M4 (TOCTOU)
- Phase 1b of improvement plan (ToolSpec capabilities) — overkill for 2 consumers
