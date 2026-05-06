package ai.closepaw.browser.script

import ai.closepaw.app.AppSettingsStore
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.AppInfo
import ai.closepaw.platform.DisplayInfo
import ai.closepaw.platform.UIAction
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.impl.BrowserScriptInvoker
import ai.closepaw.tool.impl.BrowserScriptTool
import ai.closepaw.tool.impl.DefaultBrowserScriptCapabilityGate
import ai.closepaw.trace.NoopTraceRecorder
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserRealDeviceQaInstrumentedTest {

    @Test
    fun browserScript_realChrome_roundTrip_screenshotAndTabs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppSettingsStore(context).saveBrowserScriptEnabled(true)

        val skillDir = File(context.filesDir, "skills/browser-use")
        val pageHelpers = File(skillDir, "scripts/page.js").readText()
        val tabHelpers = File(skillDir, "scripts/tabs.js").readText()
        val script = buildBrowserScript(pageHelpers, tabHelpers)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = BrowserSessionManager(
            context = context,
            sessionScope = scope,
            traceRecorder = NoopTraceRecorder,
        )
        try {
            val tool = BrowserScriptTool(
                capabilityGate = DefaultBrowserScriptCapabilityGate(
                    isExperimentalEnabled = { AppSettingsStore(context).load().browserScriptEnabled },
                    preflight = manager::preflight,
                    invokerFactory = {
                        BrowserScriptInvoker { browserScript, timeoutMs ->
                            manager.run(browserScript, timeoutMs)
                        }
                    },
                ),
                maxOutputChars = 2_000_000,
            )
            val params = JSONObject()
                .put("script", script)
                .put("timeout_ms", 90_000)
            assertEquals(ValidationResult.Valid, tool.validate(params))

            val result = tool.createInvocation(params).execute(QaToolExecutionContext)
            val success = result as? ToolExecutionResult.Success ?: failResult(result)

            val json = JSONObject(success.output)
            val screenshotData = json.getString("screenshotData")
            val screenshotBytes = Base64.decode(screenshotData, Base64.DEFAULT)
            assertTrue("CDP screenshot too small", screenshotBytes.size > 1_000)
            assertEquals(0x89.toByte(), screenshotBytes[0])
            assertEquals('P'.code.toByte(), screenshotBytes[1])
            assertEquals('N'.code.toByte(), screenshotBytes[2])
            assertEquals('G'.code.toByte(), screenshotBytes[3])

            val first = json.getJSONObject("firstPage")
            val second = json.getJSONObject("secondPage")
            val finalTab = json.getJSONObject("finalTab")
            val bodyExcerpt = json.getString("bodyExcerpt")
            assertEquals("Example Domain", first.getString("title"))
            assertEquals("Example Domain", second.getString("title"))
            assertTrue(
                "body excerpt did not include expected Example Domain content: $bodyExcerpt",
                bodyExcerpt.contains("Example Domain") && bodyExcerpt.contains("examples"),
            )
            assertNotEquals(json.getString("originalTabId"), json.getString("newTabId"))
            assertEquals(json.getString("originalTabId"), finalTab.getString("targetId"))

            val outDir = File(context.getExternalFilesDir(null), "debug-output/browser-real-device-qa")
            assertTrue(outDir.mkdirs() || outDir.isDirectory)
            val screenshotFile = File(outDir, "example_com_cdp_screenshot.png")
            screenshotFile.writeBytes(screenshotBytes)

            json.remove("screenshotData")
            json.put("screenshotPath", screenshotFile.absolutePath)
            json.put("browserUseSkillDir", skillDir.absolutePath)
            json.put("pageSnippetPath", File(skillDir, "scripts/page.js").absolutePath)
            json.put("tabsSnippetPath", File(skillDir, "scripts/tabs.js").absolutePath)
            File(outDir, "browser_script_result.json").writeText(json.toString(2))
        } finally {
            manager.close()
            scope.cancel()
        }
    }

    private fun buildBrowserScript(pageHelpers: String, tabHelpers: String): String = """
        $pageHelpers
        $tabHelpers

        await cdp("Page.enable");
        await cdp("Runtime.enable");
        await cdp("DOM.enable");
        await cdp("Network.enable");

        const original = await ensureRealTab();
        await switchTab(original.targetId);
        await navigate("https://example.com", { wait: true, timeoutMs: 20000 });
        const firstTab = await currentTab();
        const firstInfo = await pageInfo();
        const body = await pageJs("document.body ? document.body.innerText : ''");
        // Helper-shaped screenshot: writes bytes via storeArtifact and returns metadata
        // (path is null under NoopTraceRecorder, which this test uses).
        const shot = await screenshot({ full: false, maxDim: 1600, format: "png" });
        // Raw bytes for the on-host PNG header assertion below.
        const rawShot = await cdp("Page.captureScreenshot", { format: "png", fromSurface: true });

        const newTabId = await newTab("https://example.org");
        const secondLoaded = await waitForLoad({ timeoutMs: 20000, pollMs: 300 });
        const secondTab = await currentTab();
        const secondInfo = await pageInfo();
        const tabsAfterCreate = await listTabs();

        await switchTab(original.targetId);
        const finalTab = await currentTab();
        const finalTitle = await pageJs("document.title");

        return {
          originalTabId: original.targetId,
          newTabId,
          firstTab,
          firstPage: firstInfo,
          bodyExcerpt: body.slice(0, 240),
          screenshot: {
            format: shot.format,
            widthCss: shot.widthCss,
            heightCss: shot.heightCss,
            devicePixelRatio: shot.devicePixelRatio,
            scale: shot.scale,
            path: shot.path,
            estimatedBytes: shot.estimatedBytes
          },
          screenshotData: rawShot.data,
          secondLoaded,
          secondTab,
          secondPage: secondInfo,
          tabsAfterCreate,
          finalTab,
          finalTitle
        };
    """.trimIndent()

    private object QaToolExecutionContext : ToolExecutionContext {
        override val callId: String = "browser-real-device-qa"
        override val platform: AndroidPlatform = NoopAndroidPlatform
        override val currentSnapshot: ScreenSnapshot? = null
        override val appClassifier: AppClassifier? = null
        override fun isCancelled(): Boolean = false
    }

    private fun describe(result: ToolExecutionResult): String = when (result) {
        is ToolExecutionResult.Success -> "Success(${result.output.take(256)})"
        is ToolExecutionResult.Cancelled -> "Cancelled(${result.reason})"
        is ToolExecutionResult.Failure -> {
            val chain = generateSequence(result.exception) { it.cause }
                .joinToString(" <- ") { "${it::class.java.name}: ${it.message}" }
            "Failure(error=${result.error}, causeChain=$chain)"
        }
    }

    private fun failResult(result: ToolExecutionResult): Nothing {
        fail("expected browser_script success, got ${describe(result)}")
        throw AssertionError("unreachable")
    }

    private object NoopAndroidPlatform : AndroidPlatform {
        override val mode: PlatformMode = PlatformMode.ACCESSIBILITY
        override suspend fun captureScreen(): ScreenSnapshot = error("unused")
        override suspend fun performAction(action: UIAction): ActionResult = error("unused")
        override fun hasRequiredPermissions(): Boolean = true
        override fun getCurrentPackageName(): String? = "com.android.chrome"
        override fun getDisplayInfo(): DisplayInfo = DisplayInfo(0, 0, 1f)
        override suspend fun getInstalledApps(): List<AppInfo> = emptyList()
        override suspend fun launchApp(packageName: String): ActionResult = error("unused")
    }
}
