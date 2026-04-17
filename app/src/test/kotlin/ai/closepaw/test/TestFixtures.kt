package ai.closepaw.test

import android.content.Context
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.AppInfo
import ai.closepaw.platform.DisplayInfo
import ai.closepaw.platform.UIAction
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.delay

fun buildTestContext(filesDir: File): Context {
    val context = mockk<Context>(relaxed = true)
    every { context.filesDir } returns filesDir
    return context
}

class FakeAndroidPlatform(
    private val captureDelayMs: Long = 0L,
    private val timestampProvider: () -> Long = { System.currentTimeMillis() },
    private val currentPackageName: String? = "com.example.fake",
    private val displayInfo: DisplayInfo = DisplayInfo(
        widthPixels = 1080,
        heightPixels = 1920,
        density = 2f
    ),
    private val installedApps: List<AppInfo> = emptyList(),
    private val launchResult: ActionResult = ActionResult.Success()
) : AndroidPlatform {
    override suspend fun captureScreen(): ScreenSnapshot {
        if (captureDelayMs > 0) {
            delay(captureDelayMs)
        }
        return ScreenSnapshot(timestamp = timestampProvider(), elements = emptyList())
    }

    override suspend fun performAction(action: UIAction): ActionResult {
        return ActionResult.Success()
    }

    override fun hasRequiredPermissions(): Boolean = true

    override fun getCurrentPackageName(): String? = currentPackageName

    override fun getDisplayInfo(): DisplayInfo = displayInfo

    override suspend fun getInstalledApps(): List<AppInfo> = installedApps

    override suspend fun launchApp(packageName: String): ActionResult = launchResult
}
