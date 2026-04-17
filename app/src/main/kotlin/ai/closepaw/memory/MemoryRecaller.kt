package ai.closepaw.memory

/**
 * Selects and formats deterministic V2 memory for prompt injection each turn.
 */
class MemoryRecaller(private val store: MemoryStore) {

    fun recall(currentPackageName: String?): String? {
        val blocks =
            buildList {
                store.readUserMemory()?.let { add(it) }
                store.readDeviceMemory()?.let { add(it) }
                currentPackageName?.let(store::readAppMemory)?.let { add(it) }
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
}
