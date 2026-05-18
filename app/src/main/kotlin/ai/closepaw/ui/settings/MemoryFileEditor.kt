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
 *  - The text field becomes `readOnly` so the buffer cannot drift further.
 *  - The typed buffer is held in memory across the lock so the user is not
 *    yanked out of EDIT mid-session.
 *
 * Locked → unlocked transition: the agent may have appended to the file on
 * disk while the editor was locked, so the buffer must be re-validated. We
 * always reload from disk; if the buffer was dirty, a one-line notice tells
 * the user their unsaved edits were dropped in favor of the agent's writes
 * (single-writer model — the agent owns memory during a session). The disk
 * read is async (withContext on the IO dispatcher); during that window a
 * `reloading` flag stands in for `locked` on every action surface so a
 * stale pre-session buffer cannot clobber the agent's append between the
 * lock flip and the buffer refresh.
 *
 * Action-time TOCTOU: every save/delete handler re-checks
 * `gate.isLockedNow()` inside the coroutine immediately before
 * [MemoryStore.write] / [MemoryStore.delete]. If a session began between
 * the click and the IO, the write aborts and a toast is shown — the file
 * on disk is never touched. `isLockedNow()` reads
 * `SessionCoordinator.currentSessionState.value` directly, bypassing the
 * map-collector tick that `memoryEditLocked` rides on.
 */

internal const val MEMORY_EDIT_LOCKED_BANNER =
    "Session is open. Stop the session to edit memory."

internal const val MEMORY_EDIT_ABORT_TOAST =
    "Memory edit aborted — a session just started."

internal const val MEMORY_EDIT_RELOAD_TOAST =
    "Memory updated during session — reloaded from disk."

