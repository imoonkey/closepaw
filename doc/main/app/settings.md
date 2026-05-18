# Settings & Configuration

> User settings, preferences, and configuration persistence.
> Last updated: 2026-05-17 (Memory page + App Access inline expansion + per-skill Tools toggle + IA reshuffle)

## Overview

The app manages user preferences through `AppSettingsState` + `AppSettingsStore` (plain `SharedPreferences`). **Credentials live in a separate `AuthStore`** (`EncryptedSharedPreferences`, app-scoped singleton via `AuthStoreHolder`) keyed by flat `LLMProvider` — not in `AppSettingsState`. If `EncryptedSharedPreferences` init throws (e.g. broken Keystore), the exception bubbles up — no silent in-memory fallback. JVM tests inject a fake via `AuthStore`'s `prefsProvider` constructor parameter.

> See: [infra/llm.md](../infra/llm.md) for the flat `LLMProvider` enum and `AuthStore` integration with the factory.

---

## Settings Categories

### LLM Backend

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `llmBackend` | `LLMBackendType` | `OPENAI` | `OPENAI` (Cloud) or `LOCAL` (On-device) |
| `selectedModel` | `String` | `"glm-5"` | Main agent model, resolved via `ModelCatalog` (carries flat `LLMProvider`) |
| `subagentModel` | `String?` | `null` | Delegated subagent model (falls back to main; canonicalized to selectedModel.provider on commit) |
| `localModel` | `LocalModelOption` | `AVAILABLE_LOCAL_MODELS.first()` | Local model selection (id-only persistence; rehydrated via catalog lookup) |
| `openaiBaseUrl` | `String` | `""` | Base URL override for OpenAI provider (set via `openai_base_url` intent extra; persisted in SharedPreferences so it survives process restarts) |
| `otherBaseUrl` | `String` | `""` | Base URL for the user-configured OTHER provider. Set via Settings → LLM & Authentication → API Key → Other tab, or via `other_base_url` intent extra. Persisted; mutations trigger `ModelCatalogRepository.invalidate()` so the synthesized `other-custom` catalog entry refreshes. |
| `otherModelId` | `String` | `""` | Model id sent to the OTHER provider (e.g. `vendor/model-name`). Same persistence + invalidation contract as `otherBaseUrl`; both must be non-blank for the `other-custom` synth entry to be present in `ModelCatalog`. |

**Credentials are NOT in `AppSettingsState`.** They live in `AuthStore`, keyed by flat `LLMProvider` (`OPENAI_API`, `OPENAI_CODEX`, `OPENROUTER`, `OTHER`). The selected model encodes the provider, which determines exactly which credential is loaded and which client class runs — no fallback chains, no `__AUTH_METHOD` signal keys.

### Execution

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `debugMode` | `Boolean` | `false` | Verbose logging + debug artifacts |
| `browserScriptEnabled` | `Boolean` | `false` | Enables `browser_script` execution gate |
| `termuxShellEnabled` | `Boolean` | `true` | Allows `termux_shell` exposure when Termux is installed and bridge-ready |

