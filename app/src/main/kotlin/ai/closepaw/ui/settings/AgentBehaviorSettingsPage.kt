package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.theme.PageMastheadDrillDown

@Composable
internal fun AgentBehaviorSettingsPage(
    perceptionMode: String,
    onPerceptionModeChange: (String) -> Unit,
    platformMode: PlatformMode,
    effectivePlatformMode: PlatformMode?,
    onPlatformModeChange: (PlatformMode) -> Unit,
    browserScriptEnabled: Boolean,
    onBrowserScriptEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    isSessionRunning: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PageMastheadDrillDown(title = "Agent Behavior", onBack = onBack, onClose = onClose)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            SettingsSection(title = "Perception") {
                PerceptionModeSelector(selectedMode = perceptionMode, onModeChange = onPerceptionModeChange)
            }
            Spacer(modifier = Modifier.height(20.dp))
            DisplaySection(
                persistedMode = platformMode,
                effectiveMode = effectivePlatformMode,
                onPlatformModeChange = onPlatformModeChange,
            )
            Spacer(modifier = Modifier.height(20.dp))
            ToolsSection(
                browserScriptEnabled = browserScriptEnabled,
                onBrowserScriptEnabledChange = onBrowserScriptEnabledChange,
                isSessionRunning = isSessionRunning,
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
