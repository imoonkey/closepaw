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
     * budget for the whole script. Default is generous because real-device transports
     * (host-mediated relay, USB chained ADB) add latency on top of Chrome's response time —
     * 10s was empirically too tight on nubia P0110, where `Page.loadEventFired` for a
     * cellular network-fetched page can run >10s.
     */
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    private val onTransportFailure: (Throwable) -> Unit = {},
) {
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
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

    private var connection: CdpConnection? = null
    private val parkedConnections = mutableListOf<CdpConnection>()
    private var directPageWebSocketBase: String? = null

    suspend fun connect(wsUrl: String) {
        isBroken = false
        directPageWebSocketBase = null
        connection = connectionFactory.connect(wsUrl, ::onMessage, ::onFailure, ::onClosed)
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
        connection?.close()
        connection = null
        parkedConnections.forEach { it.close() }
        parkedConnections.clear()
        val err = CdpException(-1, "Client closed")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
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
        connection?.let { parkedConnections.add(it) }
        val wsUrl = "$base/$targetId"
        connection = connectionFactory.connect(wsUrl, ::onMessage, ::onFailure, ::onClosed)
        isBroken = false
        activeTargetId = targetId
        activeSessionId = null
    }

    private suspend fun sendRaw(
        method: String,
        params: JsonObject,
        sessionId: String?,
    ): JsonElement {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        try {
            val msg = buildCdpRequest(id, method, params, sessionId)
            val conn = connection ?: throw CdpException(-1, "Not connected")
            try {
                conn.send(msg)
            } catch (t: Exception) {
                val transportError = IOException("CDP transport send failed: ${t.message}", t)
                markTransportBroken(transportError)
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
            pending.remove(id)
        }
    }

    private fun onMessage(text: String) {
        when (val msg = parseCdpMessage(text)) {
            is CdpIncoming.Response -> {
                val deferred = pending[msg.id] ?: return
                if (msg.error != null) {
                    deferred.completeExceptionally(CdpException(msg.error.code, msg.error.message))
                } else {
                    deferred.complete(msg.result ?: JsonNull)
                }
            }
            is CdpIncoming.Event -> eventBuffer.add(msg)
        }
    }

    private fun onFailure(error: Throwable) {
        markTransportBroken(error)
    }

    private fun onClosed(error: CdpConnectionClosedException) {
        markTransportBroken(error)
    }

    private fun markTransportBroken(error: Throwable) {
        val cdpError = CdpException(-1, error.message ?: "Connection failed")
        isBroken = true
        pending.values.forEach { it.completeExceptionally(cdpError) }
        pending.clear()
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

    companion object {
        /**
         * Default per-CDP-command cap. Picked to comfortably cover real-device transports:
         * the host-mediated CDP relay chains every CDP frame through `adb reverse` ->
         * host adbd -> `adb forward` -> Chrome, which adds USB/network latency on top of
         * Chrome's own response time. Empirically 10s was too tight on nubia P0110 for
         * `Page.loadEventFired` waiting on a fresh page navigation; 30s leaves headroom for
         * cellular page loads through the chained relay without making transient hangs
         * invisible.
         */
        const val DEFAULT_COMMAND_TIMEOUT_MS: Long = 30_000L
    }
}
