package ai.closepaw.session

import android.content.Context
import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.FakeSharedPreferences
import ai.closepaw.auth.MissingCredential
import ai.closepaw.llm.ChatCompletionClient
import ai.closepaw.llm.LLMProvider
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionLlmConfig
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.trace.NoopTraceRecorder
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionServicesProviderRoutingTest {

  @get:Rule
  val tempDir = TemporaryFolder()

  @Test
  fun `openrouter model works without openai key`() {
    val context = contextWithCatalog()
    val authStore = AuthStore(context, prefsProvider = { FakeSharedPreferences() })
    runBlocking {
      authStore.set(LLMProvider.OPENROUTER, AuthCredential.ApiKey("sk-or-test"))
    }
    val config =
            SessionConfig(
                    llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI),
                    mainModel = "glm-4.7"
            )
    val services =
            SessionServices.create(
                    config = config,
                    platform = FakeAndroidPlatform(),
                    authStore = authStore,
                    context = context,
                    scope =
                            kotlinx.coroutines.CoroutineScope(
                                    kotlinx.coroutines.Dispatchers.Unconfined
                            ),
                    traceRecorder = NoopTraceRecorder
            )

    assertThat(services.llmClient).isInstanceOf(ChatCompletionClient::class.java)
  }

  @Test
  fun `subagent model requires its provider credential`() {
    val context = contextWithCatalog()
    val authStore = AuthStore(context, prefsProvider = { FakeSharedPreferences() })
    runBlocking {
      authStore.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("sk-openai-test"))
    }
    val config =
            SessionConfig(
                    llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI),
                    mainModel = "gpt-5.2",
                    subagentModel = "autoglm-phone-9b-multilingual"
            )

    val error =
            assertThrows(MissingCredential::class.java) {
              SessionServices.create(
                      config = config,
                      platform = FakeAndroidPlatform(),
                      authStore = authStore,
                      context = context,
                      scope =
                              kotlinx.coroutines.CoroutineScope(
                                      kotlinx.coroutines.Dispatchers.Unconfined
                              ),
                      traceRecorder = NoopTraceRecorder
              )
            }
    assertThat(error.provider).isEqualTo(LLMProvider.NOVITA)
  }

  private fun contextWithCatalog(): Context {
    val context = mockk<Context>(relaxed = true)
    val assets = mockk<AssetManager>()
    every { context.assets } returns assets
    every { context.filesDir } returns tempDir.newFolder("files")
    every { assets.list(any<String>()) } answers {
      val file = File("src/main/assets", firstArg<String>())
      if (file.isDirectory) file.list().orEmpty() else emptyArray()
    }
    every { assets.open(any<String>()) } answers {
      when (val path = firstArg<String>()) {
        "llm_models.json" -> ByteArrayInputStream(CATALOG_JSON.toByteArray())
        "security/app_tiers.json" -> ByteArrayInputStream(APP_TIERS_JSON.toByteArray())
        else -> File("src/main/assets", path).inputStream()
      }
    }
    return context
  }

  companion object {
    private val CATALOG_JSON =
            """
        {
          "gpt-5.2": {
            "display_name": "GPT-5.2",
            "provider":"OPENAI_API",
            "api": "response",
            "model_id": "gpt-5.2"
          },
          "glm-4.7": {
            "display_name": "GLM-4.7",
            "provider": "OPENROUTER",
            "api": "chat",
            "model_id": "z-ai/glm-4.7"
          },
          "autoglm-phone-9b-multilingual": {
            "display_name": "AutoGLM Phone 9B Multilingual",
            "provider": "NOVITA",
            "api": "chat",
            "model_id": "zai-org/autoglm-phone-9b-multilingual"
          }
        }
        """.trimIndent()

    private val APP_TIERS_JSON = """{"apps":{}}"""
  }
}
