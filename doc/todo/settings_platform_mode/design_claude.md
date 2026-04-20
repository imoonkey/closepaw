# Settings: Platform Mode + Shizuku Status

## Goal

Surface two existing-but-hidden capabilities in the Settings UI:

1. **Platform Mode** (`PlatformMode.ACCESSIBILITY` ↔ `PlatformMode.VIRTUAL_DISPLAY`) — already persisted, already wired through `PlatformFactory`, currently only changeable via `MainActivityIntentApplier`. End users have no way to switch.
2. **Shizuku status indicator** — read-only signal so users understand why Virtual Display is/isn't available, without forcing them through a failed session to find out.

Explicit non-goal: an "App tier security" global override toggle. `assets/security/app_tiers.json` is a static safety guardrail (BLOCKED/CAUTIOUS/NORMAL). It must not be user-mutable; documenting that decision here so it isn't relitigated.

## Approach

**One section, two rows. One new shared signal: `effectivePlatformMode`.**

Add a new **"Display Mode"** section to `PermissionsAdvancedSettingsPage` (between *Permissions* and *Debug*). It contains:

- A 2-option selector for Platform Mode (Accessibility / Virtual Display).
- A Shizuku status row that doubles as the affordance for the Virtual Display option's enabled state. When Shizuku is not ready, the Virtual Display option is disabled and the status row tells the user exactly what's missing + how to fix it.

Why one section, not two: the user only cares about Shizuku because it gates Virtual Display. Coupling them removes a "why is this disabled?" dead end and turns the Shizuku state into the canonical explanation rather than a free-floating fact.

**Persistence.** No new SharedPreferences keys. `platformMode` already lives in `AppSettingsStore` under `agent_prefs` (`AppSettingsStore.kt:23-52`); we keep using `AppSettingsState.updatePlatformMode`.

### Persisted intent vs. effective mode

The persisted `platformMode` is **intent**: what the user wants the next session to start as. The runtime may diverge (`PlatformFactory` falls back to `AccessibilityPlatform` when Shizuku is gone; `VirtualDisplayPlatform` does **not** hot-swap on binder death — it just marks itself broken, see `VirtualDisplayPlatform.kt:168-176`).

To tell the truth in the UI, we add a first-class **effective-mode signal**:

- `AgentService` exposes `val effectivePlatformMode: StateFlow<PlatformMode?>` (null = no platform instantiated).
- It is set in `observeExternalSession()` (`AgentService.kt:89-101`) from the actually-constructed `AndroidPlatform`, not from `SessionConfig.platformMode`. The construction site at `MainActivity.kt:578` already knows which class `PlatformFactory` returned; it threads that through into the service.
- `MainActivityContent` collects this StateFlow and passes the value down into `SettingsSheet` and the Home subtitle.

Selector and subtitle both render from `effectivePlatformMode` when present, falling back to persisted intent only when no session has ever been started. The selector therefore answers **"what mode will the next session use, given current Shizuku state"** — equivalently, intent ∧ availability. The subtitle answers **"what mode is the active session actually using"** when one exists.

Binder-dead during a live VD session does **not** retro-snap the selector or kill the in-flight session: the existing `VirtualDisplayPlatform` marks itself broken, the selector reflects that on the next session's evaluation. We do not lie about live mode.

**Reactivity.** Shizuku availability changes happen *outside* our app. Use `LifecycleEventObserver(ON_RESUME)` to re-read status when Settings comes back to foreground. Also register a Shizuku permission-result listener and a binder-dead listener (both via `ShizukuClient` wrappers — see Components) so in-app permission grants and runtime drops update the row instantly. No polling, no binder calls during composition.

**Permission request.** Shizuku permission *can* be requested in-app via `ShizukuClient.requestPermission(requestCode)` once the binder is alive. Status row CTA is state-driven:

| Shizuku state | Row content | CTA |
|---|---|---|
| Binder dead (not installed / not running) | "Shizuku not running" | "Learn more" → opens shizuku.rikka.app help URL |
| Binder alive, permission denied | "Shizuku running, permission needed" | "Grant" → calls `ShizukuClient.requestPermission()` |
| Binder alive, permission granted | "Shizuku ready" + check icon | (none) |

