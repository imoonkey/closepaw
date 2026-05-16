package ai.closepaw.session

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionLlmConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
                                context = mockk(relaxed = true),
                                authStore = null
                        )
                    }
            assertThat(error.message).contains("main thread")
        } finally {
            unmockkStatic(Looper::class)
        }
    }
}
