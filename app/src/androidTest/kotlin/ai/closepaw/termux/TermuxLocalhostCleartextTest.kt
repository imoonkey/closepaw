package ai.closepaw.termux

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownServiceException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TermuxLocalhostCleartextTest {

    @Test
    fun okHttp_can_use_cleartext_loopback() {
        val networkPolicy = NetworkSecurityPolicy.getInstance()
        assertFalse(
            "network security config must keep non-loopback cleartext blocked",
            networkPolicy.isCleartextTrafficPermitted(NON_LOOPBACK_HOST),
        )
        assertTrue(
            "network security config must permit cleartext loopback",
            networkPolicy.isCleartextTrafficPermitted(LOOPBACK_HOST),
        )

        val serverSocket = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST))
        val executor = Executors.newSingleThreadExecutor()
        val server = executor.submit {
            serverSocket.use { socket ->
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
                    generateSequence { reader.readLine() }
                        .takeWhile { it.isNotEmpty() }
                        .forEach { }

                    val body = "ok".toByteArray(Charsets.UTF_8)
                    val headers = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n" +
                            "\r\n"
                        ).toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write(headers)
                        write(body)
                        flush()
                    }
                }
            }
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("http://$LOOPBACK_HOST:${serverSocket.localPort}/health")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("ok", response.body?.string())
                }
            } catch (e: UnknownServiceException) {
                if (e.message.orEmpty().contains("CLEARTEXT")) {
                    fail("127.0.0.1 cleartext was blocked by network security policy: ${e.message}")
                }
                throw e
            }

            server.get(2, TimeUnit.SECONDS)
        } finally {
            serverSocket.close()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val NON_LOOPBACK_HOST = "example.com"
        const val LOOPBACK_HOST = "127.0.0.1"
    }
}
