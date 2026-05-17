package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.closepaw.BuildConfig
import ai.closepaw.llm.AuthMode
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.platform.AppManager
import ai.closepaw.protocol.AppTier
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.tool.AppClassifier
import ai.closepaw.ui.theme.Fleuron
import ai.closepaw.ui.theme.PageMasthead
import ai.closepaw.ui.theme.SectionHeader
import ai.closepaw.ui.theme.closePaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsHomePage(
    llmBackend: LLMBackendType,
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    selectedLocalModel: String,
    modelCatalog: ModelCatalog,
    perceptionMode: String,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    debugMode: Boolean,
    effectivePlatformMode: PlatformMode?,
    appClassifier: AppClassifier,
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
                .verticalScroll(rememberScrollState())
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
                subtitle = agentBehaviorSubtitle(perceptionMode),
                onClick = { onNavigate(SettingsPage.AGENT_BEHAVIOR) }
            )

            SectionHeader("Access")
            SettingsNavigationRow(
                title = "Permissions & Advanced",
                subtitle = permissionsSubtitle(isAccessibilityEnabled, isOverlayEnabled, debugMode, effectivePlatformMode),
                onClick = { onNavigate(SettingsPage.PERMISSIONS_ADVANCED) }
            )
            SettingsNavigationRow(
                title = "App Access",
                subtitle = appAccessSubtitle(appClassifier),
                onClick = { onNavigate(SettingsPage.APP_ACCESS) }
            )

            SectionHeader("About")
            SettingsNavigationRow(
                title = "Open Source Licenses",
                subtitle = "ClosePaw is Apache 2.0 · view third-party notices",
                onClick = { onNavigate(SettingsPage.OPEN_SOURCE_LICENSES) }
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
    perceptionMode: String
): String {
    return when (perceptionMode) {
        "hybrid" -> "Hybrid"
        "screenshot_only" -> "Screenshot"
        else -> "Accessibility"
    }
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

/**
 * Subtitle for the App Access entry on the Settings home page.
 *
 * Counts installed apps per effective tier (allow / ask / reject). The installed
 * app set is effectively constant, but tier classification depends on the live
 * `userOverrides` map, so we re-scan when overrides change. While the IO scan
 * is in flight, render `…` rather than block.
 */
@Composable
private fun appAccessSubtitle(classifier: AppClassifier): String {
    val context = LocalContext.current
    val overrides by classifier.userOverrides.collectAsState()
    val counts by produceState<Triple<Int, Int, Int>?>(
        initialValue = null, context, classifier, overrides,
    ) {
        value = withContext(Dispatchers.IO) {
            var allow = 0
            var ask = 0
            var reject = 0
            AppManager.getInstalledApps(context.packageManager).forEach { info ->
                when (classifier.classify(info.packageName)) {
                    AppTier.NORMAL -> allow++
                    AppTier.CAUTIOUS -> ask++
                    AppTier.BLOCKED -> reject++
                }
            }
            Triple(allow, ask, reject)
        }
    }
    return counts?.let { (a, k, r) -> "$a Allow · $k Ask · $r Reject" } ?: "…"
}
