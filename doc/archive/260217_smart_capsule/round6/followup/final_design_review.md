# Final Design Review (Claude)

Date: 2026-02-14
Reviewing: `final_design.md` + Codex linter changes to code/tests

## Verdict: APPROVE with 4 fixes needed

Design direction is correct. A3 (A11y ⊖) and V5 (VD glow) are reasonable policy changes. V2/V3/V4 approach is the right one. Codex's linter changes to `OverlayLocationPolicy`, `NavSpec`, and tests are mostly correct but introduce a few bugs.

---

## Issues in Current Code (must fix before shipping)

### 1. CRITICAL: A11y first task shows island instead of capsule

`ServiceOverlayController.kt:92` — `showPreference` defaults to `ShowPreference.ISLAND`.

`onTaskStarted` (line 211) doesn't reset it. Now that A11y respects `ShowPreference`, the first task in A11y + OTHER_APP produces:

```
normalizedShowPreference = ISLAND  (Running ≠ WI/WA/Error, no force)
showCapsule = false
showIsland = true
```

User sees a tiny island instead of full capsule with [Takeover] [Stop] controls. Contradicts `user_flow.md` A2.R: "Overlay Capsule + Glow as system windows".

**Fix:** In `onTaskStarted`, reset `showPreference = ShowPreference.CAPSULE`. This ensures every new task starts with the full capsule visible. User can ⊖ to minimize afterwards.

### 2. HIGH: A11y glow regression for Done/Error

`OverlayLocationPolicy.kt:97`:
```kotlin
showGlow = location != OverlayUserLocation.MAIN_APP && hasActiveTask
```

`hasActiveTask` is false for Done and Error. But `user_flow.md` specifies:
- A2.D: Glow = "teal, auto-hide 2s"
- A2.E: Glow = "red"

Old code used `showGlow = showCapsule` which derived from `isActive` (includes Done/Error). New code drops glow for these modes.

**Fix:** Change to `isActive`:
```kotlin
showGlow = location != OverlayUserLocation.MAIN_APP && isActive
```

**Note:** For VD (line 113), `showGlow = hasActiveTask` is acceptable since VD glow is a new addition with no prior spec for Done/Error. But if consistency is desired, use `isActive` there too.

### 3. HIGH: onIslandTapped A11y branch is a no-op

`ServiceOverlayController.kt:162-164`:
```kotlin
PlatformMode.ACCESSIBILITY -> {
    // Defensive no-op: A11y should never show island.
}
```

Now A11y CAN show island via ⊖. Tapping the island does nothing — user is stuck with no way to restore the capsule.

**Fix:** Same logic as VD_VIEWER path — restore capsule:
```kotlin
PlatformMode.ACCESSIBILITY -> {
    showPreference = ShowPreference.CAPSULE
    applyVisibility()
}
```

### 4. MEDIUM: VD glow state never updates

All `edgeGlowManager.updateState()` calls in `ServiceOverlayController` (lines 219–273) are gated on `if (platformMode == PlatformMode.ACCESSIBILITY)`. With VD glow now enabled, mode changes in VD (Running→Takeover, phase changes, task completion) won't update glow color/pulsing. The glow will show (via `applyVisibility`) but stuck on its initial state.

**Fix:** Remove the `platformMode == ACCESSIBILITY` gate, or change to `if (edgeGlowManager.isShowing())`.

---

## Minor Issues (non-blocking, fix when convenient)

### 5. LOW: Event-driven force-CAPSULE is VD-only

`onSessionError` (line 252): `if (platformMode == VIRTUAL_DISPLAY) showPreference = CAPSULE`
`onAskUser` (line 286): same VD-only gate.

Now that A11y uses `ShowPreference`, these should apply to both modes. The state-invariant in `normalizedShowPreference` handles it (belt), but event-driven should be consistent (suspenders).

### 6. LOW: Design doc prohibitions P3/P4 not updated

`user_flow.md` prohibitions need relaxation:
- P3 "A11y mode showing Status Island (ever)" → "A11y mode showing Status Island during WI/WA/Error"
- P4 "A11y overlay showing navigation buttons (📱, 👁, ⊖)" → "A11y overlay showing 📱 or 👁"
- A2 table: Row2-R column needs updating for ⊖ visibility per mode

### 7. LOW: VD glow Done test asserts no-glow

`OverlayLocationPolicyTest` "hides glow in vd when task is inactive" uses `hasActiveTask=false, mode=Done`. This is correct for VD (new feature, no prior spec for Done glow).  But if you later decide VD Done should have brief teal glow (for consistency with A11y), this test would need updating.

---

## What Looks Good

- `normalizedShowPreference` force-CAPSULE guard now applies to both A11y and VD (line 82-88). Correct.
- `NavSpec.showMinimize` removed `platformMode == VD` gate → ⊖ now available in A11y. ✓
- `NavSpec.showApp/showWatch` still gate on `platformMode != ACCESSIBILITY` → no 📱/👁 in A11y. ✓
- Force-CAPSULE test for A11y WI/WA/Error added to `OverlayLocationPolicyTest`. ✓
- A11y island visibility test added. ✓
- NavSpec tests updated for A11y ⊖ (Running shows, WI hides). ✓
- V2/V3/V4 approach (intent flags + session rebind + defensive completion) is architecturally sound.

---

## Summary

| # | Severity | Issue | Fix |
|---|----------|-------|-----|
| 1 | **Critical** | A11y first task → island | `onTaskStarted`: reset `showPreference = CAPSULE` |
| 2 | **High** | A11y glow gone for Done/Error | Line 97: `hasActiveTask` → `isActive` |
| 3 | **High** | A11y island tap = no-op | Line 162: set `CAPSULE` + `applyVisibility()` |
| 4 | **Medium** | VD glow color stuck | Remove A11y-only gate from `updateState()` calls |
| 5 | Low | Force-CAPSULE event guards VD-only | Remove `platformMode == VD` condition |
| 6 | Low | P3/P4/A2 table not updated | Update `user_flow.md` |
| 7 | Low | VD Done glow test | Design question, not bug |
