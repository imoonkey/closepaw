package ai.closepaw.ui.settings

import ai.closepaw.agent.cognition.skills.AgentSkillCatalog
import ai.closepaw.agent.cognition.skills.AgentSkillEntry
import ai.closepaw.app.AppSettingsStore
import ai.closepaw.session.SessionServices
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "AgentSkillToggleRows"
private const val NEXT_SESSION_SUBTITLE = "Takes effect next session"

/**
 * Per-skill toggle rows inside the Agent Behavior → Tools section.
 *
 * Each row shows the skill name + a Switch that mirrors / mutates
 * [AppSettingsStore.disabledAgentSkills] (Switch ON = NOT in the disabled set).
 * An Info icon opens a viewer with the full SKILL.md prompt body.
 *
 * Activation timing is **next session**: [ai.closepaw.agent.cognition.skills.AgentSkillManager]
 * snapshots the disabled set at session start. When [isSessionRunning] is true, a
 * disabled skill's subtitle reads "Takes effect next session" to make the persisted
 * state obvious to the user.
 *
 * Bundled skills (e.g. browser-use) are installed once on first composition via
 * [SessionServices.installBundledAgentSkills] on [Dispatchers.IO] so that opening
 * Settings before any session has run still discovers the bundled catalog.
 */
@Composable
internal fun AgentSkillToggleRows(
    isSessionRunning: Boolean = false,
    skillsLoader: (suspend () -> List<AgentSkillLoaderResult>)? = null,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settingsStore = remember(appContext) { AppSettingsStore(appContext) }
    val disabledSkills by settingsStore.disabledAgentSkills.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val effectiveLoader = skillsLoader ?: rememberDefaultSkillsLoader(appContext)

    var loadState by remember { mutableStateOf<SkillsLoadState>(SkillsLoadState.Loading) }
    LaunchedEffect(effectiveLoader) {
        loadState = SkillsLoadState.Loading
        loadState = try {
            SkillsLoadState.Loaded(effectiveLoader.invoke())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load agent skill catalog", e)
            SkillsLoadState.Loaded(emptyList())
        }
    }

    val skills = (loadState as? SkillsLoadState.Loaded)?.entries ?: return
    if (skills.isEmpty()) return

    var viewerSkill by remember { mutableStateOf<AgentSkillLoaderResult?>(null) }

    // Top padding lives here (not in ToolsSection) so an empty/loading catalog leaves zero
    // gap under the preceding Tools rows. Only paid once the section actually renders.
    Spacer(modifier = Modifier.height(20.dp))
    SettingsSection(title = "Agent Skills") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            skills.forEach { result ->
                val skill = result.entry
                val isDisabled = skill.name in disabledSkills
                val isEnabled = !isDisabled
                val subtitle = when {
                    isDisabled && isSessionRunning -> NEXT_SESSION_SUBTITLE
                    else -> skill.description
                }
                ToolSettingsCard(
                    title = skill.name,
                    status = ToolStatusUi(
                        label = if (isEnabled) "Enabled" else "Disabled",
                        subtitle = subtitle,
                        tone = if (isEnabled) ToolStatusTone.Positive else ToolStatusTone.Neutral,
                    ),
                    switchChecked = isEnabled,
                    onSwitchChange = { checked ->
                        scope.launch {
                            settingsStore.setSkillDisabled(skill.name, !checked)
                        }
                    },
                    trailingInfoIcon = { viewerSkill = result },
                    trailingInfoIconLabel = "View ${skill.name} prompt",
                )
            }
        }
    }

    viewerSkill?.let { result ->
        SkillContentDialog(result = result, onDismiss = { viewerSkill = null })
    }
}

@Composable
private fun SkillContentDialog(
    result: AgentSkillLoaderResult,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .heightIn(max = 560.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = result.entry.name,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.padding(top = 4.dp))
                Text(
                    text = result.entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.padding(top = 12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = result.content,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.padding(top = 12.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/**
 * Result of loading a skill: the catalog entry plus the raw SKILL.md body
 * (front-matter stripped) so the viewer can show the prompt content directly.
 */
internal data class AgentSkillLoaderResult(
    val entry: AgentSkillEntry,
    val content: String,
)

private sealed interface SkillsLoadState {
    object Loading : SkillsLoadState
    data class Loaded(val entries: List<AgentSkillLoaderResult>) : SkillsLoadState
}

@Composable
private fun rememberDefaultSkillsLoader(
    appContext: android.content.Context,
): suspend () -> List<AgentSkillLoaderResult> = remember(appContext) {
    suspend {
        withContext(Dispatchers.IO) {
            val skillsDir = File(appContext.filesDir, "skills")
            try {
                SessionServices.installBundledAgentSkills(appContext, skillsDir)
            } catch (e: Exception) {
                // Already logged inside installBundledAgentSkills; proceed with whatever
                // is on disk so the UI still renders any previously-installed catalog.
                Log.w(TAG, "Bundled skill install failed; using existing disk state", e)
            }
            AgentSkillCatalog(skillsDir).allDiscovered().map { entry ->
                AgentSkillLoaderResult(entry = entry, content = readSkillBody(entry.filePath))
            }
        }
    }
}

private fun readSkillBody(filePath: String): String = try {
    val raw = File(filePath).readText()
    stripFrontMatter(raw)
} catch (e: Exception) {
    Log.w(TAG, "Failed to read SKILL.md at $filePath", e)
    ""
}

/**
 * Strip the YAML-style `---` ... `---` front-matter block from a SKILL.md so the viewer
 * shows just the prompt body. Matches what the agent runtime injects into the model.
 */
private fun stripFrontMatter(raw: String): String {
    val trimmed = raw.trimStart()
    if (!trimmed.startsWith("---")) return raw
    val afterOpen = trimmed.indexOf('\n')
    if (afterOpen < 0) return raw
    val rest = trimmed.substring(afterOpen + 1)
    val closeMarker = rest.indexOf("\n---")
    if (closeMarker < 0) return raw
    val afterClose = rest.indexOf('\n', closeMarker + 1)
    return if (afterClose < 0) "" else rest.substring(afterClose + 1).trimStart()
}

// Visible to androidTest so the compose test can inject deterministic skill content
// without touching filesDir/skills via the `skillsLoader` parameter on AgentSkillToggleRows.