internal const val MEMORY_EDIT_RELOADING_BANNER =
    "Refreshing memory from disk…"

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
    // One-shot signal: when this nonce changes (and the editor's saveKey
    // matches the file being created), the editor enters EDIT mode after
    // load. Used by App Access "+ Memory" so the user lands directly in
    // an editable buffer. Consumed via a remembered "last-seen" nonce so
    // recomposition won't re-trigger on subsequent renders.
    startInEditOnce: String? = null,
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
    // Post-unlock disk reload is async (withContext on ioDispatcher). Between
    // the locked→unlocked flip and the reload completing, the buffer is still
    // the pre-session value: enabling Save in that window would clobber any
    // agent appends. Treat `reloading` as functionally equivalent to `locked`
    // for every action surface (buttons disabled, text field readOnly, IO-time
    // re-check inside save/delete coroutines).
    val reloadingState = remember(saveKey) { mutableStateOf(false) }
    var reloading by reloadingState

    LaunchedEffect(saveKey) {
        if (!loadingDone) {
            val text = withContext(ioDispatcher) { memoryStore.read(scope, packageName) }
            loaded = text
            buffer = text.orEmpty()
            loadingDone = true
        }
    }

    // Locked → unlocked transition: the session that just ended may have
    // appended to the file on disk while we were locked. Always reload to
    // avoid clobbering agent writes when the user next hits Save. If the
    // buffer was dirty, the unsaved edits are dropped and we surface a
    // one-line toast — single-writer model means the agent owns memory
    // during a session, and the user accepts that on session start.
    var wasLocked by remember(saveKey) { mutableStateOf(locked) }
    LaunchedEffect(saveKey, locked, loadingDone) {
        if (!loadingDone) return@LaunchedEffect
        val previouslyLocked = wasLocked
        wasLocked = locked
        if (previouslyLocked && !locked) {
            reloading = true
            try {
                val disk = withContext(ioDispatcher) { memoryStore.read(scope, packageName) }
                val wasDirty = buffer != loaded.orEmpty()
                loaded = disk
                buffer = disk.orEmpty()
                if (wasDirty) {
                    Toast.makeText(context, MEMORY_EDIT_RELOAD_TOAST, Toast.LENGTH_SHORT).show()
                }
            } finally {
                reloading = false
            }
        }
    }

    // Consume the one-shot start-in-edit signal exactly once per nonce. The
    // remembered "last-seen" string survives recomposition but not process
    // death — matching the saveKey-scoped buffer state semantics. We only
    // honor the signal while unlocked, so a session that started during the
    // creation race won't strand the user in an EDIT view of a locked file.
    var lastSeenEditNonce by remember(saveKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(saveKey, startInEditOnce, loadingDone, locked, reloading) {
        if (loadingDone && !locked && !reloading &&
            startInEditOnce != null &&
            startInEditOnce != lastSeenEditNonce
        ) {
            lastSeenEditNonce = startInEditOnce
            mode = Mode.EDIT
        }
    }

    val dirty = mode == Mode.EDIT && buffer != loaded.orEmpty()

    fun abort() {
        Toast.makeText(context, MEMORY_EDIT_ABORT_TOAST, Toast.LENGTH_SHORT).show()
        onAborted?.invoke()
    }

    val onSave: () -> Unit = handle@{
        if (locked || reloading || writing) return@handle
        writing = true
        inlineError = null
        coroutineScope.launch {
            val content = buffer
            val result: SaveResult? = withContext(ioDispatcher) {
                // Action-time TOCTOU re-check: a session may have started or a
                // post-unlock reload may still be in flight between the click
                // and our IO dispatch. `gate.isLockedNow()` uses the
                // synchronous SessionCoordinator snapshot; `reloadingState`
                // is read fresh so a reload that armed after the click also
                // aborts.
                if (gate.isLockedNow() || reloadingState.value) null
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
        if (locked || reloading || writing) return@handle
        buffer = loaded.orEmpty()
        inlineError = null
        mode = Mode.VIEW
    }

    val onConfirmDelete: () -> Unit = handle@{
        if (locked || reloading || writing) return@handle
        writing = true
        inlineError = null
        coroutineScope.launch {
            val ok: Boolean? = withContext(ioDispatcher) {
                if (gate.isLockedNow() || reloadingState.value) null
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
        if (locked || reloading) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MEMORY_EDITOR_BANNER_TAG),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = if (locked) MEMORY_EDIT_LOCKED_BANNER else MEMORY_EDIT_RELOADING_BANNER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // readOnly when in VIEW mode OR while the gate is locked OR while a
        // post-unlock disk reload is in flight. The locked/reloading cases
        // keep the in-memory buffer stable so the reload has a clean
        // dirty-vs-not signal and so the user can't type into a buffer that
        // is about to be overwritten by the disk read.
        val readOnly = mode == Mode.VIEW || locked || reloading
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
            onValueChange = { if (mode == Mode.EDIT && !locked && !reloading) buffer = it },
            readOnly = readOnly,
            modifier = textFieldModifier,
            placeholder = {
                Text(
                    if (loadingDone) "No memory yet." else "Loading…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
            shape = MaterialTheme.shapes.small,
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
                        onClick = { if (!locked && !reloading) mode = Mode.EDIT },
                        enabled = !locked && !reloading && loadingDone,
                        modifier = Modifier.testTag(MEMORY_EDITOR_EDIT_TAG),
                    ) { Text("Edit") }
                    TextButton(
                        onClick = { if (!locked && !reloading && !writing) showDeleteConfirm = true },
                        enabled = !locked && !reloading && !writing && loaded != null,
                        modifier = Modifier.testTag(MEMORY_EDITOR_DELETE_TAG),
                    ) { Text("Delete") }
                }
                Mode.EDIT -> {
                    TextButton(
                        onClick = onDiscard,
                        enabled = !locked && !reloading && !writing,
                        modifier = Modifier.testTag(MEMORY_EDITOR_DISCARD_TAG),
                    ) { Text("Discard") }
                    TextButton(
                        onClick = onSave,
                        enabled = !locked && !reloading && !writing,
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
                    enabled = !locked && !reloading && !writing,
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
