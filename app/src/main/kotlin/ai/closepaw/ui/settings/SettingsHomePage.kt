package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.closepaw.BuildConfig
import ai.closepaw.llm.AuthMode
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.protocol.AgentMode
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.theme.Fleuron
import ai.closepaw.ui.theme.PageMasthead
import ai.closepaw.ui.theme.SectionHeader
import ai.closepaw.ui.theme.closePaw
import ai.closepaw.ui.theme.todayLabel

@Composable
internal fun SettingsHomePage(
    llmBackend: LLMBackendType,
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    selectedLocalModel: String,
    modelCatalog: ModelCatalog,
    agentMode: AgentMode,
    maxTurns: Int,
    perceptionMode: String,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    debugMode: Boolean,
    effectivePlatformMode: PlatformMode?,
    onNavigate: (SettingsPage) -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageMasthead(
                title = "Settings",
                rightSlot = todayLabel(),
                leadingPaw = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionHeader("Voice")
            SettingsNavigationRow(
                title = "LLM & Authentication",
                subtitle = llmSubtitle(llmBackend, selectedModel, modelOptions, selectedLocalModel, modelCatalog),
                onClick = { onNavigate(SettingsPage.LLM_AUTH) }
            )

            SectionHeader("Behavior")
            SettingsNavigationRow(
                title = "Agent Behavior",
                subtitle = agentBehaviorSubtitle(agentMode, maxTurns, perceptionMode),
                onClick = { onNavigate(SettingsPage.AGENT_BEHAVIOR) }
            )

            SectionHeader("System")
            SettingsNavigationRow(
                title = "Permissions & Advanced",
                subtitle = permissionsSubtitle(isAccessibilityEnabled, isOverlayEnabled, debugMode, effectivePlatformMode),
                onClick = { onNavigate(SettingsPage.PERMISSIONS_ADVANCED) }
            )

            Fleuron()

            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.closePaw.monoSmall,
                color = MaterialTheme.closePaw.inkFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun llmSubtitle(
    llmBackend: LLMBackendType,
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    selectedLocalModel: String,
    modelCatalog: ModelCatalog,
): String = if (llmBackend == LLMBackendType.LOCAL) {
    AVAILABLE_LOCAL_MODELS.find { it.id == selectedLocalModel }?.displayName ?: selectedLocalModel
} else {
    val modelName = modelOptions.find { it.first == selectedModel }?.second ?: selectedModel
    val mode = modelCatalog.resolveOrNull(selectedModel)?.provider?.mode
    val authLabel = when (mode) {
        AuthMode.OAuth -> "OAuth"
        AuthMode.ApiKey -> "API key"
        AuthMode.Local, null -> "API key"
    }
    "$modelName · $authLabel"
}

private fun agentBehaviorSubtitle(
    agentMode: AgentMode,
    maxTurns: Int,
    perceptionMode: String
): String {
    val mode = if (agentMode == AgentMode.PRO) "Pro" else "Basic"
    val perception = when (perceptionMode) {
        "hybrid" -> "Hybrid"
        "screenshot_only" -> "Screenshot"
        else -> "Accessibility"
    }
    return "$mode · $maxTurns turns · $perception"
}

private fun permissionsSubtitle(
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    debugMode: Boolean,
    effectivePlatformMode: PlatformMode?,
): String {
    val grantedCount = listOf(isAccessibilityEnabled, isOverlayEnabled).count { it }
    val permSummary = when (grantedCount) {
        2 -> "All granted"
        1 -> "1 of 2 granted"
        else -> "Setup required"
    }
    val modeChip = when (effectivePlatformMode) {
        PlatformMode.VIRTUAL_DISPLAY -> " · VD"
        PlatformMode.ACCESSIBILITY -> " · A11y"
        null -> ""
    }
    return "$permSummary · Debug ${if (debugMode) "on" else "off"}$modeChip"
}
