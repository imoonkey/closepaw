package ai.closepaw.browser.cdp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class DialogStateTrackerTest {

    @Test
    fun `setOpen records latest dialog state`() {
        val tracker = DialogStateTracker()
        val state = DialogStateTracker.DialogState(
            type = "alert",
            message = "Hi",
            defaultPrompt = null,
            hasBrowserHandler = false,
        )

        tracker.setOpen("session-1", state)

        assertThat(tracker.get("session-1")).isEqualTo(state)
    }

    @Test
    fun `setClosed clears the entry but leaves siblings intact`() {
        val tracker = DialogStateTracker()
        val a = DialogStateTracker.DialogState("alert", "A", null, false)
        val b = DialogStateTracker.DialogState("confirm", "B", null, true)
        tracker.setOpen("target-A", a)
        tracker.setOpen("target-B", b)

        tracker.setClosed("target-A")

        assertThat(tracker.get("target-A")).isNull()
        assertThat(tracker.get("target-B")).isEqualTo(b)
    }

    @Test
    fun `setOpen overwrites previous state for same target`() {
        val tracker = DialogStateTracker()
        tracker.setOpen("t", DialogStateTracker.DialogState("alert", "first", null, false))
        tracker.setOpen("t", DialogStateTracker.DialogState("prompt", "second", "default", true))

        val current = tracker.get("t")
        assertThat(current?.type).isEqualTo("prompt")
        assertThat(current?.message).isEqualTo("second")
        assertThat(current?.defaultPrompt).isEqualTo("default")
        assertThat(current?.hasBrowserHandler).isTrue()
    }

    @Test
    fun `clear empties all entries`() {
        val tracker = DialogStateTracker()
        tracker.setOpen("a", DialogStateTracker.DialogState("alert", "x", null, false))
        tracker.setOpen("b", DialogStateTracker.DialogState("alert", "y", null, false))

        tracker.clear()

        assertThat(tracker.snapshot()).isEmpty()
    }
}

class ChromeCdpDialogTrackingTest {

    @Test
    fun `Page javascriptDialogOpening populates tracker for active session`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        attachSession(client, conn, sessionId = "session-1", targetId = "page-1")

        conn.injectEvent(
            "Page.javascriptDialogOpening",
            buildJsonObject {
                put("url", "https://example.com")
                put("type", "prompt")
                put("message", "Pick a name")
                put("defaultPrompt", "anonymous")
                put("hasBrowserHandler", false)
            },
            sessionId = "session-1",
        )

