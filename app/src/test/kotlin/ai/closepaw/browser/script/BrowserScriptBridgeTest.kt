package ai.closepaw.browser.script

import ai.closepaw.browser.cdp.ChromeCdpClient
import ai.closepaw.browser.cdp.FakeCdpConnection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.Collections

class BrowserScriptBridgeTest {

    @Test
    fun `cdp round-trip resolves with raw CDP result`() = runTest {
        val h = createBridge(this)

        h.bridge.handleSend("""{"id":1,"method":"Target.getTargets","params":{},"options":{}}""")
        advanceUntilIdle()

        assertThat(h.fake.sent).hasSize(1)
        assertThat(h.fake.sent[0]["method"]!!.jsonPrimitive.content).isEqualTo("Target.getTargets")
        assertThat(h.evaluations).hasSize(1)
        val js = h.evaluations[0]
        assertThat(js).startsWith("globalThis.__cdpResolve(1,")
        assertThat(js).contains("\"echo\":\"Target.getTargets\"")
    }

    @Test
    fun `cdp protocol error rejects promise with code name and message`() = runTest {
        val h = createBridge(this)
        h.fake.responder = { req ->
            buildJsonObject {
                put("id", req.id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", -32601)
                        put("message", "Method not found")
                    },
                )
            }
        }

        h.bridge.handleSend("""{"id":7,"method":"Target.bogus","params":{},"options":{}}""")
        advanceUntilIdle()

