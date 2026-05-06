package ai.closepaw.ui.settings

import ai.closepaw.browser.setup.ChromeCdpProbe
import ai.closepaw.browser.setup.ChromeFlagDeepLink
import ai.closepaw.browser.setup.CommandLineWriter
import ai.closepaw.browser.setup.ShizukuShellRunner
import ai.closepaw.platform.virtualdisplay.ShizukuClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Tools" section for the Agent Behavior settings page. Currently hosts a single tool
 * (`browser_script`); future tool toggles join here. ADDITIVE — does not replace the
 * Experimental toggle on the Permissions & Advanced page; both bind to the same pref via the
 * shared [ai.closepaw.app.AppSettingsState] threaded through SettingsSheet.
 *
 * Toggle ON path is gated. Browser_script depends on Shizuku (to write
 * `/data/local/tmp/chrome-command-line` and to drive `am start` for the chrome flag deep
 * link). If Shizuku is unavailable, persisting the pref ON would leave the user staring at a
 * permanently-✗ status row with no actionable feedback. Instead:
 *
 * 1. Check Shizuku availability + permission first. If not Ready, show an inline error and
 *    DO NOT persist.
 * 2. Run [CommandLineWriter.ensureWritten] and await it. If the write fails, surface the
 *    error inline AND revert the toggle locally.
 * 3. Only on success does the pref propagate. Toggle OFF is unconditional — it can never make
 *    things worse.
 */
@Composable
internal fun ToolsSection(
    browserScriptEnabled: Boolean,
    onBrowserScriptEnabledChange: (Boolean) -> Unit,
) {
    SettingsSection(title = "Tools") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BrowserScriptToolRow(
                enabled = browserScriptEnabled,
                onEnabledChange = onBrowserScriptEnabledChange,
            )
        }
    }
}

private sealed interface ToggleError {
    data object ShizukuUnavailable : ToggleError
    data object ShizukuNeedsPermission : ToggleError
    data object WriteFailed : ToggleError
}

private fun ToggleError.message(): String = when (this) {
    ToggleError.ShizukuUnavailable ->
        "Shizuku is not running. Start Shizuku first, then enable browser_script."
    ToggleError.ShizukuNeedsPermission ->
        "Grant Shizuku permission to ClosePaw, then re-enable browser_script."
    ToggleError.WriteFailed ->
        "Could not write Chrome's command-line file. Check Shizuku and try again."
}

@Composable
private fun BrowserScriptToolRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val shizukuClient = remember { ShizukuClient() }
    val writer = remember { CommandLineWriter() }
    var pendingEnable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ToggleError?>(null) }

    fun attemptEnable() {
        error = null
        pendingEnable = true
        scope.launch {
            val gate = withContext(Dispatchers.IO) { evaluateShizuku(shizukuClient) }
            if (gate != null) {
                error = gate
                pendingEnable = false
                return@launch
            }
            val outcome = writer.ensureWritten()
            pendingEnable = false
            if (outcome == CommandLineWriter.Outcome.Failed) {
                error = ToggleError.WriteFailed
                return@launch
            }
            onEnabledChange(true)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "browser_script",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Drive Chrome via DevTools (CDP)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pendingEnable) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Switch(
                    checked = enabled,
                    enabled = !pendingEnable,
                    onCheckedChange = { value ->
                        if (value) {
                            attemptEnable()
                        } else {
                            error = null
                            onEnabledChange(false)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }

            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it.message(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (enabled && error == null) {
                Spacer(modifier = Modifier.height(12.dp))
                BrowserCdpStatusRow()
            }
        }
    }
}

/**
 * Shizuku availability check. Returns null when Ready (toggle may proceed), or the appropriate
 * [ToggleError] otherwise. Mirrors [rememberShizukuStatus] but synchronous so we can gate the
 * persist + write inside the same coroutine.
 */
private fun evaluateShizuku(client: ShizukuClient): ToggleError? = when {
    !client.isAvailable() -> ToggleError.ShizukuUnavailable
    !client.hasPermission() -> ToggleError.ShizukuNeedsPermission
    else -> null
}

private sealed interface CdpStatusUi {
    data object Probing : CdpStatusUi
    data object Bound : CdpStatusUi
    data object NotBound : CdpStatusUi
    data object Unknown : CdpStatusUi
}

@Composable
private fun BrowserCdpStatusRow() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val shellRunner = remember { ShizukuShellRunner() }
    val probe = remember(shellRunner) { ChromeCdpProbe(shellRunner = shellRunner) }
    val deepLink = remember(appContext, shellRunner) {
        ChromeFlagDeepLink(context = appContext, shellRunner = shellRunner)
    }
    var status by remember { mutableStateOf<CdpStatusUi>(CdpStatusUi.Probing) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        status = CdpStatusUi.Probing
        status = when (probe.probe()) {
            ChromeCdpProbe.Result.Bound -> CdpStatusUi.Bound
            ChromeCdpProbe.Result.NotBound -> CdpStatusUi.NotBound
            ChromeCdpProbe.Result.Unknown -> CdpStatusUi.Unknown
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val s = status) {
            CdpStatusUi.Probing -> StatusLine(
                label = "Checking Chrome devtools socket…",
                showSpinner = true,
            )
            CdpStatusUi.Bound -> StatusLine(label = "✓ Active")
            CdpStatusUi.NotBound, CdpStatusUi.Unknown -> {
                StatusLine(
                    label = if (s is CdpStatusUi.NotBound) {
                        "✗ Chrome devtools socket not exposed"
                    } else {
                        "✗ Could not check status (Shizuku?)"
                    },
                )
                Text(
                    text = "Open Chrome's flags page, enable " +
                            "“Enable command line on non-rooted devices”, " +
                            "then restart Chrome.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { scope.launch { deepLink.open() } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(text = "Open chrome://flags →", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = { refreshTick++ },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(text = "Re-check", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, showSpinner: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
