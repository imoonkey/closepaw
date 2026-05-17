package ai.closepaw.session

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.MissingCredential
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ModelCatalogRepository
import ai.closepaw.llm.ModelDiscoveryCache
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionLlmConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionLlmBootstrapperTest {

    @Test
    fun `create throws when called on main thread`() {
        val mainLooper = mockk<Looper>()
        mockkStatic(Looper::class)
        try {
            every { Looper.getMainLooper() } returns mainLooper
            every { Looper.myLooper() } returns mainLooper

            // Fixture repo — relaxed mock so we don't need real assets; the main-thread guard
            // throws before the catalog is read so the StateFlow stub is never touched.
            val repo = mockk<ModelCatalogRepository>(relaxed = true)

            val error =
                    assertThrows(IllegalStateException::class.java) {
                        SessionLlmBootstrapper.create(
                                config =
                                        SessionConfig(
                                                llm =
                                                        SessionLlmConfig(
                                                                backendType =
                                                                        LLMBackendType.OPENAI
                                                        ),
                                                mainModel = "test"
                                        ),
                                catalogRepository = repo,
                                context = mockk(relaxed = true),
                                authStore = null
                        )
                    }
            assertThat(error.message).contains("main thread")
        } finally {
            unmockkStatic(Looper::class)
        }
    }

    @Test
    fun `create short-circuits other-custom to MissingCredential when synth entry absent`() {
        val mainLooper = mockk<Looper>()
        val workerLooper = mockk<Looper>()
        mockkStatic(Looper::class)
        try {
            every { Looper.getMainLooper() } returns mainLooper
            every { Looper.myLooper() } returns workerLooper

            // Catalog WITHOUT other-custom — mimics blank otherBaseUrl/otherModelId.
            val catalog = ModelCatalog.fromJson(
                """{ "gpt-5.2": {"display_name":"GPT","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"} }"""
            )
            val repo = mockk<ModelCatalogRepository>(relaxed = true)
            every { repo.catalog } returns MutableStateFlow(catalog)

            val authStore = mockk<AuthStore>(relaxed = true)
            every { authStore.has(LLMProvider.OTHER) } returns true

            val error = assertThrows(MissingCredential::class.java) {
                SessionLlmBootstrapper.create(
                    config = SessionConfig(
                        llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI),
                        mainModel = "other-custom",
                    ),
                    catalogRepository = repo,
                    context = mockk(relaxed = true),
                    authStore = authStore,
                )
            }
            assertThat(error.provider).isEqualTo(LLMProvider.OTHER)
        } finally {
            unmockkStatic(Looper::class)
        }
    }

    @Test
    fun `create short-circuits other-custom to MissingCredential when OTHER key absent`() {
        val mainLooper = mockk<Looper>()
        val workerLooper = mockk<Looper>()
        mockkStatic(Looper::class)
        try {
            every { Looper.getMainLooper() } returns mainLooper
            every { Looper.myLooper() } returns workerLooper

            // Catalog WITH other-custom synth entry but key not stored.
            val catalog = ModelCatalog.fromJson(
                """{ "other-custom": {"display_name":"Custom","provider":"OTHER","api":"chat","model_id":"vendor/model","base_url":"https://api.example.com/v1"} }"""
            )
            val repo = mockk<ModelCatalogRepository>(relaxed = true)
            every { repo.catalog } returns MutableStateFlow(catalog)

            val authStore = mockk<AuthStore>(relaxed = true)
            every { authStore.has(LLMProvider.OTHER) } returns false

            val error = assertThrows(MissingCredential::class.java) {
                SessionLlmBootstrapper.create(
                    config = SessionConfig(
                        llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI),
                        mainModel = "other-custom",
                    ),
                    catalogRepository = repo,
                    context = mockk(relaxed = true),
                    authStore = authStore,
                )
            }
            assertThat(error.provider).isEqualTo(LLMProvider.OTHER)
        } finally {
            unmockkStatic(Looper::class)
        }
    }

    @Test
    fun `create surfaces invalid-baseUrl OTHER as MissingCredential not as client error`() {
        // Regression for Sub 1c Codex HIGH #2: parity between findMissingCloudKeys (which
        // validates baseUrl) and ensureRequiredCredentials (which used to only check catalog
        // membership + auth key). Invalid persisted URL → repo refuses to synth → bootstrap
        // throws MissingCredential(OTHER), surfacing as a clean Other-tab deep-link.
        val mainLooper = mockk<Looper>()
        val workerLooper = mockk<Looper>()
        mockkStatic(Looper::class)
        try {
            every { Looper.getMainLooper() } returns mainLooper
            every { Looper.myLooper() } returns workerLooper

            // Fixture repo that mirrors the production wiring: invalid URL → no synth.
            val store = mockk<ai.closepaw.app.AppSettingsStore>(relaxed = true)
            every { store.load() } returns ai.closepaw.app.AppSettings(
                selectedModel = "other-custom",
                debugMode = false,
                perceptionMode = "accessibility_only",
                llmBackend = LLMBackendType.OPENAI,
                localModel = ai.closepaw.ui.settings.AVAILABLE_LOCAL_MODELS.first(),
                platformMode = ai.closepaw.protocol.PlatformMode.ACCESSIBILITY,
                traceEnabled = false,
                browserScriptEnabled = false,
                termuxShellEnabled = false,
                openaiBaseUrl = "",
                otherBaseUrl = "not a url",          // <- invalid
                otherModelId = "vendor/model",
            )
            val context = mockk<android.content.Context>(relaxed = true)
            every { context.applicationContext } returns context
            val assets = mockk<android.content.res.AssetManager>(relaxed = true)
            every { assets.open("llm_models.json") } answers {
                // Non-empty seed so ModelCatalog.fromJson() does not refuse to construct.
                // The test only checks `other-custom` is absent because of the invalid URL.
                java.io.ByteArrayInputStream(
                    """{ "glm-5": {"display_name":"GLM-5","provider":"OPENROUTER","api":"chat","model_id":"z-ai/glm-5"} }""".toByteArray()
                )
            }
            every { context.assets } returns assets
            val realRepo = ModelCatalogRepository(
                context = context,
                settingsStore = store,
                discoveryCache = ModelDiscoveryCache(context),
            )
            assertThat(realRepo.catalog.value.resolveOrNull("other-custom")).isNull()

            val authStore = mockk<AuthStore>(relaxed = true)
            every { authStore.has(LLMProvider.OTHER) } returns true

            val error = assertThrows(MissingCredential::class.java) {
                SessionLlmBootstrapper.create(
                    config = SessionConfig(
                        llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI),
                        mainModel = "other-custom",
                    ),
                    catalogRepository = realRepo,
                    context = mockk(relaxed = true),
                    authStore = authStore,
                )
            }
            assertThat(error.provider).isEqualTo(LLMProvider.OTHER)
        } finally {
            unmockkStatic(Looper::class)
        }
    }
}
