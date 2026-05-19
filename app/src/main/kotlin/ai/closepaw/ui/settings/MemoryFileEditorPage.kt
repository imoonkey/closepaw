package ai.closepaw.ui.settings

import ai.closepaw.app.MemoryEditGate
import ai.closepaw.memory.MemoryScope
import ai.closepaw.memory.MemoryStore
import ai.closepaw.ui.theme.PageMastheadDrillDown
import ai.closepaw.ui.theme.closePaw
import ai.closepaw.ui.theme.foldedPaper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
    onDeleted: (() -> Unit)? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PageMastheadDrillDown(title = title, onBack = onBack, onClose = onClose)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = MaterialTheme.closePaw.spacing.lg)
                .foldedPaper(MaterialTheme.shapes.medium),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(MaterialTheme.closePaw.spacing.cardPadding),
            ) {
                MemoryFileEditor(
                    memoryStore = memoryStore,
                    scope = scope,
                    packageName = packageName,
                    gate = gate,
                    bounded = false,
                    modifier = Modifier.fillMaxSize(),
                    onDeleted = {
                        onDeleted?.invoke()
                        onBack()
                    },
                    ioDispatcher = ioDispatcher,
                )
            }
        }
    }
}
