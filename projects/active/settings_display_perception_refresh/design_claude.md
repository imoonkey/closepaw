# Settings: Display + Perception Refresh

Date: 2026-05-17
Owner: Claude

## Goal

Two adjacent problems on the Settings sheet, fixed together because the fix shares one pattern:

1. **Display Mode lives in the wrong page and uses the wrong widget.** It's currently a radio (Accessibility vs Virtual Display) under `Permissions & Advanced`, but it's a *behavior* choice (where the agent runs), not a permission. And the radio shape lies about the semantics — Accessibility is always-available baseline, Virtual Display is a conditional upgrade gated by Shizuku. That's a toggle, not a radio. The label "Accessibility" also leaks the underlying `AccessibilityService` API into UI copy, where users have to translate "a11y mode" → "agent runs on my screen."
2. **Perception Mode copy is opaque and the layout starves descriptions.** Three horizontal segmented buttons reading `Accessibility Only` / `Hybrid (A11y + Screenshot)` / `Screenshot Only` — same Accessibility-leak, no room for the actual tradeoff each option carries, and `Hybrid (…)` just describes mechanism instead of outcome. Users picking this for the first time have no basis to choose.

Outcome:
- `Display Mode` section moves to `Agent Behavior` and becomes a single `Virtual Display` toggle card that reuses the existing `ToolSettingsCard` / `ToolStatusUi` shape (the [tool_settings_unify](../tool_settings_unify/design_claude.md) work has landed). Shizuku state shows inline as status + action, same pattern as `browser_script`.
- `Perception Mode` keeps its single-of-three semantics but switches to a vertical radio list with outcome-oriented labels and one-line subtitles. `Screen Transcript (Recommended)` / `Screen Image` / `Transcript + Image`.

