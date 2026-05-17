package ai.closepaw.ui.capsule.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * What the voice-mic caller should do given the current RECORD_AUDIO permission state.
 *
 * Mirrors [ai.closepaw.ui.settings.RunCommandPermissionDisposition]; see that file for the
 * rationale (shouldShowRationale is false both pre-first-ask AND post-permanent-deny, so
 * `hasAttempted` is needed to disambiguate).
 */
internal sealed interface VoicePermissionDisposition {
    data object Granted : VoicePermissionDisposition
    data object Request : VoicePermissionDisposition
    data object OpenAppSettings : VoicePermissionDisposition
}

internal fun classifyVoicePermission(
    isGranted: Boolean,
    hasAttempted: Boolean,
    shouldShowRationale: Boolean,
): VoicePermissionDisposition = when {
    isGranted -> VoicePermissionDisposition.Granted
    hasAttempted && !shouldShowRationale -> VoicePermissionDisposition.OpenAppSettings
    else -> VoicePermissionDisposition.Request
}

internal class VoicePermissionGate internal constructor(private val activity: Activity) {
    internal var hasAttempted by mutableStateOf(false)
        private set

    var pending by mutableStateOf(false)
        private set

    private var launcher: ActivityResultLauncher<String>? = null

    internal fun attachLauncher(launcher: ActivityResultLauncher<String>) {
        this.launcher = launcher
    }

    internal fun markAttempted() {
        hasAttempted = true
    }

    internal fun clearPending() {
        pending = false
    }

    fun disposition(): VoicePermissionDisposition =
        classifyVoicePermission(
            isGranted = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
            hasAttempted = hasAttempted,
            shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO,
            ),
        )

    fun requestPermission() {
        if (pending) return
        val launcher = launcher ?: return
        pending = true
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", activity.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }
}

@Composable
internal fun rememberVoicePermissionGate(
    activity: Activity,
    onResult: (granted: Boolean) -> Unit,
): VoicePermissionGate {
    val gate = remember(activity) { VoicePermissionGate(activity = activity) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        gate.clearPending()
        gate.markAttempted()
        onResult(granted)
    }
    SideEffect { gate.attachLauncher(launcher) }
    return gate
}
