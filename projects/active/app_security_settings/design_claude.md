# App Security Settings — Design

Date: 2026-05-17
Owner: Claude

## Goal

Today, per-app security policy is invisible to the user:

- Bundled tiers (`BLOCKED` / `NORMAL` / `CAUTIOUS`) live in `assets/security/app_tiers.json` — fixed, opaque, can't be inspected from the app UI.
- The persistent allow-list (written by `ApprovalScope.ALWAYS` taps) silently accumulates whichever packages the user approved during prompts — there is no way to see or revoke it.
- The user has no way to pre-set a brand-new app as Always or Reject without first triggering an action that hits a prompt.

We want a Settings sub-page that lists every app installed on the device and lets the user see and set its policy: **Always**, **Ask**, or **Reject** — the same vocabulary the capsule already uses when it asks for approval. The page must respect the BLOCKED safety floor (e.g. banking apps remain BLOCKED regardless of what the user picks).

## Approach

### Collapse "bundled tier" + "persistent allow-list" into one model

The current architecture has two sources of truth that decide per-package behavior:

1. `AppClassifier.classify(pkg)` → bundled tier (BLOCKED / NORMAL / CAUTIOUS).
2. `PolicyEngine.persistentAllowedPackages` → set of "always allow" packages that *bypass* the tier-based decision.

That's two parallel mechanisms for the same question ("how permissive is this app?"). For a UI that shows one row per app with one of three states, that split makes nothing clearer and adds reconciliation logic. Collapse them.

**New model**: per-package user override map `Map<String, AppTier>`, layered on top of bundled tiers. Effective tier:

```
effectiveTier(pkg):
  bundled = bundledTiers[pkg]
  if bundled == BLOCKED: return BLOCKED        // safety floor — un-overridable
  override = userOverrides[pkg]
  return override ?: bundled ?: CAUTIOUS
```

Three knock-on simplifications:

