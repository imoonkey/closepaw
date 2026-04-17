package ai.closepaw.platform

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object AppManager {
    fun getInstalledApps(packageManager: PackageManager): List<AppInfo> {
        val intent =
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { resolveInfo ->
                    val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                    val resolvedLabel =
                            runCatching { resolveInfo.loadLabel(packageManager) }
                                    .getOrNull()
                                    ?.toString()
                                    .orEmpty()
                    AppInfo(
                            packageName = activityInfo.packageName,
                            label = resolvedLabel.ifBlank { activityInfo.packageName },
                            isSystemApp =
                                    (activityInfo.applicationInfo.flags and
                                            ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
    }
}
