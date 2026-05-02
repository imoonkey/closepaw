package ai.closepaw.browser.cdp

import java.io.IOException

interface CdpConnection {
    fun send(text: String)
    fun close()
}

class CdpConnectionClosedException(
    val code: Int,
    val reason: String,
) : IOException("CDP connection closed: code=$code, reason=$reason")

fun interface CdpConnectionFactory {
    suspend fun connect(
        url: String,
        onMessage: (String) -> Unit,
        onFailure: (Throwable) -> Unit,
        onClosed: (CdpConnectionClosedException) -> Unit,
    ): CdpConnection
}
