package ai.closepaw.browser.cdp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull

data class CdpOptions(
    val sessionId: String? = null,
    val targetId: String? = null,
)

class CdpException(
    val code: Int,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class CdpError(val code: Int, val message: String)

sealed class CdpIncoming {
    data class Response(
        val id: Int,
        val result: JsonElement?,
        val error: CdpError?,
    ) : CdpIncoming()

    data class Event(
        val method: String,
        val params: JsonObject,
        val sessionId: String?,
    ) : CdpIncoming()
}

internal fun buildCdpRequest(
    id: Int,
    method: String,
    params: JsonObject,
    sessionId: String?,
): String = buildJsonObject {
    put("id", id)
    put("method", method)
    put("params", params)
    sessionId?.let { put("sessionId", it) }
}.toString()

internal fun parseCdpMessage(text: String): CdpIncoming {
    val json = Json.parseToJsonElement(text).jsonObject
    val id = json["id"]?.jsonPrimitive?.intOrNull
    return if (id != null) {
        val err = json["error"]?.jsonObject
        CdpIncoming.Response(
            id = id,
            result = if (err == null) (json["result"] ?: JsonNull) else null,
            error = err?.let {
                CdpError(
                    code = it["code"]?.jsonPrimitive?.int ?: 0,
                    message = it["message"]?.jsonPrimitive?.content ?: "unknown",
                )
            },
        )
    } else {
        CdpIncoming.Event(
            method = json["method"]?.jsonPrimitive?.content ?: "unknown",
            params = json["params"]?.jsonObject ?: JsonObject(emptyMap()),
            sessionId = json["sessionId"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
