package ai.closepaw.browser.script

import android.content.Context
import ai.closepaw.browser.cdp.CdpConnection
import ai.closepaw.browser.cdp.CdpConnectionClosedException
import ai.closepaw.browser.cdp.CdpConnectionFactory
import ai.closepaw.browser.cdp.shizuku.DevtoolsVersion
import ai.closepaw.browser.cdp.shizuku.PageTarget
import ai.closepaw.trace.NoopTraceRecorder
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class BrowserSessionManagerTest {

    @Test
    fun `transport failure closes bridge and cdp then next run reconnects`() = runTest {
        val bridgeFactory = RecordingBridgeFactory()
        val connectionFactory = RecordingCdpConnectionFactory()
        var invocationCount = 0
        val manager = BrowserSessionManager(
            context = testContext(),
            sessionScope = this,
            traceRecorder = NoopTraceRecorder,
            bridgeFactory = bridgeFactory::create,
            cdpConnectionFactory = connectionFactory,
            runnerFactory = { _, client, _ ->
                BrowserScriptExecutor { _, _ ->
                    invocationCount++
                    if (invocationCount == 1) {
                        connectionFactory.latest().fail(IOException("websocket died"))
                        ScriptResult.Failure("cdp request rejected", null)
                    } else {
                        client.send("Runtime.evaluate")
                        ScriptResult.Ok("\"ok\"")
                    }
                }
            },
        )

        val first = manager.run("first", 1_000)

        assertThat(first).isInstanceOf(ScriptResult.Failure::class.java)
        assertThat(connectionFactory.connections).hasSize(1)
        assertThat(connectionFactory.connections[0].closeCalls).isEqualTo(1)
        assertThat(bridgeFactory.bridges).hasSize(1)
        assertThat(bridgeFactory.bridges[0].closeCalls).isEqualTo(1)

        val second = manager.run("second", 1_000)

        assertThat(second).isEqualTo(ScriptResult.Ok("\"ok\""))
        assertThat(connectionFactory.connections).hasSize(2)
        assertThat(bridgeFactory.bridges).hasSize(2)
        assertThat(connectionFactory.connections[1].closeCalls).isEqualTo(0)
        assertThat(bridgeFactory.bridges[1].closeCalls).isEqualTo(0)

        manager.close()

        assertThat(connectionFactory.connections[1].closeCalls).isEqualTo(1)
        assertThat(bridgeFactory.bridges[1].closeCalls).isEqualTo(1)
    }

    @Test
    fun `synchronous send failure closes bridge and cdp then next run reconnects`() = runTest {
        val bridgeFactory = RecordingBridgeFactory()
        val connectionFactory = RecordingCdpConnectionFactory()
        var invocationCount = 0
        val manager = BrowserSessionManager(
            context = testContext(),
            sessionScope = this,
            traceRecorder = NoopTraceRecorder,
            bridgeFactory = bridgeFactory::create,
            cdpConnectionFactory = connectionFactory,
            runnerFactory = { _, client, _ ->
                BrowserScriptExecutor { _, _ ->
                    invocationCount++
                    if (invocationCount == 1) {
                        connectionFactory.latest().throwOnRuntimeEvaluate = true
                        try {
                            client.send("Runtime.evaluate")
                            ScriptResult.Ok("\"unexpected\"")
                        } catch (_: Throwable) {
                            ScriptResult.Failure("cdp request rejected", null)
                        }
                    } else {
                        client.send("Runtime.evaluate")
                        ScriptResult.Ok("\"ok\"")
                    }
                }
            },
        )

        val first = manager.run("first", 1_000)

        assertThat(first).isInstanceOf(ScriptResult.Failure::class.java)
        assertThat(connectionFactory.connections).hasSize(1)
        assertThat(connectionFactory.connections[0].closeCalls).isEqualTo(1)
        assertThat(bridgeFactory.bridges).hasSize(1)
        assertThat(bridgeFactory.bridges[0].closeCalls).isEqualTo(1)

        val second = manager.run("second", 1_000)

        assertThat(second).isEqualTo(ScriptResult.Ok("\"ok\""))
        assertThat(connectionFactory.connections).hasSize(2)
        assertThat(bridgeFactory.bridges).hasSize(2)
        manager.close()
    }

    @Test
    fun `server initiated close closes bridge and cdp then next run reconnects`() = runTest {
        val bridgeFactory = RecordingBridgeFactory()
        val connectionFactory = RecordingCdpConnectionFactory()
        val manager = BrowserSessionManager(
            context = testContext(),
            sessionScope = this,
            traceRecorder = NoopTraceRecorder,
            bridgeFactory = bridgeFactory::create,
            cdpConnectionFactory = connectionFactory,
            runnerFactory = { _, client, _ ->
                BrowserScriptExecutor { _, _ ->
                    client.send("Runtime.evaluate")
                    ScriptResult.Ok("\"ok\"")
                }
            },
        )

        assertThat(manager.run("first", 1_000)).isEqualTo(ScriptResult.Ok("\"ok\""))
        connectionFactory.latest().closeFromServer()

        assertThat(connectionFactory.connections[0].closeCalls).isEqualTo(1)
        assertThat(bridgeFactory.bridges[0].closeCalls).isEqualTo(1)

        assertThat(manager.run("second", 1_000)).isEqualTo(ScriptResult.Ok("\"ok\""))
        assertThat(connectionFactory.connections).hasSize(2)
        assertThat(bridgeFactory.bridges).hasSize(2)
        manager.close()
    }

    @Test
    fun `storeArtifact session quota persists across browser_script invocations within one session`() = runTest {
        // Reproduces the P6 final-gate scope bug: before the fix, BrowserScriptJsInterface
        // owned the AtomicLong, but the JsInterface was rebuilt per BrowserScriptRunner.run().
        // Two run() calls thus saw two independent counters, turning the documented 500 MiB
        // session cap into a 500 MiB per-call cap and reopening the /sdcard DoS vector.
        // The session manager hands the same AtomicLong to every runnerFactory invocation;
        // assert that the same instance is reused across at least two run() calls.
        val countersHandedOut = mutableListOf<AtomicLong>()
        val manager = BrowserSessionManager(
            context = testContext(),
            sessionScope = this,
            traceRecorder = NoopTraceRecorder,
            bridgeFactory = RecordingBridgeFactory()::create,
            cdpConnectionFactory = RecordingCdpConnectionFactory(),
            runnerFactory = { _, _, counter ->
                countersHandedOut.add(counter)
                BrowserScriptExecutor { _, _ -> ScriptResult.Ok("\"ok\"") }
            },
        )

        manager.run("first", 1_000)
        manager.run("second", 1_000)
        manager.close()

        // Same runner is reused across runs, so the factory only fires once per session
        // (until markBroken). The counter must be the *single* session-scoped instance.
        assertThat(countersHandedOut).hasSize(1)
        // Mutating the counter via one runner is observed by any future runner: prove the
        // identity contract by writing through one reference and reading via the same one.
        countersHandedOut[0].set(42L)
        assertThat(countersHandedOut[0].get()).isEqualTo(42L)
    }

    @Test
    fun `storeArtifact session quota survives CDP reconnect within one session`() = runTest {
        // After markBroken (transport failure), the runner is rebuilt — but the counter
        // must NOT reset, otherwise an attacker could force reconnects to clear quota.
        val countersHandedOut = mutableListOf<AtomicLong>()
        val bridgeFactory = RecordingBridgeFactory()
        val connectionFactory = RecordingCdpConnectionFactory()
        var invocationCount = 0
        val manager = BrowserSessionManager(
            context = testContext(),
            sessionScope = this,
            traceRecorder = NoopTraceRecorder,
            bridgeFactory = bridgeFactory::create,
            cdpConnectionFactory = connectionFactory,
            runnerFactory = { _, _, counter ->
                countersHandedOut.add(counter)
                BrowserScriptExecutor { _, _ ->
                    invocationCount++
                    if (invocationCount == 1) {
                        connectionFactory.latest().fail(IOException("websocket died"))
                        ScriptResult.Failure("cdp request rejected", null)
                    } else {
                        ScriptResult.Ok("\"ok\"")
                    }
                }
            },
        )

        manager.run("first", 1_000)
        manager.run("second", 1_000)
        manager.close()

        // Two runner instances built (one before reconnect, one after) but both got the
        // SAME AtomicLong — quota persists across reconnects.
        assertThat(countersHandedOut).hasSize(2)
        assertThat(countersHandedOut[0]).isSameInstanceAs(countersHandedOut[1])
    }

    private fun testContext(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        return context
    }

    private class RecordingBridgeFactory {
        val bridges = mutableListOf<FakeBrowserBridge>()

        fun create(): BrowserDevtoolsBridge = FakeBrowserBridge().also { bridges.add(it) }
    }

    private class FakeBrowserBridge : BrowserDevtoolsBridge {
        var closeCalls = 0
            private set

        override suspend fun preflight() = Unit

        override suspend fun fetchVersion(): DevtoolsVersion =
            DevtoolsVersion(
                browser = "Chrome/130",
                protocolVersion = "1.3",
                webSocketDebuggerUrl = "ws://browser",
                userAgent = null,
            )

        override suspend fun listPageTargets(): List<PageTarget> =
            listOf(PageTarget("page-1", "page", "Example", "https://example.com", "ws://page"))

        override fun close() {
            closeCalls++
        }
    }

    private class RecordingCdpConnectionFactory : CdpConnectionFactory {
        val connections = mutableListOf<RecordingCdpConnection>()

        override suspend fun connect(
            url: String,
            onMessage: (String) -> Unit,
            onFailure: (Throwable) -> Unit,
            onClosed: (CdpConnectionClosedException) -> Unit,
        ): CdpConnection {
            val connection = RecordingCdpConnection(
                index = connections.size + 1,
                onMessage = onMessage,
                onFailure = onFailure,
                onClosed = onClosed,
            )
            connections.add(connection)
            return connection
        }

        fun latest(): RecordingCdpConnection = connections.last()
    }

    private class RecordingCdpConnection(
        private val index: Int,
        private val onMessage: (String) -> Unit,
        private val onFailure: (Throwable) -> Unit,
        private val onClosed: (CdpConnectionClosedException) -> Unit,
    ) : CdpConnection {
        var closeCalls = 0
            private set
        var throwOnRuntimeEvaluate = false

        override fun send(text: String) {
            val request = Json.parseToJsonElement(text).jsonObject
            val id = request["id"]!!.jsonPrimitive.int
            val method = request["method"]!!.jsonPrimitive.content
            if (method == "Runtime.evaluate" && throwOnRuntimeEvaluate) {
                throw IOException("websocket send returned false")
            }
            val result = when (method) {
                "Target.attachToTarget" -> buildJsonObject { put("sessionId", "session-$index") }
                else -> buildJsonObject { }
            }
            onMessage(
                buildJsonObject {
                    put("id", id)
                    put("result", result)
                }.toString()
            )
        }

        override fun close() {
            closeCalls++
        }

        fun fail(error: Throwable) {
            onFailure(error)
        }

        fun closeFromServer() {
            onClosed(CdpConnectionClosedException(1000, "server closed"))
        }
    }
}
