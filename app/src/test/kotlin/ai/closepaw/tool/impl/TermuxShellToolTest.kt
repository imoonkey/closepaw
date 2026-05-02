package ai.closepaw.tool.impl

import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class TermuxShellToolTest {

    @Test
    fun `missing command returns invalid request failure`() = runTest {
        val result = TermuxShellTool()
            .createInvocation(JSONObject())
            .execute(testContext())

        assertThat(failureReason(result)).isEqualTo("invalid_request")
    }

    @Test
    fun `timeout above maximum is clamped in bridge request`() = runTest {
        withServer { server ->
            server.enqueue(jsonResponse(200, """{"exit_code":0,"stdout":"ok\n"}"""))

            val result = execute(server, JSONObject()
                .put("command", "echo ok")
                .put("timeout_seconds", 999))

            // Current implementation accepts values above the schema maximum and clamps
            // timeout_ms sent to the bridge to 120 seconds.
            assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
            val requestJson = JSONObject(server.takeRequest().body.readUtf8())
            assertThat(requestJson.getLong("timeout_ms")).isEqualTo(120_000L)
        }
    }

    @Test
    fun `http 200 exit zero maps to success with stdout`() = runTest {
        withServer { server ->
            server.enqueue(jsonResponse(200, """{"exit_code":0,"stdout":"hi\n"}"""))

            val output = successJson(execute(server, JSONObject().put("command", "printf hi")))

            assertThat(output.getInt("exit_code")).isEqualTo(0)
            assertThat(output.getString("stdout")).isEqualTo("hi\n")
        }
    }

    @Test
    fun `http 200 non-zero exit code is still success`() = runTest {
        withServer { server ->
            server.enqueue(jsonResponse(200, """{"exit_code":7,"stdout":"","stderr":"bad"}"""))

            val result = execute(server, JSONObject().put("command", "exit 7"))

            assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
            assertThat(successJson(result).getInt("exit_code")).isEqualTo(7)
        }
    }

    @Test
    fun `http 200 timed out maps to success with null exit code`() = runTest {
        withServer { server ->
            server.enqueue(jsonResponse(200, """{"timed_out":true,"stdout":"","stderr":""}"""))

            val output = successJson(execute(server, JSONObject().put("command", "sleep 999")))

            assertThat(output.getBoolean("timed_out")).isTrue()
            assertThat(output.isNull("exit_code")).isTrue()
        }
    }

    @Test
    fun `http 409 maps to bridge busy failure`() = runTest {
        withServer { server ->
            server.enqueue(jsonResponse(409, """{"error":"busy"}"""))

            val result = execute(server, JSONObject().put("command", "echo hi"))

            assertThat(failureReason(result)).isEqualTo("bridge_busy")
        }
    }

    @Test
    fun `http 400 workspace escape maps to workspace escape failure`() = runTest {
        withServer { server ->
            server.enqueue(jsonResponse(400, """{"error":"workspace_escape"}"""))

            val result = execute(server, JSONObject().put("command", "pwd"))

            assertThat(failureReason(result)).isEqualTo("workspace_escape")
        }
    }

    @Test
    fun `connection refused maps to bridge unavailable failure`() = runTest {
        val deadPort = ServerSocket(0).use { it.localPort }
        val tool = TermuxShellTool(bridgeBaseUrl = "http://127.0.0.1:$deadPort")

        val result = tool.createInvocation(JSONObject().put("command", "echo hi"))
            .execute(testContext())

        assertThat(failureReason(result)).isEqualTo("bridge_unavailable")
    }

    @Test
    fun `cancelling coroutine while bridge call is pending throws promptly`() = runTest {
        withServer { server ->
            server.enqueue(
                jsonResponse(200, """{"exit_code":0,"stdout":"late"}""")
                    .setHeadersDelay(5, TimeUnit.SECONDS),
            )
            val invocation = tool(server)
                .createInvocation(JSONObject().put("command", "sleep 5"))

            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                invocation.execute(testContext())
            }
            assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNotNull()

            deferred.cancel()
            try {
                withTimeout(1_000) { deferred.await() }
                error("Expected cancellation")
            } catch (_: CancellationException) {
                // The suspended OkHttp await is cancellation-aware and does not wait for the response.
            }
        }
    }

    @Test
    fun `okhttp call timeout includes bridge timeout plus grace`() = runTest {
        withServer { server ->
            var observedCallTimeoutNanos = -1L
            val client = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    observedCallTimeoutNanos = chain.call().timeout().timeoutNanos()
                    chain.proceed(chain.request())
                })
                .build()
            server.enqueue(jsonResponse(200, """{"exit_code":0,"stdout":"ok\n"}"""))

            execute(
                server = server,
                params = JSONObject().put("command", "echo ok").put("timeout_seconds", 10),
                httpClient = client,
            )

            assertThat(observedCallTimeoutNanos)
                .isEqualTo(TimeUnit.MILLISECONDS.toNanos(15_000L))
        }
    }

    private suspend fun execute(
        server: MockWebServer,
        params: JSONObject,
        httpClient: OkHttpClient = OkHttpClient(),
    ): ToolExecutionResult =
        tool(server, httpClient).createInvocation(params).execute(testContext())

    private fun tool(
        server: MockWebServer,
        httpClient: OkHttpClient = OkHttpClient(),
    ): TermuxShellTool =
        TermuxShellTool(
            httpClient = httpClient,
            bridgeBaseUrl = server.url("/").toString(),
        )

    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer().apply { start() }
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun successJson(result: ToolExecutionResult): JSONObject {
        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        return JSONObject((result as ToolExecutionResult.Success).output)
    }

    private fun failureReason(result: ToolExecutionResult): String {
        assertThat(result).isInstanceOf(ToolExecutionResult.Failure::class.java)
        return JSONObject((result as ToolExecutionResult.Failure).error).getString("reason")
    }

    private fun testContext(): ToolExecutionContext =
        object : ToolExecutionContext {
            override val platform = FakeAndroidPlatform()
            override val currentSnapshot = null
            override fun isCancelled(): Boolean = false
        }
}
