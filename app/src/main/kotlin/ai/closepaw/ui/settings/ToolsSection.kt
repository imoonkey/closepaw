package ai.closepaw.ui.settings

import ai.closepaw.app.AppSettingsStore
import ai.closepaw.browser.setup.ChromeCdpProbe
import ai.closepaw.browser.setup.ChromeFlagDeepLink
import ai.closepaw.browser.setup.CommandLineWriter
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Tools" section for the Agent Behavior settings page. Currently hosts a single tool
 * (`browser_script`); future tool toggles join here. ADDITIVE — does not replace the
 * Experimental toggle on the Permissions & Advanced page; both bind to the same pref.
 *
 * The browser_script toggle drives two side effects when flipped ON:
 * 1. Probes Chrome's `chrome_devtools_remote` socket so we can show ✓/✗ status.
 * 2. Idempotently writes `/data/local/tmp/chrome-command-line` via Shizuku so Chrome will
 *    bind the socket once the user flips the chrome flag and restarts Chrome.
 *
 * The status row + CTA only render when the toggle is ON, keeping the off state minimal.
 */
@Composable
internal fun ToolsSection() {
    SettingsSection(title = "Tools") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BrowserScriptToolRow()
        }
    }
}

@Composable
private fun BrowserScriptToolRow() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settingsStore = remember(appContext) { AppSettingsStore(appContext) }
    val enabled by settingsStore.browserScriptEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

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
                Switch(
                    checked = enabled,
                    onCheckedChange = { value ->
                        scope.launch { settingsStore.setBrowserScriptEnabled(value) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }

            if (enabled) {
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
    val probe = remember { ChromeCdpProbe() }
    val writer = remember { CommandLineWriter() }
    val deepLink = remember(appContext) { ChromeFlagDeepLink(appContext) }
    var status by remember { mutableStateOf<CdpStatusUi>(CdpStatusUi.Probing) }
    var refreshTick by remember { mutableStateOf(0) }

    // Toggle ON triggers two parallel side effects: write the command-line file (silent,
    // idempotent — see CommandLineWriter docs) and probe the socket so the status row can
    // render within ~500ms.
    LaunchedEffect(refreshTick) {
        status = CdpStatusUi.Probing
        scope.launch(Dispatchers.IO) { writer.ensureWritten() }
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
