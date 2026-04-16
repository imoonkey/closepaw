# UI/UX Quality -- Codex Code Review

**Reviewer**: Claude Opus 4.6 (1M context)
**Date**: 2026-04-16
**Baseline**: `317ae8f8`
**Commits reviewed**: `d9be858a`, `ebcb83a0`
**Files**: 15 changed across 6 phases

---

## Phase 1: Capsule Composition Correctness

### SmartCapsuleSurface.kt

**Verdict**: Correct. The `previousModeState` internal tracking (a composition-time write) is fully removed. `previousMode` is now a caller-supplied parameter. The `LaunchedEffect(renderSpec.row3?.clearInput)` properly moves the state write into an effect, fixing the composition-during-composition violation.

### SmartCapsuleCompose.kt

**Verdict**: Correct. The `previousMode` parameter is plumbed through to `SmartCapsuleSurface`. Default `null` preserves backward compatibility for callers that don't supply it.

### CapsuleStateHolder.kt / ChatScreen.kt / CapsuleOverlayHost.kt

**Verdict**: Correct. Both the in-app path (`ChatScreen` line 167: `previousMode = stateHolder?.previousMode`) and the overlay path (`CapsuleOverlayHost` line 118: `previousMode = stateHolder.previousMode`) read from the single source of truth. The `stateHolder.previousMode` is a plain `var` written on the main thread inside `setMode()` -- safe for both callers since Compose UI reads always happen on Main.

**No findings for Phase 1.**

---

## Phase 2: Settings State Hoisting

### SettingsSheet.kt

**Verdict**: Correct. `settingsPage` migrated from `remember` to `rememberSaveable`. `SettingsPage` is an enum -- Kotlin enums are `Serializable`, so the default `autoSaver` works.

### LlmAuthSettingsPage.kt -- selectedTab

**Verdict**: Correct. `rememberSaveable(authMethod, llmBackend)` re-derives when external state changes (e.g., returning from OAuth sets `authMethod = "oauth"`) and survives rotation otherwise. `LlmAuthTab` is an enum, so `autoSaver` works. Tab taps no longer fire backend mutations -- those are deferred to `commitSignIn/commitApiKey/commitLocal` wrapper functions that fire only on real user actions within tab content.

### LlmAuthSettingsPage.kt -- selectedProvider

**Verdict**: Correct. `rememberSaveable(selectedModel)` re-derives from the externally selected model and survives rotation otherwise.

## [HIGH] Provider sub-selector no longer canonicalizes models on switch
**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/LlmAuthSettingsPage.kt:246-249`
**Issue**: When the user taps a different provider in the API Key tab's `SegmentedButtonRow`, the old code called `canonicalizeModels()` to ensure the selected model was valid for the new provider. The new code only sets `selectedProvider` locally (line 248). This means the model dropdown will show the new provider's models, but the actually-committed `selectedModel` may be one that doesn't belong to that provider. If the user then closes settings without picking a new model, the persisted model will be stale/invalid for the displayed provider context.

The `commitApiKey` wrapper only fires when the user picks a model or types a key, so if they switch provider and leave without touching the model dropdown, the model is never canonicalized.

**Fix**: Call `canonicalizeModels()` inside the provider `onClick` (or more defensibly, inside a `LaunchedEffect(selectedProvider)` that canonicalizes whenever the provider changes). This is purely a local UI concern -- it should update the dropdown selection to a valid model for the new provider context.

---

## Phase 3: Chat Scroll

### scrollKey derivation

**Verdict**: Correct. `messages` comes from `mutableStateListOf` in `ChatViewModel`, so `derivedStateOf` properly observes snapshot mutations. The formula `messages.size * 100_000 + contentSignal` covers both new messages and streaming growth. The multiplier `100_000` is large enough to avoid collisions for any realistic session.

### isNearBottom

**Verdict**: Correct. `derivedStateOf` reading `listState.layoutInfo` is snapshot-tracked via `LazyListState`.

### Scroll-to-bottom FAB

**Verdict**: Clean. Animation transitions (fadeIn + scaleIn / fadeOut + scaleOut), theme-token colors, contentDescription present.

## [MEDIUM] scrollKey may over-trigger during streaming
**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:226`
**Issue**: `last.content.length` changes on every streaming delta (character-by-character). This means `scrollKey` changes on every delta, causing `LaunchedEffect(scrollKey)` to cancel and re-launch `animateScrollToItem` on every single character. While `animateScrollToItem` to the same index is cheap, the constant cancellation + re-launch is wasteful during fast streaming. On slower devices, the rapid animation restarts could cause visible jank.
**Fix**: Consider quantizing the content signal (e.g., `last.content.length / 50`) so the scroll key only changes every ~50 characters. Streaming is already keeping the last item visible via layout growth; the scroll effect really only matters when content exceeds the viewport. Alternatively, use `scrollToItem` (instant, no animation) when streaming and `animateScrollToItem` only on new messages.

