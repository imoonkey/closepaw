package com.moonkey.androidagent.tool.action

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenImageSource
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.AppInfo
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.SystemButtonType
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.protocol.AppTier
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.tool.AppClassifier
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolObservation
import com.moonkey.androidagent.tool.handlers.UIActionInvocation
import com.moonkey.androidagent.tool.impl.OpenAppTool
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

/**
 * P0.2 — Capture-layer privacy gate tests.
 *
 * Verifies that BLOCKED-app content never leaks through observations:
 * - buildObservation masks elements + image for BLOCKED apps
 * - OpenAppTool returns masked observation when launching a BLOCKED app
 * - UIActionInvocation returns masked observation when on a BLOCKED app
 */
class CapturePrivacyGateTest {

    private val blockedPkg = "com.chase.sig.android"
    private val normalPkg = "com.android.settings"

    private fun classifier() = AppClassifier(
        mapOf(blockedPkg to AppTier.BLOCKED, normalPkg to AppTier.NORMAL)
    )

    // ---- buildObservation ----

    @Test
    fun `buildObservation masks snapshot for BLOCKED app`() {
        val platform = FakeAndroidPlatform(currentPackageName = blockedPkg)
        val snapshot = richSnapshot()

        val obs = buildObservation(snapshot, platform, classifier())

        assertThat(obs).isNotNull()
        assertThat(obs!!.elementCount).isEqualTo(0)
        assertThat(obs.snapshot?.elements).isEmpty()
        assertThat(obs.snapshot?.image).isNull()
        assertThat(obs.accessibilityTree).contains("BLOCKED")
    }

    @Test
    fun `buildObservation returns full observation for NORMAL app`() {
        val platform = FakeAndroidPlatform(currentPackageName = normalPkg)
        val snapshot = richSnapshot()

        val obs = buildObservation(snapshot, platform, classifier())

        assertThat(obs).isNotNull()
        assertThat(obs!!.elementCount).isEqualTo(2)
        assertThat(obs.snapshot?.elements).hasSize(2)
        assertThat(obs.snapshot?.image).isNotNull()
    }

    @Test
    fun `buildObservation without classifier returns full observation even for blocked package`() {
        val platform = FakeAndroidPlatform(currentPackageName = blockedPkg)
        val snapshot = richSnapshot()

        val obs = buildObservation(snapshot, platform)

        assertThat(obs).isNotNull()
        assertThat(obs!!.elementCount).isEqualTo(2)
    }

    @Test
    fun `buildObservation strips image for BLOCKED app`() {
        val platform = FakeAndroidPlatform(currentPackageName = blockedPkg)
        val snapshot = richSnapshot()

        val obs = buildObservation(snapshot, platform, classifier())

        assertThat(obs).isNotNull()
        assertThat(obs!!.snapshot?.hasScreenshot).isNotEqualTo(true)
    }

    // ---- OpenAppTool ----

    @Test
    fun `open_app launching blocked app returns masked observation`() = runTest {
        val platform = RichFakePlatform(
            initialPackage = "com.android.launcher",
            installedApps = listOf(AppInfo(blockedPkg, "Chase"))
        )
        val context = contextWith(platform, classifier())

        val invocation = OpenAppTool()
            .createInvocation(JSONObject().put("app_name", "Chase"))
        val result = invocation.execute(context)

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val obs = (result as ToolExecutionResult.Success).observation
                as? ToolObservation.ScreenState
        assertThat(obs).isNotNull()
        assertThat(obs!!.elementCount).isEqualTo(0)
        assertThat(obs.snapshot?.image).isNull()
        assertThat(obs.accessibilityTree).contains("BLOCKED")
    }

    @Test
    fun `open_app launching normal app returns full observation`() = runTest {
        val platform = RichFakePlatform(
            initialPackage = "com.android.launcher",
            installedApps = listOf(AppInfo(normalPkg, "Settings", isSystemApp = true))
        )
        val context = contextWith(platform, classifier())

        val invocation = OpenAppTool()
            .createInvocation(JSONObject().put("app_name", "Settings"))
        val result = invocation.execute(context)

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val obs = (result as ToolExecutionResult.Success).observation
                as? ToolObservation.ScreenState
        assertThat(obs).isNotNull()
        assertThat(obs!!.elementCount).isGreaterThan(0)
    }

    // ---- UIActionInvocation ----