There is no Max Turns setting. Production runs are bounded by context-window
auto-compaction (see [agent/loop.md](../agent/loop.md#auto-compaction)), not by a
turn count. The eval bridge has its own `eval_turn_budget` safety net wired
through `SessionConfig.evalTurnBudget` (`null` in production).

### Platform

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `platformMode` | `PlatformMode` | `ACCESSIBILITY` | `ACCESSIBILITY` (standard) or `VIRTUAL_DISPLAY` (Shizuku-based) |

> See: [infra/platform.md](../infra/platform.md) for `VirtualDisplayPlatform` and `PlatformFactory` details.

### Perception

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `perceptionMode` | `String` | `"accessibility_only"` | One of: `accessibility_only`, `screenshot_only`, `hybrid` |

> See: [infra/platform.md](../infra/platform.md) for `PerceptionConfig` variants and capture behavior.

---

## SessionConfig Compilation

> See: `protocol/SessionConfig.kt`

Settings are compiled into `SessionConfig` when creating a session:

```kotlin
data class SessionConfig(
    val actionDelayMs: Long = 2000,
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    val llm: SessionLlmConfig = SessionLlmConfig(),
    val mainModel: String = "glm-5",
    val subagentModel: String? = null,
    val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT,
    val platformMode: PlatformMode = PlatformMode.ACCESSIBILITY,
    val evalTurnBudget: Int? = null,   // eval-only safety net
    // ...
)
```

- `perceptionConfig` is built from `perceptionMode` string: `accessibility_only` → `AccessibilityOnly`, `screenshot_only` → `ScreenshotOnly`, `hybrid` → `Hybrid`
- `subagentModel` falls back to `mainModel` when null
- `platformMode` selects `AndroidPlatform` implementation via `PlatformFactory`

---

## Settings UI

> See: `ui/settings/SettingsSheet.kt`

The settings UI is a full-screen page overlay with sub-page navigation (system back: sub-page → HOME, HOME → dismiss). The HOME page groups entries into three sections:

| Section | Entry | Destination |
|---------|-------|-------------|
| **Behavior** | LLM & Authentication | `LlmAuthSettingsPage` — provider → model hierarchy, Sign In / API Key tabs (Local tab gated by `LOCAL_TAB_ENABLED`) |
| | Agent Behavior | `AgentBehaviorSettingsPage` — Perception, Display Mode (Virtual Display toggle, Shizuku-gated), Tools (incl. per-skill toggles), Termux Shell |
| | Memory | `MemorySettingsPage` — User Memory + Device Memory editors (per-app memory lives under App Access) |
| **Access** | Permissions & Advanced | Accessibility / Overlay status, platform mode, debug, trace, version |
| | App Access | `AppAccessSettingsPage` — per-app classification (Allow/Ask/Reject) + inline skill viewer + bounded memory editor |
| **About** | Open Source Licenses | Runtime deps + licenses (generated by `com.jaredsburrows.license` into `assets/open_source_licenses.json`, rendered by `OpenSourceLicensesPage`; Leap SDK shows as proprietary "Leap Terms of Use" — see `NOTICE`) |

The standalone Voice header was dropped in the IA reshuffle. `LLM & Authentication` now lives under Behavior alongside Agent Behavior; voice-related controls move into the relevant per-feature page when they apply.

### Memory Page

> See: `ui/settings/MemorySettingsPage.kt`, `ui/settings/MemoryFileEditor.kt`, `ui/settings/MemoryFileEditorPage.kt`

The Memory page lists two navigation rows — **User Memory** and **Device Memory** — that push a full-screen `MemoryFileEditorPage`. Per-app memory is not listed here; the user reaches it from App Access (an installed app implies an editor location, and Memory does not list packages with no override).

`MemoryFileEditor` is the reusable composable behind both the standalone page and the App Access expansion. Two variants:

- **unbounded** (`bounded = false`) — fills the page. Used by `MemoryFileEditorPage` and the standalone Memory page rows.
- **bounded** (`bounded = true`) — height capped at 240.dp with internal scroll. Used inside the App Access inline expansion. Renders an `↗ Open` affordance (disabled while the buffer is dirty to avoid a double-buffer race with the full-page editor).

Both variants observe `MemoryEditGate.memoryEditLocked` (see [memory.md → MemoryEditGate](../agent/memory.md#single-writer-model-memoryeditgate)). When locked: Save / Discard / Delete disable, a banner reads *"Session is open. Stop the session to edit memory."*, and the typed buffer is preserved (the user is not popped out of EDIT mode). Every save / delete handler re-checks `gate.memoryEditLocked.value` inside the coroutine immediately before calling `MemoryStore.write` / `delete` to close the click-to-IO TOCTOU window; if it lost the race, the write aborts with a toast and the file on disk is untouched.

### App Access — inline expansion

> See: `ui/settings/AppAccessSettingsPage.kt`, `ui/settings/AppRowExpansion.kt`, `ui/settings/AppAccessContentIndex.kt`

Each App Access row collapses by default to the package's display name + tier selector + content chips. Chips show **Skill** when the package has a bundled `app_skills/<pkg>/SKILL.md`, **Memory** when `apps/<pkg>.md` exists, and a **+ Memory** affordance when neither applies (gate-aware: disabled and inert while `memoryEditLocked` is true).

Tapping a row toggles `AppRowExpansion`:

- **Read-only App Skill viewer** when the package ships a bundled skill — bounded scroll, body loaded lazily on `Dispatchers.IO` via `AssetAppSkillRepository`.
- **Bounded `MemoryFileEditor`** for `apps/<pkg>.md` when either a memory file already exists or the user just created one via `+ Memory`. The editor sets `scope = MemoryScope.APP, packageName = pkg`.
- **Blocked-app warning chip** above the editor when the package is in the `BLOCKED` tier. The inline editor is intentionally **not** disabled — a Settings edit is the user's explicit consent (the agent-side write gate exists to require this consent, not to be redundant with it). The warning makes the consequence explicit: *"Reject only blocks the agent from writing this memory; saved entries are still recalled when this app is foreground."*

The `+ Memory` chip routes through a coroutine that:

1. Re-checks `gate.memoryEditLocked.value` (UI-level disabling can race the lock flipping mid-recomposition; the launch-time re-check is the source of truth).
2. Re-reads via `memoryStore.read(MemoryScope.APP, pkg)`. If the file already exists (stale index, or a double-tap raced), it skips the write so an existing `apps/<pkg>.md` is **never blanked**.
3. Otherwise calls `memoryStore.write(MemoryScope.APP, pkg, "")` and inspects the `SaveResult`. On `Success`, it updates the page-scoped `AppAccessContentIndex` (no filesystem rescan), expands the row, and seeds a per-package one-shot nonce so the editor lands directly in **EDIT** mode rather than VIEW.
4. On a lock-race abort, shows the standard memory toast (*"Memory edit aborted — a session just started."*).

`AppAccessContentIndex` is a page-scoped preload that drives the chips in O(1). It builds a `Map<package, AppContentSummary(hasMemory, hasSkill)>` once on mount via a `Mutex`-serialized `load()` on `Dispatchers.IO`, merging `MemoryStore.listAppPackages()` with the parsed `app_skills/` asset tree (filtering to entries whose `SKILL.md` parses cleanly via `SkillFrontmatterParser`, matching the gating used by `AssetAppSkillRepository`). Save / delete inside the inline editor calls `index.update(pkg, summary)` to keep the chip current without re-scanning. The same mutex serializes `load()` with `update()` so an in-flight scan cannot clobber a row-level update.

### Agent Behavior → Tools

> See: `ui/settings/AgentBehaviorSettingsPage.kt`, `ui/settings/ToolsSection.kt`, `ui/settings/AgentSkillToggleRows.kt`

The Tools section now hosts both capability toggles and **per-skill enable/disable rows** sourced from the bundled + installed Agent Skill catalog:

- Each row shows the skill name, a Switch (ON = enabled), and an info icon. Info opens a dialog viewer with the full SKILL.md body (frontmatter stripped, matching what the model would see).
- The switch is mirrored to `AppSettingsStore.disabledAgentSkills`: ON ⇔ NOT in the disabled set. Writes go through `setSkillDisabled(name, disabled)` which is `Mutex`-serialized so rapid toggles cannot race the read-modify-write on the prefs commit.
- **Activation is at session creation.** `AgentSkillManager` snapshots the disabled set at construction time inside `SessionServices.create`. When a session is running and a skill is disabled in Settings, the row's subtitle reads *"Takes effect next session"* — the persisted state is committed, but the current session continues to see the skill.
- On first composition the rows install bundled Agent Skills via `SessionServices.installBundledAgentSkills` on `Dispatchers.IO`, so opening Settings before any session has run still discovers the bundled catalog.

See [agent_skills.md → Disable filter (next-session semantics)](../agent/agent_skills.md#disable-filter-next-session-semantics) for the runtime side.

### Local Model Loading Status

| Status | UI |
|--------|----|
| `Idle` | No indicator |
| `Downloading(progress)` | Progress bar with percentage |
| `Loading` | Indeterminate progress |
| `Ready` | Green checkmark |
| `Error(message)` | Red error text |

`ModelLoadingStatusHolder` (`app/ModelLoadingStatusHolder.kt`) owns this status outside Compose. Picking a model in the (currently hidden) Local tab calls `updateLocalModel(model)`, which kicks off a background prewarm: a disposable `LFMLLMClient` runs `loadModel` to warm the on-disk Leap cache, then `cleanup()` releases the runner so tensor memory isn't held until the real session boots. A monotonic generation token gates every status write so stale callbacks from a cancelled prewarm can't clobber a newer selection or the real session's status. Cancellation rethrows `CancellationException`; cleanup runs under `NonCancellable`. The "already warmed" short-circuit keys on `modelSlug/quantizationSlug` so different quants of the same slug re-download.

The Local tab itself is hidden by `LOCAL_TAB_ENABLED = false` in `LlmAuthSettingsPage.kt`. LFM 1.2B Q4 on phone CPU takes 1–3 min to emit the first tool call against the 12-tool agent schema — works but unusable. The rest of the local stack (`LFMLLMClient`, prewarm, `LocalTabContent`, `AVAILABLE_LOCAL_MODELS`) stays wired; flip the flag to one line re-expose. A `LaunchedEffect` repairs a process-restored `selectedTab = LOCAL` to `API_KEY` when the flag is off, so saved state can't bypass the gate.

### Settings Files

```
ui/settings/
├── SettingsSheet.kt              # Main composable (full-screen page + BackHandler)
├── SettingsHomePage.kt           # HOME landing: Behavior / Access / About sections
├── SettingsModels.kt             # Data models (LocalModelOption, ModelLoadingStatus)
├── SettingsDropdowns.kt          # Backend/model dropdowns
├── SettingsDropdown.kt           # Generic reusable dropdown composable
├── SettingsWidgets.kt            # Shared widgets (Header, Section, Row, StatusIndicator)
├── ApiKeyFields.kt               # API key input fields (masked + visibility toggle)
├── MemorySettingsPage.kt         # Settings → Memory page (User + Device rows)
├── MemoryFileEditor.kt           # Bounded + unbounded editor variants, gate-aware
├── MemoryFileEditorPage.kt       # Full-screen wrapper around unbounded editor
├── AppAccessSettingsPage.kt      # App rows + filter + + Memory chip + expansion
├── AppRowExpansion.kt            # Inline skill viewer + bounded memory editor
├── AppAccessContentIndex.kt      # O(1) memory/skill presence preload (Mutex-serialized)
├── AgentBehaviorSettingsPage.kt  # Perception + Tools section
├── AgentSkillToggleRows.kt       # Per-skill toggle + info viewer (next-session subtitle)
└── ToolsSection.kt               # Browser Script, Termux Shell + skill rows host
```

---

## Persistence

`AppSettingsState` (non-secret) is persisted in plain `SharedPreferences` via `AppSettingsStore`. Credentials are persisted separately in `AuthStore` (`EncryptedSharedPreferences`).

`AppSettingsStore` keys:
- `llm_backend` — `OPENAI` or `LOCAL`
- `debug_mode` — boolean
- `trace_enabled` — boolean
- `browser_script_enabled` — boolean, gates `browser_script` before Shizuku/Chrome preflight
- `termux_shell_enabled` — boolean, gates `termux_shell` exposure at the next session snapshot
- `disabled_agent_skills` — JSON array of skill names excluded from the catalog at next session start; mutated through `setSkillDisabled(name, disabled)` under a `Mutex` so concurrent toggles cannot lose entries on the read-modify-write
- `perception_mode` — `accessibility_only`, `screenshot_only`, `hybrid`
- `platform_mode` — `ACCESSIBILITY`, `VIRTUAL_DISPLAY`
- `model` — model name string

`openaiBaseUrl`, `otherBaseUrl`, and `otherModelId` are persisted SharedPreferences entries (see the field table above). They can be primed from the matching intent extras (`openai_base_url`, `other_base_url`, `other_model_id`) but the values survive process death; clearing them requires writing an empty value through Settings or the intent path.

The Browser Script toggle lives under **Agent Behavior → Tools → Browser Script**. Turning it on
only permits the runtime capability gate to continue; `browser_script` still requires Shizuku
availability, Shizuku permission, Chrome DevTools socket preflight, and the normal tool policy
approval flow.

The Termux Shell row lives under **Agent Behavior → Execution**. The row can render
`NotInstalled`, `NeedsSetup(reason)`, `SetupInProgress`, `Ready`, or `Disabled`; full bootstrap runs
only from an explicit row action. Setting changes apply to the next session because
`SessionServices.create(...)` captures an immutable Termux capability snapshot.

→ See: [termux_shell.md](termux_shell.md) for setup states, lifecycle invariants, and OEM limits.

`AuthStore` keys (`EncryptedSharedPreferences`, file `auth_store.xml`):
- One entry per `LLMProvider.name` (e.g. `OPENAI_API`, `OPENAI_CODEX`, `OPENROUTER`, `OTHER`), value is a JSON-encoded `AuthCredential` (`ApiKey` or `OAuth`).
- Per-provider generation counter for cache invalidation in `LLMClientFactory`.

### Security

- Credentials are persisted only in `EncryptedSharedPreferences` (AES256-GCM via `MasterKey`). No plaintext fallback file. If KeyStore init throws, the exception propagates — `AuthStore` does not silently degrade to memory. Caller decides whether to surface a "secure storage unavailable" error. JVM tests inject `FakeSharedPreferences` via the `prefsProvider` constructor parameter.
- API keys are masked in UI input fields and not emitted in normal debug logs.
- `CodexResponseClient` never captures OAuth state; every request reads fresh `CodexHeaders` from `AuthStore.codexHeaders()` (mutex-guarded refresh near 5-min expiry).

---

## Onboarding

> See: `onboarding/` package, `ui/onboarding/` package

First-launch onboarding wizard gates chat behind required permissions and a validated API key. Steps: Accessibility (hard) → Overlay (hard) → Battery (soft/skippable) → API Key (validated) → Demo → Complete.

- `OnboardingStore` manages its own prefs file (`onboarding_prefs`), separate from settings. Schema v2 — legacy `auth_method` and encrypted `api_key_draft` keys removed; auth state derives from `selectedModel.provider.mode + AuthStore.has(provider)`.
- Typed API-key text in onboarding is ViewModel-transient (no encrypted draft persistence) — process death means re-type.
- Legacy users detected via AuthStore presence, session history, or non-default settings → auto-skip onboarding
- Eval/debug bypass: `EXTRA_FRESH_SESSION + EXTRA_GOAL` intent skips onboarding
- Post-onboarding: `PermissionRepairCard` shows targeted repair if a permission is later revoked
- API-key validator base URL resolution mirrors `LLMClientFactory.build()` — for `OPENAI_API` entries, `AppSettingsState.openaiBaseUrl` (set from intent extra `openai_base_url`, debug-only) wins over `entry.effectiveBaseUrl`. Required so debug builds talking to the in-house proxy validate forward-versioned mock model IDs (`gpt-5.4`, etc.) instead of hitting `api.openai.com`.

---

## Settings Deep-Link

> See: `ui/chat/SettingsDeepLink.kt`, `app/MainActivityContent.kt`, `app/MainActivity.kt`

Two paths open Settings with a target page/tab pre-selected:

1. **Banner tap** — session bootstrap throws `MissingCredential` / `OAuthRefreshFailed` / `WrongCredentialType` → `ChatViewModel.reportStartupFailure(deepLink)` stores the link in `_startupErrorDeepLink` → tapping the banner calls `onOpenSettings(viewModel.startupErrorDeepLink.value)`.
2. **Pre-flight check** — `MainActivity.validateCloudKeysForSelectedModels()` runs before send; on missing credentials, it sets `pendingSettingsDeepLink` (passed as `initialSettingsDeepLink` to `MainActivityContent`) and flips `showSettings = true`.

Both paths converge on `MainActivityContent.pendingDeepLink`, forwarded as `initialPage` / `initialAuthTab` to `SettingsSheet` → `LlmAuthSettingsPage`. The auth tab is derived from the missing provider's `mode` (OAuth → Sign In, ApiKey → API Key).

---

## Related Docs

- [Protocol](../protocol/overview.md) - `SessionConfig` contract
- [Session](../infra/session.md) - Runtime wiring of config
- [LLM](../infra/llm.md) - Model catalog and client factory
- [UI Tech Design](../ui/tech_design.md) - Settings UI components
