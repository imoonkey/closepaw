package com.moonkey.androidagent.platform

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class AppManagerTest {

    @Test
    fun `getInstalledApps deduplicates and falls back to package label`() {
        val packageManager = mockk<PackageManager>()
        val duplicatePackage = "com.example.beta"

        val alpha = resolveInfo(packageName = "com.example.alpha", label = "Alpha")
        val betaFirst =
                resolveInfo(packageName = duplicatePackage, label = "Beta", isSystemApp = true)
        val betaDuplicate = resolveInfo(packageName = duplicatePackage, label = "Beta Duplicate")
        val blankLabel = resolveInfo(packageName = "com.example.blank", label = "")

        every { packageManager.queryIntentActivities(any(), PackageManager.MATCH_ALL) } returns
                listOf(betaFirst, alpha, betaDuplicate, blankLabel)

        val apps = AppManager.getInstalledApps(packageManager)

        assertThat(apps)
                .isEqualTo(
                        listOf(
                                AppInfo(
                                        packageName = "com.example.alpha",
                                        label = "com.example.alpha",
                                        isSystemApp = false
                                ),
                                AppInfo(
                                        packageName = "com.example.beta",
                                        label = "com.example.beta",
                                        isSystemApp = true
                                ),
                                AppInfo(
                                        packageName = "com.example.blank",
                                        label = "com.example.blank",
                                        isSystemApp = false
                                )
                        )
                )
    }

    private fun resolveInfo(
            packageName: String,
            label: String,
            isSystemApp: Boolean = false
    ): ResolveInfo {
        val applicationInfo =
                ApplicationInfo().apply {
                    flags = if (isSystemApp) ApplicationInfo.FLAG_SYSTEM else 0
                }
        val activityInfo =
                ActivityInfo().apply {
                    this.packageName = packageName
                    this.applicationInfo = applicationInfo
                }
        return ResolveInfo().apply {
            this.activityInfo = activityInfo
            this.nonLocalizedLabel = label
        }
    }
}
