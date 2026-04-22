# Dead Code & Over-Abstraction: Improvement Plan

Date: 2026-04-08
Based on: `review.md` (same directory)

---

## Phase 1: Safe Deletions (zero-risk, zero-dependency)

Estimated savings: ~500 lines

### 1.1 Delete entire dead files

| File | Lines | Action |
|------|-------|--------|
| `protocol/AgentError.kt` | ~170 | Delete file. Zero references anywhere. |
| `tool/ToolCallState.kt` | ~115 | Delete file. State machine was never implemented. |
| `tool/handlers/DataQueryInvocation.kt` | ~51 | Delete file. No tool creates this. |
| `util/StatusUtils.kt` | ~104 | Delete file. Zero imports. |
| `session/SessionServicesSummaryFormatter.kt` | ~31 | Delete file. Only caller is dead method. |

### 1.2 Delete dead methods from live files

| File | Method | Action |
|------|--------|--------|
| `session/SessionServices.kt` | `getSummary()` | Delete method |
| `session/SessionServices.kt` | `updateApprovalMode()` | Delete method |
| `tool/AppClassifier.kt` | `addUserOverride()` + `userOverrides` field | Delete method and field |
| `tool/ToolCallResult.kt` | `isSuccess()` | Delete method |
| `tool/ToolCallResult.kt` | `getOutputOrNull()` | Delete method |
| `tool/ToolSpec.kt` | `toFunctionSchema()` | Delete method |
| `platform/ActionResult.kt` | `isSuccess()` | Delete method |

### 1.3 Delete dead fields

| File | Field | Action |
|------|-------|--------|
| `tool/ToolCallResult.kt` | `Success.data: Any?` | Remove field, update all construction sites (all pass `null` or omit) |
| `tool/ToolSpec.kt` | `ToolExecutionResult.Success.data: Any?` | Remove field, update all construction sites |

---

## Phase 2: Dead Members in Live Files

Estimated savings: ~40 lines

### 2.1 Remove unused computed properties from Models.kt

```
// Remove from Bounds:
val width: Int get() = right - left
val height: Int get() = bottom - top
val centerX: Int get() = (left + right) / 2
val centerY: Int get() = (top + bottom) / 2

// Remove from ScreenSnapshot:
val hasElements: Boolean get() = elements.isNotEmpty()
val hasScreenshot: Boolean get() = image != null
```

Note: `ObservationBuilder.kt` uses `snapshot.hasElements` -- update that single callsite to `snapshot.elements.isNotEmpty()` before removing.

### 2.2 Remove `ScreenSnapshotDebug.captureQualityPath`

Remove the field and the single setter in `AccessibilityPlatform`. No consumer reads it.

### 2.3 Remove dead `MobileActionName` members

Remove `Back`, `Home`, `Wait`, `SystemButton` from the sealed class and their `from()`/`fromOrNull()` dispatch entries. These overlap with standalone tools (`system_button`, `wait`) and are never matched as mobile_action sub-actions.

### 2.4 Remove `ExecutorStepDecision.WarnApproaching`

The only producer is `ExecutorStepPolicy.evaluate()`. The only consumer (`AgentTurnRunner.buildWarnings()`) doesn't handle it. Two options:
- **Option A**: Delete the variant. Change the threshold logic to only emit `Continue` or `ForceStop`.
- **Option B**: Actually handle it by emitting a warning. But this was deliberately removed per comment: "advisory warnings have been removed -- they caused false positives."

Recommended: **Option A** -- delete the variant.

---

## Phase 3: Interface Simplification

Estimated savings: ~30 lines (and reduced cognitive load)

### 3.1 Merge `OnboardingDemoController` into `DefaultOnboardingDemoController`

Currently: interface + single implementation.
Change: Delete the interface file. Rename `DefaultOnboardingDemoController` to `OnboardingDemoController` (now a concrete class). Update the 2 references in `OnboardingViewModel` and `MainActivity`.

### 3.2 Merge `LlmCredentialValidator` into `HttpLlmCredentialValidator`

Same pattern. Delete the interface. Rename impl. Keep the `Result` sealed interface as a nested type.

---

## Phase 4: Optional Cleanup (low priority)

### 4.1 `AgentEventDomains.kt` marker interfaces

12 marker interfaces that no consumer filters on. They add ~35 lines. Removing them would require removing `implements` clauses from 15+ event classes. Low ROI, cosmetic only. **Defer.**

### 4.2 `ScreenSnapshot.textEnriched`

Set in `Perceptor`, read in `UiChangeDetector` to guard a path. The guard may still be functionally relevant. **Investigate before removing.**

---

## Execution Order

```
Phase 1.1 (file deletions)      -- safest, do first
Phase 1.2 (method deletions)    -- safe after file deletions
Phase 1.3 (field deletions)     -- requires updating construction sites
Phase 2   (member cleanup)      -- requires updating a few callsites
Phase 3   (interface merges)    -- requires renaming + updating references
Phase 4   (optional)            -- defer
```

## Verification

After each phase:
1. `./gradlew assembleDebug` -- build passes
2. `./gradlew lint` -- no new lint warnings
3. `./gradlew test` -- all tests pass

## Total Estimated Reduction

- **Phase 1**: ~500 lines deleted
- **Phase 2**: ~40 lines deleted
- **Phase 3**: ~30 lines deleted
- **Total**: ~570 lines removed (~1.6% of codebase)
- **Files removed**: 5 entire files deleted
- **Interfaces removed**: 2 unnecessary interfaces collapsed
- **Dead sealed variants removed**: 6+ unused type members
