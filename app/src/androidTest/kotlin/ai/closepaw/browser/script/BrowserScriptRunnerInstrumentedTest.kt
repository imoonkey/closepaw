package ai.closepaw.browser.script

import ai.closepaw.browser.cdp.CdpConnection
import ai.closepaw.browser.cdp.CdpConnectionClosedException
import ai.closepaw.browser.cdp.CdpConnectionFactory
import ai.closepaw.browser.cdp.ChromeCdpClient
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device prelude semantics test. This is the authoritative coverage for the JS prelude:
 * it loads the real prelude into a real Android WebView and exercises a [BrowserScriptRunner.run]
 * round-trip. Any change to [BrowserScriptPrelude.PRELUDE], the @JavascriptInterface bridge,
 * or [BrowserScriptRunner] should be validated by running:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.browser.script.BrowserScriptRunnerInstrumentedTest
 */
@RunWith(AndroidJUnit4::class)
class BrowserScriptRunnerInstrumentedTest {

    @Test
    fun cdp_round_trip_resolves_on_real_webview() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val fake = InlineCdpConnectionFactory()
        val cdpClient = ChromeCdpClient(fake)
        cdpClient.connect("ws://test")

        val runner = BrowserScriptRunner(ctx, cdpClient)
        val script = """
            const r = await cdp("Target.getTargets", {}, {});
            return r.targetInfos.length;
        """.trimIndent()

        val result = runner.run(script, timeoutMs = 10_000)

        assertTrue("expected Ok, got $result", result is ScriptResult.Ok)
        val ok = result as ScriptResult.Ok
        assertEquals("0", ok.resultJson)
    }

    /**
     * Minimal in-memory [CdpConnectionFactory] that synchronously responds to every command
     * with a canned-but-valid CDP envelope. Keeps the test free of network and Shizuku.
     */
    private class InlineCdpConnectionFactory : CdpConnectionFactory {
        override suspend fun connect(
            url: String,
            onMessage: (String) -> Unit,
            onFailure: (Throwable) -> Unit,
            onClosed: (CdpConnectionClosedException) -> Unit,
        ): CdpConnection = object : CdpConnection {
            override fun send(text: String) {
                val req = Json.parseToJsonElement(text).jsonObject
                val id = req["id"]!!.jsonPrimitive.int
                val method = req["method"]!!.jsonPrimitive.content
                val response = buildJsonObject {
                    put("id", id)
                    put(
                        "result",
                        when (method) {
                            "Target.getTargets" -> buildJsonObject {
                                put("targetInfos", kotlinx.serialization.json.buildJsonArray {})
                            }
                            else -> buildJsonObject { put("ok", true) }
                        },
                    )
                }
                onMessage(response.toString())
            }
            override fun close() {}
        }
    }
}
