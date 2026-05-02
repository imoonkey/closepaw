package ai.closepaw.browser.cdp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.fail
import org.junit.Test

class ChromeCdpRecoveryTest {

    @Test
    fun `stale session recovery reattaches and retries once`() = runTest {
        val conn = FakeCdpConnection()
        var attachCount = 0
        var navigateCount = 0

        conn.responder = { req ->
            when (req.method) {
                "Target.attachToTarget" -> {
                    attachCount++
                    buildJsonObject {
                        put("id", req.id)
                        put("result", buildJsonObject {
                            put("sessionId", "session-$attachCount")
                        })
                    }
                }
                "Page.navigate" -> {
                    navigateCount++
                    if (navigateCount == 1) {
                        staleSessionError(req.id)
                    } else {
                        buildJsonObject {
                            put("id", req.id)
                            put("result", buildJsonObject { put("frameId", "frame-1") })
                        }
                    }
                }
                "Target.getTargets" -> targetInfosResponse(req.id)
                else -> FakeCdpConnection.defaultResponse(req)
            }
        }

        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        client.attachToTarget("initial-target")
        assertThat(client.activeSessionId).isEqualTo("session-1")

        val result = client.send("Page.navigate", buildJsonObject { put("url", "https://example.com") })
        assertThat(result.jsonObject["frameId"]!!.jsonPrimitive.content).isEqualTo("frame-1")
        assertThat(client.activeSessionId).isEqualTo("session-2")
    }

    @Test
    fun `stale session recovery does not retry infinitely`() = runTest {
        val conn = FakeCdpConnection()
        var attachCount = 0

        conn.responder = { req ->
            when (req.method) {
                "Target.attachToTarget" -> {
                    attachCount++
                    buildJsonObject {
                        put("id", req.id)
                        put("result", buildJsonObject {
                            put("sessionId", "session-$attachCount")
                        })
                    }
                }
                "Page.navigate" -> staleSessionError(req.id)
                "Target.getTargets" -> targetInfosResponse(req.id)
                else -> FakeCdpConnection.defaultResponse(req)
            }
        }

        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        client.attachToTarget("initial-target")

        try {
            client.send("Page.navigate", buildJsonObject { put("url", "https://example.com") })
            fail("Expected CdpException")
        } catch (e: CdpException) {
            assertThat(e.message).contains("Session with given id not found")
        }

        val getTargetsCalls = conn.sent.count {
            it["method"]!!.jsonPrimitive.content == "Target.getTargets"
        }
        assertThat(getTargetsCalls).isEqualTo(1)
    }

    @Test
    fun `recovery failure propagates typed exception with cause`() = runTest {
        val conn = FakeCdpConnection()
        var attachCount = 0

        conn.responder = { req ->
            when (req.method) {
                "Target.attachToTarget" -> {
                    attachCount++
                    if (attachCount == 1) {
                        buildJsonObject {
                            put("id", req.id)
                            put("result", buildJsonObject { put("sessionId", "session-1") })
                        }
                    } else {
                        buildJsonObject {
                            put("id", req.id)
                            put("error", buildJsonObject {
                                put("code", -32600)
                                put("message", "Target closed")
                            })
                        }
                    }
                }
                "Page.navigate" -> staleSessionError(req.id)
                "Target.getTargets" -> targetInfosResponse(req.id)
                else -> FakeCdpConnection.defaultResponse(req)
            }
        }

        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        client.attachToTarget("initial-target")

        try {
            client.send("Page.navigate")
            fail("Expected CdpException")
        } catch (e: CdpException) {
            assertThat(e.message).contains("Session recovery failed")
            assertThat(e.cause).isInstanceOf(CdpException::class.java)
            assertThat(e.cause!!.message).contains("Target closed")
        }
    }

    @Test
    fun `cancellation during recovery propagates as CancellationException`() = runTest {
        val conn = FakeCdpConnection()
        var attachCount = 0

        conn.responder = { req ->
            when (req.method) {
                "Target.attachToTarget" -> {
                    attachCount++
                    buildJsonObject {
                        put("id", req.id)
                        put("result", buildJsonObject { put("sessionId", "session-$attachCount") })
                    }
                }
                "Page.navigate" -> staleSessionError(req.id)
                "Target.getTargets" -> null
                else -> FakeCdpConnection.defaultResponse(req)
            }
        }

        val client = ChromeCdpClient(conn.factory(), commandTimeoutMs = 60_000)
        client.connect("ws://test")
        client.attachToTarget("initial-target")

        supervisorScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                client.send("Page.navigate")
            }

            job.cancel()

            try {
                job.await()
                fail("Expected CancellationException")
            } catch (_: CancellationException) {
                // Correct: cancellation propagated without wrapping
            } catch (e: Exception) {
                fail("Expected CancellationException, got ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    // -- shared helpers --

    private fun staleSessionError(id: Int): JsonObject = buildJsonObject {
        put("id", id)
        put("error", buildJsonObject {
            put("code", -32602)
            put("message", "Session with given id not found")
        })
    }

    private fun targetInfosResponse(id: Int): JsonObject = buildJsonObject {
        put("id", id)
        put("result", buildJsonObject {
            put("targetInfos", buildJsonArray {
                add(buildJsonObject {
                    put("targetId", "page-1")
                    put("type", "page")
                    put("url", "https://example.com")
                    put("title", "Example")
                })
            })
        })
    }
}
