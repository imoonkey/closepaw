# Custom LLM endpoint + dynamic model discovery

> **Status:** design v3, Codex-approved (3 review rounds). Ready to implement PR1.
> **Owner:** TBD. **Reviewer:** human (final sign-off).

## Goal

Two related changes to the LLM provider layer:

1. **Replace `LLMProvider.NOVITA` with `OTHER`** — a generic
   OpenAI-compatible slot where the user supplies their own base URL,
   model id, and API key. Removes the hardcoded `api.novita.ai` string
   and gives one user-configurable escape hatch for any OpenAI-API
   compatible upstream (Novita, vLLM, LM Studio, self-hosted, etc.).
2. **Dynamic model discovery via `GET /models`** — for `OPENROUTER` and
   `OTHER`, add a "Refresh models" button that fetches the upstream
   model list, parses per-provider metadata, and caches it locally.
   Keep `llm_models.json` as the curated seed for `OPENAI_API`,
   `OPENAI_CODEX`, and `LOCAL_LFM` (OpenAI's `/models` is too thin to
   drive UX).

Why now: Novita is the only `NOVITA`-only thing in the codebase and one
of three API-key providers users see. Making it generic + adding
discovery removes the "we have to ship a code change every time someone
wants a new model/endpoint" coupling.

## Background — upstream `/models` reality

Confirmed by live API calls (see chat log 2026-05-16):

| Upstream | `display_name` | context | API type | Notes |
|---|---|---|---|---|
| OpenRouter | ✓ `name` | ✓ `context_length` | infer `chat` | + pricing, supported_parameters, modality |
| Novita | ✓ `display_name` | ✓ `context_size` | ✓ `model_type` + `endpoints` | richest metadata |
| OpenAI | ✗ | ✗ | ✗ | bare `{id, object, created, owned_by}`, 134 mixed entries |

→ Discovery is viable for OpenRouter / Novita / other OAI-compatible
upstreams. OpenAI's endpoint is too thin — keep hand-curated entries.

## Approach (v3)

### PR1 — `NOVITA` → `OTHER` (custom endpoint, runtime-synthesized catalog entry)

KISS: single `OTHER` slot. Base URL and custom model id live in
`AppSettingsState` (matching the existing `openaiBaseUrl` precedent).
The API key stays in `AuthStore`. A synthesized `ModelEntry` is added
to the runtime catalog so the rest of the stack — selector, factory,
session bootstrap, validation — keeps working unchanged.

**Why settings not credential** (revised from v1, per Codex #5): there is
already an `AppSettingsState.openaiBaseUrl` for the same kind of thing.
Putting OTHER's URL there keeps writes atomic (key and URL are separate
fields, debouncer only touches key), matches precedent, and removes the
need to evolve `AuthCredential.ApiKey`'s wire format.

#### Storage layout

- `AuthStore[OTHER] = AuthCredential.ApiKey(key)` — unchanged shape.
- `AppSettingsState.otherBaseUrl: String` (persisted, default `""`).
- `AppSettingsState.otherModelId: String` (persisted, default `""`).
- No `defaultBaseUrl` on `LLMProvider.OTHER` (was Novita's hardcoded
  URL); user-supplied only.

#### Synthesized catalog entry

`ModelCatalogRepository.get()` (new, see PR2 too) returns:

```
seed JSON entries
  ∪ (otherBaseUrl.isNotBlank() && otherModelId.isNotBlank()
       → synthOtherEntry(otherBaseUrl, otherModelId))
  ∪ discovered entries (PR2 only)
```

`synthOtherEntry` produces a single `ModelEntry`:
- `name = "other-custom"` (stable key — UI dropdown shows just this row
  for OTHER until PR2 lands)
