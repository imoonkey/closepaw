Round: 0002
Agent: CLAUDE
Vote: CHANGES

## What changed in `final/design_aligned.md`

### Resolved OQ1 — Tab semantics (immediate config selector)

Both designs independently concluded tabs are config selectors. Evidence:
- Every existing control in `SettingsSheet` persists immediately — no batch/apply pattern exists anywhere
- The UX spec's "tab switching does not change saved settings" refers to preserving API key text values across tab visits, not deferring backend changes
- Without immediate mutation, there's no way to activate the LOCAL backend (no separate "Apply" control)

Added explicit tab side effects, initial tab derivation logic, and `LlmAuthTab` enum to the design.

### Resolved OQ3 — `remember` over `rememberSaveable`

Evidence from codebase:
- The sheet is gated by `if (showSettings)` in `MainActivityContent.kt:66`
- `showSettings` is a `private var` on `MainActivity.kt:87`, not a `rememberSaveable`
- No `android:configChanges` declared in `AndroidManifest.xml` for `MainActivity`
- On configuration change: Activity recreates → `showSettings` resets to `false` → sheet leaves composition → all `remember` AND `rememberSaveable` state is destroyed
- `rememberSaveable` would never actually restore because the sheet's parent `if` block evaluates to `false` before restoration happens

`remember` is correct. If `showSettings` ever becomes restorable, this can be revisited.

### Resolved OQ4 — `LLMProvider` directly, no wrapper enum

Both reviews agreed. `LLMProvider` maps 1:1 to the needed values. Added `LLMProvider.displayLabel` extension property. No redundant `ApiKeyProvider` enum.

### Narrowed OQ2 — OAuth extraction shape

Narrowed from "what shape" to the specific manager-vs-suspend-function trade-off with both positions stated. My position: the flow is linear with no cross-call state — a suspend function is simpler and structured concurrency handles cancellation. Refresh is a separate function (`refreshOAuthTokenIfNeeded()` at `MainActivity.kt:637`, 18 lines) that doesn't benefit from being bundled into the same class.

### Added to design

- AnimatedContent transition spec (directional horizontal slides)
- `LlmAuthTab` enum definition
- OAuth state derivation logic (how to compute initial `OpenAiAuthUiState` from settings)
- Explicit removal list (BackendSelector, ApiKeysSection, PerceptionModeSelector relocation)
- `LLMProvider.displayLabel` extension
- Split task 4 into three tasks (tab structure, provider selector + filtering, OAuth card)
- Renumbered tasks to 8 total

## Unresolved

OQ2 (OAuth extraction shape) remains. My position is stated in the design. Codex should respond.
