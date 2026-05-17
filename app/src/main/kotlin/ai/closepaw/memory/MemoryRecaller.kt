package ai.closepaw.memory

import android.util.Log

/**
 * Selects and formats deterministic memory for prompt injection each turn.
 *
 * Files are read raw (side-effect-free). Blank files and files larger than the
 * configured per-file cap are skipped — blank to avoid empty `## Recalled Memory`
 * sections from `+ Memory`-created placeholders, oversize to keep stale pre-cap
 * files from silently inflating every prompt.
 */
class MemoryRecaller(private val store: MemoryStore) {

    companion object {
        private const val TAG = "MemoryRecaller"
    }

    fun recall(currentPackageName: String?): String? {
        val blocks =
            buildList {
                store.read(MemoryScope.USER)?.let(::keepBlock)?.let(::add)
                store.read(MemoryScope.DEVICE)?.let(::keepBlock)?.let(::add)
                currentPackageName?.let { store.read(MemoryScope.APP, it) }
                    ?.let(::keepBlock)?.let(::add)
            }
        if (blocks.isEmpty()) return null

        return buildString {
            appendLine("## Recalled Memory")
            appendLine()
            appendLine("These are durable learnings from previous sessions. Use them when relevant.")
            for (block in blocks) {
                appendLine()
                appendLine(block.trim())
            }
        }.trim()
    }

    private fun keepBlock(raw: String): String? {
        val trimmed = raw.takeIf { it.isNotBlank() } ?: return null
        val bytes = trimmed.toByteArray(Charsets.UTF_8).size
        if (bytes > store.maxFileBytes) {
            Log.w(TAG, "Skipping recall of memory file: $bytes bytes exceeds cap")
            return null
        }
        return trimmed
    }
}
