package ai.closepaw.browser.cdp

import ai.closepaw.browser.cdp.shizuku.PageTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ChromeCdpClient(
    private val connectionFactory: CdpConnectionFactory,
    /**
     * Per-CDP-command timeout. Each `cdp(method, ...)` from the agent script is wrapped in
     * `withTimeout(commandTimeoutMs)`; the script's outer `timeout_ms` is a separate, larger
     * budget for the whole script. Default is generous because the wireless-ADB self-pair
     * relay adds adbd-loopback latency on top of Chrome's response time — 10s was empirically
     * too tight on nubia P0110, where `Page.loadEventFired` for a cellular network-fetched
     * page can run >10s.
     */
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    private val onTransportFailure: (Throwable) -> Unit = {},
) {
    private val nextId = AtomicInteger(1)
    private val recoveryMutex = Mutex()

    val eventBuffer = ChromeCdpEventBuffer()

    @Volatile
    var activeSessionId: String? = null
        private set

    @Volatile
    var activeTargetId: String? = null
        private set

    @Volatile
    var isBroken: Boolean = false
        private set

    @Volatile
    private var current: LiveConnection? = null
    private var directPageWebSocketBase: String? = null

    /** Visible for tests: count of WS connections still considered active (not closed by us). */
    val activeConnectionCount: Int
        get() = if (current?.active == true) 1 else 0

    suspend fun connect(wsUrl: String) {
        // Unlink and close any existing connection BEFORE swapping in the new one so the old
        // connection's callbacks see `current !== this` and drop without touching shared state.
        val prev = current
        current = null
        prev?.closeQuietly("CDP connection replaced")
        isBroken = false
        directPageWebSocketBase = null
        current = openConnection(wsUrl)
    }

    fun useDirectPageTarget(targetId: String, wsUrl: String) {
        activeTargetId = targetId
        activeSessionId = null
        directPageWebSocketBase = wsUrl.substringBeforeLast('/', missingDelimiterValue = wsUrl)
    }

    suspend fun attachToTarget(targetId: String): String {
        val result = sendRaw(
            "Target.attachToTarget",
            buildJsonObject {
                put("targetId", targetId)
                put("flatten", true)
            },
            sessionId = null,
        )
        val sessionId = result.jsonObject["sessionId"]?.jsonPrimitive?.content
            ?: throw CdpException(-1, "No sessionId in attachToTarget response")
        activeSessionId = sessionId
        activeTargetId = targetId
        return sessionId
    }

    suspend fun attachToFirstRealPage(targets: List<PageTarget>): String {
        val target = ChromeCdpTarget.firstRealPage(targets)
        val targetId = target?.id ?: run {
            val result = sendRaw(
                "Target.createTarget",
                buildJsonObject { put("url", "about:blank") },
                sessionId = null,
            )
            result.jsonObject["targetId"]?.jsonPrimitive?.content
                ?: throw CdpException(-1, "Failed to create target")
        }
        return attachToTarget(targetId)
    }

    suspend fun send(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        options: CdpOptions = CdpOptions(),
    ): JsonElement {
        if (options.targetId != null) {
            if (directPageWebSocketBase != null) {
                switchDirectPageTarget(options.targetId)
                return sendRaw(method, params, sessionId = null)
            }
            val sid = attachToTarget(options.targetId)
            return sendRaw(method, params, sid)
        }

        val sessionId = routeSessionId(method, options)

        return try {
            sendRaw(method, params, sessionId)
        } catch (e: CdpException) {
            if (!isStaleSessionError(e) || sessionId == null || sessionId != activeSessionId) throw e

            recoveryMutex.withLock {
                if (activeSessionId != sessionId) {
                    sendRaw(method, params, activeSessionId)
                } else {
                    val recovered = try {
                        recoverStaleSession()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (recoveryError: Exception) {
                        throw CdpException(
                            -1,
                            "Session recovery failed: ${recoveryError.message}",
                            recoveryError,
                        )
                    }
                    if (recovered) {
                        sendRaw(method, params, activeSessionId)
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    fun drainEvents(): List<CdpIncoming.Event> = eventBuffer.drain()

    fun close() {
        val prev = current
        current = null
        prev?.closeQuietly("Client closed")
        eventBuffer.clear()
        activeSessionId = null
        activeTargetId = null
        directPageWebSocketBase = null
    }

    private fun routeSessionId(method: String, options: CdpOptions): String? {
        if (options.sessionId != null) return options.sessionId
        if (method.startsWith("Target.") || method.startsWith("Browser.")) return null
        if (directPageWebSocketBase != null) return null
        return activeSessionId
            ?: throw CdpException(-1, "No active page session; call attachToTarget first")
    }

    private suspend fun switchDirectPageTarget(targetId: String) {
        val base = directPageWebSocketBase ?: return
        if (targetId == activeTargetId) return
        val previous = current
        val wsUrl = "$base/$targetId"
        // Open new first, then swap `current` so the previous connection's incoming callbacks
        // observe `current !== this` and drop. closeQuietly then drains the previous pending
        // map with CdpException("CDP connection switched") so any in-flight requests on the
        // dead WS reject immediately rather than waiting commandTimeoutMs.
        current = openConnection(wsUrl)
        isBroken = false
        activeTargetId = targetId
        activeSessionId = null
        previous?.closeQuietly("CDP connection switched")
    }

    private suspend fun sendRaw(
        method: String,
        params: JsonObject,
        sessionId: String?,
    ): JsonElement {
        val live = current ?: throw CdpException(-1, "Not connected")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        live.pending[id] = deferred
        try {
            val msg = buildCdpRequest(id, method, params, sessionId)
            try {
                live.raw!!.send(msg)
            } catch (t: Exception) {
                val transportError = IOException("CDP transport send failed: ${t.message}", t)
                markTransportBroken(transportError, live)
                throw transportError
            }
            return try {
                withTimeout(commandTimeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                // Surface the offending CDP method + the actual cap so the agent (and trace)
                // can see exactly what blew the budget, instead of the bare kotlinx.coroutines
                // "Timed out waiting for X ms" which leaks no context.
                throw CdpException(
                    -1,
                    "CDP command '$method' timed out after ${commandTimeoutMs}ms " +
                        "(per-command cap; script-level timeout_ms is a separate, larger budget)",
                )
            }
        } finally {
            live.pending.remove(id)
        }
    }

    private fun handleMessage(source: LiveConnection, text: String) {
        when (val msg = parseCdpMessage(text)) {
            is CdpIncoming.Response -> {
                // Per-connection pending map naturally isolates response handling — a stale
                // response on a switched-away WS targets its own (already-drained) map and
                // can never complete a deferred owned by the new connection.
                val deferred = source.pending.remove(msg.id) ?: return
                if (msg.error != null) {
                    deferred.completeExceptionally(CdpException(msg.error.code, msg.error.message))
                } else {
                    deferred.complete(msg.result ?: JsonNull)
                }
            }
            is CdpIncoming.Event -> {
                // Drop stale events from a switched-away WS so they cannot pollute the
                // shared event buffer the agent script will drain.
                if (current === source) eventBuffer.add(msg)
            }
        }
    }

    private fun handleFailure(source: LiveConnection, error: Throwable) {
        markTransportBroken(error, source)
    }

    private fun handleClosed(source: LiveConnection, error: CdpConnectionClosedException) {
        markTransportBroken(error, source)
    }

    private fun markTransportBroken(error: Throwable, source: LiveConnection) {
        // Stale failure from a switched-away WS — already drained by closeQuietly. Don't
        // mark the new connection broken or invoke onTransportFailure for it.
        if (current !== source) return
        val cdpError = CdpException(-1, error.message ?: "Connection failed")
        isBroken = true
        source.pending.values.forEach { it.completeExceptionally(cdpError) }
        source.pending.clear()
        onTransportFailure(error)
    }

    private suspend fun recoverStaleSession(): Boolean {
        val result = sendRaw("Target.getTargets", JsonObject(emptyMap()), sessionId = null)
        val infos = result.jsonObject["targetInfos"]?.jsonArray ?: return false

        val firstPage = infos
            .mapNotNull { it.jsonObject }
            .firstOrNull { info ->
                val type = info["type"]?.jsonPrimitive?.contentOrNull ?: return@firstOrNull false
                val url = info["url"]?.jsonPrimitive?.contentOrNull ?: return@firstOrNull false
                ChromeCdpTarget.isRealPage(type, url)
            } ?: return false

        val targetId = firstPage["targetId"]?.jsonPrimitive?.content ?: return false
        attachToTarget(targetId)
        return true
    }

    private fun isStaleSessionError(e: CdpException): Boolean =
        "Session with given id not found" in e.message

    /**
     * One CDP WebSocket plus its own pending-request map. The per-connection map is the
     * atomicity boundary: a stale onMessage on a switched-away WS removes from THIS
     * (already-drained) map and cannot complete a deferred owned by the new connection.
     * `active` is read by callers as a hint for tests; correctness comes from the
     * per-connection map plus the `current === source` checks in markTransportBroken /
     * handleMessage(event).
     */
    private class LiveConnection {
        @Volatile var active: Boolean = true
        @Volatile var raw: CdpConnection? = null
        val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()

        fun closeQuietly(reason: String) {
            active = false
            // Reject all in-flight requests on this dead WS so callers don't wait
            // commandTimeoutMs for responses that will never arrive.
            val err = CdpException(-1, reason)
            pending.values.forEach { it.completeExceptionally(err) }
            pending.clear()
            try {
                raw?.close()
            } catch (_: Throwable) {
                // Best-effort; the per-connection map drain above is what matters.
            }
        }
    }

    private suspend fun openConnection(wsUrl: String): LiveConnection {
        val live = LiveConnection()
        live.raw = connectionFactory.connect(
            wsUrl,
            { text -> handleMessage(live, text) },
            { error -> handleFailure(live, error) },
            { error -> handleClosed(live, error) },
        )
        return live
    }

    companion object {
        /**
         * Default per-CDP-command cap. Picked to comfortably cover the wireless-ADB self-pair
         * relay: every CDP frame goes through our in-app TCP relay → adbd → Chrome's abstract
         * socket, which adds adbd-loopback latency on top of Chrome's own response time.
         * Empirically 10s was too tight on nubia P0110 for `Page.loadEventFired` waiting on a
         * fresh page navigation; 30s leaves headroom for cellular page loads without making
         * transient hangs invisible.
         */
        const val DEFAULT_COMMAND_TIMEOUT_MS: Long = 30_000L
    }
}
