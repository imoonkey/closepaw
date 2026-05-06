package ai.closepaw.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.closepaw.app.AppSettingsStore
import ai.closepaw.protocol.AgentMode
import ai.closepaw.termux.NeedsSetupReason
import ai.closepaw.termux.TermuxBridgeManager
import ai.closepaw.termux.TermuxBridgeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TERMUX_INSTALL_URL = "https://f-droid.org/packages/com.termux/"
private const val TERMUX_PACKAGE = "com.termux"

@Composable
internal fun AgentBehaviorSettingsPage(
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit,
    agentMode: AgentMode,
    onAgentModeChange: (AgentMode) -> Unit,
    perceptionMode: String,
    onPerceptionModeChange: (String) -> Unit,
    browserScriptEnabled: Boolean,
    onBrowserScriptEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSubPageHeader(title = "Agent Behavior", onBack = onBack, onClose = onClose)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            SettingsSection(title = "Max Turns") {
                MaxTurnsDropdown(maxTurns = maxTurns, onMaxTurnsChange = onMaxTurnsChange)
            }
            Spacer(modifier = Modifier.height(20.dp))
            SettingsSection(title = "Execution") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AgentModeDropdown(agentMode = agentMode, onAgentModeChange = onAgentModeChange)
                    TermuxShellSettingsRow()
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            SettingsSection(title = "Perception") {
                PerceptionModeSelector(selectedMode = perceptionMode, onModeChange = onPerceptionModeChange)
            }
            Spacer(modifier = Modifier.height(20.dp))
            ToolsSection(
                browserScriptEnabled = browserScriptEnabled,
                onBrowserScriptEnabledChange = onBrowserScriptEnabledChange,
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TermuxShellSettingsRow() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val manager = remember(appContext) { TermuxBridgeManager.get(appContext) }
    val settingsStore = remember(appContext) { AppSettingsStore(appContext) }
    val bridgeStatus by manager.state.collectAsStateWithLifecycle()
    val termuxShellEnabled by settingsStore.termuxShellEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = rowAction != null) { rowAction?.invoke() },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Termux Shell",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = displayedStatus.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = displayedStatus.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (displayedStatus == TermuxBridgeStatus.SetupInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Switch(
                    checked = termuxShellEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsStore.setTermuxShellEnabled(enabled) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            Text(
                text = "Setting changes apply on the next session.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
    }
}

private val TermuxBridgeStatus.label: String
    get() =
        when (this) {
            TermuxBridgeStatus.NotInstalled -> "Not Installed"
            is TermuxBridgeStatus.NeedsSetup -> "Needs Setup"
            TermuxBridgeStatus.SetupInProgress -> "Setting up…"
            TermuxBridgeStatus.Ready -> "Ready"
            TermuxBridgeStatus.Disabled -> "Disabled"
        }

private val TermuxBridgeStatus.subtitle: String
    get() =
        when (this) {
            TermuxBridgeStatus.NotInstalled -> "Install Termux from F-Droid"
            is TermuxBridgeStatus.NeedsSetup -> reason.toDisplayText()
            TermuxBridgeStatus.SetupInProgress -> "This may take a minute"
            TermuxBridgeStatus.Ready -> "Termux bridge running — tap to restart"
            TermuxBridgeStatus.Disabled -> "Toggle to enable"
        }

private fun NeedsSetupReason.toDisplayText(): String =
    when (this) {
        NeedsSetupReason.PERMISSION_MISSING -> "RUN_COMMAND permission missing. Grant the permission, then tap setup."
        NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING ->
            "Allow external apps is disabled in Termux. Enable it, then tap setup."
        NeedsSetupReason.TERMUX_NOT_RUNNING ->
            "Termux is not running. Tap to open Termux, then return here."
        NeedsSetupReason.TERMUX_RUN_COMMAND_UNAVAILABLE ->
            "This Termux build cannot accept external commands. Install Termux from F-Droid (the Google Play build is incompatible)."
        NeedsSetupReason.PACKAGES_MISSING -> "Missing packages — tap to install python/git/ripgrep"
        NeedsSetupReason.BRIDGE_OUTDATED -> "Bridge daemon out of date — tap to update"
        NeedsSetupReason.HEALTH_TIMEOUT -> "Bridge unreachable — tap to retry setup"
        NeedsSetupReason.TERMUX_TIMEOUT -> "Termux command timed out — open Termux once and retry"
        NeedsSetupReason.PORT_IN_USE -> "Port 18422 in use by another process"
        NeedsSetupReason.UNKNOWN -> "Setup error — tap to retry"
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
