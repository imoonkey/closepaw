package ai.closepaw.ui.settings

import ai.closepaw.app.MemoryEditGate
import ai.closepaw.memory.MemoryScope
import ai.closepaw.memory.MemoryStore
import ai.closepaw.ui.theme.closePaw
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val APP_EXPANSION_ROOT_TAG = "app-expansion-root"
internal const val APP_EXPANSION_SKILL_BODY_TAG = "app-expansion-skill-body"
internal const val APP_EXPANSION_BLOCKED_WARNING_TAG = "app-expansion-blocked-warning"
internal const val APP_EXPANSION_ADD_MEMORY_TAG = "app-expansion-add-memory"

internal const val APP_BLOCKED_MEMORY_WARNING =
    "This app is set to Reject. Reject only blocks the agent from writing this memory; " +
        "saved entries are still recalled when this app is foreground."

/**
 * Inline expanded area under an App Access row. Composed of:
 *  - (optional) read-only App Skill viewer when [skillLoader] returns a non-null
 *    body. Pre-loaded once on `Dispatchers.IO`.
 *  - (optional) [MemoryFileEditor] (bounded variant) for `apps/<pkg>.md`.
 *    Rendered when either a memory file already exists for the package OR the
 *    caller indicated the row was just created via the `+ Memory` chip.
 *  - Blocked-app warning chip above the editor when [isBlocked] is true. The
 *    inline editor is intentionally NOT disabled — a Settings edit is the
 *    user's explicit consent (the agent-side write gate exists to require
 *    this consent, not to be redundant with it).
 *
 * Save/delete propagate to [onMemoryPresenceChanged] so the page-scoped
 * [AppAccessContentIndex] can refresh the summary chip in O(1) without
 * re-scanning the filesystem.
 */
@Composable
internal fun AppRowExpansion(
    packageName: String,
    isBlocked: Boolean,
    showMemoryEditor: Boolean,
    skillLoader: suspend (String) -> String?,
    memoryStore: MemoryStore,
    gate: MemoryEditGate,
    onMemoryPresenceChanged: (hasMemory: Boolean) -> Unit,
    onAddMemory: (() -> Unit)? = null,
    addMemoryLocked: Boolean = false,
    onOpenFullMemoryEditor: (() -> Unit)? = null,
    startInEditNonce: String? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    modifier: Modifier = Modifier,
) {
    val skillBodyState = produceState<SkillLoad>(initialValue = SkillLoad.Loading, packageName) {
        value = SkillLoad.Loaded(withContext(ioDispatcher) { skillLoader(packageName) })
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(APP_EXPANSION_ROOT_TAG),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val skillState = skillBodyState.value
        if (skillState is SkillLoad.Loaded && skillState.body != null) {
            AppSkillViewer(body = skillState.body)
        }

        if (showMemoryEditor) {
            SectionLabel(text = "App Memory")
            if (isBlocked) {
                BlockedAppWarning()
            }
            MemoryFileEditor(
                memoryStore = memoryStore,
                scope = MemoryScope.APP,
                packageName = packageName,
                gate = gate,
                bounded = true,
                onOpenFull = onOpenFullMemoryEditor,
                onSaved = { onMemoryPresenceChanged(true) },
                onDeleted = { onMemoryPresenceChanged(false) },
                startInEditOnce = startInEditNonce,
                ioDispatcher = ioDispatcher,
            )
        } else if (onAddMemory != null) {
            // Skill-only app (or empty row that was force-expanded): surface a
            // "+ Memory" affordance inside the expansion so users don't have
            // to collapse and chase the trailing-slot chip, which is hidden
            // for skill-bearing rows by design.
            AddMemoryChip(onAddMemory = onAddMemory, enabled = !addMemoryLocked)
        }
    }
}

@Composable
private fun AppSkillViewer(body: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(text = "App Skill")
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.small,
            tonalElevation = 1.dp,
        ) {
            // Bounded internal scroll so a long SKILL.md body cannot push the
            // memory editor or tier selector out of reach inside the row.
            Column(
                modifier = Modifier
                    .heightIn(min = 0.dp, max = 200.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.closePaw.monoSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(APP_EXPANSION_SKILL_BODY_TAG),
                )
            }
        }
    }
}

@Composable
private fun BlockedAppWarning() {
    SettingsAlertCard(
        message = APP_BLOCKED_MEMORY_WARNING,
        tone = AlertTone.Warning,
        icon = Icons.Outlined.Warning,
        modifier = Modifier.testTag(APP_EXPANSION_BLOCKED_WARNING_TAG),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.closePaw.inkFaint,
    )
}

@Composable
private fun AddMemoryChip(onAddMemory: () -> Unit, enabled: Boolean) {
    Surface(
        onClick = onAddMemory,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag(APP_EXPANSION_ADD_MEMORY_TAG),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.closePaw.inkFaint,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Memory",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.closePaw.inkFaint,
            )
        }
    }
}

private sealed interface SkillLoad {
    object Loading : SkillLoad
    data class Loaded(val body: String?) : SkillLoad
}
