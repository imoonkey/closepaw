package ai.closepaw.protocol

import android.content.pm.PackageManager

/**
 * Runtime metadata captured at `TaskCompleted` for Virtual Display tasks so the
 * chat row can render explicit handoff CTAs ("Open <App>", "View virtual screen").
 *
 * Absent (null) for non-VD completions. Carries only facts knowable at completion
 * time — no intent classification.
 */
data class CompletionHandoff(
        val appPackage: String?,
        val appLabel: String?,
        val virtualDisplayAvailable: Boolean,
)

/**
 * Build a [CompletionHandoff] from runtime facts at task completion.
 *
 * Drops self/system-UI packages so the chat row never offers to "open ClosePaw" or
 * "open SystemUI" — render-time filtering for launcher intent / blocklist happens later.
 * Label resolution catches [PackageManager.NameNotFoundException] and falls back to null.
 */
fun buildVdCompletionHandoff(
        appPackage: String?,
        viewerAvailable: Boolean,
        packageManager: PackageManager,
        selfPackage: String,
): CompletionHandoff {
    val filtered = appPackage?.takeIf { pkg ->
        pkg != selfPackage && pkg != "com.android.systemui"
    }
    val label = filtered?.let { pkg ->
        try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
    return CompletionHandoff(
            appPackage = filtered,
            appLabel = label,
            virtualDisplayAvailable = viewerAvailable,
    )
}
