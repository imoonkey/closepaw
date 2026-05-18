package ai.closepaw.ui.settings

import ai.closepaw.app.MemoryEditGate
import ai.closepaw.memory.MemoryScope
import ai.closepaw.memory.MemoryStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal const val MEMORY_SETTINGS_BANNER_TAG = "memory-settings-banner"
internal const val MEMORY_SETTINGS_USER_ROW_TAG = "memory-settings-user-row"
internal const val MEMORY_SETTINGS_DEVICE_ROW_TAG = "memory-settings-device-row"

/**
 * Settings → Memory. Lists User and Device memory entries. Per-app memory
 * is reached from App Access, not here.
 *
 * Internal navigation swaps between the list and [MemoryFileEditorPage]; Back
 * collapses the editor before exiting the page so callers see a single
 * `onBack` event per tap-out.
 */
@Composable
internal fun MemorySettingsPage(
    memoryStore: MemoryStore,
    gate: MemoryEditGate,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    var openScopeWire by rememberSaveable { mutableStateOf<String?>(null) }
    val openScope = openScopeWire?.let { MemoryScope.fromWireValue(it) }

    BackHandler(enabled = openScope != null) { openScopeWire = null }

    if (openScope != null) {
        MemoryFileEditorPage(
            title = openScope.pageTitle(),
            memoryStore = memoryStore,
            scope = openScope,
            packageName = null,
            gate = gate,
            onBack = { openScopeWire = null },
            onClose = onClose,
        )
        return
    }

    val locked by gate.memoryEditLocked.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSubPageHeader(title = "Memory", onBack = onBack, onClose = onClose)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (locked) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MEMORY_SETTINGS_BANNER_TAG),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = MEMORY_EDIT_LOCKED_BANNER,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            SettingsNavigationRow(
                title = "User Memory",
                subtitle = "Facts and preferences shared across every session",
                onClick = { openScopeWire = MemoryScope.USER.wireValue },
                modifier = Modifier.testTag(MEMORY_SETTINGS_USER_ROW_TAG),
            )
            SettingsNavigationRow(
                title = "Device Memory",
                subtitle = "Facts, pitfalls, and verification notes about this device",
                onClick = { openScopeWire = MemoryScope.DEVICE.wireValue },
                modifier = Modifier.testTag(MEMORY_SETTINGS_DEVICE_ROW_TAG),
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun MemoryScope.pageTitle(): String = when (this) {
    MemoryScope.USER -> "User Memory"
    MemoryScope.DEVICE -> "Device Memory"
    MemoryScope.APP -> "App Memory"
}