        val state = client.eventBuffer.dialogTracker.get("session-1")
        assertThat(state).isNotNull()
        assertThat(state!!.type).isEqualTo("prompt")
        assertThat(state.message).isEqualTo("Pick a name")
        assertThat(state.defaultPrompt).isEqualTo("anonymous")
        assertThat(state.hasBrowserHandler).isFalse()
    }

    @Test
    fun `Page javascriptDialogClosed clears tracker entry`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        attachSession(client, conn, sessionId = "session-1", targetId = "page-1")

        conn.injectEvent(
            "Page.javascriptDialogOpening",
            buildJsonObject {
                put("type", "alert")
                put("message", "boom")
                put("hasBrowserHandler", false)
            },
            sessionId = "session-1",
        )
        assertThat(client.eventBuffer.dialogTracker.get("session-1")).isNotNull()

        conn.injectEvent(
            "Page.javascriptDialogClosed",
            buildJsonObject { put("result", true) },
            sessionId = "session-1",
        )

        assertThat(client.eventBuffer.dialogTracker.get("session-1")).isNull()
    }

    @Test
    fun `dialogs on different sessions are tracked independently`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")

        conn.injectEvent(
            "Page.javascriptDialogOpening",
            buildJsonObject {
                put("type", "alert")
                put("message", "from A")
                put("hasBrowserHandler", false)
            },
            sessionId = "session-A",
        )
        conn.injectEvent(
            "Page.javascriptDialogOpening",
            buildJsonObject {
                put("type", "confirm")
                put("message", "from B")
                put("hasBrowserHandler", true)
            },
            sessionId = "session-B",
        )

        val a = client.eventBuffer.dialogTracker.get("session-A")
        val b = client.eventBuffer.dialogTracker.get("session-B")
        assertThat(a?.message).isEqualTo("from A")
        assertThat(b?.message).isEqualTo("from B")
        assertThat(b?.hasBrowserHandler).isTrue()

        // Closing one does not affect the other.
        conn.injectEvent(
            "Page.javascriptDialogClosed",
            buildJsonObject {},
            sessionId = "session-A",
        )

        assertThat(client.eventBuffer.dialogTracker.get("session-A")).isNull()
        assertThat(client.eventBuffer.dialogTracker.get("session-B")?.message).isEqualTo("from B")
    }

    @Test
    fun `direct-page mode keys dialogs by activeTargetId when sessionId is absent`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://127.0.0.1:9222/devtools/page/page-1")
        client.useDirectPageTarget("page-1", "ws://127.0.0.1:9222/devtools/page/page-1")

        conn.injectEvent(
            "Page.javascriptDialogOpening",
            buildJsonObject {
                put("type", "beforeunload")
                put("message", "Leave?")
                put("hasBrowserHandler", true)
            },
            sessionId = null,
        )

        val state = client.eventBuffer.dialogTracker.get("page-1")
        assertThat(state).isNotNull()
        assertThat(state!!.type).isEqualTo("beforeunload")
        assertThat(state.hasBrowserHandler).isTrue()
    }

    @Test
    fun `multiple sequential dialogs on same target replace state and finally clear`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        attachSession(client, conn, sessionId = "session-1", targetId = "page-1")

        // First dialog: alert.
        conn.injectEvent(
            "Page.javascriptDialogOpening",
            buildJsonObject {
                put("type", "alert")
                put("message", "first")
                put("hasBrowserHandler", false)
            },
            sessionId = "session-1",
        )
        assertThat(client.eventBuffer.dialogTracker.get("session-1")?.message).isEqualTo("first")

        conn.injectEvent("Page.javascriptDialogClosed", buildJsonObject {}, sessionId = "session-1")
        assertThat(client.eventBuffer.dialogTracker.get("session-1")).isNull()

        // Second dialog: prompt with a default value.
        conn.injectEvent(
            "Page.javascriptDialogOpening",
            buildJsonObject {
                put("type", "prompt")
                put("message", "second")
                put("defaultPrompt", "yes")
                put("hasBrowserHandler", false)
            },
            sessionId = "session-1",
        )
        val second = client.eventBuffer.dialogTracker.get("session-1")
        assertThat(second?.type).isEqualTo("prompt")
        assertThat(second?.defaultPrompt).isEqualTo("yes")

        conn.injectEvent("Page.javascriptDialogClosed", buildJsonObject {}, sessionId = "session-1")
        assertThat(client.eventBuffer.dialogTracker.get("session-1")).isNull()
    }

    @Test
    fun `stale dialog event from switched-away WS does not pollute new target`() = runTest {
        val opened = mutableListOf<FakeCdpConnection>()
        val factory = CdpConnectionFactory { url, onMsg, onFail, onClose ->
            FakeCdpConnection().bind(url, onMsg, onFail, onClose).also { opened += it }
        }
        val client = ChromeCdpClient(factory)
        client.connect("ws://127.0.0.1:9222/devtools/page/page-1")
        client.useDirectPageTarget("page-1", "ws://127.0.0.1:9222/devtools/page/page-1")

        client.send("Page.bringToFront", options = CdpOptions(targetId = "page-2"))

        // Inject a dialog-opening event on the now-defunct previous WS — the buffer guard
        // (`current === source`) means it must be dropped before reaching the tracker.
        opened[0].injectEvent(
            "Page.javascriptDialogOpening",
            buildJsonObject {
                put("type", "alert")
                put("message", "stale")
                put("hasBrowserHandler", false)
            },
            sessionId = null,
        )

        assertThat(client.eventBuffer.dialogTracker.get("page-1")).isNull()
        assertThat(client.eventBuffer.dialogTracker.get("page-2")).isNull()
    }

    @Test
    fun `ClosePaw_getDialog returns null when no dialog is open`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        attachSession(client, conn, sessionId = "session-1", targetId = "page-1")

        val response = client.send(ChromeCdpClient.DIALOG_QUERY_METHOD)

        assertThat(response).isEqualTo(JsonNull)
        // Synthetic method must not hit the wire — we only sent the bootstrap attach above.
        val onWire = conn.sent.count {
            it["method"]!!.jsonPrimitive.content == ChromeCdpClient.DIALOG_QUERY_METHOD
        }
        assertThat(onWire).isEqualTo(0)
    }

    @Test
    fun `ClosePaw_getDialog returns the tracker payload when a dialog is open`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        attachSession(client, conn, sessionId = "session-1", targetId = "page-1")

        conn.injectEvent(
            "Page.javascriptDialogOpening",
            buildJsonObject {
                put("type", "prompt")
                put("message", "what's your name")
                put("defaultPrompt", "guest")
                put("hasBrowserHandler", false)
            },
            sessionId = "session-1",
        )

        val response = client.send(ChromeCdpClient.DIALOG_QUERY_METHOD).jsonObject

        assertThat(response["type"]!!.jsonPrimitive.content).isEqualTo("prompt")
        assertThat(response["message"]!!.jsonPrimitive.content).isEqualTo("what's your name")
        assertThat(response["defaultPrompt"]!!.jsonPrimitive.content).isEqualTo("guest")
        assertThat(response["hasBrowserHandler"]!!.jsonPrimitive.boolean).isFalse()
    }

    @Test
    fun `close clears dialog tracker`() = runTest {
        val conn = FakeCdpConnection()
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")
        attachSession(client, conn, sessionId = "session-1", targetId = "page-1")

        conn.injectEvent(
            "Page.javascriptDialogOpening",
            buildJsonObject {
                put("type", "alert")
                put("message", "x")
                put("hasBrowserHandler", false)
            },
            sessionId = "session-1",
        )
        assertThat(client.eventBuffer.dialogTracker.get("session-1")).isNotNull()

        client.close()

        assertThat(client.eventBuffer.dialogTracker.snapshot()).isEmpty()
    }

    @Test
    fun `onTargetActivated fires after direct-page switch and not on no-op switch`() = runTest {
        val opened = mutableListOf<FakeCdpConnection>()
        val factory = CdpConnectionFactory { url, onMsg, onFail, onClose ->
            FakeCdpConnection().bind(url, onMsg, onFail, onClose).also { opened += it }
        }
        val client = ChromeCdpClient(factory)
        client.connect("ws://127.0.0.1:9222/devtools/page/page-1")
        client.useDirectPageTarget("page-1", "ws://127.0.0.1:9222/devtools/page/page-1")

        var callbackCount = 0
        client.onTargetActivated = { callbackCount++ }

        // Real switch to a new target — callback fires.
        client.send("Page.enable", options = CdpOptions(targetId = "page-2"))
        assertThat(callbackCount).isEqualTo(1)

        // No-op switch (already on page-2) — callback must NOT fire so we don't churn
        // domain re-enable on every same-target send().
        client.send("Page.enable", options = CdpOptions(targetId = "page-2"))
        assertThat(callbackCount).isEqualTo(1)

        // Switch again — callback fires.
        client.send("Page.enable", options = CdpOptions(targetId = "page-3"))
        assertThat(callbackCount).isEqualTo(2)
    }

    @Test
    fun `onTargetActivated fires after attach-mode targetId switch`() = runTest {
        var attachCount = 0
        val conn = FakeCdpConnection()
        conn.responder = { req ->
            when (req.method) {
                "Target.attachToTarget" -> {
                    attachCount++
                    buildJsonObject {
                        put("id", req.id)
                        put("result", buildJsonObject { put("sessionId", "s-$attachCount") })
                    }
                }
                else -> FakeCdpConnection.defaultResponse(req)
            }
        }
        val client = ChromeCdpClient(conn.factory())
        client.connect("ws://test")

        var callbackCount = 0
        client.onTargetActivated = { callbackCount++ }

        // Bootstrap attach is direct, NOT through send(), so it must NOT fire the callback
        // — BrowserSessionManager calls enableCoreDomains itself for bootstrap.
        client.attachToTarget("bootstrap-target")
        assertThat(callbackCount).isEqualTo(0)

        // send() with targetId triggers a re-attach and must fire the callback.
        client.send("Page.enable", options = CdpOptions(targetId = "page-2"))
        assertThat(callbackCount).isEqualTo(1)
    }

    private suspend fun attachSession(
        client: ChromeCdpClient,
        conn: FakeCdpConnection,
        sessionId: String,
        targetId: String,
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
        client.attachToTarget(targetId)
        conn.responder = prev
    }
}