        assertThat(h.evaluations).hasSize(1)
        val js = h.evaluations[0]
        assertThat(js).startsWith("globalThis.__cdpReject(7,")
        assertThat(js).contains("\"message\":\"Method not found\"")
        assertThat(js).contains("\"code\":-32601")
        assertThat(js).contains("\"name\":\"CdpException\"")
    }

    @Test
    fun `concurrent requests routed back to correct promise by id`() = runTest {
        val h = createBridge(this)
        h.fake.responder = { _ -> null }

        h.bridge.handleSend("""{"id":1,"method":"Target.getTargets","params":{},"options":{}}""")
        h.bridge.handleSend("""{"id":2,"method":"Browser.getVersion","params":{},"options":{}}""")
        runCurrent()

        h.fake.injectResponse(2, buildJsonObject { put("name", "Chrome") })
        h.fake.injectResponse(1, buildJsonObject { put("targets", buildJsonObject {}) })
        runCurrent()

        assertThat(h.evaluations).hasSize(2)
        // Resolution order follows completion order (2 first)
        assertThat(h.evaluations[0]).startsWith("globalThis.__cdpResolve(2,")
        assertThat(h.evaluations[0]).contains("\"name\":\"Chrome\"")
        assertThat(h.evaluations[1]).startsWith("globalThis.__cdpResolve(1,")
        assertThat(h.evaluations[1]).contains("\"targets\"")
    }

    @Test
    fun `cdp options sessionId is propagated to client`() = runTest {
        val h = createBridge(this)

        h.bridge.handleSend(
            """{"id":3,"method":"Page.navigate","params":{"url":"https://x.test"},"options":{"sessionId":"my-sess"}}"""
        )
        advanceUntilIdle()

        assertThat(h.fake.sent).hasSize(1)
        assertThat(h.fake.sent[0]["sessionId"]!!.jsonPrimitive.content).isEqualTo("my-sess")
    }

    @Test
    fun `done with ok true completes result with raw value`() = runTest {
        val h = createBridge(this)
        h.bridge.handleDone("""{"ok":true,"result":{"title":"Example"}}""")

        val r = h.bridge.awaitResult()
        assertThat(r).isInstanceOf(ScriptResult.Ok::class.java)
        assertThat((r as ScriptResult.Ok).resultJson).contains("\"title\":\"Example\"")
    }

    @Test
    fun `done with ok true and null result is preserved as JSON null`() = runTest {
        val h = createBridge(this)
        h.bridge.handleDone("""{"ok":true,"result":null}""")

        val r = h.bridge.awaitResult() as ScriptResult.Ok
        assertThat(r.resultJson).isEqualTo("null")
    }

    @Test
    fun `done with ok false produces failure result with message and stack`() = runTest {
        val h = createBridge(this)
        h.bridge.handleDone("""{"ok":false,"error":{"message":"boom","stack":"at line 1"}}""")

        val r = h.bridge.awaitResult() as ScriptResult.Failure
        assertThat(r.message).isEqualTo("boom")
        assertThat(r.stack).isEqualTo("at line 1")
    }

    @Test
    fun `cancelPending cancels in-flight cdp jobs and emits Cancelled result`() = runTest {
        val h = createBridge(this)
        h.fake.responder = { _ -> null }

        h.bridge.handleSend("""{"id":1,"method":"Page.navigate","params":{},"options":{"sessionId":"s1"}}""")
        // Advance just enough to register the in-flight CDP request without hitting the
        // per-command timeout cap (which would otherwise convert this test from "cancelled
        // by user" to "timed out by client" and call back into JS via __cdpReject).
        runCurrent()

        h.bridge.cancelPending("user-cancelled")
        runCurrent()

        val r = h.bridge.awaitResult() as ScriptResult.Cancelled
        assertThat(r.reason).contains("user-cancelled")
        // Cancelled jobs do not call back into JS
        assertThat(h.evaluations).isEmpty()
    }

    @Test
    fun `completeWithTimeout returns Timeout result and prevents further sends`() = runTest {
        val h = createBridge(this)
        h.bridge.completeWithTimeout(123L)

        val r = h.bridge.awaitResult() as ScriptResult.Timeout
        assertThat(r.timeoutMs).isEqualTo(123L)

        // After timeout, new sends are ignored
        h.bridge.handleSend("""{"id":99,"method":"Target.getTargets","params":{},"options":{}}""")
        advanceUntilIdle()
        assertThat(h.fake.sent).isEmpty()
    }

    @Test
    fun `late callbacks ignored after done resolves the bridge`() = runTest {
        val h = createBridge(this)
        h.bridge.handleDone("""{"ok":true,"result":null}""")
        advanceUntilIdle()

        h.bridge.handleSend("""{"id":99,"method":"Target.getTargets","params":{},"options":{}}""")
        advanceUntilIdle()

        assertThat(h.fake.sent).isEmpty()
        assertThat(h.evaluations).isEmpty()
        assertThat(h.bridge.isTerminated).isTrue()
    }

    @Test
    fun `malformed send with id rejects the JS promise instead of hanging`() = runTest {
        val h = createBridge(this)

        // id present but method missing → must reject so the JS Promise does not hang
        h.bridge.handleSend("""{"id":42,"missing_method":true}""")
        advanceUntilIdle()

        assertThat(h.fake.sent).isEmpty()
        assertThat(h.evaluations).hasSize(1)
        val js = h.evaluations[0]
        assertThat(js).startsWith("globalThis.__cdpReject(42,")
        assertThat(js).contains("Malformed cdp() request")
        assertThat(h.bridge.isTerminated).isFalse()
    }

    @Test
    fun `malformed send without id is dropped silently`() = runTest {
        val h = createBridge(this)

        h.bridge.handleSend("not json")
        h.bridge.handleSend("""{"missing_id":true}""")
        advanceUntilIdle()

        assertThat(h.fake.sent).isEmpty()
        assertThat(h.evaluations).isEmpty()
        assertThat(h.bridge.isTerminated).isFalse()
    }

    @Test
    fun `non-object params are rejected, not silently coerced`() = runTest {
        val h = createBridge(this)

        // params is a string — must reject, not submit empty params for the wrong method
        h.bridge.handleSend("""{"id":17,"method":"Target.getTargets","params":"oops","options":{}}""")
        advanceUntilIdle()

        assertThat(h.fake.sent).isEmpty()
        assertThat(h.evaluations).hasSize(1)
        val js = h.evaluations[0]
        assertThat(js).startsWith("globalThis.__cdpReject(17,")
        assertThat(js).contains("params must be a JSON object")
    }

    @Test
    fun `non-object options are rejected, not silently coerced`() = runTest {
        val h = createBridge(this)

        // options is a number — must reject, not silently drop sessionId/targetId routing
        h.bridge.handleSend("""{"id":18,"method":"Target.getTargets","params":{},"options":7}""")
        advanceUntilIdle()

        assertThat(h.fake.sent).isEmpty()
        assertThat(h.evaluations).hasSize(1)
        val js = h.evaluations[0]
        assertThat(js).startsWith("globalThis.__cdpReject(18,")
        assertThat(js).contains("options must be a JSON object")
    }

    @Test
    fun `JSON null params and options are tolerated as empty objects`() = runTest {
        val h = createBridge(this)

        h.bridge.handleSend("""{"id":19,"method":"Target.getTargets","params":null,"options":null}""")
        advanceUntilIdle()

        assertThat(h.fake.sent).hasSize(1)
        assertThat(h.evaluations).hasSize(1)
        assertThat(h.evaluations[0]).startsWith("globalThis.__cdpResolve(19,")
    }

    @Test
    fun `reject script escapes error message safely as JS string`() {
        val js = rejectScript(5, "weird \"quote\" and \\ backslash")
        assertThat(js).startsWith("globalThis.__cdpReject(5,")
        assertThat(js).contains("\\\"quote\\\"")
        assertThat(js).contains("\\\\ backslash")
    }

    @Test
    fun `reject script embeds optional code and name fields`() {
        val js = rejectScript(9, "boom", code = -32000, name = "CdpException")
        assertThat(js).startsWith("globalThis.__cdpReject(9,")
        assertThat(js).contains("\"message\":\"boom\"")
        assertThat(js).contains("\"code\":-32000")
        assertThat(js).contains("\"name\":\"CdpException\"")
    }

    @Test
    fun `reject script embeds optional cause as nested name + message`() {
        val js = rejectScript(
            id = 10,
            message = "outer failure",
            code = -32001,
            name = "CdpException",
            cause = "IOException" to "socket closed",
        )
        assertThat(js).contains("\"cause\":{\"name\":\"IOException\",\"message\":\"socket closed\"}")
    }

    @Test
    fun `resolve script embeds raw CDP result JsonElement literal including null`() {
        val obj = resolveScript(11, buildJsonObject { put("foo", "bar") })
        assertThat(obj).isEqualTo("globalThis.__cdpResolve(11, {\"foo\":\"bar\"});")
        // JsonNull is a valid JsonElement → must be embedded as JS null, not coerced to "{}"
        val nullJs = resolveScript(12, kotlinx.serialization.json.JsonNull)
        assertThat(nullJs).isEqualTo("globalThis.__cdpResolve(12, null);")
    }

    private data class Harness(
        val bridge: BrowserScriptBridge,
        val fake: FakeCdpConnection,
        val evaluations: MutableList<String>,
    )

    private suspend fun createBridge(scope: TestScope): Harness {
        val fake = FakeCdpConnection()
        val client = ChromeCdpClient(fake.factory(), commandTimeoutMs = 60_000)
        client.connect("ws://test")
        val evaluations: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val evaluator = JsEvaluator { js -> evaluations.add(js) }
        val bridge = BrowserScriptBridge(client, evaluator, scope)
        return Harness(bridge, fake, evaluations)
    }
}
