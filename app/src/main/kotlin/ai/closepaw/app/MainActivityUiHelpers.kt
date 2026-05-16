package ai.closepaw.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import ai.closepaw.llm.LFMLLMClient
import ai.closepaw.ui.settings.ModelLoadingStatus
import ai.closepaw.ui.viewer.VirtualDisplayViewerActivity

internal const val EXTRA_KEEP_VIEWER_OPEN_WHEN_IDLE =
    "ai.closepaw.extra.KEEP_VIEWER_OPEN_WHEN_IDLE"

internal fun LFMLLMClient.ModelLoadingState.toUiStatus(): ModelLoadingStatus {
    return when (this) {
        is LFMLLMClient.ModelLoadingState.NotLoaded -> ModelLoadingStatus.Idle
        is LFMLLMClient.ModelLoadingState.Downloading -> ModelLoadingStatus.Downloading(progress)
        is LFMLLMClient.ModelLoadingState.Loading -> ModelLoadingStatus.Loading
        is LFMLLMClient.ModelLoadingState.Ready -> ModelLoadingStatus.Ready
        is LFMLLMClient.ModelLoadingState.Error -> ModelLoadingStatus.Error(message)
    }
}

internal fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

internal fun openOverlaySettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    context.startActivity(intent)
}

internal fun openViewer(context: Context, keepOpenWhenIdle: Boolean = false) {
    val intent = Intent(context, VirtualDisplayViewerActivity::class.java)
    if (keepOpenWhenIdle) {
        intent.putExtra(EXTRA_KEEP_VIEWER_OPEN_WHEN_IDLE, true)
    }
    context.startActivity(intent)
}
