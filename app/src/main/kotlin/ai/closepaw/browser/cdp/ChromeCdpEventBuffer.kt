package ai.closepaw.browser.cdp

class ChromeCdpEventBuffer(private val maxSize: Int = 500) {
    private val buffer = ArrayDeque<CdpIncoming.Event>()

    @Synchronized
    fun add(event: CdpIncoming.Event) {
        if (buffer.size >= maxSize) buffer.removeFirst()
        buffer.addLast(event)
    }

    @Synchronized
    fun drain(): List<CdpIncoming.Event> {
        val result = buffer.toList()
        buffer.clear()
        return result
    }

    val size: Int @Synchronized get() = buffer.size

    @Synchronized
    fun clear() = buffer.clear()
}