Out of scope: changing platform mode runtime behavior, changing perception runtime behavior, renaming `PlatformMode` enum or the perception config string IDs (those stay because they're on-disk + IPC contracts), redesigning the Permissions section.

## Approach

### Decision 1 — Display Mode becomes a `Virtual Display` toggle in Agent Behavior

Today's `DisplayModeSection` is structurally identical to a Shizuku-gated tool toggle:

| Concept | `browser_script` (Agent Behavior → Tools) | Display Mode today |
|---|---|---|
| Default state | Off (tool unavailable to LLM) | A11y (always-available substrate) |
| "On" state | Requires Shizuku ready + permission | Requires Shizuku ready + permission |
| Failure mode | Gate refuses to persist; inline error | Radio greys out; separate status row |
| Effective state vs persisted | Session-scoped; on errors LLM sees tool absent | Session-scoped; on errors silent fallback to A11y |

So the natural shape is a single toggle card driven by [`ToolSettingsCard`](../tool_settings_unify/design_claude.md#components) — same component, new mapper. `A11y` becomes the implicit off-state; the subtitle in the off state spells that out ("Agent runs on your current screen"). The Accessibility-leak in UI copy disappears not by renaming but by making the choice asymmetric: only the *opt-in* side gets a label, the default is "off."

This also means: no need for the earlier proposed `Main Display` vs `Virtual Display` symmetric naming — symmetry was a workaround for the radio shape, and we're killing the radio.

#### Gate behavior

A new `VirtualDisplayToggleGate`, parallel to `BrowserScriptToggleGate`. Same persist-only-on-success contract for the UI surface. Programmatic writes from `MainActivityIntentApplier` (deep links / intents) bypass the gate by design — they go straight through `AppSettingsStore.savePlatformMode(VIRTUAL_DISPLAY)`. The status card honestly reflects whatever state results (rows 6–9 cover "persisted=VD but Shizuku not actually ready"), so the bypass is visible rather than hidden. If we ever want intents to fail loudly instead, the fix is to wrap the intent path in `gateVirtualDisplayEnable` — out of scope here.

```kotlin
internal sealed interface VirtualDisplayToggleError {
    data object ShizukuUnavailable : VirtualDisplayToggleError
    data object ShizukuPermissionDenied : VirtualDisplayToggleError
}

internal fun VirtualDisplayToggleError.message(): String = when (this) {
    ShizukuUnavailable -> "Shizuku is not running. Install or start Shizuku, then turn on Virtual Display."
    ShizukuPermissionDenied -> "Permission denied. Tap to retry, or grant in Shizuku Manager."
}

internal suspend fun gateVirtualDisplayEnable(
    isShizukuAvailable: suspend () -> Boolean = { ShizukuClient().isAvailable() },
    hasShizukuPermission: suspend () -> Boolean = { ShizukuClient().hasPermission() },
    requestShizukuPermission: suspend () -> PermissionRequestResult = {
        ShizukuRuntimeGateway().requestPermissionAndAwait()
    },
): VirtualDisplayToggleError? {
    if (!isShizukuAvailable()) return ShizukuUnavailable
    if (!hasShizukuPermission()) {
        when (requestShizukuPermission()) {
            Granted    -> if (!hasShizukuPermission()) return ShizukuPermissionDenied
            Denied     -> return ShizukuPermissionDenied
            Error      -> return ShizukuUnavailable
        }
    }
    return null
}
```

Persist mapping is binary:
- gate returns `null` → `savePlatformMode(VIRTUAL_DISPLAY)`
- toggle OFF (unconditional, no gate) → `savePlatformMode(ACCESSIBILITY)`

The protocol enum `PlatformMode` and its on-disk key `KEY_PLATFORM_MODE` stay unchanged — UI is the only thing that re-frames it as a toggle. The persisted value still encodes the symmetric choice, which is correct: if we later add a third substrate (e.g. cast/mirror) the protocol can grow without a migration.

#### "Ready" means "preference accepted, will be applied on next session start" — nothing stronger

Browser Script's gate does real setup work: it writes Chrome's command-line file (`CommandLineWriter.ensureWritten`) before persisting ON, so a successful gate proves the tool can actually launch. VD's gate is pure permission-check — Shizuku available + permitted. A successful gate does **not** prove that `VirtualDisplayPlatform.start()` will succeed on the next session (Shizuku could die between toggle-on and session start, or display creation could fail for unrelated reasons).

The status copy reflects this honesty. `Ready` means "the *preference* is accepted and Shizuku is currently ready" — not "VD is definitely running." The runtime row (driven by `effectivePlatformMode`) is where we say what's actually selected for the live session.

Also: the gate doesn't re-poll Shizuku after toggle-on succeeds. If Shizuku binder dies later, the persisted `VIRTUAL_DISPLAY` stays on (it's still the user's preference), and the status row reports the current Shizuku state. Browser Script behaves the same way; the symmetry matters because the persisted toggle is *preference*, not *health*.

#### `effectivePlatformMode` is "platform selected for the observed session" — not live runtime health

Important constraint discovered during review: `effectivePlatformMode` in `AgentService` is set from `session.getServices().platform.mode` when a session is observed, then cleared on session destroy. It does **not** update on Shizuku binder death, runtime fallback after the platform exists, or any other live-health signal. Specifically:

- VD session created OK → `effectiveMode = VIRTUAL_DISPLAY` (stays VD even if Shizuku dies mid-session — `VirtualDisplayPlatform` marks itself broken internally; the field doesn't switch back).
- `PlatformFactory` falls back to `AccessibilityPlatform` at session-create time (Shizuku unavailable when session starts) → `effectiveMode = ACCESSIBILITY`.
- A11y session → `effectiveMode = ACCESSIBILITY`.
- No active session → `effectiveMode = null`.

The mapper must respect this. Earlier draft had a row like "persisted=VD, shizuku=Unavailable, effective=ACCESSIBILITY → Lost Shizuku connection" — that's fantasy when the active session is *still* a `VirtualDisplayPlatform` with broken Shizuku. Corrected table below splits on `effectiveMode` honestly.

#### Status mapper — corrected table

Inputs: `(persistedMode, effectiveMode, shizukuStatus, gatePending, gateError)`. Resolution order: first match wins.

| # | Condition | label | subtitle | tone | spinner | switch checked | switch enabled | row action |
|---|---|---|---|---|---|---|---|---|
| 1 | `gatePending` | `Setting up…` | "Checking Shizuku…" | Neutral | ✓ | `persistedMode == VD` | – (disabled) | – |
| 2 | `gateError == ShizukuUnavailable && persistedMode == ACCESSIBILITY` | `Needs Setup` | "Shizuku is not running. Install or start Shizuku, then turn on Virtual Display." | Warning | – | unchecked | ✓ | `RetryEnable` (`clearError` + `setEnabled(true)`). Expanded slot: `Learn more` button → opens [shizuku.rikka.app](https://shizuku.rikka.app). |
| 3 | `gateError == ShizukuPermissionDenied && persistedMode == ACCESSIBILITY` | `Needs Setup` | "Permission denied. Tap to retry, or grant in Shizuku Manager." | Warning | – | unchecked | ✓ | `RetryEnable` (`clearError` + `setEnabled(true)`) |
| 4 | `persistedMode == ACCESSIBILITY` and `effectiveMode != VIRTUAL_DISPLAY` (i.e. (A11y, null) or (A11y, A11y)) | `Disabled` | "Agent runs on your current screen." | Neutral | – | unchecked | ✓ | – |
| 5 | `persistedMode == ACCESSIBILITY` and `effectiveMode == VIRTUAL_DISPLAY` (user toggled off while a VD session is still alive) | `Disabled` | "Current session is still on a virtual display; next session will use your current screen." | Neutral | – | unchecked | ✓ | – |
| 6 | `persistedMode == VIRTUAL_DISPLAY` and `shizukuStatus == Unavailable` and `effectiveMode != VIRTUAL_DISPLAY` | `Not Available` | "Shizuku is not running. If a new session starts now, it will run on your current screen." | Warning | – | checked | ✓ | – |
| 7 | `persistedMode == VIRTUAL_DISPLAY` and `shizukuStatus == NeedsPermission` and `effectiveMode != VIRTUAL_DISPLAY` | `Needs Setup` | "Grant Shizuku permission to use Virtual Display." | Warning | – | checked | ✓ | `RequestPermission` (guarded by local `permissionRequestPending` — see below) |
| 8 | `persistedMode == VIRTUAL_DISPLAY` and `shizukuStatus == Unavailable` and `effectiveMode == VIRTUAL_DISPLAY` (Shizuku died mid-VD-session) | `Degraded` | "Lost Shizuku connection — the current virtual-display session may stop working. Restart Shizuku to recover." | Warning | – | checked | ✓ | – |
| 9 | `persistedMode == VIRTUAL_DISPLAY` and `shizukuStatus == NeedsPermission` and `effectiveMode == VIRTUAL_DISPLAY` | `Degraded` | "Shizuku permission revoked — the current virtual-display session may stop working." | Warning | – | checked | ✓ | `RequestPermission` (same guard as row 7) |
| 10 | `persistedMode == VIRTUAL_DISPLAY`, `shizukuStatus == Ready`, `effectiveMode == VIRTUAL_DISPLAY` | `Ready` | "Current session is configured for a virtual display." | Positive | – | checked | ✓ | – |
| 11 | `persistedMode == VIRTUAL_DISPLAY`, `shizukuStatus == Ready`, `effectiveMode == ACCESSIBILITY` (factory fell back at session-start, or session predates the toggle) | `Ready` | "Will switch to a virtual display on the next session." | Neutral | – | checked | ✓ | – |
| 12 | `persistedMode == VIRTUAL_DISPLAY`, `shizukuStatus == Ready`, `effectiveMode == null` (no active session) | `Ready` | "Will start on a virtual display." | Positive | – | checked | ✓ | – |

Three invariants encoded by this table:

- **Switch checked state mirrors persisted mode strictly** (`switchChecked = (persistedMode == VIRTUAL_DISPLAY)`). No optimistic UI during pending, no flip on retry. Matches `BrowserScriptToolRow`'s `switchChecked = enabled` exactly. Removes the under-specified "follows last user gesture" failure mode.
- **Gate errors only show when persisted is `ACCESSIBILITY`** (rows 2–3 condition now explicitly includes `persistedMode == ACCESSIBILITY`). Belt-and-suspenders: the gate also auto-clears `error` whenever it observes a persisted-mode change to `VIRTUAL_DISPLAY` (handles the rare path where a deep-link or intent flips persisted while a stale gate error is still in memory). One stale field can never override the persisted-mode reality.
- **Row 10's "configured for" is deliberately weaker than "actively running on"** — `effectiveMode == VIRTUAL_DISPLAY` only proves the session *selected* `VirtualDisplayPlatform`; it doesn't prove `platform.start()` succeeded (`AgentSession` can emit "Platform initialization failed" while `effectivePlatformMode` remains `VIRTUAL_DISPLAY`). Until we add a live `platform.isReady` signal, the UI doesn't claim more than it can prove. (Future work: surface platform-start failure as a 13th row sourced from `AgentSession.initializeForFirstTask`'s failure event, then row 10 can upgrade copy to "Agent runs on a virtual display in the background.")

Tone → color uses the same rule as `tool_settings_unify`: Warning = `onSurface` label, never `tertiary` (1.77:1 contrast hard-fail — see [`SettingsWidgets.kt:232–234`](../../../app/src/main/kotlin/ai/closepaw/ui/settings/SettingsWidgets.kt#L232)).

#### Mapper output type

```kotlin
internal data class VirtualDisplayCardState(
    val status: ToolStatusUi,
    val switchChecked: Boolean,
    val switchEnabled: Boolean,
    val rowAction: VirtualDisplayRowAction?,
)

internal sealed interface VirtualDisplayRowAction {
    /** Re-attempt OFF→ON gate. Composable calls `gate.clearError(); gate.setEnabled(true)`. */
    data object RetryEnable : VirtualDisplayRowAction
    /** Request Shizuku permission directly; bypasses the gate. Composable calls
     *  `ShizukuRuntimeGateway().requestPermissionAndAwait()` on a coroutine scope. */
    data object RequestPermission : VirtualDisplayRowAction
}

internal fun virtualDisplayCardState(
    persistedMode: PlatformMode,
    effectiveMode: PlatformMode?,
    shizukuStatus: ShizukuStatus,
    gatePending: Boolean,
    gateError: VirtualDisplayToggleError?,
): VirtualDisplayCardState = /* table lookup */
```

Returns a single struct (not a `Pair`) carrying every input the card needs. Pure JVM, no Compose deps, table-driven and unit-testable row-by-row.

#### Section title in Agent Behavior

Keep `Display Mode`. Codex pushed back on `Display` as a scan-regression — in Settings UIs "Display" often means theme/brightness/screen. `Display Mode` stays unambiguous and matches the existing pageheader users already recognize.

After this refactor `AgentBehaviorSettingsPage` layout becomes:

```
Perception              (1 selector card — vertical radio list)
Display Mode            (1 toggle card — Virtual Display)
Tools                   (2 toggle cards — Termux Shell, Browser Script)
```

Three sibling sections, all driven by `ToolSettingsCard` (Display Mode + Tools) or a comparable surface (Perception). Visually coherent.

### Decision 2 — Perception Mode copy + layout

#### Copy

Same three underlying values (`accessibility_only` / `hybrid` / `screenshot_only` — string IDs unchanged, they're persisted + parsed by `MainActivityIntentPayload` deep links). New UI labels speak in user-outcome terms:

| Value (unchanged) | New label | New subtitle |
|---|---|---|
| `accessibility_only` | **Screen Transcript** | "Reads on-screen text and structure. Fastest, lowest cost, and works for most apps." |
| `screenshot_only` | **Screen Image** | "Looks at screenshots only. Slower and higher cost; use for maps, canvas, games, or apps that hide their structure." |
| `hybrid` | **Transcript + Image** | "Combines both. Most reliable for tricky apps; slowest and highest cost." |

`Recommended` rendered as a small chip on a line below the `Screen Transcript` label (placement nit — see layout sketch). The Transcript subtitle explicitly says "fastest, lowest cost, works for most apps" so the recommendation isn't confusing next to Transcript+Image's "Most reliable" — they're optimizing different axes, and the subtitle makes the tradeoff legible at a glance.

Choices made:
- `Transcript` (noun) not `Transcribe` (verb) — labels read better as nouns.
- Subtitle says *why you'd pick it*, not *how it works internally*. Mechanism words ("accessibility tree", "OCR", "vision LLM") stay out of UI.
- "Screen" prefix keeps both labels parallel and orients the user (these are about how the agent perceives the *screen*, not e.g. audio or text input).

`SettingsHomePage` subtitle chips follow:
- `accessibility_only` → `"Transcript"`
- `screenshot_only` → `"Image"`
- `hybrid` → `"Transcript + Image"`

#### Layout — vertical radio list, not horizontal segmented buttons

Three options, each with a one-line description that doesn't fit in `labelSmall` inside a segmented button. The current widget is unreadable on first encounter. Replace with a vertical radio list (Material3 `RadioButton` + title + subtitle per row), inside the same `Surface` container the section already uses.

```
┌─ Perception ─────────────────────────────────────────┐
│ ⨀  Perception Mode                                    │
│    How the agent sees the screen                      │
│                                                       │
│  ● Screen Transcript                                  │
│    [Recommended]                                      │
│    Reads on-screen text and structure. Fastest,       │
│    lowest cost, and works for most apps.              │
│                                                       │
│  ○ Screen Image                                       │
│    Looks at screenshots only. Slower and higher cost; │
│    use for maps, canvas, games, or apps that hide     │
│    their structure.                                   │
│                                                       │
│  ○ Transcript + Image                                 │
│    Combines both. Most reliable for tricky apps;      │
│    slowest and highest cost.                          │
└───────────────────────────────────────────────────────┘
```

The `Recommended` chip is laid out below the label, not trailing, so narrow screens don't have to choose between truncating the label and breaking the chip across lines. On wider screens the chip can render trailing the label if Compose layout permits — this is a UI nit, not a copy decision.

Why radio list over dropdown:
- Three options + meaningful descriptions: a dropdown hides the tradeoffs behind a tap, which defeats the point of writing the descriptions at all. Users pick this rarely but *carefully*; they need to compare.
- Matches Android system Settings idiom for "pick one behavior" (default browser, SMS app).
- No new Compose component, just `RadioButton` + `Text` rows.

Why not dropdown: ruled out above (hides tradeoffs).
Why not keep segmented buttons + tooltip: tooltips are poor discovery on mobile.

#### Accessibility (the a11y sense)

The radio rows live inside a `Column` with `Modifier.selectableGroup()` — this is the Compose-required marker that tells TalkBack the rows form one radio group (without it, each row is announced as an isolated control). Each row then has `Modifier.selectable(selected = …, onClick = …, role = Role.RadioButton)`, with the entire row as the tap target (not just the radio dot) per Material guidelines. Title is the row's primary text; subtitle is read as the description.

## Components

### New

- `app/src/main/kotlin/ai/closepaw/ui/settings/VirtualDisplayToggleGate.kt` — `VirtualDisplayToggleError` sealed interface (variants: `ShizukuUnavailable`, `ShizukuPermissionDenied`), `.message()`, `gateVirtualDisplayEnable()` suspend helper, `VirtualDisplayToggleGate` class, `rememberVirtualDisplayToggleGate()`. Mirrors `BrowserScriptToggleGate.kt` shape one-for-one — *with the explicit asymmetry that VD's gate does no I/O preflight* (no equivalent of `CommandLineWriter.ensureWritten`). Comment at top of file documents that asymmetry so readers don't expect a write-failure error variant. No shared base class with browser_script — both gates are ~50 lines and the duplication is honest. The stale-error guard lives in `DisplaySection`, not the gate — current `platformMode` plumbing in `SettingsSheet` is a Compose `MutableState`, not a `StateFlow`, so a `LaunchedEffect(persistedMode)` from the composable is the KISS path; pushing flow plumbing through the gate just to call `clearError()` would be more abstraction than this needs.
- `app/src/main/kotlin/ai/closepaw/ui/settings/VirtualDisplayStatusMapper.kt` — pure `virtualDisplayCardState(persistedMode, effectiveMode, shizukuStatus, gatePending, gateError): VirtualDisplayCardState` (struct, not `Pair`). Includes `VirtualDisplayCardState` and `VirtualDisplayRowAction` types (see "Mapper output type" above). Table-driven, no Compose deps, deterministic.
- `app/src/main/kotlin/ai/closepaw/ui/settings/DisplaySection.kt` — replaces `DisplayModeSection.kt`. Hosts one `ToolSettingsCard` driven by the gate + mapper. Holds the `ShizukuClient` instance + `rememberShizukuStatus` plumbing, plus a UI-local `permissionRequestPending: MutableState<Boolean>` that guards against double-taps on the `RequestPermission` row action. **Guard contract**: when `permissionRequestPending == true`, the composable passes `onRowClick = null` (and `onRowClickLabel = null`) to `ToolSettingsCard` — *not* a no-op lambda — so the card renders a non-interactive surface with correct TalkBack semantics (a non-null no-op would give TalkBack a dead button affordance). **Stale-error guard**: `LaunchedEffect(persistedMode) { if (persistedMode == PlatformMode.VIRTUAL_DISPLAY) gate.clearError() }` covers the deep-link race where persisted flips ON via `MainActivityIntentApplier` while a stale gate error is in memory.
- `app/src/test/kotlin/ai/closepaw/ui/settings/VirtualDisplayStatusMapperTest.kt` — every row of the 12-row status table above has a passing assertion (label, subtitle, tone, spinner, switchChecked, switchEnabled, rowAction). Pure JVM.
- `app/src/test/kotlin/ai/closepaw/ui/settings/VirtualDisplayToggleGateTest.kt` — every gate branch exercised with fakes (mirrors `BrowserScriptToggleGateTest`): Shizuku unavailable, permission granted on request, permission denied, RuntimeGateway.Error, setEnabled(false) bypasses gate, double-tap-on during pending is no-op.

### Modified

- `app/src/main/kotlin/ai/closepaw/ui/settings/AgentBehaviorSettingsPage.kt` — gains a `Display Mode` section between `Perception` and `Tools`. New parameters: `platformMode: PlatformMode`, `effectivePlatformMode: PlatformMode?`, `onPlatformModeChange: (PlatformMode) -> Unit`. The page composable just calls `DisplaySection(...)` — Shizuku status hooks live inside that section.
- `app/src/main/kotlin/ai/closepaw/ui/settings/PermissionsAdvancedSettingsPage.kt` — `DisplayModeSection(...)` call deleted; `platformMode` / `effectivePlatformMode` / `onPlatformModeChange` parameters removed from the function signature. Permissions section, Debug section, Data & Storage section all untouched.
- `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsHomePage.kt` —
  - `permissionsSubtitle(...)` drops its `effectivePlatformMode` argument; the ` · VD` / ` · A11y` chip is removed from this row (the Display Mode info now lives on the Agent Behavior row). Subtitle simplifies to e.g. `"All granted · Debug on"`.
  - `agentBehaviorSubtitle(...)` signature gains **both** `platformMode: PlatformMode` (persisted) **and** `effectivePlatformMode: PlatformMode?` — both are required to disambiguate display state. Appends a display chip:
    - `(persisted=ACCESSIBILITY, effective ∈ {null, ACCESSIBILITY})` → no chip (default, no need to advertise)
    - `(persisted=ACCESSIBILITY, effective=VIRTUAL_DISPLAY)` → ` · VD (this session)` — discloses that current session is still on VD
    - `(persisted=VIRTUAL_DISPLAY, effective=VIRTUAL_DISPLAY)` → ` · VD`
    - `(persisted=VIRTUAL_DISPLAY, effective=ACCESSIBILITY)` → ` · VD (next session)` — discloses the pending switch on an active A11y session
    - `(persisted=VIRTUAL_DISPLAY, effective=null)` → ` · VD` — no active session; the qualifier "(next session)" would imply a current-vs-next contrast that doesn't exist
  - Both call sites in this file pass through the new parameter; `SettingsSheet` already has access to persisted `platformMode` so the plumbing is just one extra arg per call.
- `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsSheet.kt` — `platformMode` / `effectivePlatformMode` / `onPlatformModeChange` arguments shift from the `PermissionsAdvancedSettingsPage` call to the `AgentBehaviorSettingsPage` call. Additionally pass `platformMode` (persisted) into `SettingsHomePage` if not already plumbed (verify; the `SettingsSheet` function signature already takes it from `MainActivity`).
- `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsWidgets.kt` — `PerceptionModeSelector` rewritten: vertical radio list with title + subtitle per row + a `Recommended` chip on the first option. Same `Surface` container, same `MaterialTheme.shapes.medium`.
- `app/src/main/kotlin/ai/closepaw/ui/settings/ToolSettingsCard.kt` — **one small additive change**: gains an optional `switchModifier: Modifier = Modifier` parameter (default no-op) applied to the internal `Switch`. Reason: `DisplaySection` needs a stable testTag on the switch (`display-mode-switch`), and the card currently has no plumbing for it. Termux/Browser Script callers continue passing nothing and are unaffected. No other source changes.

### Removed

- `app/src/main/kotlin/ai/closepaw/ui/settings/DisplayModeSection.kt` — replaced by `DisplaySection.kt`. The `ModeOption` / `EffectiveModeRow` / `ShizukuStatusRow` private composables go with it; their semantics are subsumed by `ToolSettingsCard` + `virtualDisplayCardState`.
- The two display-mode `testTag`s `display-mode-accessibility` / `display-mode-virtual-display` are replaced with a single `display-mode-switch` tag on the new toggle's `Switch` (instrumentation tests need *some* stable handle). Any test that asserted on the two radio tags is updated to drive the single switch (see task-7).

### Untouched

- `protocol/PlatformMode.kt`, `app/AppSettingsStore.kt` `KEY_PLATFORM_MODE` + `savePlatformMode`, `platform/PlatformFactory.kt`, `platform/virtualdisplay/ShizukuClient.kt`, `platform/virtualdisplay/ShizukuRuntimeGateway.kt`, `MainActivityIntentPayload.kt` (perception value parsing).
- `RememberShizukuStatus.kt` + `ShizukuStatus.kt` reused as-is.
- `BrowserScriptToggleGate.kt` and its tests.
- All perception runtime: `PerceptionConfig`, `SessionCheckpointCoordinator.kt:102-111` (string-id round-tripping for checkpoints).

## Interactions

### Display toggle — state machine

```
PersistedOff (PlatformMode.ACCESSIBILITY)
  └─ user taps toggle on
       └─ VirtualDisplayToggleGate.setEnabled(true)
            ├─ pending = true, switch disabled, status="Setting up…"
            │   (switch visual stays UNCHECKED — checked mirrors persisted, not gesture)
            ├─ gateVirtualDisplayEnable() runs on Dispatchers.IO
            │    ├─ Shizuku unavailable → error = ShizukuUnavailable, pending = false
            │    │     status card: Needs Setup · "Shizuku is not running..."
            │    │     (switch UNCHECKED — gate refused to flip persisted)
            │    ├─ Shizuku ready, permission ungranted → request inline
            │    │     ├─ Granted → null (success path)
            │    │     ├─ Denied → error = ShizukuPermissionDenied
            │    │     └─ Error → error = ShizukuUnavailable
            │    └─ all green → null
            └─ on null → onPersist(true) → savePlatformMode(VIRTUAL_DISPLAY)
                  → recomposition observes persistedMode == VIRTUAL_DISPLAY
                  → switch CHECKED, status: Ready · "Will start on a virtual display"

PersistedOn (PlatformMode.VIRTUAL_DISPLAY)
  └─ user taps toggle off
       └─ unconditional savePlatformMode(ACCESSIBILITY) — no gate, no error path
            → switch UNCHECKED, status: Disabled · "Agent runs on your current screen."

RetryGrant (persisted = VIRTUAL_DISPLAY, shizuku = NeedsPermission)
  └─ user taps row (rowAction = RequestPermission)
       └─ guarded by local `permissionRequestPending` MutableState in DisplaySection
            ├─ if already pending → row click is null (card surface non-interactive); no second launch
            └─ else → permissionRequestPending = true
                 → scope.launch { ShizukuRuntimeGateway().requestPermissionAndAwait() }
                      ├─ Granted → rememberShizukuStatus recomputes → status becomes Ready,
                      │            no toggle flip, no error storage
                      ├─ Denied  → rememberShizukuStatus stays NeedsPermission → row stays as-is
                      └─ Error   → same as Denied for UI purposes
                 → finally: permissionRequestPending = false
```

Four invariants:
- **`pending` blocks repeat-taps** for OFF→ON gate attempts; the gate ignores a second `setEnabled(true)` while a previous one is in flight.
- **`permissionRequestPending` blocks repeat-taps** for `RequestPermission` row actions (rows 7, 9) by causing `DisplaySection` to pass `onRowClick = null` to `ToolSettingsCard` while pending — non-interactive surface, correct TalkBack semantics, no dead-button affordance. This state lives in `DisplaySection` (not in the mapper or the gate) because it's a UI-local concurrency guard, not a domain-level state.
- **Switch checked state mirrors persisted mode**, not gesture — `switchChecked = (persistedMode == VIRTUAL_DISPLAY)`. Mirrors `BrowserScriptToolRow`.
- **Gate errors are scoped to OFF→ON attempts**: rows 2–3 explicitly require `persistedMode == ACCESSIBILITY`, and `DisplaySection` runs `LaunchedEffect(persistedMode) { if (persistedMode == VIRTUAL_DISPLAY) gate.clearError() }` so any deep-link / intent path that flips persisted to ON automatically wipes stale gate state. One error field never has to mean two things.

### Display card — status mapping

See the full 12-row table in "Status mapper — corrected table" under Approach. The mapper consumes `(persistedMode, effectiveMode, shizukuStatus, gatePending, gateError)` and returns a `VirtualDisplayCardState` struct (status + switchChecked + switchEnabled + optional `VirtualDisplayRowAction`). First-match-wins resolution keeps the test a flat row-by-row walk.

### Perception selector — state machine

Trivial — three radio rows, mutually exclusive, tap-to-select. Same `onModeChange(value)` call as today; only the rendering changes.

Tap → `onPerceptionModeChange(value)` immediately (no gate, no async). Persisted via `AppSettingsStore.savePerceptionMode` — existing path, unchanged.

## Tasks

| Slug | Scope (file globs) | Acceptance | Depends |
|---|---|---|---|
| `vd-toggle-gate` | `app/src/main/kotlin/ai/closepaw/ui/settings/VirtualDisplayToggleGate.kt` (new), `app/src/test/kotlin/ai/closepaw/ui/settings/VirtualDisplayToggleGateTest.kt` (new) | `VirtualDisplayToggleError` (sealed: `ShizukuUnavailable`, `ShizukuPermissionDenied`), `.message()`, `gateVirtualDisplayEnable(...)`, `VirtualDisplayToggleGate` class with `setEnabled` / `clearError` / `pending` / `error`, `rememberVirtualDisplayToggleGate`. Tests cover: Shizuku unavailable → `ShizukuUnavailable` · Shizuku ready, permission granted on request → success · permission denied → `ShizukuPermissionDenied` · `RuntimeGateway.Error` → `ShizukuUnavailable` · setEnabled(false) bypasses gate · double-tap-on during pending is no-op. JVM tests pass under `./gradlew test`. (Stale-error guard lives in `DisplaySection` via `LaunchedEffect(persistedMode)`, not in the gate — keeps gate dispatcher-agnostic and `StateFlow`-free.) | none |
| `vd-status-mapper` | `app/src/main/kotlin/ai/closepaw/ui/settings/VirtualDisplayStatusMapper.kt` (new), `app/src/test/kotlin/ai/closepaw/ui/settings/VirtualDisplayStatusMapperTest.kt` (new) | Pure `virtualDisplayCardState(persistedMode, effectiveMode, shizukuStatus, gatePending, gateError): VirtualDisplayCardState` with companion `VirtualDisplayRowAction` sealed interface (`RetryEnable`, `RequestPermission`). Every one of the 12 rows of the status table above has an assertion: label · subtitle · tone · spinner · switchChecked · switchEnabled · rowAction presence/type. No Compose deps in mapper or test. | `vd-toggle-gate` (for `VirtualDisplayToggleError` type) |
| `display-section` | `app/src/main/kotlin/ai/closepaw/ui/settings/DisplaySection.kt` (new), `app/src/main/kotlin/ai/closepaw/ui/settings/ToolSettingsCard.kt` (add `switchModifier: Modifier = Modifier`), delete `DisplayModeSection.kt` | `DisplaySection()` hosts one `ToolSettingsCard` driven by `virtualDisplayCardState`. `ShizukuClient` instance + `rememberShizukuStatus` reused. Local `permissionRequestPending` MutableState guards the `RequestPermission` row action against double-taps; while pending, the composable passes `onRowClick = null` (not a no-op) so the card surface goes non-interactive. `LaunchedEffect(persistedMode)` clears stale gate error when persisted flips to VIRTUAL_DISPLAY. Card title `"Virtual Display"`. Section title `"Display Mode"`. New `display-mode-switch` testTag on the `Switch` via the new `switchModifier`. Existing Termux/Browser Script callers continue passing nothing into `switchModifier` and are unaffected. Build is green. | `vd-status-mapper` (`tool_settings_unify` is already landed) |
| `move-display-to-agent-behavior` | `app/src/main/kotlin/ai/closepaw/ui/settings/AgentBehaviorSettingsPage.kt`, `app/src/main/kotlin/ai/closepaw/ui/settings/PermissionsAdvancedSettingsPage.kt`, `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsSheet.kt` | `AgentBehaviorSettingsPage` gains `platformMode` / `effectivePlatformMode` / `onPlatformModeChange` parameters and renders `DisplaySection(...)` between `PerceptionModeSelector` and `ToolsSection`. `PermissionsAdvancedSettingsPage` loses those three parameters and the `DisplayModeSection` call. `SettingsSheet` re-routes the three args from one page call to the other; its own signature is unchanged. | `display-section` |
| `homepage-subtitles` | `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsHomePage.kt` | `permissionsSubtitle(...)` drops `effectivePlatformMode`; the ` · VD` / ` · A11y` chip removed. `agentBehaviorSubtitle(...)` signature adds **both** `platformMode: PlatformMode` and `effectivePlatformMode: PlatformMode?`. Display chip rules: `(A11y, ∈{null,A11y})` → nothing · `(A11y, VD)` → ` · VD (this session)` · `(VD, VD)` → ` · VD` · `(VD, A11y)` → ` · VD (next session)` · `(VD, null)` → ` · VD`. Call sites updated. | `move-display-to-agent-behavior` |
| `perception-selector-rewrite` | `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsWidgets.kt` (the `PerceptionModeSelector` function only), `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsHomePage.kt` (chip text) | `PerceptionModeSelector` renders a vertical radio list inside `Column.selectableGroup()` with title + subtitle per row + a `Recommended` chip on `Screen Transcript` (chip below the label to avoid narrow-screen overflow). Labels exactly: `"Screen Transcript"` / `"Screen Image"` / `"Transcript + Image"`. Subtitles exactly as in the Copy table above. Each row uses `Modifier.selectable(role = Role.RadioButton)` with the full row as the tap target. `SettingsHomePage` chip mapping updated (`hybrid` → `"Transcript + Image"`, `screenshot_only` → `"Image"`, default → `"Transcript"`). | none (can run parallel with `vd-toggle-gate`) |
| `fix-stale-tests` | `app/src/androidTest/kotlin/ai/closepaw/qa/*` (any file referencing the deleted display-mode tags or the old perception labels) | No test asserts on `display-mode-accessibility` / `display-mode-virtual-display` testTags; switch to `display-mode-switch`. No test asserts on the old `"Accessibility Only"` / `"Hybrid (A11y + Screenshot)"` / `"Screenshot Only"` strings — replace with the new labels. `grep -rn "display-mode-accessibility\|display-mode-virtual-display\|Accessibility Only\|Hybrid (A11y" app/src/androidTest` returns empty. | `display-section`, `perception-selector-rewrite` |
| `update-docs` | `doc/main/app/settings.md`, `doc/dev/development.md` (any reference to `Permissions & Advanced → Display Mode` or `Perception Mode` copy) | References to `Permissions & Advanced → Display Mode` updated to `Agent Behavior → Display Mode`. Any `Accessibility Only` / `Hybrid` / `Screenshot Only` copy updated to the new labels. `grep -rn "Permissions.*Display\|Accessibility Only\|Hybrid (A11y" doc/` clean. | `move-display-to-agent-behavior`, `perception-selector-rewrite` |
| `verify` | full build + tests | `./gradlew clean assembleDebug lint test` green. `connectedDebugAndroidTest` for any settings-sheet test class green. Manual visual pass: (a) Agent Behavior shows Perception (radio list) · Display Mode (toggle card) · Tools (toggle cards) in that order, all three sections visually coherent; (b) toggling Virtual Display without Shizuku surfaces the inline error and does not flip the switch; (c) toggling on with Shizuku ready persists VIRTUAL_DISPLAY and shows `Ready` / `Ready · Next session` correctly. | all |

### Note on `tool_settings_unify`

`ToolSettingsCard`, `ToolStatusUi`, and `ToolStatusTone` are already in the tree (the `tool_settings_unify` design has landed). `display-section` consumes them as-is — no new types, no local copy, no extra task ordering.

## Trade-offs

- **Toggle (with implicit A11y off-state) vs. symmetric radio with renamed labels (`Main Display` vs `Virtual Display`).** Both fix the Accessibility-leak. Toggle wins because (a) it accurately models "VD is a conditional upgrade", (b) it reuses the `ToolSettingsCard` pattern directly — no new widget — and (c) it kills the awkward `EffectiveModeRow` banner by folding "effective vs persisted" into status subtitle. The cost is users no longer see the word "Accessibility" anywhere in this section; they have to read the off-state subtitle to learn what "off" means. Acceptable — that subtitle is precisely the place that explanation belongs.
- **Off-state subtitle: `"Agent runs on your current screen"` vs `"Agent uses your screen via Accessibility"`.** Codex flagged the latter as still leaking the implementation term. The chosen phrasing avoids `Accessibility` entirely and uses the same plain-language register as the rest of the card. After this change, the `Accessibility Service` row in Permissions is the only place that word appears in the Settings sheet — it can't move (it's the OS-grant name) but it's no longer cross-contaminating the Display Mode UX.
- **Section title `Display Mode` (kept) vs `Display`.** Earlier draft proposed shortening to `Display` to match section neighbors `Tools` and `Perception`. Reverted after Codex pointed out that `Display` in a Settings sheet often connotes theme/brightness/screen-density, which would mis-orient users. The slight asymmetry with neighboring section names is worth the clarity.
- **Move to Agent Behavior vs. keep in Permissions & Advanced.** Display Mode is a behavior choice (where the agent runs), not an OS permission. Permissions & Advanced is becoming the pure OS-toggles + debug + data page after `tool_settings_unify` already removed the Experimental browser_script duplicate. The trend is clear and we follow it. Cost: Shizuku grant entry is now split — A11y service grant in Permissions, Shizuku grant inline with the VD card in Agent Behavior. This split is honest (A11y is an OS-mandatory service, Shizuku is a VD-only enabler), not a bug.
- **Re-poll Shizuku to auto-disable VD toggle on later failures?** No. Browser_script doesn't, and the symmetry matters. The persisted toggle is the *user's preference*; the runtime status reports what's *actually happening*. Auto-disabling would surprise users who fixed Shizuku and expected VD to come back without re-toggling.
- **Three perception options vs. collapse to two (`Transcript` vs `Image`, drop `Hybrid`).** Considered. Rejected because `Hybrid` is genuinely the most reliable in mixed-content apps; it has a real use case that neither pure mode covers. The cost is a third radio row to display, which the vertical list handles fine.
- **Vertical radio list vs. dropdown for perception.** Dropdown hides three carefully-written tradeoff subtitles behind a tap. Defeats the point. Radio list is verbose but informative — settings users tolerate that exchange.
- **Add `Recommended` chip vs. fold into subtitle.** Chip wins. Subtitle is for tradeoffs (what each option *does for you*); recommended-ness is metadata (which option is the *default*). Separating them keeps subtitle reading consistent across all three rows.

## Self-review

- Covers goal: Display Mode no longer leaks `Accessibility` into UI; lives in the page that matches its semantics; reuses the unified tool-card pattern. Perception Mode reads in outcome terms and has room to explain itself. ✓
- Edge cases turned canonical:
  - "User toggles VD on without Shizuku" — same path as `browser_script`: gate refuses, status surfaces the actionable error with a `Learn more` link in the expanded slot (rows 2–3 of the status table), no toggle flip.
  - "User toggles VD on mid-session, current session already started on A11y" — row 11 (`Ready` + "Will switch on next session") makes the pending state explicit; no surprise on next session start.
  - "User toggles VD off mid-VD-session" — row 5 (`Disabled` + "Current session still on a virtual display; next session will use your current screen") makes the asymmetry explicit instead of pretending the change is instant.
  - "Shizuku binder dies mid-VD-session" — row 8 (`Degraded` + "Lost Shizuku connection — the current virtual-display session may stop working"). The runtime does not auto-fallback (VD platform marks itself broken; `effectivePlatformMode` stays `VIRTUAL_DISPLAY`); the status row tells the truth instead of inventing a fallback.
  - "Shizuku permission revoked after VD was enabled" — rows 7 and 9 expose a `RequestPermission` row action; a successful re-grant silently clears the warning via `rememberShizukuStatus` recomputing — no toggle flip, no gate flow, no error storage.
  - "User picks the perception chip that doesn't apply to their device" (e.g. Screen Image on a maps-heavy phone) — recoverable; subtitle tells them when each mode shines.
- Open questions:
  - Should the Permissions section's "Accessibility Service" row get a "(required for Agent baseline)" hint to clarify it's an OS permission, not the display-mode choice? Not in v1 — premature; revisit if users conflate them.
  - Should rows 2–3 vs row 6 share the same `Learn more` link affordance? Today row 6 is silent. v1 keeps it that way (the `Not Available` subtitle is already actionable); add link only if telemetry shows users get stuck on row 6.
