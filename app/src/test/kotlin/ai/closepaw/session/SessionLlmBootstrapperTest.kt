package ai.closepaw.session

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.MissingCredential
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ModelCatalogRepository
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
}
