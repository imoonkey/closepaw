package ai.closepaw.ui.settings

import ai.closepaw.app.MemoryEditGate
import ai.closepaw.memory.MemoryScope
import ai.closepaw.memory.MemoryStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Full-screen variant of [MemoryFileEditor] for the standalone Settings →
 * Memory page (and the bounded inline editor's "↗ Open" affordance). The
 * unbounded editor body fills available space.
 */
@Composable
internal fun MemoryFileEditorPage(
    title: String,
    memoryStore: MemoryStore,
    scope: MemoryScope,
    packageName: String?,
    gate: MemoryEditGate,
    onBack: () -> Unit,
    onClose: () -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsSubPageHeader(title = title, onBack = onBack, onClose = onClose)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            MemoryFileEditor(
                memoryStore = memoryStore,
                scope = scope,
                packageName = packageName,
                gate = gate,
                bounded = false,
                onDeleted = onBack,
                ioDispatcher = ioDispatcher,
            )
        }
    }
}
