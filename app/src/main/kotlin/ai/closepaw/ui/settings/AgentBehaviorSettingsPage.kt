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
import ai.closepaw.protocol.AgentMode

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
                AgentModeDropdown(agentMode = agentMode, onAgentModeChange = onAgentModeChange)
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