- `PolicyEngine.persistentAllowedPackages` and `isUserAllowed()` go away. "Always allow" = override to NORMAL. The branch `if (currentMode != ALWAYS_ASK && isUserAllowed(...)) Allow` collapses into the existing AppTier dispatch (NORMAL → Allow).
- `AgentSession`'s `ApprovalScope.ALWAYS` writes an override of NORMAL instead of calling `allowPackagePersistent`.
- The session-scoped allow-list stays separate (it's transient, not user-visible, exists to suppress repeat prompts inside one session).

### User → AppTier mapping

Labels match the capsule's approval prompt vocabulary (today the capsule shows `Always / Session / Reject`):

| UI label  | Stored tier  | Capsule analog                                                       |
| --------- | ------------ | -------------------------------------------------------------------- |
| Always    | `NORMAL`     | "Always" (persistent approve) — same word, same semantic              |
| Ask       | `CAUTIOUS`   | default state — leads the next action to trigger the capsule prompt   |
| Reject    | `BLOCKED`    | "Reject" — but **persistent** here, vs. one-prompt in the capsule    |

`Session` from the capsule has no settings-page row: it's by definition transient (cleared on session reset), so there's nothing persistent to show. The capsule keeps that button for one-off approvals; the settings page is strictly the persistent layer.

`BLOCKED` is the only tier with absolute floor semantics: bundled-`BLOCKED` is non-overridable, **enforced at write time** by `AppClassifier.setOverride`. Calls with `tier != BLOCKED` on a bundled-BLOCKED package are rejected (return value indicates refusal) and never persisted, so the UI gets immediate feedback and stale entries can't accumulate in the override map.

### Classifier lifetime

`AppClassifier` is hoisted to **app scope** via a `AppClassifierHolder` singleton, mirroring the existing `AuthStoreHolder` / `ModelCatalogRepositoryHolder` pattern. Today there are **three** construction sites — `AgentSession.create:71`, `AgentSession.reload:150`, and the nullable fallback in `SessionServices.create:133` — all calling `AppClassifier.fromAssets(service.assets)`. With user overrides attached, having three instances means three independent override maps and three independent `StateFlow`s. Unacceptable.

New shape:

- `tool/AppClassifierHolder.kt` — `object` with `fun get(context: Context): AppClassifier`. Lazy single-flight init: loads bundled JSON from assets + initial overrides from `AppSettingsStore`, wires the persistence callback.
- `AgentSession.create` / `AgentSession.reload` — drop local `AppClassifier.fromAssets(...)`. Call `AppClassifierHolder.get(service.applicationContext)` and pass the result to both `PlatformFactory.create(isPackageBlocked = ...)` and `SessionServices.create(appClassifier = ...)`.
- `SessionServices.create` — `appClassifier: AppClassifier` becomes **non-nullable**. Fallback removed.
- `MainActivity` / settings UI — `AppClassifierHolder.get(applicationContext)`. Same instance as the live session and capsule, so the `StateFlow` is genuinely shared.

`MainActivity` is explicitly **not** the owner (process can outlive the activity). The holder is the owner.

### `PolicyEngine.check` ordering (canonical)

The current ordering has subtle invariants that the "Always = NORMAL" framing could quietly break (`PolicyEngine.kt:62-99`). Pin the post-change order explicitly:

```
let currentTier = classifier.classify(packageName)
let destTier    = destinationPackage?.let { classifier.classify(it) }
let effective   = if destTier != null then minOf(currentTier, destTier) else currentTier
let subject     = destinationPackage ?: packageName

1. Non-screen-changing tool          → Allow                     (unchanged)
2. Escape (back/home)                → Allow                     (unchanged)
3. effective == BLOCKED              → Deny                      (covers bundled and user-set BLOCKED on EITHER current or destination — preserves the "stricter wins" rule for open_app)
4. tool == BrowserScript             → browserScriptDecision()    (its own AUTO/SMART/ASK matrix; user NORMAL override does NOT bypass)
5. mode != ALWAYS_ASK && isSessionAllowed(subject) → Allow        (session allow-list — written by capsule's "Session" button via allowPackageForSession; mirrors today's mode != ALWAYS_ASK && isUserAllowed branch)
6. Apply ApprovalMode using effective tier:
     ALWAYS_ASK   → AskUser           (user NORMAL override does NOT bypass — matches existing semantics)
     AUTO_APPROVE → Allow
     SMART        → NORMAL → Allow, CAUTIOUS → AskUser
```

Three invariants that must survive the refactor:

- **Destination strictness for `open_app`.** Step 3 evaluates `min(currentTier, destTier)`, not just `classify(packageName)`. A NORMAL foreground app launching a BLOCKED destination is still BLOCKED. Tests: `open_app` to bundled-BLOCKED dest from NORMAL current → Deny; to CAUTIOUS dest under SMART → AskUser.
- **`ALWAYS_ASK` is absolute** above both the session allow-list (step 5 gated on `mode != ALWAYS_ASK`) and the tier dispatch (step 6). A user-set NORMAL is not a "skip approval" — it's a tier downgrade that `ApprovalMode.SMART` interprets as "auto-approve", while `ALWAYS_ASK` still asks.
- **`browser_script` keeps its own gate** (step 4, before allow-list and tier dispatch). A NORMAL override on Chrome does not silently enable CDP automation.

`PolicyEngineTest` gains: (a) `open_app` destination tests above; (b) user-set NORMAL on `com.android.chrome` under `ALWAYS_ASK` → `AskUser`; (c) same package, `tool=browser_script`, `mode=SMART` → `AskUser` not `Allow`; (d) session-allowed package under `SMART` → `Allow`; under `ALWAYS_ASK` → `AskUser`.

The session allow-list (`sessionAllowedPackages`) and `allowPackageForSession()` stay on `PolicyEngine` as today — they're transient, never persisted, never visible in settings. Only the persistent allow-list collapses into the classifier.

### Live updates without re-launching session

`AppClassifier` becomes mutable: it owns the user override map and is the **single writer**. The map is exposed as `val userOverrides: StateFlow<Map<String, AppTier>>` backed by a private `MutableStateFlow<Map<String, AppTier>>` (immutable maps inside — never expose the backing mutable map). Compose observers use `collectAsState()`; never read `.value` from a composable.

Mutation API — atomic copy-on-write plus serialized persistence:

```
sealed interface SetOverrideResult { object Accepted; object Removed; object RefusedBlocked }

private val mutex = Mutex()

suspend fun setOverride(pkg, tier): SetOverrideResult = mutex.withLock {
  if bundled[pkg] == BLOCKED && tier != BLOCKED:
    return@withLock RefusedBlocked        // hard refusal — neither persisted nor emitted

  val next = _userOverrides.updateAndGet { old ->
    if tier == (bundled[pkg] ?? CAUTIOUS) then old - pkg
    else old + (pkg to tier)
  }
  onUserOverridesChanged(next)            // persist the emitted snapshot, inside the lock
  if next.containsKey(pkg) Accepted else Removed
}
```

Two layers of correctness:

- **`updateAndGet`** handles in-memory CAS for the `StateFlow`. The lambda may re-run on contention, so it must be pure (no side effects).
- **`Mutex.withLock`** serializes the *whole* update-then-persist sequence. Without it, two concurrent writers can interleave: `update → emit {a}` · `update → emit {a,b}` · `persist {a,b}` · `persist {a}` — leaving disk stale relative to memory. The mutex ensures every emission is followed by its own persistence before another emission can start.

Callers from `Dispatchers.Main` (settings UI tap, capsule `ApprovalScope.ALWAYS` after the approval coroutine resumes) launch a coroutine to invoke `setOverride`; the call itself runs on `Dispatchers.IO` because the persistence callback writes `SharedPreferences`.

`PolicyEngine.allowPackagePersistent` is removed; `ApprovalScope.ALWAYS` becomes `scope.launch(Dispatchers.IO) { services.appClassifier.setOverride(pkg, NORMAL) }`.

Test coverage must check the **final persisted map**, not just the final `StateFlow` value:

- Two concurrent `setOverride` calls with different packages → both present in `StateFlow` AND in the persisted snapshot.
- One concurrent `setOverride(pkg=A, NORMAL)` + `setOverride(pkg=A, BLOCKED)` → final on-disk tier equals final `StateFlow` tier (no skew).

**Single-writer rule** at the architectural level: every override change — settings UI tap, capsule `ApprovalScope.ALWAYS`, future deep-links — calls `classifier.setOverride(...)`. The UI never writes `AppSettingsStore` directly. The classifier's persistence callback (inside the mutex) is the only path to disk.

### Settings page UX

A single scrolling list of installed apps:

- **Top bar**: search box (filter by name or package) + segmented chip row (`All` / `Always` / `Ask` / `Reject` / `Custom` / `Restricted`). Default = `All`. `Custom` = "has a user override" (explicit user choice — the answer to *what did I change?*). `Restricted` = "bundled-BLOCKED" (the answer to *what's locked by ClosePaw?*). The four state chips filter by *effective* tier and don't distinguish defaults from explicit picks.
- **Each row**: app icon · display name (1 line, ellipsized) · package (mono, faint, secondary line) · trailing segmented selector for the three states.
- **Locked rows**: bundled-BLOCKED apps render the row in restricted style — full-width `Reject` chip only (no `Always`/`Ask` chips at all) with a small lock badge and inline secondary text "Permanently restricted." No disabled chips, no snackbar plumbing. Simpler and works in Compose without intercepting disabled clicks.
- **Sort**: alphabetical by display name. (User-customized apps don't bubble up — too noisy as the user fiddles. The `Custom` filter chip is the answer to "show me what I changed"; `Restricted` answers "what's locked".)
- **Empty/loading**: skeleton list during PackageManager scan. The scan happens once on first navigate, cached in the page composable's state for the lifetime of the sheet.

### State machine (row interaction)

```
Row state = (pkgInfo, effectiveTier, isLockedBlocked)

Idle ──tap chip(t)─► [isLockedBlocked ∧ t != BLOCKED]: unreachable — chip not rendered
                  └► [else]: classifier.setOverride(pkg, t)
                              │
                              └── if t == bundled[pkg] ?? CAUTIOUS: classifier removes entry
                                   (single side effect; persistence is the classifier's callback)
```

The locked branch is unreachable because the row doesn't render `Always`/`Ask` chips for bundled-BLOCKED apps — the impossible-by-construction approach is simpler than runtime guarding.

## Components

### New

- `app/src/main/kotlin/ai/closepaw/tool/AppClassifierHolder.kt` — app-scope singleton, mirrors `AuthStoreHolder` / `ModelCatalogRepositoryHolder`. Single-flight lazy init.
- `app/src/main/kotlin/ai/closepaw/ui/settings/AppSecuritySettingsPage.kt` — the page composable, filter/search state, row composable.
- `app/src/main/kotlin/ai/closepaw/ui/settings/AppRow.kt` — UI-only row model + on-demand icon loader (keeps `Drawable` out of `platform/AppInfo`).
- `app/src/test/kotlin/ai/closepaw/tool/AppClassifierOverrideTest.kt` — unit coverage for layered resolution + write-time BLOCKED refusal + remove-on-match + concurrent `setOverride` (no lost writes) + `StateFlow` emission.

### Changed

- `protocol/AppTier.kt` — unchanged values, but doc updated to reflect that `NORMAL`/`CAUTIOUS` are now user-settable.
- `platform/AppManager.kt` — **do not change `AppInfo`**. Shared platform model stays UI-free (no `Drawable`). Add a UI-only sibling in the settings package: `ui/settings/AppRow.kt` exposes `data class AppRow(val info: AppInfo, val iconLoader: suspend () -> ImageBitmap?)` and a helper `loadInstalledAppRows(context): List<AppRow>` that calls `AppManager.getInstalledApps(...)` and attaches a per-row on-demand icon loader (called from a `LazyColumn` `produceState` so a 200-app list doesn't pin 200 drawables in memory). **Reuse, do not duplicate** — no new `InstalledAppLoader.kt`, no `Drawable` in `AppInfo`.
- `tool/AppClassifier.kt` — accepts `initialUserOverrides: Map<String, AppTier>` and `onUserOverridesChanged: (Map<String, AppTier>) -> Unit`. Adds `setOverride(pkg, tier): SetOverrideResult` (returns `Accepted` / `Removed` / `RefusedBlocked`), `bundledTier(pkg)`, and `userOverrides: StateFlow<Map<String, AppTier>>` for Compose observability. `classify()` applies the layered rule with bundled-BLOCKED as the floor.
- `tool/PolicyEngine.kt` — drops `persistentAllowedPackages`, `allowPackagePersistent`, `isUserAllowed`, `onPersistentAllowListChanged`, `initialPersistentAllowList`. The user-allow-list branch in `check()` collapses; allow now happens via the tier mapping. Session allow-list stays.
- `app/MainActivity.kt` — (1) replace local `AppClassifier.fromAssets(...)` construction in `AgentSession.create/reload` paths with `AppClassifierHolder.get(applicationContext)` (same instance threaded into `PlatformFactory` and `SessionServices`). (2) update `hasLegacyUsageEvidence()` (line ~932) to check `loadUserAppOverrides().isNotEmpty()` instead of the removed `loadPersistentAllowList()`. (3) plumb the holder-provided classifier into `SettingsSheet`.
- `app/MainActivityContent.kt` — forward the classifier reference to `SettingsSheet`.
- `session/AgentSession.kt` — `AgentSession.create:71` and `AgentSession.reload:150` use `AppClassifierHolder.get(...)` instead of local construction. `ApprovalScope.ALWAYS` calls `services.appClassifier.setOverride(op.packageName, AppTier.NORMAL)` instead of `policyEngine.allowPackagePersistent(...)`.
- `session/SessionToolingBootstrapper.kt` + `session/SessionServices.kt` — `appClassifier: AppClassifier` becomes non-nullable. Drop the persistent-allow-list parameters and callbacks; classifier owns persistence via the holder.
- `app/AppSettingsStore.kt` — `KEY_USER_ALLOWED_PACKAGES` removed. New `KEY_USER_APP_OVERRIDES` storing a JSON object `{pkg: tier_string}`. `loadUserAppOverrides(): Map<String, AppTier>` + `saveUserAppOverrides(map)`. Unknown tier strings are dropped, not crashed. No migration code — pre-release; existing dev installs lose their allow-list entries and that's fine.
- `ui/settings/SettingsSheet.kt` — adds `APP_SECURITY` to `SettingsPage` enum and a branch that renders `AppSecuritySettingsPage`. New parameter: `appClassifier: AppClassifier`.
- `ui/settings/SettingsHomePage.kt` — new row under a "Security" section: "App Security", subtitle `"N apps customized · M restricted"`. The composable observes `classifier.userOverrides.collectAsState()` so the count updates live; `M` is read once from `bundledTier` counts (constant for the app run).

### Removed

- `AppSettingsStore.loadPersistentAllowList` / `savePersistentAllowList` / `KEY_USER_ALLOWED_PACKAGES`.
- `PolicyEngine.persistentAllowedPackages` and related methods.

## Interactions

```
User taps "Always" on Spotify in the page
  │
  ▼  classifier.setOverride("com.spotify.music", NORMAL)
  │
AppClassifier
  ├─► overrides StateFlow emits → page recomposes (chip selected)
  └─► onUserOverridesChanged → AppSettingsStore.saveUserAppOverrides(map)
       (single writer; UI never touches the store directly)

next tool call targeting com.spotify.music
  ▼
PolicyEngine.check → classifier.classify("com.spotify.music")
  → bundled = NORMAL, override = NORMAL → returns NORMAL → Allow
```

For `ApprovalScope.ALWAYS` from a runtime approval prompt, the *exact same* `classifier.setOverride(pkg, NORMAL)` call is invoked from `AgentSession`. If the settings page is open, it recomposes via the same `StateFlow`. No second code path, no reconciliation.

## Tasks

| Slug                              | Scope (file globs)                                                                                | Acceptance                                                                                                                  | Depends |
| --------------------------------- | ------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ------- |
| `settings-store-overrides`        | `app/AppSettingsStore.kt`, related tests                                                          | New `loadUserAppOverrides` / `saveUserAppOverrides` (JSON-encoded). Old allow-list key + methods removed. Unknown tier strings dropped, not crashed. | —       |
| `classifier-overrides`            | `tool/AppClassifier.kt`, `tool/AppClassifierHolder.kt` (new), `tool/PolicyEngine.kt`, `session/SessionToolingBootstrapper.kt`, `session/SessionServices.kt`, `session/AgentSession.kt`, `app/MainActivity.kt` (`hasLegacyUsageEvidence` + classifier construction sites) | `AppClassifier` is the single writer, exposes `userOverrides: StateFlow<Map<String, AppTier>>`, refuses NORMAL/CAUTIOUS overrides on bundled-BLOCKED at write time, auto-removes entries that match the bundled default, uses `updateAndGet` for atomic in-memory updates *and* a `Mutex` around the full update+persist sequence so on-disk state never diverges from `StateFlow`. `PolicyEngine.check` ordering follows the 6-step canonical sequence above, preserving destination-strictness (`minOf(currentTier, destTier)`), the session allow-list (`allowPackageForSession` stays on PolicyEngine), the `ALWAYS_ASK` invariant, and the `browser_script` gate. Tests cover: bundled-BLOCKED write-time refusal · remove-on-match · concurrent setOverride preserves final persisted snapshot (different + same package) · `open_app` to bundled-BLOCKED/CAUTIOUS destinations · session-allowed package gated by `ALWAYS_ASK` · `browser_script` ignores NORMAL override. Classifier app-scoped via `AppClassifierHolder`; all three previous construction sites collapsed. `hasLegacyUsageEvidence()` no longer references `loadPersistentAllowList`. Build is green. | `settings-store-overrides` |
| `app-list-with-icons`             | `ui/settings/AppRow.kt` (new), test                                                               | Reuses `AppManager.getInstalledApps(...)`. Provides `AppRow` + per-row on-demand icon loader (UI package only). `platform/AppInfo` unchanged. | —       |
| `app-security-page`               | `ui/settings/AppSecuritySettingsPage.kt`, `ui/settings/SettingsSheet.kt`, `ui/settings/SettingsHomePage.kt` | Renders the list via `AppRow`; search + six filter chips (`All`/`Always`/`Ask`/`Reject`/`Custom`/`Restricted`) work; row segmented selector calls `classifier.setOverride` directly. Bundled-BLOCKED rows render Reject-only with a lock badge (no disabled chips). Home page row observes `classifier.userOverrides.collectAsState()`. | `classifier-overrides`, `app-list-with-icons` |
| `wire-mainactivity`               | `app/MainActivity.kt`, `app/MainActivityContent.kt`                                               | `SettingsSheet` constructed with the holder-provided classifier. Capsule and UI observe the same `StateFlow`. Deep-link enum not extended (out of scope). | `app-security-page`     |
| `verify`                          | (test/lint/build only)                                                                            | `./gradlew test lint assembleDebug` clean.                                                                                  | all     |

Feed this Tasks section to `/update-tasks` to materialize the graph.

## Trade-offs

- **Collapsing allow-list into overrides** vs. keeping both. Keeping both means two writers, two readers, and a UI that has to display the union. The collapsed model has one map and one resolution rule; the only loss is conceptual ("user allow-list" was a familiar phrase). Net win.
- **User-overridable BLOCKED?** Considered letting the user un-block bundled-BLOCKED apps. Rejected: the bundled list exists precisely because the user can't always be trusted to evaluate a banking-app prompt (social engineering, malware accessibility-impersonation). Keeping BLOCKED as a floor preserves the safety property `AppClassifier.maskIfBlocked` was built to provide.
- **List all apps vs. user-visible apps only**. Filtering by "has launcher intent" drops ~30–40% of packages on a typical phone (system services, providers, plugins). Those packages aren't ones a user can identify or set policy for meaningfully. If we miss a legitimate launcher-less app, we can revisit, but the default should optimize for a usable list.
- **No bubbling of user-customized rows to the top**. Considered — rejected because it makes the list jump as the user taps chips. The `Custom` filter chip is the answer to "show me what I changed."
- **No deep-link entry** (`SettingsPage.APP_SECURITY` in the `chat.SettingsDeepLink` enum). The deep-link enum today is `HOME, LLM_AUTH`; nothing in product flow needs to jump directly to App Security yet. Skipping until we have a concrete entry point keeps the surface area small.
- **`StateFlow` on the classifier vs. observing via the settings store.** The classifier is already the read path for `PolicyEngine`, so attaching observability there means UI, capsule, and policy all share one source. Putting the flow on `AppSettingsStore` would require the page to translate raw map data through the bundled-tier floor, duplicating logic that already lives in the classifier.

## Self-review

- Covers goal: every installed app is listable; all three states (`Always` / `Ask` / `Reject`) are settable and reversible; labels match the capsule's approval prompt; BLOCKED floor is preserved. ✓
- Edge cases turned canonical:
  - "Already approved-via-prompt apps" — same code path as the page (`setOverride`), no special case.
  - "User picks the chip that matches bundled tier" — automatically clears the override row, keeping the persisted map minimal.
  - "Bundled BLOCKED app" — single rule (`isLockedBlocked`), no special-case scattered through PolicyEngine.
- Open question: do we need an explicit "reset all overrides" button? Not for v1 — three taps per app is fine for the realistic case (handful of customizations). Add only if usage shows otherwise.
