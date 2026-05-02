package ai.closepaw.browser.cdp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Collections

internal class FakeCdpConnection : CdpConnection {

    data class FakeRequest(
        val id: Int,
        val method: String,
        val params: JsonObject,
        val sessionId: String?,
    )

    val sent: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    val connectedUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var onMessage: ((String) -> Unit)? = null
    private var onFailure: ((Throwable) -> Unit)? = null
    private var onClosed: ((CdpConnectionClosedException) -> Unit)? = null

    var responder: (FakeRequest) -> JsonObject? = { defaultResponse(it) }

    var closed = false
        private set

    override fun send(text: String) {
        val json = Json.parseToJsonElement(text).jsonObject
        sent.add(json)
        val req = FakeRequest(
            id = json["id"]!!.jsonPrimitive.int,
            method = json["method"]!!.jsonPrimitive.content,
            params = json["params"]?.jsonObject ?: JsonObject(emptyMap()),
            sessionId = json["sessionId"]?.jsonPrimitive?.contentOrNull,
        )
        val response = responder(req) ?: return
        onMessage?.invoke(response.toString())
    }

    override fun close() {
        closed = true
    }

    fun injectEvent(method: String, params: JsonObject, sessionId: String? = null) {
        onMessage?.invoke(buildJsonObject {
            put("method", method)
            put("params", params)
            sessionId?.let { put("sessionId", it) }
        }.toString())
    }

    fun injectResponse(id: Int, result: JsonObject) {
        onMessage?.invoke(buildJsonObject {
            put("id", id)
            put("result", result)
        }.toString())
    }

    fun injectFailure(error: Throwable) {
        onFailure?.invoke(error)
    }

    fun injectClosed(code: Int = 1000, reason: String = "closed") {
        onClosed?.invoke(CdpConnectionClosedException(code, reason))
    }

    fun lastSent(): JsonObject = sent.last()

    fun factory(): CdpConnectionFactory = CdpConnectionFactory { url, onMsg, onFail, onClose ->
        connectedUrls.add(url)
        onMessage = onMsg
        onFailure = onFail
        onClosed = onClose
        this
    }

    companion object {
        fun defaultResponse(req: FakeRequest): JsonObject = buildJsonObject {
            put("id", req.id)
            put("result", buildJsonObject { put("echo", req.method) })
        }
    }
}