    @Test
    fun `UIActionInvocation on blocked app returns masked observation`() = runTest {
        val platform = RichFakePlatform(initialPackage = blockedPkg)
        val context = contextWith(platform, classifier())

        val invocation = UIActionInvocation(
            toolName = "system_button",
            params = JSONObject().put("button", "back"),
            description = "Press back",
            uiAction = UIAction.SystemButton(SystemButtonType.BACK)
        )
        val result = invocation.execute(context)

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val obs = (result as ToolExecutionResult.Success).observation
                as? ToolObservation.ScreenState
        assertThat(obs).isNotNull()
        assertThat(obs!!.elementCount).isEqualTo(0)
        assertThat(obs.snapshot?.image).isNull()
        assertThat(obs.accessibilityTree).contains("BLOCKED")
    }

    @Test
    fun `UIActionInvocation on normal app returns full observation`() = runTest {
        val platform = RichFakePlatform(initialPackage = normalPkg)
        val context = contextWith(platform, classifier())

        val invocation = UIActionInvocation(
            toolName = "system_button",
            params = JSONObject().put("button", "home"),
            description = "Press home",
            uiAction = UIAction.SystemButton(SystemButtonType.HOME)
        )
        val result = invocation.execute(context)

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val obs = (result as ToolExecutionResult.Success).observation
                as? ToolObservation.ScreenState
        assertThat(obs).isNotNull()
        assertThat(obs!!.elementCount).isGreaterThan(0)
    }

    // ---- Helpers ----

    private fun testElement(index: Int) = PerceptionElement(
        index = index,
        text = "Element $index",
        resourceId = "com.test:id/el_$index",
        className = "android.widget.Button",
        description = "Button $index",
        isClickable = true,
        isEditable = false,
        isScrollable = false,
        isEnabled = true,
        isFocused = false,
        isLongClickable = false,
        bounds = Bounds(0, index * 48, 100, (index + 1) * 48),
        center = Point(50, index * 48 + 24)
    )

    private fun richSnapshot() = ScreenSnapshot(
        timestamp = 1000L,
        elements = listOf(testElement(0), testElement(1)),
        image = ScreenImage(
            width = 1080, height = 1920,
            mimeType = "image/jpeg",
            bytes = ByteArray(100),
            source = ScreenImageSource.ACCESSIBILITY_SCREENSHOT
        )
    )

    private fun contextWith(
        platform: AndroidPlatform,
        appClassifier: AppClassifier
    ) = object : ToolExecutionContext {
        override val platform: AndroidPlatform = platform
        override val currentSnapshot: ScreenSnapshot? = null
        override val appClassifier: AppClassifier = appClassifier
        override fun isCancelled() = false
    }
}

/**
 * Fake platform that returns a rich snapshot (with elements + image)
 * and simulates foreground-package change on launchApp.
 */
private class RichFakePlatform(
    private var initialPackage: String? = "com.example.fake",
    private val installedApps: List<AppInfo> = emptyList()
) : AndroidPlatform {

    private fun element(index: Int) = PerceptionElement(
        index = index,
        text = "Element $index",
        resourceId = "com.test:id/el_$index",
        className = "android.widget.Button",
        description = "Button $index",
        isClickable = true,
        isEditable = false,
        isScrollable = false,
        isEnabled = true,
        isFocused = false,
        isLongClickable = false,
        bounds = Bounds(0, index * 48, 100, (index + 1) * 48),
        center = Point(50, index * 48 + 24)
    )

    override suspend fun captureScreen() = ScreenSnapshot(
        timestamp = System.currentTimeMillis(),
        elements = listOf(element(0), element(1), element(2)),
        image = ScreenImage(
            width = 1080, height = 1920,
            mimeType = "image/jpeg",
            bytes = ByteArray(100),
            source = ScreenImageSource.ACCESSIBILITY_SCREENSHOT
        )
    )

    override suspend fun performAction(action: UIAction) = ActionResult.Success()
    override fun hasRequiredPermissions() = true
    override fun getCurrentPackageName() = initialPackage
    override fun getDisplayInfo() = DisplayInfo(1080, 1920, 2f)
    override suspend fun getInstalledApps() = installedApps
    override suspend fun launchApp(packageName: String): ActionResult {
        initialPackage = packageName
        return ActionResult.Success("Launched $packageName")
    }
}