Virtual Display option is enabled iff status == `Ready`.

## State machine

```
Selector tap "Virtual Display":
    shizukuStatus == Ready ?
       yes → AppSettingsState.updatePlatformMode(VIRTUAL_DISPLAY)
       no  → no-op (option disabled; status row explains why)

Status producer (rememberShizukuStatus):
    init                          → read ShizukuClient.isAvailable + hasPermission
    ON_RESUME                     → re-read
    permission-result listener    → re-read
    binder-dead listener          → re-read
    DisposableEffect onDispose    → unregister both listeners
```

Selector visual: bound to `effectivePlatformMode ?: persistedPlatformMode`. Persisted intent only changes on user tap.

## Components

**Modify**

- `app/AgentService.kt` — add `private val _effectivePlatformMode = MutableStateFlow<PlatformMode?>(null)` and `val effectivePlatformMode: StateFlow<PlatformMode?>`. Set it in `observeExternalSession()` from the resolved platform.
- `app/MainActivity.kt:578` — after `PlatformFactory.create(...)`, pass the resolved platform's mode (`AndroidPlatform.mode`) into `AgentService.observeExternalSession()` instead of re-passing `SessionConfig.platformMode`.
- `app/MainActivityContent.kt` — collect `agentService.effectivePlatformMode` as state; pass into `SettingsSheet` and into the call sites that compute the Home subtitle.
- `ui/settings/SettingsSheet.kt` — accept `effectivePlatformMode: PlatformMode?` and pass through to the page. Do **not** plumb a Shizuku flow or request lambda from the Activity — UI owns the Shizuku observer.
- `ui/settings/PermissionsAdvancedSettingsPage.kt` — accept `platformMode`, `effectivePlatformMode`, `onPlatformModeChange`. Insert `DisplayModeSection(...)` between Permissions and Debug.
- `ui/settings/SettingsHomePage.kt` — extend `permissionsSubtitle(...)` to append `" · VD"` or `" · A11y"` **only when `effectivePlatformMode != null`**. If null (no session yet), omit mode from the subtitle.
- `platform/virtualdisplay/ShizukuClient.kt` and `ShizukuRuntimeGateway.kt` — add `addRequestPermissionResultListener` / `removeRequestPermissionResultListener` wrappers. UI must never call `Shizuku.*` directly.

**Add**

- `ui/settings/DisplayModeSection.kt` — new file (section header, segmented selector, Shizuku status row). Self-contained, ~120 LOC. Uses `rememberShizukuStatus()`.
- `ui/settings/ShizukuStatus.kt` — sealed class `Unavailable | NeedsPermission | Ready`. Lives in the settings UI package; it's a UI-layer projection of two `ShizukuClient` booleans.
- `ui/settings/RememberShizukuStatus.kt` — `produceState`-based composable `rememberShizukuStatus(client: ShizukuClient): State<ShizukuStatus>`. Reads only on init, `ON_RESUME`, and listener callbacks. `DisposableEffect` registers permission-result + binder-dead listeners on enter and unregisters both on dispose. **No reads in the composable body.**

**Remove**

- Nothing. Intent path in `MainActivityIntentApplier.kt:74-77` keeps working unchanged (writes the same `updatePlatformMode`).

**Explicitly NOT added**

- No app-tier override UI, no SharedPreferences key, no policy injection point. The static JSON is the contract.

## Interactions

```
AgentService.effectivePlatformMode (StateFlow<PlatformMode?>)
        │
        ▼
MainActivityContent  ──► SettingsSheet ──► PermissionsAdvancedSettingsPage
        │                                          │
        │                                          ├── DisplayModeSection
        │                                          │      ├─ rememberShizukuStatus() ── ShizukuClient
        │                                          │      └─ onPlatformModeChange ──► AppSettingsState
        │                                          │                                       │
        │                                          │                                       ▼
        │                                          │                                AppSettingsStore (SharedPreferences)
        │                                          └── (existing rows)
        │
        └─► SettingsHomePage subtitle (mode chip iff effective != null)

next session start ──► PlatformFactory.create(persistedIntent)
                              │
                              ├─ resolves to AccessibilityPlatform or VirtualDisplayPlatform
                              └─► AgentService.observeExternalSession(platform.mode)
                                          │
                                          ▼
                                  _effectivePlatformMode.value = platform.mode
```

