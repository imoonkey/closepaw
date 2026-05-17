package ai.closepaw.ui.settings

import ai.closepaw.app.AppSettingsStore
import ai.closepaw.browser.setup.ChromeCdpProbe
import ai.closepaw.browser.setup.ChromeFlagDeepLink
import ai.closepaw.browser.setup.ShizukuShellRunner
import ai.closepaw.termux.NeedsSetupReason
import ai.closepaw.termux.TermuxBridgeManager
import ai.closepaw.termux.TermuxBridgeStatus
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TERMUX_INSTALL_URL = "https://f-droid.org/packages/com.termux/"
private const val TERMUX_PACKAGE = "com.termux"

/**
 * "Tools" section for the Agent Behavior settings page. Hosts agent tool toggles
 * (`termux_shell`, `browser_script`).
 *
 * Row order: termux_shell first, then browser_script. SettingsTermuxRowTest indexes
 * Switches by document order (`isToggleable()[0]` = Termux), so keep Termux first.
 *
 * Agent Behavior → Tools is the single UI surface for the `browser_script` toggle (the old
 * Permissions & Advanced → Experimental duplicate was removed). The toggle still routes
 * through [gateBrowserScriptEnable] — see `BrowserScriptToggleGate.kt`.
 */
@Composable
internal fun ToolsSection(
    browserScriptEnabled: Boolean,
    onBrowserScriptEnabledChange: (Boolean) -> Unit,
) {
    SettingsSection(title = "Tools") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TermuxShellSettingsRow()
            BrowserScriptToolRow(
                enabled = browserScriptEnabled,
                onEnabledChange = onBrowserScriptEnabledChange,
            )
        }
    }
}

