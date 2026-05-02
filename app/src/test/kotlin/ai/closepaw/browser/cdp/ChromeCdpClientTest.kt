package ai.closepaw.browser.cdp

import ai.closepaw.browser.cdp.shizuku.PageTarget
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import org.junit.Assert.fail
import org.junit.Test

class ChromeCdpClientTest {

    @Test
    fun `send matches response by command id`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")

        val r1 = client.send("Target.getTargets")
        assertThat(r1.jsonObject["echo"]!!.jsonPrimitive.content).isEqualTo("Target.getTargets")

        val r2 = client.send("Target.getVersion")
        assertThat(r2.jsonObject["echo"]!!.jsonPrimitive.content).isEqualTo("Target.getVersion")

        assertThat(conn.sent[0]["id"]!!.jsonPrimitive.int).isEqualTo(1)
        assertThat(conn.sent[1]["id"]!!.jsonPrimitive.int).isEqualTo(2)
    }

    @Test
    fun `out-of-order responses resolve to correct commands`() = runTest {
        val conn = FakeCdpConnection()
        conn.responder = { null }

        val client = ChromeCdpClient(conn.factory(), commandTimeoutMs = 60_000)
        client.connect("ws://test")

        val d1 = async(start = CoroutineStart.UNDISPATCHED) {
            client.send("Target.getTargets")
        }
        val d2 = async(start = CoroutineStart.UNDISPATCHED) {
            client.send("Browser.getVersion")
        }

        conn.injectResponse(2, buildJsonObject { put("version", "1.3") })
        conn.injectResponse(1, buildJsonObject { put("targetInfos", buildJsonArray {}) })

        assertThat(d1.await().jsonObject.containsKey("targetInfos")).isTrue()
        assertThat(d2.await().jsonObject.containsKey("version")).isTrue()
    }

    @Test
    fun `browser and target domains route without session`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        setupSession(client, conn, "active-session")

        client.send("Target.getTargets")
        assertThat(conn.lastSent()["sessionId"]).isNull()

        client.send("Browser.getVersion")
        assertThat(conn.lastSent()["sessionId"]).isNull()
    }

    @Test
    fun `page domains route through active session`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        setupSession(client, conn, "active-session")

        client.send("Page.navigate", buildJsonObject { put("url", "https://example.com") })
        assertThat(conn.lastSent()["sessionId"]!!.jsonPrimitive.content).isEqualTo("active-session")

        client.send("Runtime.evaluate", buildJsonObject { put("expression", "1+1") })
        assertThat(conn.lastSent()["sessionId"]!!.jsonPrimitive.content).isEqualTo("active-session")
    }

    @Test
    fun `page domain without active session fails fast`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")

        try {
            client.send("Page.navigate")
            fail("Expected CdpException")
        } catch (e: CdpException) {
            assertThat(e.message).contains("No active page session")
        }

        val r = client.send("Target.getTargets")
        assertThat(r.jsonObject["echo"]!!.jsonPrimitive.content).isEqualTo("Target.getTargets")
    }

    @Test
    fun `command protocol error does not mark client broken`() = runTest {
        val conn = FakeCdpConnection()
        conn.responder = { req ->
            buildJsonObject {
                put("id", req.id)
                put("error", buildJsonObject {
                    put("code", -32000)
                    put("message", "Protocol command failed")
                })
            }
        }
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")

        try {
            client.send("Target.getTargets")
            fail("Expected CdpException")
        } catch (e: CdpException) {
            assertThat(e.message).contains("Protocol command failed")
        }

        assertThat(client.isBroken).isFalse()
    }

    @Test
    fun `synchronous send failure marks client broken`() = runTest {
        var transportFailures = 0
        val client = ChromeCdpClient(
            connectionFactory = CdpConnectionFactory { _, _, _, _ ->
                object : CdpConnection {
                    override fun send(text: String) {
                        throw IOException("websocket send returned false")
                    }

                    override fun close() = Unit
                }
            },
            onTransportFailure = { transportFailures++ },
        )
        client.connect("ws://test")

        try {
            client.send("Target.getTargets")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertThat(e.message).contains("CDP transport send failed")
        }

        assertThat(client.isBroken).isTrue()
        assertThat(transportFailures).isEqualTo(1)
    }

    @Test
    fun `server close marks client broken`() = runTest {
        var transportFailures = 0
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(
            connectionFactory = conn.factory(),
            onTransportFailure = { transportFailures++ },
        )
        client.connect("ws://test")

        conn.injectClosed(code = 1001, reason = "server shutdown")

        assertThat(client.isBroken).isTrue()
        assertThat(transportFailures).isEqualTo(1)
    }

    @Test
    fun `explicit session option overrides default routing`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        setupSession(client, conn, "active-session")

        client.send("Page.navigate", options = CdpOptions(sessionId = "custom-session"))
        assertThat(conn.lastSent()["sessionId"]!!.jsonPrimitive.content).isEqualTo("custom-session")
    }

    @Test
    fun `events are buffered and drained`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")

        conn.injectEvent("Page.loadEventFired", buildJsonObject { put("timestamp", 1.0) }, "s1")
        conn.injectEvent("Network.requestWillBeSent", buildJsonObject { put("id", "r1") }, "s1")

        val events = client.drainEvents()
        assertThat(events).hasSize(2)
        assertThat(events[0].method).isEqualTo("Page.loadEventFired")
        assertThat(events[0].sessionId).isEqualTo("s1")
        assertThat(events[1].method).isEqualTo("Network.requestWillBeSent")

        assertThat(client.drainEvents()).isEmpty()
    }

    @Test
    fun `event buffer respects max size`() {
        val buffer = ChromeCdpEventBuffer(maxSize = 3)
        repeat(5) { i ->
            buffer.add(CdpIncoming.Event("event-$i", JsonObject(emptyMap()), null))
        }
        assertThat(buffer.size).isEqualTo(3)
        val events = buffer.drain()
        assertThat(events[0].method).isEqualTo("event-2")
        assertThat(events[2].method).isEqualTo("event-4")
    }

    @Test
    fun `attach to target sends correct command and stores session`() = runTest {
        val conn = FakeCdpConnection()
        conn.responder = { req ->
            when (req.method) {
                "Target.attachToTarget" -> buildJsonObject {
                    put("id", req.id)
                    put("result", buildJsonObject { put("sessionId", "new-session") })
                }
                else -> FakeCdpConnection.defaultResponse(req)
            }
        }

        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")

        val sid = client.attachToTarget("page-42")
        assertThat(sid).isEqualTo("new-session")
        assertThat(client.activeSessionId).isEqualTo("new-session")
        assertThat(client.activeTargetId).isEqualTo("page-42")

        val attachMsg = conn.sent.first()
        assertThat(attachMsg["method"]!!.jsonPrimitive.content).isEqualTo("Target.attachToTarget")
        val params = attachMsg["params"]!!.jsonObject
        assertThat(params["targetId"]!!.jsonPrimitive.content).isEqualTo("page-42")
        assertThat(params["flatten"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(attachMsg["sessionId"]).isNull()
    }

    @Test
    fun `attach to first real page skips internal targets`() = runTest {
        val conn = FakeCdpConnection()
        conn.responder = { req ->
            when (req.method) {
                "Target.attachToTarget" -> buildJsonObject {
                    put("id", req.id)
                    put("result", buildJsonObject { put("sessionId", "session-1") })
                }
                else -> FakeCdpConnection.defaultResponse(req)
            }
        }

        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")

        val targets = listOf(
            PageTarget("t1", "page", "New Tab", "chrome://newtab/", null),
            PageTarget("t2", "page", "Extensions", "chrome-extension://abc", null),
            PageTarget("t3", "page", "Example", "https://example.com", null),
        )

        client.attachToFirstRealPage(targets)

        val attachParams = conn.lastSent()["params"]!!.jsonObject
        assertThat(attachParams["targetId"]!!.jsonPrimitive.content).isEqualTo("t3")
        assertThat(client.activeSessionId).isEqualTo("session-1")
    }

    @Test
    fun `attach to first real page creates blank when no real pages`() = runTest {
        val conn = FakeCdpConnection()
        conn.responder = { req ->
            when (req.method) {
                "Target.createTarget" -> buildJsonObject {
                    put("id", req.id)
                    put("result", buildJsonObject { put("targetId", "new-blank") })
                }
                "Target.attachToTarget" -> buildJsonObject {
                    put("id", req.id)
                    put("result", buildJsonObject { put("sessionId", "session-blank") })
                }
                else -> FakeCdpConnection.defaultResponse(req)
            }
        }

        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")

        val targets = listOf(
            PageTarget("t1", "page", "New Tab", "chrome://newtab/", null),
        )

        client.attachToFirstRealPage(targets)

        val createMsg = conn.sent.first { it["method"]!!.jsonPrimitive.content == "Target.createTarget" }
        assertThat(createMsg["params"]!!.jsonObject["url"]!!.jsonPrimitive.content).isEqualTo("about:blank")
        assertThat(client.activeSessionId).isEqualTo("session-blank")
    }

    @Test
    fun `close clears session state and events`() = runTest {
        val conn = FakeCdpConnection()
        conn.responder = { req ->
            when (req.method) {
                "Target.attachToTarget" -> buildJsonObject {
                    put("id", req.id)
                    put("result", buildJsonObject { put("sessionId", "s1") })
                }
                else -> FakeCdpConnection.defaultResponse(req)
            }
        }
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        client.attachToTarget("t1")
        conn.injectEvent("Page.loadEventFired", buildJsonObject { put("ts", 1.0) })
        assertThat(client.eventBuffer.size).isEqualTo(1)

        client.close()
        assertThat(client.activeSessionId).isNull()
        assertThat(client.activeTargetId).isNull()
        assertThat(client.eventBuffer.size).isEqualTo(0)
        assertThat(conn.closed).isTrue()
    }

    @Test
    fun `target filtering identifies real pages`() {
        assertThat(ChromeCdpTarget.isRealPage("page", "https://example.com")).isTrue()
        assertThat(ChromeCdpTarget.isRealPage("page", "http://localhost:3000")).isTrue()
        assertThat(ChromeCdpTarget.isRealPage("page", "chrome://newtab/")).isFalse()
        assertThat(ChromeCdpTarget.isRealPage("page", "chrome-untrusted://foo")).isFalse()
        assertThat(ChromeCdpTarget.isRealPage("page", "devtools://inspector")).isFalse()
        assertThat(ChromeCdpTarget.isRealPage("page", "chrome-extension://abc")).isFalse()
        assertThat(ChromeCdpTarget.isRealPage("page", "about:blank")).isFalse()
        assertThat(ChromeCdpTarget.isRealPage("service_worker", "https://example.com")).isFalse()
    }

    // -- test infrastructure --

    private suspend fun setupSession(
        client: ChromeCdpClient,
        conn: FakeCdpConnection,
        sessionId: String,
    ) {
        val prev = conn.responder
        conn.responder = { req ->
            if (req.method == "Target.attachToTarget") {
                buildJsonObject {
                    put("id", req.id)
                    put("result", buildJsonObject { put("sessionId", sessionId) })
                }
            } else {
                prev(req)
            }
        }
        client.attachToTarget("setup-target")
        conn.responder = prev
    }
}
