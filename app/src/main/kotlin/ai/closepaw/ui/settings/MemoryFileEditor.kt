package ai.closepaw.ui.settings

import ai.closepaw.app.MemoryEditGate
import ai.closepaw.memory.MemoryScope
import ai.closepaw.memory.MemoryStore
import ai.closepaw.memory.SaveResult
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Free-text editor for a single memory file (`user.md`, `device.md`,
 * `apps/<pkg>.md`). Two variants:
 *
 *  - **bounded** (`bounded = true`): height capped at 240.dp + internal
 *    scroll. Renders an "↗ Open" affordance that is disabled while the
 *    editor is dirty (prevents the double-buffer race with a full-page
 *    editor).
 *  - **unbounded** (`bounded = false`): fills available space; used inside
 *    [MemoryFileEditorPage] and the standalone Settings → Memory page.
 *
 * Both observe [MemoryEditGate.memoryEditLocked]. When locked:
 *  - Save / Discard / Delete are disabled and a banner is shown.
 *  - The typed buffer is preserved (we do not pop the user out of EDIT).
 *
 * Action-time TOCTOU: every save/delete handler re-reads
 * `memoryEditLocked.value` inside the coroutine immediately before
 * [MemoryStore.write] / [MemoryStore.delete]. If a session began between
 * the click and the IO, the write aborts and a toast is shown — the file
 * on disk is never touched.
 */

internal const val MEMORY_EDIT_LOCKED_BANNER =
    "Session is open. Stop the session to edit memory."

internal const val MEMORY_EDIT_ABORT_TOAST =
    "Memory edit aborted — a session just started."

internal const val MEMORY_EDITOR_TEXTFIELD_TAG = "memory-editor-textfield"
internal const val MEMORY_EDITOR_OPEN_FULL_TAG = "memory-editor-open-full"
internal const val MEMORY_EDITOR_SAVE_TAG = "memory-editor-save"
internal const val MEMORY_EDITOR_DISCARD_TAG = "memory-editor-discard"
internal const val MEMORY_EDITOR_DELETE_TAG = "memory-editor-delete"
internal const val MEMORY_EDITOR_EDIT_TAG = "memory-editor-edit"
internal const val MEMORY_EDITOR_BANNER_TAG = "memory-editor-banner"
internal const val MEMORY_EDITOR_DELETE_CONFIRM_TAG = "memory-editor-delete-confirm"

private enum class Mode { VIEW, EDIT }

