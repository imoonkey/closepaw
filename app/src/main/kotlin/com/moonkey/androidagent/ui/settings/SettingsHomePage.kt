package com.moonkey.androidagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moonkey.androidagent.BuildConfig
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType

@Composable
internal fun SettingsHomePage(
    llmBackend: LLMBackendType,
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    selectedLocalModel: String,
    agentMode: AgentMode,
    maxTurns: Int,
    perceptionMode: String,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    debugMode: Boolean,
    onNavigate: (SettingsPage) -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsHeader(onClose = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsNavigationRow(
                title = "LLM & Authentication",
                subtitle = llmSubtitle(llmBackend, selectedModel, modelOptions, selectedLocalModel),
                onClick = { onNavigate(SettingsPage.LLM_AUTH) }
            )

            SettingsNavigationRow(
                title = "Agent Behavior",
                subtitle = agentBehaviorSubtitle(agentMode, maxTurns, perceptionMode),
                onClick = { onNavigate(SettingsPage.AGENT_BEHAVIOR) }
            )

            SettingsNavigationRow(
                title = "Permissions & Advanced",
                subtitle = permissionsSubtitle(isAccessibilityEnabled, isOverlayEnabled, debugMode),
                onClick = { onNavigate(SettingsPage.PERMISSIONS_ADVANCED) }
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    selectedLocalModel: String
): String = if (llmBackend == LLMBackendType.LOCAL) {
    AVAILABLE_LOCAL_MODELS.find { it.id == selectedLocalModel }?.displayName ?: selectedLocalModel
} else {
    val modelName = modelOptions.find { it.first == selectedModel }?.second ?: selectedModel
    "$modelName · API key"
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
    debugMode: Boolean
): String {
    val grantedCount = listOf(isAccessibilityEnabled, isOverlayEnabled).count { it }
    val permSummary = when (grantedCount) {
        2 -> "All granted"
        1 -> "1 of 2 granted"
        else -> "Setup required"
    }
    return "$permSummary · Debug ${if (debugMode) "on" else "off"}"
}
