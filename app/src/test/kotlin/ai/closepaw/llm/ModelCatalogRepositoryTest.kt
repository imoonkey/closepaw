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
        // mockkConstructor so the holder's lazy build of AppSettingsStore in `get()` doesn't
        // touch real SharedPreferences (mock Context can't satisfy it otherwise).
        mockkConstructor(AppSettingsStore::class)
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

    // ── Fixtures ──

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
        return context
    }
}
