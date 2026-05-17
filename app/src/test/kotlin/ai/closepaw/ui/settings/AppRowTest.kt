package ai.closepaw.ui.settings

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AppRowTest {

    @Test
    fun `loadInstalledAppRows returns one row per installed app preserving info`() {
        val pm = mockk<PackageManager>()
        val context = mockk<Context>()
        every { context.packageManager } returns pm
        every { pm.queryIntentActivities(any(), PackageManager.MATCH_ALL) } returns
                listOf(
                        resolveInfo("com.example.alpha", "Alpha"),
                        resolveInfo("com.example.beta", "Beta", isSystemApp = true)
                )

        val rows = loadInstalledAppRows(context)

        assertThat(rows.map { it.info.packageName })
                .containsExactly("com.example.alpha", "com.example.beta")
                .inOrder()
        assertThat(rows.first().info.label).isEqualTo("com.example.alpha")
        assertThat(rows.last().info.isSystemApp).isTrue()
    }

    @Test
    fun `loadInstalledAppRows does not eagerly load any icons`() {
        val pm = mockk<PackageManager>()
        val context = mockk<Context>()
        every { context.packageManager } returns pm
        every { pm.queryIntentActivities(any(), PackageManager.MATCH_ALL) } returns
                listOf(
                        resolveInfo("com.example.alpha", "Alpha"),
                        resolveInfo("com.example.beta", "Beta")
                )

        loadInstalledAppRows(context)

        verify(exactly = 0) { pm.getApplicationIcon(any<String>()) }
    }

    @Test
    fun `iconLoader is invoked per-row only when called`() = runBlocking {
        val pm = mockk<PackageManager>()
        every { pm.getApplicationIcon("com.example.alpha") } throws
                PackageManager.NameNotFoundException("alpha")

        val icon = loadAppIcon(pm, "com.example.alpha")

        assertThat(icon).isNull()
        verify(exactly = 1) { pm.getApplicationIcon("com.example.alpha") }
    }

    @Test
    fun `iconLoader returns null when package manager throws`() = runBlocking {
        val pm = mockk<PackageManager>()
        val context = mockk<Context>()
        every { context.packageManager } returns pm
        every { pm.queryIntentActivities(any(), PackageManager.MATCH_ALL) } returns
                listOf(resolveInfo("com.example.alpha", "Alpha"))
        every { pm.getApplicationIcon(any<String>()) } throws
                PackageManager.NameNotFoundException("missing")

        val rows = loadInstalledAppRows(context)
        val icon = rows.single().iconLoader()

        assertThat(icon).isNull()
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