@Composable
private fun TermuxShellSettingsRow() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val activity = remember(context) { context.findActivity() }
    val manager = remember(appContext) { TermuxBridgeManager.get(appContext) }
    val settingsStore = remember(appContext) { AppSettingsStore(appContext) }
    val bridgeStatus by manager.state.collectAsStateWithLifecycle()
    val termuxShellEnabled by settingsStore.termuxShellEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Permission gate is null when LocalContext is not an Activity (Compose previews and the
    // SettingsTermuxRowTest IntentRecordingContext) — in that case we silently skip the runtime
    // request and fall back to the legacy behavior of just calling manager.setup() on tap. The
    // permission flow only applies on real device runs where ComponentActivity is available.
    val permissionGate = activity?.let {
        rememberRunCommandPermissionGate(activity = it) { granted ->
            if (granted) {
                scope.launch(Dispatchers.IO) { manager.setup() }
            }
        }
    }

    LaunchedEffect(manager, termuxShellEnabled) {
        if (termuxShellEnabled) {
            val detected = manager.detectInstalled()
            if (detected !is TermuxBridgeStatus.NotInstalled) {
                manager.healthCheck()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, manager, termuxShellEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && termuxShellEnabled) {
                scope.launch {
                    val detected = manager.detectInstalled()
                    if (detected !is TermuxBridgeStatus.NotInstalled) {
                        manager.healthCheck()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val displayedStatus = if (termuxShellEnabled) bridgeStatus else TermuxBridgeStatus.Disabled
    val permissionDisposition = permissionGate?.disposition()
    val statusUi = termuxStatusUi(
        state = bridgeStatus,
        permissionDisposition = permissionDisposition,
        enabledPref = termuxShellEnabled,
    )

    val rowAction: (() -> Unit)? =
        when (displayedStatus) {
            TermuxBridgeStatus.NotInstalled -> {
                { context.openTermuxInstallPage() }
            }
            is TermuxBridgeStatus.NeedsSetup -> {
                when (displayedStatus.reason) {
                    NeedsSetupReason.TERMUX_RUN_COMMAND_UNAVAILABLE -> {
                        { context.openTermuxInstallPage() }
                    }
                    NeedsSetupReason.TERMUX_NOT_RUNNING -> {
                        { context.launchTermux() }
                    }
                    NeedsSetupReason.PERMISSION_MISSING -> permissionRowAction(
                        gate = permissionGate,
                        runSetup = { scope.launch(Dispatchers.IO) { manager.setup() } },
                    )
                    else -> {
                        { scope.launch(Dispatchers.IO) { manager.setup() } }
                    }
                }
            }
            TermuxBridgeStatus.Ready -> {
                { scope.launch(Dispatchers.IO) { manager.restart() } }
            }
            TermuxBridgeStatus.SetupInProgress,
            TermuxBridgeStatus.Disabled -> null
        }

    val rowClickLabel = when (displayedStatus) {
        TermuxBridgeStatus.NotInstalled -> "Install Termux"
        is TermuxBridgeStatus.NeedsSetup -> "Fix Termux setup"
        TermuxBridgeStatus.Ready -> "Restart Termux bridge"
        else -> null
    }

    ToolSettingsCard(
        title = "Termux Shell",
        status = statusUi,
        switchChecked = termuxShellEnabled,
        onSwitchChange = { enabled ->
            scope.launch {
                settingsStore.setTermuxShellEnabled(enabled)
                if (enabled) {
                    // Proactively request the dangerous permission so the user sees the system
                    // dialog the first time they enable the toggle, instead of having to enable →
                    // see "RUN_COMMAND missing" → tap the row → see the dialog. Skipped silently
                    // when the gate is null (preview / unit-test ContextWrapper) or when
                    // permission is already granted.
                    when (permissionGate?.disposition()) {
                        RunCommandPermissionDisposition.Request ->
                            permissionGate.requestPermission()
                        RunCommandPermissionDisposition.OpenAppSettings ->
                            permissionGate.openAppSettings()
                        RunCommandPermissionDisposition.Granted, null -> Unit
                    }
                }
            }
        },
        onRowClick = rowAction,
        onRowClickLabel = rowClickLabel,
    )
}

/**
 * Build the tap handler for the PERMISSION_MISSING reason. Routes through the gate's
 * disposition: already-granted means the bridge state is stale (raced with another grant) so
 * just retry setup; Request fires the system dialog; OpenAppSettings sends the user to the app
 * details page so they can flip the toggle manually.
 */
private fun permissionRowAction(
    gate: RunCommandPermissionGate?,
    runSetup: () -> Unit,
): () -> Unit = {
    when (gate?.disposition()) {
        RunCommandPermissionDisposition.Granted, null -> runSetup()
        RunCommandPermissionDisposition.Request -> gate.requestPermission()
        RunCommandPermissionDisposition.OpenAppSettings -> gate.openAppSettings()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun android.content.Context.openTermuxInstallPage() {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_INSTALL_URL)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "Unable to open Termux install page", Toast.LENGTH_SHORT).show()
    }
}

private fun android.content.Context.launchTermux() {
    val intent = packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
    if (intent == null) {
        Toast.makeText(this, "Termux is not installed", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "Unable to launch Termux", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun BrowserScriptToolRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val gate = rememberBrowserScriptToggleGate(onPersist = onEnabledChange)
    val shellRunner = remember { ShizukuShellRunner() }
    val probe = remember(shellRunner) { ChromeCdpProbe(shellRunner = shellRunner) }
    val deepLink = remember(appContext, shellRunner) {
        ChromeFlagDeepLink(context = appContext, shellRunner = shellRunner)
    }
    var probeState by remember {
        mutableStateOf<BrowserScriptProbeState>(BrowserScriptProbeState.Probing)
    }
    var refreshTick by remember { mutableStateOf(0) }

    // Only probe when the tool is actually live — enabled, gate done, no error. Probing
    // pre-gate would cache a stale Unknown/NotBound that survives a successful Shizuku setup
    // (the probe wouldn't re-run until refreshTick changes), leaving the row showing wrong
    // status until the user manually taps Re-check. When probeActive flips false we also
    // reset to Probing so a stale Bound/NotBound from a prior session doesn't leak into the
    // mapper after a disable/re-enable cycle.
    val probeActive = enabled && !gate.pending && gate.error == null
    LaunchedEffect(probeActive, refreshTick) {
        if (!probeActive) {
            probeState = BrowserScriptProbeState.Probing
            return@LaunchedEffect
        }
        probeState = BrowserScriptProbeState.Probing
        probeState = when (probe.probe()) {
            ChromeCdpProbe.Result.Bound -> BrowserScriptProbeState.Bound
            ChromeCdpProbe.Result.NotBound -> BrowserScriptProbeState.NotBound
            ChromeCdpProbe.Result.Unknown -> BrowserScriptProbeState.Unknown
        }
    }

    val statusResult = browserScriptStatusUi(
        enabledPref = enabled,
        gatePending = gate.pending,
        gateError = gate.error,
        probeResult = probeState,
    )

    val rowAction: (() -> Unit)? = when (statusResult.rowAction) {
        RowAction.ClearErrorAndRetry -> {
            {
                gate.clearError()
                gate.setEnabled(true)
            }
        }
        RowAction.None, null -> null
    }
    val rowClickLabel = when (statusResult.rowAction) {
        RowAction.ClearErrorAndRetry -> "Retry Shizuku setup"
        RowAction.None, null -> null
    }

    // Expanded help only when the tool is on, no gate error, and the probe failed/was inconclusive.
    // NotBound gets the explicit chrome://flags Button; Unknown skips it (Chrome may already be
    // configured — there's nothing to fix) and just shows the manual-paste URL + Re-check.
    val showExpandedHelp = enabled && gate.error == null &&
        (probeState is BrowserScriptProbeState.NotBound ||
                probeState is BrowserScriptProbeState.Unknown)

    ToolSettingsCard(
        title = "Browser Script",
        status = statusResult.status,
        switchChecked = enabled,
        switchEnabled = !gate.pending,
        // Always wipe a stale inline error on tap. setEnabled() also clears its own error on the
        // happy paths, but the explicit call here covers the early-bail (pending) branch and
        // makes the contract obvious to future readers.
        onSwitchChange = { value ->
            gate.clearError()
            gate.setEnabled(value)
        },
        onRowClick = rowAction,
        onRowClickLabel = rowClickLabel,
        expanded = if (showExpandedHelp) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (probeState is BrowserScriptProbeState.NotBound) {
                        Button(
                            onClick = { scope.launch { deepLink.open() } },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text(
                                text = "Open chrome://flags →",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    // Inline manual-paste recovery. Always rendered alongside the CTA so the user
                    // sees the URL even when ACTION_VIEW or `am start` "succeed" but Chrome
                    // silently drops the navigation (real, observed on nubia P0110). The Toast in
                    // ChromeFlagDeepLink is transient and frequently obscured by Chrome opening
                    // on top — this surface is durable.
                    FlagUrlInlineHelp(
                        onCopy = {
                            val ok = deepLink.copyFlagUrlToClipboard()
                            Toast.makeText(
                                context,
                                if (ok) "URL copied to clipboard" else "Copy failed — try again",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                    OutlinedButton(
                        onClick = { refreshTick++ },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(text = "Re-check", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } else null,
    )
}

/**
 * Manual-paste recovery: shows the chrome:// URL with a Copy button. Rendered alongside the
 * CTA whenever the probe can't confirm the socket is bound, so the user has a durable surface
 * (not a transient Toast) for when Chrome opens but drops the URL.
 */
@Composable
private fun FlagUrlInlineHelp(onCopy: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = "Chrome didn't open the flags page? Paste this URL manually:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = ChromeFlagDeepLink.FLAG_URL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(text = "Copy URL", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
