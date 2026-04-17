package ai.closepaw.onboarding

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

class HttpLlmCredentialValidatorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun validator() =
        HttpLlmCredentialValidator(
            baseUrl = server.url("/v1").toString(),
            modelId = "test-model",
            connectTimeoutMs = 500,
            readTimeoutMs = 500,
        )

    @Test
    fun `HTTP 200 returns Valid`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[]}"""))
        val result = validator().validate("sk-good")
        assertThat(result).isEqualTo(LlmCredentialValidator.Result.Valid)
    }

    @Test
    fun `HTTP 401 returns InvalidKey`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = validator().validate("sk-bad")
        assertThat(result).isInstanceOf(LlmCredentialValidator.Result.InvalidKey::class.java)
    }

    @Test
    fun `HTTP 403 returns InvalidKey`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = validator().validate("sk-bad")
        assertThat(result).isInstanceOf(LlmCredentialValidator.Result.InvalidKey::class.java)
    }

    @Test
    fun `HTTP 429 returns TransientError not InvalidKey`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        val result = validator().validate("sk-x")
        assertThat(result).isInstanceOf(LlmCredentialValidator.Result.TransientError::class.java)
        assertThat(result).isNotInstanceOf(LlmCredentialValidator.Result.InvalidKey::class.java)
    }

    @Test
    fun `HTTP 500 returns TransientError not InvalidKey`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = validator().validate("sk-x")
        assertThat(result).isInstanceOf(LlmCredentialValidator.Result.TransientError::class.java)
        assertThat(result).isNotInstanceOf(LlmCredentialValidator.Result.InvalidKey::class.java)
    }

    @Test
    fun `HTTP 503 returns TransientError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = validator().validate("sk-x")
        assertThat(result).isInstanceOf(LlmCredentialValidator.Result.TransientError::class.java)
    }

    @Test
    fun `disconnect without response returns TransientError`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = validator().validate("sk-x")
        assertThat(result).isInstanceOf(LlmCredentialValidator.Result.TransientError::class.java)
        assertThat(result).isNotInstanceOf(LlmCredentialValidator.Result.InvalidKey::class.java)
    }

    @Test
    fun `connection refused returns TransientError`() = runTest {
        val deadPort = ServerSocket(0).use { it.localPort }
        val v = HttpLlmCredentialValidator("http://127.0.0.1:$deadPort/v1", "m")
        val result = v.validate("sk-x")
        assertThat(result).isInstanceOf(LlmCredentialValidator.Result.TransientError::class.java)
        assertThat(result).isNotInstanceOf(LlmCredentialValidator.Result.InvalidKey::class.java)
    }
}
