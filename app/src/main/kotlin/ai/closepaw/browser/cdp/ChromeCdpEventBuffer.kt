package ai.closepaw.browser.cdp

class ChromeCdpEventBuffer(private val maxSize: Int = 500) {
    private val buffer = ArrayDeque<CdpIncoming.Event>()

    val dialogTracker: DialogStateTracker = DialogStateTracker()

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
    fun clear() {
        buffer.clear()
        dialogTracker.clear()
    }
}

/**
 * Tracks pending JavaScript dialogs (`alert`/`confirm`/`prompt`/`beforeunload`) per target so
 * agent helpers can surface dialog state before attempting `Runtime.evaluate` — page JS is
 * frozen while a modal dialog is open and any evaluate would silently hang.
 *
 * Keyed by sessionId in attach mode and by targetId in direct-page mode (one WS per target).
 * Both are unique within the session so a single string key is sufficient.
 */
class DialogStateTracker {

    data class DialogState(
        /** "alert" | "confirmation" | "prompt" | "beforeunload" — passed through from CDP. */
        val type: String,
        val message: String,
        /** Only present for `prompt`-type dialogs. */
        val defaultPrompt: String?,
        val hasBrowserHandler: Boolean,
        /**
         * Frame URL that owns the dialog, copied verbatim from `Page.javascriptDialogOpening`.
         * Surfaced so recovery helpers can show the agent which page raised the modal — the
         * page's own JS is frozen while a dialog is open, so `pageInfo()` cannot read
         * `location.href` until the dialog is dismissed.
         */
        val url: String?,
    )

    private val states = mutableMapOf<String, DialogState>()

    @Synchronized
    fun setOpen(targetKey: String, state: DialogState) {
        states[targetKey] = state
    }

    @Synchronized
    fun setClosed(targetKey: String) {
        states.remove(targetKey)
    }

    @Synchronized
    fun get(targetKey: String): DialogState? = states[targetKey]

    @Synchronized
    fun snapshot(): Map<String, DialogState> = states.toMap()

    @Synchronized
    fun clear() = states.clear()
}