@Composable
internal fun MemoryFileEditor(
    memoryStore: MemoryStore,
    scope: MemoryScope,
    packageName: String?,
    gate: MemoryEditGate,
    bounded: Boolean,
    modifier: Modifier = Modifier,
    onOpenFull: (() -> Unit)? = null,
    onSaved: (() -> Unit)? = null,
    onDeleted: (() -> Unit)? = null,
    onAborted: (() -> Unit)? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val locked by gate.memoryEditLocked.collectAsStateWithLifecycle()

    // Saved keys: scope + package. Re-opening the same file restores buffer.
    val saveKey = "memory-editor:${scope.wireValue}:${packageName ?: ""}"

    var loaded by rememberSaveable(saveKey) { mutableStateOf<String?>(null) }
    var loadingDone by rememberSaveable(saveKey) { mutableStateOf(false) }
    var buffer by rememberSaveable(saveKey) { mutableStateOf("") }
    var mode by rememberSaveable(saveKey) { mutableStateOf(Mode.VIEW) }
    var writing by remember(saveKey) { mutableStateOf(false) }
    var inlineError by remember(saveKey) { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember(saveKey) { mutableStateOf(false) }

    LaunchedEffect(saveKey) {
        if (!loadingDone) {
            val text = withContext(ioDispatcher) { memoryStore.read(scope, packageName) }
            loaded = text
            buffer = text.orEmpty()
            loadingDone = true
        }
    }

    val dirty = mode == Mode.EDIT && buffer != loaded.orEmpty()

    fun abort() {
        Toast.makeText(context, MEMORY_EDIT_ABORT_TOAST, Toast.LENGTH_SHORT).show()
        onAborted?.invoke()
    }

    val onSave: () -> Unit = handle@{
        if (locked || writing) return@handle
        writing = true
        inlineError = null
        coroutineScope.launch {
            val content = buffer
            val result: SaveResult? = withContext(ioDispatcher) {
                // Action-time TOCTOU re-check: a session may have started
                // between the click and our IO dispatch.
                if (gate.memoryEditLocked.value) null
                else memoryStore.write(scope, packageName, content)
            }
            writing = false
            when (result) {
                null -> abort()
                SaveResult.Success -> {
                    loaded = content
                    mode = Mode.VIEW
                    onSaved?.invoke()
                }
                SaveResult.TooLarge ->
                    inlineError = "Memory files cap at ${memoryStore.maxFileBytes} bytes. Trim and try again."
                SaveResult.InvalidScope ->
                    inlineError = "Invalid memory target."
                is SaveResult.IoError ->
                    inlineError = "Save failed: ${result.message}"
            }
        }
    }

    val onDiscard: () -> Unit = handle@{
        if (locked || writing) return@handle
        buffer = loaded.orEmpty()
        inlineError = null
        mode = Mode.VIEW
    }

    val onConfirmDelete: () -> Unit = handle@{
        if (locked || writing) return@handle
        writing = true
        inlineError = null
        coroutineScope.launch {
            val ok: Boolean? = withContext(ioDispatcher) {
                if (gate.memoryEditLocked.value) null
                else memoryStore.delete(scope, packageName)
            }
            writing = false
            showDeleteConfirm = false
            when (ok) {
                null -> abort()
                true -> {
                    loaded = null
                    buffer = ""
                    mode = Mode.VIEW
                    onDeleted?.invoke()
                }
                false -> inlineError = "Delete failed."
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (locked) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MEMORY_EDITOR_BANNER_TAG),
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
            Spacer(modifier = Modifier.height(8.dp))
        }

        val readOnly = mode == Mode.VIEW
        val textFieldModifier = Modifier
            .fillMaxWidth()
            .testTag(MEMORY_EDITOR_TEXTFIELD_TAG)
            .semantics { contentDescription = "Memory file content" }
            .let { base ->
                // Bounded variant caps the text field height so 8 KB content
                // does not stretch the row. Internal scroll handles overflow.
                if (bounded) base.heightIn(min = 96.dp, max = 240.dp) else base
            }

        OutlinedTextField(
            value = buffer,
            onValueChange = { if (mode == Mode.EDIT) buffer = it },
            readOnly = readOnly,
            modifier = textFieldModifier,
            placeholder = {
                Text(
                    if (loadingDone) "No memory yet." else "Loading…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )

        if (inlineError != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = inlineError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (writing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .height(16.dp),
                    strokeWidth = 2.dp,
                )
            }

            Box(modifier = Modifier.weight(1f))

            // ↗ Open is bounded-only; disabled while dirty to prevent the
            // double-buffer race documented in the design.
            if (bounded && onOpenFull != null) {
                TextButton(
                    onClick = onOpenFull,
                    enabled = !dirty,
                    modifier = Modifier.testTag(MEMORY_EDITOR_OPEN_FULL_TAG),
                ) { Text("↗ Open") }
            }

            when (mode) {
                Mode.VIEW -> {
                    TextButton(
                        onClick = { if (!locked) mode = Mode.EDIT },
                        enabled = !locked && loadingDone,
                        modifier = Modifier.testTag(MEMORY_EDITOR_EDIT_TAG),
                    ) { Text("Edit") }
                    TextButton(
                        onClick = { if (!locked && !writing) showDeleteConfirm = true },
                        enabled = !locked && !writing && loaded != null,
                        modifier = Modifier.testTag(MEMORY_EDITOR_DELETE_TAG),
                    ) { Text("Delete") }
                }
                Mode.EDIT -> {
                    TextButton(
                        onClick = onDiscard,
                        enabled = !locked && !writing,
                        modifier = Modifier.testTag(MEMORY_EDITOR_DISCARD_TAG),
                    ) { Text("Discard") }
                    TextButton(
                        onClick = onSave,
                        enabled = !locked && !writing,
                        modifier = Modifier.testTag(MEMORY_EDITOR_SAVE_TAG),
                    ) { Text("Save") }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!writing) showDeleteConfirm = false },
            title = { Text("Delete memory?") },
            text = {
                Text(
                    "This removes the file. The agent may write a new one later.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmDelete,
                    enabled = !locked && !writing,
                    modifier = Modifier.testTag(MEMORY_EDITOR_DELETE_CONFIRM_TAG),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!writing) showDeleteConfirm = false },
                ) { Text("Cancel") }
            },
        )
    }
}
