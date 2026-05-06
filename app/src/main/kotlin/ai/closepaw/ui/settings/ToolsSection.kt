package ai.closepaw.ui.settings

import ai.closepaw.browser.setup.ChromeCdpProbe
import ai.closepaw.browser.setup.ChromeFlagDeepLink
import ai.closepaw.browser.setup.ShizukuShellRunner
import android.widget.Toast
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
import kotlinx.coroutines.launch

/**
 * "Tools" section for the Agent Behavior settings page. Currently hosts a single tool
 * (`browser_script`); future tool toggles join here.
 *
 * Toggle ON path is gated by the shared [gateBrowserScriptEnable] helper — see
 * `BrowserScriptToggleGate.kt` for why both this surface AND Permissions & Advanced go
 * through the same gate.
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

@Composable
private fun BrowserScriptToolRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val gate = rememberBrowserScriptToggleGate(onPersist = onEnabledChange)

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
                if (gate.pending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Switch(
                    checked = enabled,
                    enabled = !gate.pending,
                    onCheckedChange = gate::setEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }

            gate.error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it.message(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (enabled && gate.error == null) {
                Spacer(modifier = Modifier.height(12.dp))
                BrowserCdpStatusRow()
            }
        }
    }
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
                // Honest status text. The previous "Could not check status (Shizuku?)" copy
                // implied Shizuku was missing, but on a real nubia P0110 we observed it firing
                // even when Shizuku was granted and active — the probe simply couldn't get a
                // definitive answer. Tell the user that, and surface the manual-paste path
                // below so they can recover without re-tapping Re-check forever.
                StatusLine(
                    label = if (s is CdpStatusUi.NotBound) {
                        "✗ Chrome devtools socket not exposed"
                    } else {
                        "? Cannot probe socket on this device"
                    },
                )
                Text(
                    text = if (s is CdpStatusUi.NotBound) {
                        "Open Chrome's flags page, enable " +
                                "“Enable command line on non-rooted devices”, " +
                                "then restart Chrome."
                    } else {
                        "If you've already enabled the flag and restarted Chrome, " +
                                "the agent will still try to connect when needed — the probe " +
                                "just couldn't read the system file on this device."
                    },
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
    }
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
