package ai.closepaw.browser.cdp.shizuku

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Strict, fail-closed HTTP/1.1 + JSON helpers for Chrome DevTools /json/version and /json/list. */
internal object DevtoolsHttpProtocol {

    private const val CRLF = "\r\n"
    private val UTF8 = Charsets.UTF_8
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** Build a minimal `Connection: close` GET. Single byte stream, no chunked support needed. */
    fun buildGet(path: String, host: String = "localhost"): ByteArray {
        require(path.startsWith("/")) { "path must begin with /, got: $path" }
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1").append(CRLF)
            append("Host: ").append(host).append(CRLF)
            append("Accept: application/json").append(CRLF)
            append("User-Agent: ClosePaw-DevTools-Bridge/1.0").append(CRLF)
            append("Connection: close").append(CRLF)
            append(CRLF)
        }
        return request.toByteArray(UTF8)
    }

    /** Parse `HTTP/1.x 200 OK\r\n…\r\n\r\nbody` into a UTF-8 body string. */
    fun parseHttpBody(raw: ByteArray): String {
        val text = raw.toString(UTF8)
        val headerEnd = text.indexOf("$CRLF$CRLF")
        if (headerEnd < 0) {
            throw DevtoolsSetupError.MalformedResponse(
                "no CRLF CRLF header terminator (got ${raw.size} bytes)"
            )
        }
        val statusLineEnd = text.indexOf(CRLF)
        if (statusLineEnd < 0) {
            throw DevtoolsSetupError.MalformedResponse("no CRLF after status line")
        }
        val statusLine = text.substring(0, statusLineEnd)
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) {
            throw DevtoolsSetupError.MalformedResponse("invalid status line: $statusLine")
        }
        val status = parts[1].toIntOrNull()
            ?: throw DevtoolsSetupError.MalformedResponse("non-numeric status: $statusLine")
        if (status != 200) {
            throw DevtoolsSetupError.MalformedResponse("HTTP $status from DevTools endpoint")
        }
        return text.substring(headerEnd + 4)
    }

    /** Parse `/json/version` payload. */
    fun parseVersion(body: String): DevtoolsVersion = try {
        val obj = json.parseToJsonElement(body) as? JsonObject
            ?: throw DevtoolsSetupError.MalformedResponse("/json/version expected JSON object")
        DevtoolsVersion(
            browser = obj["Browser"]?.jsonPrimitive?.content
                ?: throw DevtoolsSetupError.MalformedResponse("missing Browser field"),
            protocolVersion = obj["Protocol-Version"]?.jsonPrimitive?.content
                ?: throw DevtoolsSetupError.MalformedResponse("missing Protocol-Version field"),
            webSocketDebuggerUrl = obj["webSocketDebuggerUrl"]?.jsonPrimitive?.content,
            userAgent = obj["User-Agent"]?.jsonPrimitive?.content,
        )
    } catch (e: DevtoolsSetupError) {
        throw e
    } catch (e: Throwable) {
        throw DevtoolsSetupError.MalformedResponse("/json/version parse failed: ${e.message}", e)
    }

    /**
     * Parse `/json/list` payload (array of targets).
     *
     * Page targets that lack a `webSocketDebuggerUrl` are silently dropped — Chrome
     * occasionally lists ephemeral page targets (omnibox popups, tear-downs) that have no
     * attachable WebSocket and would be useless to surface. Non-page targets are kept even
     * without a WS URL because workers/iframes legitimately have one only after attach.
     */
    fun parsePageTargets(body: String): List<PageTarget> = try {
        val arr = json.parseToJsonElement(body) as? JsonArray
            ?: throw DevtoolsSetupError.MalformedResponse("/json/list expected JSON array")
        arr.mapIndexedNotNull { idx, el ->
            val obj = el as? JsonObject
                ?: throw DevtoolsSetupError.MalformedResponse("/json/list[$idx] not an object")
            val id = obj["id"]?.jsonPrimitive?.content
                ?: throw DevtoolsSetupError.MalformedResponse("/json/list[$idx] missing id")
            val type = obj["type"]?.jsonPrimitive?.content
                ?: throw DevtoolsSetupError.MalformedResponse("/json/list[$idx] missing type")
            val url = obj["url"]?.jsonPrimitive?.content
                ?: throw DevtoolsSetupError.MalformedResponse("/json/list[$idx] missing url")
            val ws = obj["webSocketDebuggerUrl"]?.jsonPrimitive?.content
            if (type == "page" && ws.isNullOrBlank()) {
                return@mapIndexedNotNull null
            }
            PageTarget(
                id = id,
                type = type,
                title = obj["title"]?.jsonPrimitive?.content ?: "",
                url = url,
                webSocketDebuggerUrl = ws,
            )
        }
    } catch (e: DevtoolsSetupError) {
        throw e
    } catch (e: Throwable) {
        throw DevtoolsSetupError.MalformedResponse("/json/list parse failed: ${e.message}", e)
    }
}

@Serializable
data class DevtoolsVersion(
    @SerialName("browser") val browser: String,
    @SerialName("protocol_version") val protocolVersion: String,
    @SerialName("ws_url") val webSocketDebuggerUrl: String?,
    @SerialName("user_agent") val userAgent: String?,
)

@Serializable
data class PageTarget(
    val id: String,
    val type: String,
    val title: String,
    val url: String,
    @SerialName("ws_url") val webSocketDebuggerUrl: String?,
)
