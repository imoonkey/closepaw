package ai.closepaw.llm

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import ai.closepaw.app.AppSettings
import ai.closepaw.app.AppSettingsStore
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.settings.AVAILABLE_LOCAL_MODELS
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class ModelCatalogRepositoryTest {

    private val seedAssetFile = File("src/main/assets/llm_models.json")

    @After
    fun tearDown() {
        ModelCatalogRepositoryHolder.resetForTest()
    }

    @Test
    fun `initial seed load matches assets llm_models json`() {
        val seedBytes = seedAssetFile.readBytes()
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes),
            settingsStore = fakeSettingsStore(otherBaseUrl = "", otherModelId = ""),
            discoveryCache = mockk(relaxed = true),
        )

        val catalog = repo.catalog.value
        val expectedKeys = ModelCatalog.fromJson(String(seedBytes)).names()
        assertThat(catalog.names()).isEqualTo(expectedKeys)
        assertThat(catalog.size).isEqualTo(expectedKeys.size)
    }

    @Test
    fun `invalidate emits a new catalog value`() {
        val first = """{"a":{"display_name":"A","provider":"OPENROUTER","api":"chat","model_id":"x/a"}}"""
        val second =
            """{"a":{"display_name":"A","provider":"OPENROUTER","api":"chat","model_id":"x/a"},""" +
                """"b":{"display_name":"B","provider":"OPENROUTER","api":"chat","model_id":"x/b"}}"""
        val context = contextReturningSequentialAssets(first.toByteArray(), second.toByteArray())
        val repo = ModelCatalogRepository(
            context = context,
            settingsStore = fakeSettingsStore(otherBaseUrl = "", otherModelId = ""),
            discoveryCache = mockk(relaxed = true),
        )

        val before = repo.catalog.value
        assertThat(before.names()).containsExactly("a")

        repo.invalidate()

        val after = repo.catalog.value
        assertThat(after.names()).containsExactly("a", "b")
        assertThat(after).isNotSameInstanceAs(before)
    }

    @Test
    fun `resetForTest clears the singleton instance`() {
        // mockkConstructor so the holder's lazy build doesn't touch real
        // SharedPreferences (AppSettingsStore) or filesDir (ModelDiscoveryCache).
        mockkConstructor(AppSettingsStore::class)
        mockkConstructor(ModelDiscoveryCache::class)
        try {
            val seedBytes = seedAssetFile.readBytes()
            val ctxA = contextReturningAsset(seedBytes)
            val ctxB = contextReturningAsset(seedBytes)

            val first = ModelCatalogRepositoryHolder.get(ctxA)
            val cached = ModelCatalogRepositoryHolder.get(ctxA)
            assertThat(cached).isSameInstanceAs(first)

            ModelCatalogRepositoryHolder.resetForTest()

            val second = ModelCatalogRepositoryHolder.get(ctxB)
            assertThat(second).isNotSameInstanceAs(first)
        } finally {
            unmockkConstructor(AppSettingsStore::class)
            unmockkConstructor(ModelDiscoveryCache::class)
        }
    }

    // ── Synthesized OTHER entry ──────────────────────────────────────────

    @Test
    fun `synthesizes other-custom when both otherBaseUrl + otherModelId are non-blank`() {
        val store = fakeSettingsStore(otherBaseUrl = "https://api.example.com/v1", otherModelId = "vendor/model")
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = mockk(relaxed = true),
        )

        val entry = repo.catalog.value.resolveOrNull("other-custom")
        assertThat(entry).isNotNull()
        assertThat(entry!!.provider).isEqualTo(LLMProvider.OTHER)
        assertThat(entry.api).isEqualTo(ApiType.CHAT)
        assertThat(entry.modelId).isEqualTo("vendor/model")
        assertThat(entry.baseUrl).isEqualTo("https://api.example.com/v1")
        assertThat(entry.supportsVision).isFalse()
        assertThat(entry.displayName).isEqualTo("vendor/model")
    }

    @Test
    fun `no other-custom entry when otherBaseUrl is blank`() {
        val store = fakeSettingsStore(otherBaseUrl = "", otherModelId = "vendor/model")
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = mockk(relaxed = true),
        )

        assertThat(repo.catalog.value.resolveOrNull("other-custom")).isNull()
    }

    @Test
    fun `no other-custom entry when otherModelId is blank`() {
        val store = fakeSettingsStore(otherBaseUrl = "https://api.example.com/v1", otherModelId = "")
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = mockk(relaxed = true),
        )

        assertThat(repo.catalog.value.resolveOrNull("other-custom")).isNull()
    }

    @Test
    fun `invalidate re-reads settings and adds other-custom on next read`() {
        val seed = seedBytes()
        var baseUrl = ""
        var modelId = ""
        val store = mockk<AppSettingsStore>(relaxed = true)
        every { store.load() } answers { defaultSettings(otherBaseUrl = baseUrl, otherModelId = modelId) }
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seed),
            settingsStore = store,
            discoveryCache = mockk(relaxed = true),
        )

        assertThat(repo.catalog.value.resolveOrNull("other-custom")).isNull()

        baseUrl = "https://api.example.com/v1"
        modelId = "vendor/model"
        repo.invalidate()

        val entry = repo.catalog.value.resolveOrNull("other-custom")
        assertThat(entry).isNotNull()
        assertThat(entry!!.baseUrl).isEqualTo("https://api.example.com/v1")
        assertThat(entry.modelId).isEqualTo("vendor/model")
    }

    @Test
    fun `no other-custom entry when otherBaseUrl fails validation`() {
        // OtherBaseUrlValidator rejects non-http(s); see HIGH #2 in the Sub 1c review.
        // synth must refuse to materialize so ensureRequiredCredentials reports a clean
        // MissingCredential(OTHER) instead of leaking through to the client builder.
        val store = fakeSettingsStore(otherBaseUrl = "not-a-url", otherModelId = "vendor/model")
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = mockk(relaxed = true),
        )

        assertThat(repo.catalog.value.resolveOrNull("other-custom")).isNull()
    }

    @Test
    fun `synth entry stores normalized URL with trailing slash trimmed`() {
        val store = fakeSettingsStore(
            otherBaseUrl = "https://api.example.com/v1/",
            otherModelId = "vendor/model",
        )
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = mockk(relaxed = true),
        )

        val entry = repo.catalog.value.resolveOrNull("other-custom")
        assertThat(entry).isNotNull()
        assertThat(entry!!.baseUrl).isEqualTo("https://api.example.com/v1")
    }

    // ── Discovery integration ────────────────────────────────────────────

    @Test
    fun `refresh writes cache, emits new catalog containing discovered entries`() = runTest {
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val openRouterBase = LLMProvider.OPENROUTER.defaultBaseUrl!!
        val store = fakeSettingsStore(otherBaseUrl = "", otherModelId = "")
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = cache,
            discoverFn = { provider, base, _ ->
                assertThat(provider).isEqualTo(LLMProvider.OPENROUTER)
                assertThat(base).isEqualTo(openRouterBase)
                listOf(
                    DiscoveredModel(
                        entry = ModelEntry(
                            name = "openrouter:anthropic/claude-opus-4.7",
                            displayName = "Claude Opus 4.7",
                            provider = LLMProvider.OPENROUTER,
                            api = ApiType.CHAT,
                            modelId = "anthropic/claude-opus-4.7",
                            contextWindow = 200_000,
                            baseUrl = openRouterBase,
                        ),
                        created = 1_700_000_000L,
                    )
                )
            },
            clock = { 9_999L },
        )

        repo.refresh(LLMProvider.OPENROUTER, "sk-fake", openRouterBase)
        val after = repo.catalog.value
        assertThat(after.resolveOrNull("openrouter:anthropic/claude-opus-4.7")).isNotNull()
        assertThat(repo.discoveryState.value.lastFetchedAt[LLMProvider.OPENROUTER]).isEqualTo(9_999L)
        assertThat(repo.discoveryState.value.refreshing).isEmpty()
    }

    @Test
    fun `refresh failure surfaces error in discoveryState and leaves cache untouched`() = runTest {
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val store = fakeSettingsStore(otherBaseUrl = "", otherModelId = "")
        val openRouterBase = LLMProvider.OPENROUTER.defaultBaseUrl!!
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = cache,
            discoverFn = { _, _, _ -> throw java.io.IOException("HTTP 503") },
        )

        repo.refresh(LLMProvider.OPENROUTER, "sk-fake", openRouterBase)

        val state = repo.discoveryState.value
        assertThat(state.lastError[LLMProvider.OPENROUTER]).contains("503")
        assertThat(state.refreshing).isEmpty()
        // Cache is empty — no successful refresh happened.
        assertThat(cache.readAll()).isEmpty()
    }

    @Test
    fun `OTHER refresh rejected when baseUrl invalid`() = runTest {
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val store = fakeSettingsStore(otherBaseUrl = "", otherModelId = "")
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = cache,
            discoverFn = { _, _, _ -> error("should not be called") },
        )

        repo.refresh(LLMProvider.OTHER, "sk-fake", baseUrl = "not-a-url")

        assertThat(repo.discoveryState.value.lastError[LLMProvider.OTHER]).isNotNull()
    }

    @Test
    fun `refresh uses caller-provided baseUrl, NOT persisted settings (race fix)`() = runTest {
        // Codex review CRITICAL #1: persisted A, live B; refresh must hit B.
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val staleUrlA = "https://api-a.example.com/v1"
        val freshUrlB = "https://api-b.example.com/v1"
        val store = mockk<AppSettingsStore>(relaxed = true)
        // Settings store has the OLD URL (debounce hasn't committed B yet).
        every { store.load() } returns defaultSettings(otherBaseUrl = staleUrlA, otherModelId = "")
        var discoverHit: String? = null
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = cache,
            discoverFn = { _, base, _ -> discoverHit = base; emptyList() },
        )

        repo.refresh(LLMProvider.OTHER, "sk-fake", baseUrl = freshUrlB)

        assertThat(discoverHit).isEqualTo(freshUrlB)
    }

    @Test
    fun `stale refresh completion is dropped when effective baseUrl moved on`() = runTest {
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val urlA = "https://api-a.example.com/v1"
        val urlB = "https://api-b.example.com/v1"
        var currentUrl = urlA
        val store = mockk<AppSettingsStore>(relaxed = true)
        every { store.load() } answers { defaultSettings(otherBaseUrl = currentUrl, otherModelId = "") }
        var discoverHit: String? = null
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = cache,
            discoverFn = { _, base, _ ->
                discoverHit = base
                // Simulate the user switching URL while we're "in flight".
                currentUrl = urlB
                listOf(
                    DiscoveredModel(
                        entry = ModelEntry(
                            name = "other:vendor/from-a",
                            displayName = "From A",
                            provider = LLMProvider.OTHER,
                            api = ApiType.CHAT,
                            modelId = "vendor/from-a",
                            contextWindow = 128_000,
                            baseUrl = urlA,
                        ),
                        created = 0L,
                    )
                )
            },
        )

        repo.refresh(LLMProvider.OTHER, "sk-fake", baseUrl = urlA)

        // discover() was called with the captured URL...
        assertThat(discoverHit).isEqualTo(urlA)
        // ...but because the effective URL flipped to B mid-fetch, the result was dropped.
        assertThat(cache.readAll()).isEmpty()
        assertThat(repo.discoveryState.value.lastFetchedAt[LLMProvider.OTHER]).isNull()
        assertThat(repo.discoveryState.value.refreshing).isEmpty()
    }

    @Test
    fun `discovered entries scoped to current effective OTHER baseUrl`() = runTest {
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val urlA = "https://a.example.com/v1"
        val urlB = "https://b.example.com/v1"
        var otherUrl = urlA
        val store = mockk<AppSettingsStore>(relaxed = true)
        every { store.load() } answers { defaultSettings(otherBaseUrl = otherUrl, otherModelId = "vendor/manual") }
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = cache,
            discoverFn = { provider, base, _ ->
                listOf(
                    DiscoveredModel(
                        entry = ModelEntry(
                            name = "${provider.name.lowercase()}:vendor/from-$base",
                            displayName = "From $base",
                            provider = provider,
                            api = ApiType.CHAT,
                            modelId = "vendor/from-$base",
                            contextWindow = 128_000,
                            baseUrl = base,
                        ),
                        created = 0L,
                    )
                )
            },
        )

        repo.refresh(LLMProvider.OTHER, "sk-fake", baseUrl = urlA)
        // After refresh against A, the catalog shows the A entry.
        assertThat(repo.catalog.value.names()).contains("other:vendor/from-$urlA")

        // User flips baseUrl to B (without refresh). Catalog must hide A entries.
        otherUrl = urlB
        repo.invalidate()
        val names = repo.catalog.value.names()
        assertThat(names).doesNotContain("other:vendor/from-$urlA")
    }

    private fun realContextForCache(): Context {
        // Use a real filesDir for cache so write/read round-trips work.
        val tmp = java.nio.file.Files.createTempDirectory("repo-test-cache").toFile()
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.filesDir } returns tmp
        return ctx
    }

    // ── Fixtures ──

    private fun seedBytes(): ByteArray = seedAssetFile.readBytes()

    private fun defaultSettings(
        otherBaseUrl: String = "",
        otherModelId: String = "",
    ): AppSettings = AppSettings(
        selectedModel = AppSettingsStore.DEFAULT_MODEL,
        debugMode = AppSettingsStore.DEFAULT_DEBUG_MODE,
        perceptionMode = AppSettingsStore.DEFAULT_PERCEPTION_MODE,
        llmBackend = AppSettingsStore.DEFAULT_LLM_BACKEND,
        localModel = AVAILABLE_LOCAL_MODELS.first(),
        platformMode = AppSettingsStore.DEFAULT_PLATFORM_MODE,
        traceEnabled = AppSettingsStore.DEFAULT_TRACE_ENABLED,
        browserScriptEnabled = AppSettingsStore.DEFAULT_BROWSER_SCRIPT_ENABLED,
        termuxShellEnabled = AppSettingsStore.DEFAULT_TERMUX_SHELL_ENABLED,
        openaiBaseUrl = "",
        otherBaseUrl = otherBaseUrl,
        otherModelId = otherModelId,
    )

    private fun fakeSettingsStore(
        otherBaseUrl: String,
        otherModelId: String,
    ): AppSettingsStore {
        val store = mockk<AppSettingsStore>(relaxed = true)
        every { store.load() } returns defaultSettings(otherBaseUrl, otherModelId)
        return store
    }

    private fun contextReturningAsset(bytes: ByteArray): Context {
        val assets = mockk<AssetManager>()
        every { assets.open("llm_models.json") } answers { ByteArrayInputStream(bytes) }
        return contextWith(assets)
    }

    private fun contextReturningSequentialAssets(vararg payloads: ByteArray): Context {
        val assets = mockk<AssetManager>()
        var idx = 0
        every { assets.open("llm_models.json") } answers {
            val payload = payloads[idx.coerceAtMost(payloads.lastIndex)]
            idx++
            ByteArrayInputStream(payload)
        }
        return contextWith(assets)
    }

    private fun contextWith(assets: AssetManager): Context {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.assets } returns assets
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        // Real filesDir so ModelDiscoveryCache constructor can build its File path.
        every { context.filesDir } returns
            java.nio.file.Files.createTempDirectory("repo-test-ctx").toFile()
        return context
    }
}