- `displayName = otherModelId` (the user's id is the most readable label)
- `provider = OTHER`
- `api = ApiType.CHAT`
- `modelId = otherModelId`
- `baseUrl = otherBaseUrl`
- `contextWindow = 128_000` (default)
- `supportsVision = false` (conservative; user can't claim what we
  haven't verified)

If either field is blank → entry is **not** synthesized; the OTHER
dropdown is empty and preflight (see below) blocks session launch.

#### Settings UI changes — OTHER tab

`LlmAuthSettingsPage` API-key tab, when `OTHER` is selected:

```
Provider: [ OpenAI | OpenRouter | Other ]
Base URL:        [ https://api.novita.ai/openai/v1   ]
Custom model id: [ zai-org/autoglm-phone-9b-multilingual ]
API Key:         [ ********                          ]
Cloud Model:     ( other-custom )    ← read-only dropdown until PR2
```

All three fields debounce through their own write paths. Field
validation:
- **Base URL** (Codex r1#8, r2#9, r3#7): parse with `java.net.URI`;
  require `http` or `https` scheme; reject empty host. Single,
  environment-aware rule:
  - release: scheme **must** be `https`. No exceptions.
  - debug: `https` always allowed; `http` allowed only if host is
    `localhost`, `127.0.0.1`, or `10.0.2.2` (emulator loopback).
  - Trim trailing `/`. Implemented as
    `OtherBaseUrlValidator.validate(input, allowDebugHttp: Boolean = BuildConfig.DEBUG)`
    returning `Result<NormalizedUrl>` — `allowDebugHttp` is injectable
    so JVM CI tests can verify **both** release and debug policy
    without flipping BuildConfig. Small file in `llm/` package, NOT
    pretending `InsecureSslConfig` enforces this.
- **Custom model id** (Codex r2#12): non-blank after trim; reject if it
  contains whitespace or starts with `/` or `:` (collides with
  namespacing scheme below). Stored verbatim after trim.
- **API key**: non-blank after trim.

Selection rule (Codex r2#8, r3#6): when the user is in the OTHER
sub-tab AND all three fields validate, the settings page debounce
flushes its writes; only **after** the post-write `repo.invalidate()`
returns AND `repo.catalog.value.containsKey("other-custom")` does the
page call `settingsState.updateModel("other-custom")`. Order matters —
without this guard `selectedModel` could point at a not-yet-present
catalog row for one debounce window. When any of the three becomes
invalid, the page does **not** auto-clear `selectedModel` (preflight
catches it); user can still navigate away. Conversely, when the user
picks a non-OTHER provider sub-tab, model auto-canonicalizes through
the existing `canonicalizeMainModel` path (unchanged).

#### Preflight (Codex r1#3, r2#1)

`MainActivityModelValidation.findMissingCloudKeys()` special-cases
`selectedModel == "other-custom"` **before** calling `catalog.resolve`.
This is required because, when `otherBaseUrl` or `otherModelId` is
blank, the synth entry is absent — without the short-circuit,
`catalog.resolve` throws "unknown model" instead of a useful "Other
needs configuration" error. The check returns one
`MissingCredentialTarget` per blank/invalid field (key, url, modelId)
with a single deep-link target opening the Other tab.

`ensureRequiredCredentials()` in `SessionLlmBootstrapper` is updated in
parallel: when `config.mainModel == "other-custom"`, it verifies all
three (key, url, modelId) and throws `MissingCredential(OTHER)` with
the missing field logged (since `MissingCredential` itself is just
`(provider: LLMProvider)` today — see Codex r3#3). Field-level detail
lives in `MissingCredentialTarget` so the banner can say "Other model
needs base URL" specifically. **No changes to `AuthErrors.kt`** — keep
the existing exception shape.

`SettingsDeepLink` is extended (Codex r2#1): `provider: LLMProvider?`
added so banners can deep-link "open Other tab" specifically (the
existing `authTab: AuthMode?` is too coarse — it only opens the API Key
tab; the user still has to find the Other sub-tab). Settings host
honours `provider` by setting both `initialAuthTab = ApiKey` and the
provider sub-selector.

#### Intent extras (Codex #6 — broader scope)

- Remove `EXTRA_NOVITA_API_KEY = "novita_api_key"`.
- Add `EXTRA_OTHER_API_KEY = "other_api_key"`.
- Add `EXTRA_OTHER_BASE_URL = "other_base_url"`.
- Add `EXTRA_OTHER_MODEL_ID = "other_model_id"`.
- `MainActivityIntentApplier`: write key → `AuthStore[OTHER]`; write
  URL → `settingsState.updateOtherBaseUrl()`; write model id →
  `settingsState.updateOtherModelId()`.

#### External integrations to update in PR1 (Codex r1#6, r2#4)

- `scripts/debug-run.sh`: replace `NOVITA_API_KEY` block with
  `OTHER_API_KEY` + `OTHER_BASE_URL` + `OTHER_MODEL_ID` blocks.
- `eval/aw_bridge/native_agent_bridge.py`: `_KEY_MAP` swap
  `NOVITA_API_KEY` → `OTHER_API_KEY` → `other_api_key`; forward
  `OTHER_BASE_URL`/`OTHER_MODEL_ID` as `other_base_url`/`other_model_id`
  intent extras.
- `eval/aw_bridge/runner.py` (Codex r2#4): `_PROVIDER_REQUIRED_API_KEY`
  and `_API_KEY_NAMES` swap NOVITA → OTHER. Critically: when
  `main_model == "other-custom"`, the runner must require all three of
  `OTHER_API_KEY`, `OTHER_BASE_URL`, `OTHER_MODEL_ID` (not just the
  key), because the synth entry doesn't exist in `llm_models.json`
  preflight. Add a `_OTHER_REQUIRED_EXTRAS` constant.
- `doc/main/app/settings.md`, `doc/main/infra/llm.md`: replace NOVITA
  references with OTHER + new fields.
- `doc/PROGRESS.md`: no change (historical record).

#### Onboarding

`OnboardingProvider.NOVITA` removed entirely. Do **not** add OTHER to
onboarding — the picker stays OPENAI + OPENROUTER. OTHER is settings-only
(same advanced-only stance NOVITA already had via `visibleInUi`).

#### State machine — OTHER usability

```
   ┌─────────────────────────────────────────┐
   │ key="" │ url="" │ modelId=""            │
   │ entry absent │ preflight fails │ no-op  │
   └─────────────┬───────────────────────────┘
                 │ user fills any subset
                 ▼
   ┌─────────────────────────────────────────┐
   │ partial │ entry absent │ preflight fails │
   │ banner: "Other model needs key + url + id"│
   └─────────────┬───────────────────────────┘
                 │ all three present + url valid
                 ▼
   ┌─────────────────────────────────────────┐
   │ ready │ entry synthesized as "other-custom"│
   │ factory builds ChatCompletionClient(key,url)│
   └─────────────────────────────────────────┘
```

### PR2 — Dynamic `/models` discovery

#### Single observable catalog repository (Codex r1#2, r2#2, r2#3, r3#4, r3#5)

Today `MainActivity.modelCatalog` (lazy from assets) and
`SessionLlmBootstrapper.getOrLoadModelCatalog` (cached WeakHashMap from
assets) each build their own catalog. Discovery results would land in
neither. Fix: introduce `ModelCatalogRepository` as a **process-wide
singleton** via the existing `AuthStoreHolder`-style pattern (no custom
`Application` class today — don't add one just for this; mirror
`AuthStoreHolder` exactly):

```kotlin
object ModelCatalogRepositoryHolder {
    @Volatile private var instance: ModelCatalogRepository? = null
    fun get(context: Context): ModelCatalogRepository = instance ?: synchronized(this) {
        instance ?: ModelCatalogRepository(
            context.applicationContext,
            AppSettingsStore(context.applicationContext),
            ModelDiscoveryCache(context.applicationContext),   // PR2; PR1 stub is in-mem-empty
        ).also { instance = it }
    }
}
```

Both `MainActivity` and `SessionServices` fetch via the holder.

Critical constraint: must expose **observable** state for Compose
recomposition. Use `StateFlow<ModelCatalog>` (Codex r3#5 — Compose
`State` in a non-Compose module is awkward; `StateFlow` interops with
both Compose via `collectAsStateWithLifecycle()` and off-main consumers
via `.value`):

```kotlin
class ModelCatalogRepository(
    private val context: Context,
    private val settingsStore: AppSettingsStore,
    private val discoveryCache: ModelDiscoveryCache,
) {
    val catalog: StateFlow<ModelCatalog>            // observable, thread-safe

    /** Recompute merged view; safe to call from any thread. */
    fun invalidate()

    /** PR2: fetch from upstream, write cache, then invalidate(). */
    suspend fun refresh(provider: LLMProvider, key: String)
}
```

`settingsStore` is plain SharedPreferences (already app-singleton-safe),
not `AppSettingsState` (which is Activity-owned Compose state). The
repo reads fresh values from the store on `invalidate()`. Settings UI
**and** `MainActivityIntentApplier` (Codex r3#1 — intent path must not
leave stale catalog!) both call `repo.invalidate()` after mutating
`otherBaseUrl` / `otherModelId` / OTHER api key.

**Call sites to update** (Codex r2#7, expanded after r3#4):

- `app/src/main/kotlin/ai/closepaw/session/SessionServices.kt:136`
  — fetch via holder, pass into `SessionLlmBootstrapper.create`.
- `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:122`
  — replace lazy assets read with `repo.catalog.collectAsStateWithLifecycle()`.
- `app/src/main/kotlin/ai/closepaw/app/MainActivityIntentApplier.kt`
  — after writing OTHER settings, call `repo.invalidate()`.
- `app/src/test/kotlin/ai/closepaw/session/SessionLlmBootstrapperTest.kt:27`
  — build repo from fixture store.
- No `AgentSession.create`/`reload`/`AgentService` direct callers
  (verified by grep — only `SessionServices` calls `SessionLlmBootstrapper.create`).

PR1 ships the repo with discovery wiring stubbed (empty cache).
PR2 fills in the discovery side.

#### Discovery service

`ModelDiscovery.kt` (single file, per Codex r1#9):

```kotlin
suspend fun discover(
    provider: LLMProvider,
    baseUrl: String,
    apiKey: String,
): List<ModelEntry>
```

Implementation: GET `{baseUrl}/models` with bearer, parse with a single
flexible reader. **All discovered entries set `baseUrl = sourceBaseUrl`
explicitly** (Codex r2#6) — otherwise `LLMClientFactory` would route
OTHER discovery models through `entry.effectiveBaseUrl` falling back to
the OpenAI SDK default.

Per-entry field priority:

| field | sources tried |
|---|---|
| display_name | `name`, `display_name`, fallback to `id`; sanitize control chars; cap 80 chars |
| context | `context_length`, `context_size`, `top_provider.context_length`, fallback 128_000 |
| api type | always `CHAT` (no upstream we trust ships Responses today) |
| baseUrl | always `sourceBaseUrl` |
| supportsVision | `architecture.input_modalities` / `input_modalities` contains `"image"` → true; else **false** (Codex r1#7) |

**Filtering — required for OpenRouter scale (356 entries, see chat log
2026-05-16)**:

1. **Mandatory: tool-calling support.** Drop any entry whose
   `supported_parameters` (OpenRouter) doesn't include `"tools"`. Agents
   literally cannot work without function calling — this is the cheapest,
   strongest filter (356 → 263 on OpenRouter today). For upstreams that
   don't expose this field, accept the entry (don't over-filter).
2. **Drop non-chat by structured field when present**: `model_type` ∈
   {`embedding`, `audio`, `image`}; `endpoints` missing `chat/completions`.
3. **Id-substring fallback** (only when no structured field): drop ids
   matching `Regex("(?i)(embedding|whisper|tts|moderation|dall-e)")`.

Structured upstream fields are preferred over id-substring heuristic
(Codex r1 open Q3).

**Caching: slim before persist.** The cache file stores only fields
consumed by `ModelEntry` (name, displayName, modelId, contextWindow,
baseUrl, supportsVision, ApiType) — no pricing, no description, no
modality details. OpenRouter's raw response is ~440KB; slimmed cache
for 263 entries is ~30KB.

#### Cache, scoping, and credential safety (Codex r1#4, r2#5)

Cache file: `filesDir/model_discovery_cache.json`.

```json
{
  "OPENROUTER:https://openrouter.ai/api/v1": {
    "fetched_at": 1768000000,
    "entries": [ ...ModelEntry... ]
  },
  "OTHER:https://api.novita.ai/openai/v1": { ... }
}
```

Cache key = `"{provider.name}:{normalizedBaseUrl}"`. The merged catalog
**only surfaces entries whose cache key matches the current effective
baseUrl for that provider**:

- `OPENROUTER` effective baseUrl = `LLMProvider.OPENROUTER.defaultBaseUrl`
  (seed-fixed).
- `OTHER` effective baseUrl = `settingsStore.otherBaseUrl` (validated +
  normalized).

This kills the credential-leak failure mode Codex r2#5 raised: if the
user changes `otherBaseUrl` from A to B, cached entries for A are no
longer visible in the dropdown, so the user can't accidentally select
an old-A model that would then be sent to the current key against B.
Stale cache entries are kept on disk (cheap GC, useful if user reverts
URL); a tiny background trim could be added later.

#### Visible-name namespacing (Codex r1#4, r2#12)

```
Visible ModelCatalog keys:
  seed entries:        "gpt-5.2"            (unchanged)
  OTHER synth (PR1):   "other-custom"        (single fixed key)
  discovery (PR2):     "{provider.name.lowercase(Locale.ROOT)}:{modelId}"
                       e.g. "openrouter:anthropic/claude-opus-4.7"
```

Discovered keys are namespaced so they cannot collide with seed entries
(no chance of a discovered "gpt-5.2" hijacking the OpenAI seed and
rerouting traffic). The `ModelEntry.displayName` stays human-readable.

`selectedModel` settings field stores whatever key the user picked —
namespaced for discovered models, bare for seed entries, `other-custom`
for OTHER. Pre-release, no migration code needed for the rare case of
a name conflict because the seed list is well-defined and small.

ModelId normalization on parse: trim; reject if it contains whitespace
or starts with `:` or `/`; preserve case (some upstream ids are case-
sensitive).

#### Refresh UX + dropdown UX + gating (Codex r2#10, dropdown UX 2026-05-16)

Even after tool-calling filter, OpenRouter returns ~263 models. A flat
dropdown is unusable on a phone. PR2 ships a real picker:

```
Cloud Model:
  ┌─────────────────────────────────────────┐
  │ [🔍 search models...                  ] │
  │                                          │
  │ ▼ anthropic (13)                         │
  │     Claude Opus 4.7 (Fast)               │
  │     Claude Opus 4.7                      │
  │     ...                                  │
  │ ▶ openai (57)                            │
  │ ▶ qwen (45)                              │
  │ ▶ google (19)                            │
  │ ...                                      │
  └─────────────────────────────────────────┘
  [ ↻ Refresh models ]   Last refreshed: 2 hours ago
```

Picker behavior:

- **Search box** filters by `displayName` + `modelId` substring,
  case-insensitive. While searching, groups flatten into a single
  filtered list.
- **Grouping** by `id.split("/")[0]` (e.g. `anthropic`, `openai`, `qwen`).
  For OTHER discovery, prefix may be missing → goes into `(other)` group.
  Groups sorted alphabetically; **anthropic / openai / google pinned to
  top** because they're the most common picks.
- **Sort within group**: by `created` timestamp **descending** (newest
  first) so new models surface immediately after refresh.
- **Selected model** is always visible (auto-scroll into view; if in a
  collapsed group, auto-expand that group on open).

Refresh button (separate from picker):

- Disabled when prerequisites missing — `OPENROUTER`: key blank;
  `OTHER`: key OR url blank/invalid — with tooltip naming the missing
  piece.
- Tap → `repo.refresh(provider, key)` in `viewModelScope.launch`.
- States: `Idle | Refreshing | Error(message)`. On error: inline text,
  **no cache mutation**. On success: picker re-renders (via `repo.catalog`
  StateFlow); timestamp updates.

#### Out of scope PR2

- Scheduled background refresh (battery)
- Pricing display
- Per-model capability badges
- Multi-endpoint OTHER (still one slot)
- Stale-cache disk GC

## Components

### Changed

- `LLMProvider.kt` — rename `NOVITA` → `OTHER`; clear `defaultBaseUrl`.
- `LLMClientFactory.kt` — `OTHER` branch builds
  `ChatCompletionClient(key, entry.baseUrl)`. Hard-require
  `!entry.baseUrl.isNullOrBlank()` at this boundary (Codex r3#2): if
  anything upstream produces a malformed OTHER `ModelEntry`, the OpenAI
  SDK's default base URL would otherwise be used — leaking the user's
  OTHER key to api.openai.com. Throw `MissingCredential(OTHER)` instead.
- `AppSettingsState.kt` / `AppSettingsStore.kt` — add `otherBaseUrl`,
  `otherModelId` fields + persistence.
- `MainActivityModelValidation.kt` — extend `findMissingCloudKeys` for
  OTHER (key, url, modelId), short-circuit on `other-custom`.
- `LlmAuthSettingsPage.kt` — Other tab renders Base URL, Custom model
  id, API Key; (PR2) Refresh button.
- `MainActivity.kt` — drop `EXTRA_NOVITA_API_KEY`; add `EXTRA_OTHER_*`;
  `modelCatalog` reads from `ModelCatalogRepository.catalog`.
- `MainActivityIntentApplier.kt` / `MainActivityIntentPayload.kt` —
  swap Novita extras for OTHER trio.
- `OnboardingState.kt` / `OnboardingDemoController.kt` — drop NOVITA
  enum case.
- `SessionLlmBootstrapper.kt` — accept `ModelCatalogRepository`, stop
  loading from assets directly. `ensureRequiredCredentials` extended
  for OTHER trio.
- `SessionServices.kt:136` (sole prod call site) — pass repo through.
- `app/src/test/kotlin/ai/closepaw/session/SessionLlmBootstrapperTest.kt`
  — build repo from fixture `AppSettingsStore`.
- `ui/chat/SettingsDeepLink.kt` — add `provider: LLMProvider?`.
- `llm_models.json` — remove `autoglm-phone-9b-multilingual`.
- `scripts/debug-run.sh`, `eval/aw_bridge/native_agent_bridge.py`,
  `eval/aw_bridge/runner.py` — NOVITA env var → OTHER trio + runner
  preflight for `other-custom`.
- `doc/main/app/settings.md`, `doc/main/infra/llm.md` — update
  provider table.

### New

- `app/src/main/kotlin/ai/closepaw/llm/ModelCatalogRepository.kt` (PR1)
- `app/src/main/kotlin/ai/closepaw/llm/OtherBaseUrlValidator.kt` (PR1)
- `app/src/main/kotlin/ai/closepaw/llm/ModelDiscovery.kt` (PR2)
- `app/src/main/kotlin/ai/closepaw/llm/ModelDiscoveryCache.kt` (PR2)
- `app/src/test/kotlin/ai/closepaw/llm/ModelCatalogRepositoryTest.kt` (PR1)
- `app/src/test/kotlin/ai/closepaw/llm/OtherBaseUrlValidatorTest.kt` (PR1)
- `app/src/test/kotlin/ai/closepaw/llm/ModelDiscoveryTest.kt` (PR2)

### Removed

- `LLMProvider.NOVITA` enum value
- `EXTRA_NOVITA_API_KEY` constant + `novita_api_key` intent extra string
- `OnboardingProvider.NOVITA`
- `llm_models.json` `autoglm-phone-9b-multilingual` entry
- Hardcoded `https://api.novita.ai/openai/v1` string
- `NOVITA_API_KEY` env var refs in scripts + eval

## Interactions

### Credential resolve (post-PR1)

```
SessionLlmBootstrapper.create(config, repo)
  ├─ catalog = repo.catalog.value               ← seed ∪ OTHER synth (∪ discovery in PR2)
  ├─ ensureRequiredCredentials(catalog, config, authStore)
  │    if config.mainModel == "other-custom":
  │       short-circuit — verify key + otherBaseUrl + otherModelId
  │       throws MissingCredential(OTHER) (field detail in log/Target)
  │    else:
  │       catalog.resolve(config.mainModel) → entry
  │       authStore.has(entry.provider) → MissingCredential if absent
  └─ factory.create(config.mainModel)
       └─ when provider:
            OTHER  → require(!entry.baseUrl.isNullOrBlank())
                     ChatCompletionClient(
                       key = authStore.requireApiKey(OTHER),
                       baseUrl = entry.baseUrl    // from synth
                     )
            OPENROUTER → same shape, baseUrl from seed/discovery
            OPENAI_API/CODEX/LOCAL_LFM → unchanged
```

### Discovery flow (PR2)

```
User taps "Refresh models" (provider P, baseUrl B, key K)
  → ViewModel.refresh(P)
    → ModelDiscovery.discover(P, B, K)
        → HTTP GET {B}/models  (Authorization: Bearer K)
        → parseToEntries(...)  with field-priority reader
    → ModelDiscoveryCache.write("$P:$B", { fetched_at, entries })
    → repo.invalidate()
  → UI re-renders dropdown
```

### Catalog merge (PR2)

```
ModelCatalogRepository.get() =
   seed JSON
   + (OTHER synth if otherBaseUrl + otherModelId both non-blank)
   + (for each cached "{provider}:{baseUrl}":
        entries with namespaced name "{provider.lower}:{modelId}")
```

Discovered keys are namespaced → no collision with seed. Settings
stores the namespaced key as `selectedModel`.

## Tasks

Two PRs, each a separate task in `projects/tasks.json`. PR1 is
independently shippable (with the synth-entry mechanism) and unblocks
PR2.

### Task: `custom-endpoint-other-provider`

**Scope:**
- `app/src/main/kotlin/ai/closepaw/llm/LLMProvider.kt`
- `app/src/main/kotlin/ai/closepaw/llm/LLMClientFactory.kt`
- `app/src/main/kotlin/ai/closepaw/llm/ModelCatalogRepository.kt` (new)
- `app/src/main/kotlin/ai/closepaw/llm/ModelCatalog.kt` (small additions)
- `app/src/main/kotlin/ai/closepaw/app/AppSettingsState.kt`
- `app/src/main/kotlin/ai/closepaw/app/AppSettingsStore.kt`
- `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt`
- `app/src/main/kotlin/ai/closepaw/app/MainActivityIntentApplier.kt`
- `app/src/main/kotlin/ai/closepaw/app/MainActivityIntentPayload.kt`
- `app/src/main/kotlin/ai/closepaw/app/MainActivityModelValidation.kt`
- `app/src/main/kotlin/ai/closepaw/session/SessionLlmBootstrapper.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt`
- `app/src/main/kotlin/ai/closepaw/onboarding/OnboardingState.kt`
- `app/src/main/kotlin/ai/closepaw/onboarding/OnboardingDemoController.kt`
- `app/src/main/assets/llm_models.json`
- `scripts/debug-run.sh`
- `eval/aw_bridge/native_agent_bridge.py`
- `eval/aw_bridge/runner.py`
- `doc/main/app/settings.md`
- `doc/main/infra/llm.md`
- `app/src/test/kotlin/ai/closepaw/**`
- `app/src/androidTest/kotlin/ai/closepaw/qa/**`

**Acceptance criteria (CI — `./gradlew test lint assembleDebug`):**
- `LLMProvider.OTHER` exists; `grep -rn 'NOVITA\|novita_api_key\|NOVITA_API_KEY' app/ scripts/ eval/ doc/main/` returns no matches.
- `AppSettingsState.otherBaseUrl` and `otherModelId` persist + restore across process death (unit test on store).
- `ModelCatalogRepository.catalog` is a `StateFlow<ModelCatalog>`; both `MainActivity` and `SessionLlmBootstrapper.create` consume the same singleton (verified by removing both old load sites + grep for `ModelCatalog.fromJson` returning only the repo's own call).
- When `otherBaseUrl` and `otherModelId` are both non-blank, `repo.catalog.value` contains an `other-custom` entry whose `modelId` and `baseUrl` reflect settings; when either is blank, the entry is absent (unit test).
- `LLMClientFactory` OTHER branch throws `MissingCredential(OTHER)` when `entry.baseUrl.isNullOrBlank()` — covered by unit test (Codex r3#2).
- `MainActivityIntentApplier` calls `repo.invalidate()` after writing OTHER settings — covered by unit test asserting `catalog.value` reflects new settings post-apply.
- `findMissingCloudKeys` short-circuits on `selectedModel == "other-custom"` and returns missing entries for **any** blank/invalid field among (api_key, otherBaseUrl, otherModelId); field detail in `MissingCredentialTarget.message`.
- `ensureRequiredCredentials` throws `MissingCredential(OTHER)` for the same conditions, before catalog resolve.
- `SettingsDeepLink.provider` field exists and is propagated through ChatViewModel + SettingsSheet (unit test).
- `OtherBaseUrlValidator.validate(input, allowDebugHttp)` (Codex r3#7):
  - `allowDebugHttp=false`: rejects all `http`, accepts `https`.
  - `allowDebugHttp=true`: accepts `http://localhost`, `http://127.0.0.1`, `http://10.0.2.2`; rejects `http://other-host`.
  - Rejects non-`http(s)` scheme, empty host. Trims trailing `/`.
  - Both branches covered by unit test in plain JVM.
- `OnboardingProvider.NOVITA` removed; `OnboardingProvider.visibleInUi` unchanged shape.
- `EXTRA_OTHER_API_KEY`, `EXTRA_OTHER_BASE_URL`, `EXTRA_OTHER_MODEL_ID` round-trip through `MainActivityIntentApplier` (unit test).
- `eval/aw_bridge/runner.py` preflight rejects an `other-custom` run when any of `OTHER_API_KEY`/`OTHER_BASE_URL`/`OTHER_MODEL_ID` is missing (Python unit test or runner integration).
- `./gradlew test lint assembleDebug` exits 0.

**Acceptance criteria (manual smoke, not CI):**
- Configure Other in settings against a real OpenAI-compatible endpoint (Novita key as fixture), run one task end-to-end; verify request hits the user's URL, not api.openai.com.
- Set OTHER fields via `scripts/debug-run.sh` env vars `OTHER_API_KEY` / `OTHER_BASE_URL` / `OTHER_MODEL_ID`; verify they round-trip and the run succeeds.

**Dependencies:** none.

### Task: `dynamic-models-discovery`

**Scope:**
- `app/src/main/kotlin/ai/closepaw/llm/ModelDiscovery.kt` (new)
- `app/src/main/kotlin/ai/closepaw/llm/ModelDiscoveryCache.kt` (new)
- `app/src/main/kotlin/ai/closepaw/llm/ModelCatalogRepository.kt` (extend)
- `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/*ViewModel*.kt`
- `app/src/test/kotlin/ai/closepaw/llm/ModelDiscoveryTest.kt` (new)

**Acceptance criteria (CI):**
- `ModelDiscovery.discover(provider, baseUrl, key)` returns parsed entries for OpenRouter and OpenAI-compatible upstreams; signature is `suspend`.
- Field-priority reader covered by unit tests with three fixtures: OpenRouter (`name`, `context_length`, `architecture.input_modalities`), Novita-style (`display_name`, `context_size`, `endpoints`), OpenAI-bare (id only, fallback defaults).
- Non-chat models filtered: prefer upstream `model_type`/`endpoints`/`modality` when present; id-substring heuristic only as fallback. Unit test covers an embedding model from each fixture is dropped.
- `supportsVision` defaults to `false`; set true only when upstream modality lists `"image"`.
- Every discovered `ModelEntry.baseUrl == sourceBaseUrl` (Codex r2#6) — unit test asserts this for OTHER discovery.
- Discovered entry names are namespaced `"{provider.name.lowercase(Locale.ROOT)}:{modelId}"`; modelId rejected if it contains whitespace or starts with `:` or `/`. Unit test confirms discovered `openrouter:gpt-5.2` does NOT collide with seed `gpt-5.2`.
- `displayName` sanitized: control chars stripped, max 80 chars (Codex r3#10 nit).
- Cache `filesDir/model_discovery_cache.json` keyed by `"{provider.name}:{normalizedBaseUrl}"`; written on success, restored on app start.
- `ModelCatalogRepository.refresh(provider, key)` (suspend) updates the cache and `catalog` StateFlow emits new value.
- **Visible-name scoping**: catalog only surfaces discovered entries whose cache-key baseUrl matches the current effective baseUrl for that provider (Codex r2#5). Unit test: change `otherBaseUrl` from A to B → `catalog.value` no longer contains entries from A.
- Refresh button gating: `OPENROUTER` disabled when key blank; `OTHER` disabled when key OR url blank/invalid — covered by ViewModel-level unit test.
- **Tool-calling filter** mandatory: entries without `tools` in `supported_parameters` dropped (drops ~26% of OpenRouter today). Upstreams without the field accept everything. Unit test: a fixture entry lacking `tools` is filtered out.
- **Cache slimmed** before persist: cache JSON contains only fields consumed by `ModelEntry` (no pricing, no description). Unit test asserts a discovered OpenRouter response of ~440KB compresses to ≤50KB on disk.
- **Picker UX** (unit + Robolectric tests, wired through `./gradlew test`):
  - Search box filters by displayName + modelId substring, case-insensitive; flattens groups while filtering.
  - Group by `id.split("/")[0]`; anthropic/openai/google pinned to top, others alphabetical; OTHER entries without `/` go into `(other)` group.
  - Within group: sort by `created` desc.
  - Currently selected model is auto-scrolled into view; its group auto-expands on open.
- Preflight short-circuit for discovered OTHER (Codex r4#1): when `selectedModel.startsWith("other:")` AND the entry is no longer in `catalog.value` (e.g. user changed `otherBaseUrl`), `findMissingCloudKeys` returns a target deep-linking to the Other tab with message "Refresh or select a model for this endpoint" — unit test covers the case.
- `./gradlew test lint assembleDebug` exits 0.

**Acceptance criteria (manual smoke, not CI):**
- Refresh against OpenRouter (real API key), verify dropdown populated with current model list including a newly added model id not in seed.
- Refresh against Novita endpoint via OTHER, pick a newly discovered model, run a session end-to-end.
- Change OTHER base URL after a previous refresh — verify dropdown no longer shows old-URL models.

**Dependencies:** `custom-endpoint-other-provider`.

## Trade-offs (v3)

- **Base URL in settings (not credential).** Reverses v1. Matches the
  existing `openaiBaseUrl` field, keeps `AuthCredential.ApiKey`
  wire-compatible, makes per-field debounced writes naturally atomic.
  Cost: a misconfigured Other (key present, url missing) is "stored"
  across two places — preflight + short-circuit catch it.
- **One `OTHER` slot vs. N user-defined endpoints.** One. KISS — enum
  stays. Codex correctly flagged that the enum→registry refactor is
  not "contained"; we accept that as the price of KISS for now.
- **Synthesized catalog entry for OTHER.** Required (Codex r1#1). The
  whole rest of the stack — session bootstrap, model resolver, settings
  selector, validation, factory — speaks `ModelEntry`. Synthesizing
  one row is much cheaper than threading "user-typed model id" through
  every layer.
- **Single observable `ModelCatalogRepository`.** Required (Codex r1#2,
  r2#2). Today there are two catalog loaders; that's a latent bug
  waiting for PR2 to surface, AND the immutable-getter approach
  wouldn't trigger Compose recomposition.
- **Repo as app singleton, takes `AppSettingsStore` not State.**
  Required (Codex r2#3). Session bootstrap runs from a foreground
  service after the Activity may be gone; activity-scoped repo would
  crash or stale-read.
- **Discovered names namespaced as `{provider.lower}:{modelId}`.**
  Required (Codex r1#4). Seed names stay unnamespaced because they're
  stable and curated.
- **Visible-name scoping by current effective baseUrl.** Required
  (Codex r2#5) to prevent sending the current API key to a stale URL
  via an old cached entry.
- **`supportsVision` defaults false for discovered entries.** Required
  (Codex r1#7).
- **`OtherBaseUrlValidator` — new file, not `InsecureSslConfig` reuse.**
  Required (Codex r1#8, r2#9). Single rule across debug/release; no
  contradictions.
- **`SettingsDeepLink.provider` field.** Required (Codex r2#1) for
  "open Other tab" deep-linking from banners.
- **Eval runner special-cases `other-custom`.** Required (Codex r2#4)
  because the synth entry isn't in `llm_models.json` — runner's static
  preflight would otherwise miss the OTHER trio requirement.
- **Auto-flip `selectedModel = "other-custom"` when synth becomes
  valid.** Required (Codex r2#8) — without it, nothing wires the
  user-typed config to the runtime selector. UX cost: surprise if
  user is "just trying things". Mitigation: only flip while user is
  in the OTHER tab; explicit picker elsewhere.
- **Keep JSON seed.** Unchanged. OpenAI's `/models` is too thin.
- **PR1/PR2 split kept.** PR1 is independently shippable.

## Open questions

1. ~~`OTHER` API type — CHAT only?~~ **Decided: CHAT only.**
2. ~~Reuse `InsecureSslConfig.validateBaseUrl`?~~ **No** — new
   `OtherBaseUrlValidator`.
3. ~~Filter non-chat models by id substring or upstream fields?~~
   **Both** — upstream first, substring fallback.
4. ~~Auto-fill model id from discovery after PR2?~~ **Yes**, UX
   detail of PR2; PR1 keeps the manual field.
5. ~~Where does `ModelCatalogRepository` live?~~ **App-singleton via
   `ModelCatalogRepositoryHolder`** (mirror existing `AuthStoreHolder`
   pattern; no custom `Application` class). Holder exposes a
   `resetForTest()` method so unit tests touching the singleton don't
   leak `StateFlow` state across cases (Codex r4#3).
6. **Should `selectedModel` auto-flip to `"other-custom"` when synth
   becomes valid, or require explicit user pick?** Default in v3:
   auto-flip while user is in the OTHER tab. Open to "explicit pick"
   if it surprises users.
7. **Should discovered entries persist `displayName` localization or
   raw upstream text?** Default: raw upstream text. Localization is
   not in scope.

## Next step

Round 3 Codex review of this v3 doc. After approval:
`/update-tasks` to refresh the two task entries with the revised scope
and acceptance criteria, then `/implement custom-endpoint-other-provider`.
