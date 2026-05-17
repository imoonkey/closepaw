# Review: 250bf121 refactor(llm): introduce ModelCatalogRepository as app singleton

## Summary

Reviewed the refactor against the requested singleton, StateFlow, IO, coverage, migration, context-leak, and KISS concerns. No CRITICAL/HIGH issues found.

## Findings

1. [MEDIUM] `ModelCatalogRepository` performs synchronous asset IO during construction, and `MainActivity.onCreate()` constructs the singleton on the main thread. `MutableStateFlow(load())` runs immediately at `app/src/main/kotlin/ai/closepaw/llm/ModelCatalogRepository.kt:43`, `load()` reads `assets.open("llm_models.json")` at lines 54-58, and `MainActivity` calls `ModelCatalogRepositoryHolder.get(applicationContext)` from `onCreate()` at `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:136`. The normal session path remains off-main because `AgentSession.create/reload` is reached through `withContext(Dispatchers.Default)`, but the UI path still blocks the main thread and `SessionServices.create()` calls the holder before `SessionLlmBootstrapper.create()` enforces its main-thread guard. This is not a CRITICAL/HIGH blocker because the previous UI catalog load was also main-thread lazy IO and the asset is small, but the refactor does not fully satisfy the off-main IO goal. Prefer making the seed load explicitly happen on an IO dispatcher, or document that this small seed read is intentionally synchronous.

2. [LOW] The repository already carries unused future-facing dependencies (`AppSettingsStore`, `ModelDiscoveryCache`) and a stub cache. That matches the stated PR series direction, but for this pure refactor it adds constructor parameters, test fixture setup, and suppression noise before behavior exists. KISS-wise, keep this only if PR2 lands immediately; otherwise defer the unused wiring until it is consumed.

## Checked Items

- [OK] Singleton pattern: `ModelCatalogRepositoryHolder.get()` mirrors `AuthStoreHolder`'s volatile double-checked locking and stores `context.applicationContext`. `setForTest()` and `resetForTest()` update the same volatile reference under the same monitor, so visibility is safe for tests.
- [OK] StateFlow update: `invalidate()` assigns `_catalog.value = load()`. `load()` creates a new `ModelCatalog` instance, and `ModelCatalog` uses identity equality, so `MutableStateFlow` will treat it as a new value and `collectAsStateWithLifecycle()` in `MainActivity` will receive updates.
- [OK] Test coverage: `ModelCatalogRepositoryTest` covers seed load against `assets/llm_models.json`, invalidate producing a new catalog value, and `resetForTest()` clearing the singleton. `SessionServicesProviderRoutingTest` also covers holder injection for session creation.
- [OK] Caller migration: `rg -n "ModelCatalog\\(|ModelCatalog\\.fromJson" app/` shows no remaining production `ModelCatalog.fromJson` load sites outside `ModelCatalogRepository`; the other production `ModelCatalog(...)` references are the private factory returns inside `ModelCatalog` itself. Remaining `fromJson` calls are tests/androidTest fixtures.
- [OK] Context/leak risk: production holder construction passes `context.applicationContext` into `ModelCatalogRepository`, `AppSettingsStore`, and `ModelDiscoveryCache`; no Activity or Service context is retained by the singleton in production.

## Verification

- `git show 250bf121`
- `rg -n "ModelCatalog\\(|ModelCatalog\\.fromJson" app/`
- `./gradlew :app:testDebugUnitTest --tests ai.closepaw.llm.ModelCatalogRepositoryTest --tests ai.closepaw.session.SessionLlmBootstrapperTest --tests ai.closepaw.session.SessionServicesProviderRoutingTest`

VERDICT: APPROVE