## Tasks

1. **slug:** `shizuku-client-permission-listener`
   - **scope:** `platform/virtualdisplay/ShizukuClient.kt`, `ShizukuRuntimeGateway.kt`
   - **acceptance:** `addRequestPermissionResultListener(listener)` and `removeRequestPermissionResultListener(listener)` exist and proxy to `Shizuku.*`. No new direct `Shizuku.*` call sites in UI code after subsequent tasks.
   - **deps:** none

2. **slug:** `effective-platform-mode-signal`
   - **scope:** `app/AgentService.kt`, `app/MainActivity.kt` (~line 578), `app/MainActivityContent.kt`
   - **acceptance:** `AgentService.effectivePlatformMode: StateFlow<PlatformMode?>` is set from the constructed `AndroidPlatform.mode` on session start; collected into Compose state in `MainActivityContent`; null until first session.
   - **deps:** none

3. **slug:** `shizuku-status-observer`
   - **scope:** `ui/settings/ShizukuStatus.kt`, `ui/settings/RememberShizukuStatus.kt`
   - **acceptance:** `produceState` reads only on init, `ON_RESUME`, permission-result, and binder-dead. Both listeners registered/unregistered via `ShizukuClient` wrappers in a single `DisposableEffect`. No `Shizuku.*` imports.
   - **deps:** `shizuku-client-permission-listener`

4. **slug:** `display-mode-section`
   - **scope:** `ui/settings/DisplayModeSection.kt`
   - **acceptance:** Renders 2-option selector + status row. Selector visual is `effectivePlatformMode ?: persistedPlatformMode`. Virtual Display option disabled iff status != `Ready`. "Grant" calls `ShizukuClient.requestPermission`; "Learn more" opens external URL via `Intent.ACTION_VIEW`.
   - **deps:** `shizuku-status-observer`

5. **slug:** `wire-permissions-page`
   - **scope:** `ui/settings/PermissionsAdvancedSettingsPage.kt`, `ui/settings/SettingsSheet.kt`, `ui/settings/SettingsHomePage.kt`
   - **acceptance:** Section renders between Permissions and Debug; Home subtitle shows mode chip iff `effectivePlatformMode != null`; intent-driven changes (`MainActivityIntentApplier`) still work end-to-end. No Shizuku flow plumbed from the Activity.
   - **deps:** `display-mode-section`, `effective-platform-mode-signal`

6. **slug:** `qa-coverage`
   - **scope:** `app/src/androidTest/kotlin/ai/closepaw/qa/QaSettingsHelpers.kt` + new test
   - **acceptance:** Asserts (a) toggling selector calls `updatePlatformMode`; (b) Virtual Display option disabled when status is `Unavailable`; (c) Home subtitle reflects `effectivePlatformMode` and is omitted when null.
   - **deps:** `wire-permissions-page`

## Trade-offs

- **Effective-mode source vs. render-intent-everywhere.** Chosen: effective-mode source. Settings + Home would otherwise lie when `PlatformFactory` falls back. Cost is one StateFlow plus a constructor-time write — small, and the UI already collects flows from `AgentService`.
- **Selector semantics: "next session" vs. "live session".** Chosen: next-session availability. Live VD doesn't hot-swap on binder death (`VirtualDisplayPlatform.kt:168-176`); claiming the selector reflects live mode would be false. The Home subtitle covers the live case via `effectivePlatformMode`.
- **Coupling Shizuku status with the mode selector vs. a separate "Permissions" row.** Chosen: coupled. A bare status row in *Permissions* would force users to mentally connect "Shizuku not granted" → "that's why VD doesn't work".
- **Status owner.** Chosen: settings-UI-local. The Activity does not pass a flow or lambda; only `onPlatformModeChange` bubbles up. One owner, no split state.
- **Persisting "effective" mode vs. "intended" mode on Shizuku loss.** Chosen: persist intended. Re-installing Shizuku auto-restores VD without re-toggling.
- **App-tier global toggle.** Rejected. Security policy that the user can disable isn't policy.
