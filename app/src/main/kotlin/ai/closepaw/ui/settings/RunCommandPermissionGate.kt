package ai.closepaw.ui.settings

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

internal const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

/**
 * What the row's tap handler should do given the current permission state.
 *
 * Termux 0.118+ declares `com.termux.permission.RUN_COMMAND` with `prot=dangerous`, so a
 * manifest `<uses-permission>` does not grant it on install — the app must request it at
 * runtime. The row drives that request through this disposition.
 */
internal sealed interface RunCommandPermissionDisposition {
    /** Permission already granted — caller should proceed with `manager.setup()`. */
    data object Granted : RunCommandPermissionDisposition

    /** Caller should fire the system permission dialog via [RunCommandPermissionGate.requestPermission]. */
    data object Request : RunCommandPermissionDisposition

    /**
     * The user picked "Don't ask again" (or denied twice on Android 11+). The system will not
     * surface a dialog anymore — caller should send the user to the app's permission settings.
     */
    data object OpenAppSettings : RunCommandPermissionDisposition
}

/**
 * Pure classifier: given the three observable inputs, decide what tapping the row should do.
 *
 * `shouldShowRationale` returns false BOTH before the first ask AND after the user picks
 * "Don't ask again" — `hasAttempted` disambiguates. If we have not yet asked this app run,
 * always [Request] regardless of the rationale flag. Once we have asked at least once, a
 * false rationale signals a hard refusal that only App Settings can recover.
 */
internal fun classifyRunCommandPermission(
    isGranted: Boolean,
    hasAttempted: Boolean,
    shouldShowRationale: Boolean,
): RunCommandPermissionDisposition = when {
    isGranted -> RunCommandPermissionDisposition.Granted
    hasAttempted && !shouldShowRationale -> RunCommandPermissionDisposition.OpenAppSettings
    else -> RunCommandPermissionDisposition.Request
}

/**
 * Compose state holder that bundles the runtime-permission launcher with the small amount of
 * state needed to distinguish first-deny (system will still show a dialog on re-ask) from
 * permanent-deny (system stays silent — only App Settings recovers it).
 *
 * `hasAttempted` is process-scoped on purpose: after the user toggles the permission in App
 * Settings the system also resets its own rationale flag, so on the next attempt
 * `shouldShowRationale` returns false again — without `hasAttempted` we would mis-classify
 * a fresh-grant flow as "permanently denied" the moment the row recomposes.
 */
internal class RunCommandPermissionGate internal constructor(private val activity: Activity) {
    /** Snapshot-state so disposition() recomposes when the launcher fires its callback. */
    internal var hasAttempted by mutableStateOf(false)
        private set

    /**
     * True from the moment [requestPermission] launches the system dialog until the launcher
     * callback fires. Drives the rapid-tap guard in [requestPermission] and is exposed so the
     * UI can disable interaction while the dialog is up (the Switch in TermuxShellSettingsRow
     * and the row tap can both reach [requestPermission] independently).
     */
    var pending by mutableStateOf(false)
        private set

    private var launcher: ActivityResultLauncher<String>? = null

    internal fun attachLauncher(launcher: ActivityResultLauncher<String>) {
        this.launcher = launcher
    }

    internal fun markAttempted() {
        hasAttempted = true
    }

    /** Re-arm the gate after the launcher callback fires, regardless of granted/denied. */
    internal fun clearPending() {
        pending = false
    }

    /** Read the current disposition. Safe to call from any thread; reads framework state. */
    fun disposition(): RunCommandPermissionDisposition =
        classifyRunCommandPermission(
            isGranted = ContextCompat.checkSelfPermission(activity, TERMUX_RUN_COMMAND_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED,
            hasAttempted = hasAttempted,
            shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                TERMUX_RUN_COMMAND_PERMISSION,
            ),
        )

    /**
     * Fire the system permission dialog. No-op if the launcher hasn't been attached yet
     * (constructor-time taps) OR if a previous request is still in flight — without the
     * pending guard, rapid taps before the launcher callback fires would stack multiple
     * system dialogs (and a late granted callback would still run setup unconditionally).
     */
    fun requestPermission() {
        if (pending) return
        val launcher = launcher ?: return
        pending = true
        launcher.launch(TERMUX_RUN_COMMAND_PERMISSION)
    }

    /** Send the user to ClosePaw's App Settings → Permissions page. */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", activity.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }
}

/**
 * Build a [RunCommandPermissionGate] bound to [activity] and a Compose-managed
 * [ActivityResultContracts.RequestPermission] launcher.
 *
 * The launcher must be created via [rememberLauncherForActivityResult] (the
 * `ActivityResultRegistry` requires Compose to register the contract before STARTED). The gate
 * is created first because the launcher's callback needs to mutate it; we attach the launcher
 * to the gate via [SideEffect] (idempotent — the launcher instance is stable across
 * recompositions).
 */
@Composable
internal fun rememberRunCommandPermissionGate(
    activity: Activity,
    onResult: (granted: Boolean) -> Unit,
): RunCommandPermissionGate {
    val gate = remember(activity) { RunCommandPermissionGate(activity = activity) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Clear pending FIRST so the gate is re-armed even if onResult throws or the caller's
        // setup work bails. Order matters: mark-attempted is independent and fine either way.
        gate.clearPending()
        gate.markAttempted()
        onResult(granted)
    }
    SideEffect { gate.attachLauncher(launcher) }
    return gate
}