### MessageBubble.kt -- SimpleDateFormat removal

**Verdict**: Correct. `SimpleDateFormat` replaced with thread-safe `DateTimeFormatter` at top level. Matches `TimeUtils.kt` pattern. `Locale.getDefault()` used for formatting.

## [LOW] Locale-change resilience for top-level DateTimeFormatter
**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/components/MessageBubble.kt:186`
**Issue**: `private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())` captures the locale at class-load time. If the user changes locale while the app is running, the formatter will still use the old locale until process restart. This matches what the old `SimpleDateFormat` code did (allocated per-call with `Locale.getDefault()` but was thread-unsafe). The current approach is strictly better (thread-safe), but not perfect.
**Fix**: No action required unless locale-hot-swap is a goal. Document the tradeoff with a comment.

### ActionCard.kt -- double rotation removal

**Verdict**: Correct. The redundant `infiniteTransition` rotation wrapper around `CircularProgressIndicator` is removed. `CircularProgressIndicator` already animates its own indeterminate spin internally. Clean removal with no behavioral change.

---

## Phase 4: Destructive Action Confirmations

### NavigationDrawer.kt

**Verdict**: Correct. `showDeleteConfirm` state is scoped to `DrawerSessionItem` via `remember`. Dialog dismisses on both `onDismissRequest` and "Cancel". Confirm button resets state before calling `onDelete()`. Error-colored confirm button.

### PermissionsAdvancedSettingsPage.kt

## [HIGH] Compose state written from Dispatchers.IO
**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/PermissionsAdvancedSettingsPage.kt:168` and `:193`
**Issue**: Inside the confirm dialog's onClick handler, `scope.launch(Dispatchers.IO)` runs a background coroutine that writes `tracesCleared = true` (line 168) and `sessionsCleared = true` (line 193). These are Compose `mutableStateOf` variables. Writing to Compose snapshot state from a non-Main dispatcher is undefined behavior -- it can silently drop the write, crash with a snapshot exception, or appear to work by coincidence. The old code (pre-diff) had the same issue, so this is inherited rather than newly introduced.
**Fix**: Move the state write after the IO work completes on Main:
```kotlin
scope.launch(Dispatchers.IO) {
    context.getExternalFilesDir(TRACE_DIR)?.deleteRecursively()
}.invokeOnCompletion { tracesCleared = true }
```
Or use `withContext`:
```kotlin
scope.launch {
    withContext(Dispatchers.IO) {
        context.getExternalFilesDir(TRACE_DIR)?.deleteRecursively()
    }
    tracesCleared = true
}
```
The second form (`withContext`) is idiomatic and ensures the state write runs on the scope's dispatcher (Main).

---

## Phase 5: Accessibility

### OnboardingShell.kt

**Verdict**: Correct. `Icon` + `clickable` replaced with `IconButton`, providing the mandatory 48dp minimum touch target. `Spacer` reduced from 12dp to 4dp to compensate for `IconButton`'s built-in padding.

### StatusIslandCompose.kt

**Verdict**: Mostly correct. `role = Role.Button` and `contentDescription` added via `semantics` block. Hardcoded `Color.White` and `Color(0xFF171717)` replaced with `MaterialTheme.colorScheme.surface` and `MaterialTheme.colorScheme.onSurface`.

