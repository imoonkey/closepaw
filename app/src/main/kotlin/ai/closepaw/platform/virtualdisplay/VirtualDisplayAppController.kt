package ai.closepaw.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AppInfo
import ai.closepaw.platform.AppManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App-level operations on the virtual display (launch/list apps). */
internal class VirtualDisplayAppController(
        private val service: AccessibilityService,
        private val shizuku: ShizukuClient,
        private val displayIdProvider: () -> Int
) {
        companion object {
                private const val TAG = "VDAppController"
        }

        suspend fun getInstalledApps(): List<AppInfo> {
                return withContext(Dispatchers.IO) {
                        try {
                                AppManager.getInstalledApps(service.packageManager)
                        } catch (e: Exception) {
                                Log.e(TAG, "Failed to get installed apps", e)
                                emptyList()
                        }
                }
        }

        suspend fun launchApp(packageName: String): ActionResult {
                val displayId = displayIdProvider()
                return withContext(Dispatchers.IO) {
                        try {
                                val launchIntent =
                                        service.packageManager.getLaunchIntentForPackage(packageName)
                                                ?: return@withContext ActionResult.Failure(
                                                        "App not found or not launchable: $packageName"
                                                )

                                val component = launchIntent.component?.flattenToShortString()
                                val shizukuAvailable = shizuku.isAvailable()
                                Log.d(
                                        TAG,
                                        "launchApp: component=$component, shizukuAvailable=$shizukuAvailable"
                                )

                                if (component != null && shizukuAvailable) {
                                        Log.d(TAG, "Launching $component on display $displayId via shell")
                                        val cmd =
                                                arrayOf(
                                                        "am",
                                                        "start",
                                                        "-n",
                                                        component,
                                                        "--display",
                                                        "$displayId"
                                                )
                                        val code = shizuku.executeShellCommand(cmd)
                                        if (code == 0) {
                                                return@withContext ActionResult.Success(
                                                        "Launched $component on display $displayId (shell)"
                                                )
                                        }
                                        Log.w(
                                                TAG,
                                                "Shell launch failed (code $code), falling back to intent"
                                        )
                                }

                                launchIntent.addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                                )
                                shizuku.launchOnDisplay(service, launchIntent, displayId)
                                ActionResult.Success(
                                        "Launched $packageName on display $displayId (intent)"
                                )
                        } catch (e: Exception) {
                                Log.e(TAG, "Failed to launch $packageName on display $displayId", e)
                                ActionResult.Failure("Failed to launch $packageName: ${e.message}")
                        }
                }
        }
}