## [MEDIUM] Redundant semantics -- clickable already merges Role.Button
**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/StatusIslandCompose.kt:37-41`
**Issue**: The `clickable` modifier already sets `Role.Button` semantics for TalkBack. Adding an explicit `.semantics { role = Role.Button }` is redundant. However, the `contentDescription` in the same block IS needed, so the `semantics` modifier should stay -- just the `role` line is unnecessary.
**Fix**: Remove `role = Role.Button` from the semantics block; the `contentDescription` alone is sufficient. Alternatively, use `clickable(onClickLabel = "Status: $text", onClick = onClick)` which is cleaner. Minor/cosmetic.

## [MEDIUM] Redundant clip before Surface with same shape
**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/StatusIslandCompose.kt:36`
**Issue**: `.clip(RoundedCornerShape(20.dp))` is applied on the modifier, but `Surface` already clips to its `shape = RoundedCornerShape(20.dp)`. The outer clip is redundant. This was pre-existing, not introduced in this diff.
**Fix**: Remove `.clip(RoundedCornerShape(20.dp))` from the modifier. Pre-existing, low priority.

### SmartCapsuleSurfaceParts.kt

**Verdict**: Correct. `contentDescription` added to all `NavIconButton` calls. The `NavIconButton` signature now requires `contentDescription: String` (non-nullable), preventing future callers from omitting it.

---

## Phase 6: Overlay State Unification

### CapsuleOverlayHost.kt

**Verdict**: Correct. The three duplicate `MutableStateFlow` fields (`capsuleContext`, `platformMode`, `hasIsland`) are removed. Compose now reads directly from `stateHolder.context`, `stateHolder.platformMode`, `stateHolder.hasIsland`. The `updateNavContext()` function is deleted. Host-only concerns (`transientThought`, `inputFocused`, `interactionLocked`) correctly remain local.

### ServiceOverlayController.kt

**Verdict**: Correct. `capsuleManager.updateNavContext(...)` call replaced with `stateHolder.setHasIsland(...)`. The `stateHolder.setContext(ctx)` call on the line above already handles context propagation. No duplicate writes remain.

### CapsuleStateHolder.kt

**Verdict**: Correct. New `_hasIsland` / `hasIsland` flow added with `setHasIsland()` setter. Clean addition, consistent with existing patterns.

## [LOW] CapsuleOverlayHost collectAsState() without initial values
**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt:109-111`
**Issue**: The new lines use `collectAsState()` (no `initial` parameter) while the existing lines above (108) use `collectAsState(initial = CapsuleMode.Hidden)`. This inconsistency is cosmetic -- `collectAsState()` without `initial` uses the `StateFlow.value` as the initial value, which is correct. But the mixed style within the same block is slightly confusing.
**Fix**: Either add explicit `initial` parameters to lines 109-111 for consistency, or remove the `initial` parameters from lines 107-108. The latter is preferred since `StateFlow` always has a current value.

---

## Cross-Cutting Observations

## [MEDIUM] No tests for any of these changes
**Issue**: The improvement plan explicitly lists verification steps for each phase (compose tests for capsule transitions, rotation survival tests for settings, etc.). None of these tests appear in the diff. All 15 changed files are production code.
**Fix**: Add at minimum: (1) A compose test verifying `SmartCapsuleSurface` input clearing behavior on mode transitions (Phase 1 verification). (2) A test for the `rememberSaveable` keying behavior in `LlmAuthSettingsPage` (Phase 2 verification). These are the P0 phases and carry the highest regression risk.

---

## Summary

| Severity | Count | Details |
|----------|-------|---------|
| CRITICAL | 0 | -- |
| HIGH | 2 | IO-thread state write in settings dialogs; provider switch skips model canonicalization |
| MEDIUM | 4 | Scroll over-trigger during streaming; redundant semantics role; redundant clip; no tests |
| LOW | 2 | Locale-change resilience; collectAsState style inconsistency |

**Recommendation**: **CHANGES REQUESTED** (2 HIGH findings).

The HIGH-severity IO-thread state write (Phase 4) is a latent threading bug that can cause silent state loss or crashes. The provider canonicalization gap (Phase 2) is a real UX regression where switching API-key providers leaves a stale model selected. Both should be fixed before merging.

The overall quality of the changes is high -- the core architecture improvements (composition correctness, state unification, destructive confirmations) are well-executed and align precisely with the improvement plan.
