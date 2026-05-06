package ai.closepaw.browser.cdp

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
            url = "https://example.com/",
        )

        tracker.setOpen("session-1", state)

        assertThat(tracker.get("session-1")).isEqualTo(state)
    }

    @Test
    fun `setClosed clears the entry but leaves siblings intact`() {
        val tracker = DialogStateTracker()
        val a = DialogStateTracker.DialogState("alert", "A", null, false, null)
        val b = DialogStateTracker.DialogState("confirm", "B", null, true, "https://b.example/")
        tracker.setOpen("target-A", a)
        tracker.setOpen("target-B", b)

        tracker.setClosed("target-A")

        assertThat(tracker.get("target-A")).isNull()
        assertThat(tracker.get("target-B")).isEqualTo(b)
    }

    @Test
    fun `setOpen overwrites previous state for same target`() {
        val tracker = DialogStateTracker()
        tracker.setOpen("t", DialogStateTracker.DialogState("alert", "first", null, false, null))
        tracker.setOpen(
            "t",
            DialogStateTracker.DialogState("prompt", "second", "default", true, "https://t/"),
        )

        val current = tracker.get("t")
        assertThat(current?.type).isEqualTo("prompt")
        assertThat(current?.message).isEqualTo("second")
        assertThat(current?.defaultPrompt).isEqualTo("default")
        assertThat(current?.hasBrowserHandler).isTrue()
        assertThat(current?.url).isEqualTo("https://t/")
    }

    @Test
    fun `clear empties all entries`() {
        val tracker = DialogStateTracker()
        tracker.setOpen("a", DialogStateTracker.DialogState("alert", "x", null, false, null))
        tracker.setOpen("b", DialogStateTracker.DialogState("alert", "y", null, false, null))

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
                put("url", "https://example.com/")
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
        assertThat(state.url).isEqualTo("https://example.com/")
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
                put("url", "https://login.example/")
            },
            sessionId = "session-1",
        )

        val response = client.send(ChromeCdpClient.DIALOG_QUERY_METHOD).jsonObject

        assertThat(response["type"]!!.jsonPrimitive.content).isEqualTo("prompt")
        assertThat(response["message"]!!.jsonPrimitive.content).isEqualTo("what's your name")
        assertThat(response["defaultPrompt"]!!.jsonPrimitive.content).isEqualTo("guest")
        assertThat(response["hasBrowserHandler"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(response["url"]!!.jsonPrimitive.content).isEqualTo("https://login.example/")
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

        val activations = mutableListOf<Pair<String?, String?>>()
        client.onTargetActivated = { sessionId, targetId ->
            activations += sessionId to targetId
        }

        // Real switch to a new target — callback fires with explicit (null, "page-2").
        client.send("Page.enable", options = CdpOptions(targetId = "page-2"))
        assertThat(activations).containsExactly(null to "page-2").inOrder()

        // No-op switch (already on page-2) — callback must NOT fire so we don't churn
        // domain re-enable on every same-target send().
        client.send("Page.enable", options = CdpOptions(targetId = "page-2"))
        assertThat(activations).hasSize(1)

        // Switch again — callback fires with the new target id.
        client.send("Page.enable", options = CdpOptions(targetId = "page-3"))
        assertThat(activations).containsExactly(null to "page-2", null to "page-3").inOrder()
    }

    @Test
    fun `onTargetActivated receives explicit sessionId after attach-mode targetId switch`() = runTest {
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

        val activations = mutableListOf<Pair<String?, String?>>()
        client.onTargetActivated = { sessionId, targetId ->
            activations += sessionId to targetId
        }

        // Bootstrap attach is direct, NOT through send(), so it must NOT fire the callback
        // — BrowserSessionManager calls enableCoreDomains itself for bootstrap.
        client.attachToTarget("bootstrap-target")
        assertThat(activations).isEmpty()

        // send() with targetId triggers a re-attach and must fire the callback with the
        // EXPLICIT new session id, not the global activeSessionId snapshot.
        client.send("Page.enable", options = CdpOptions(targetId = "page-2"))
        assertThat(activations).containsExactly("s-2" to "page-2").inOrder()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `callback receives explicit sessionId even when activeSessionId has drifted`() = runTest {
        // Codex HIGH: the OLD callback took no arguments and the production wiring then read
        // `activeSessionId` from the client to bind Page.enable. Two interleaved cdp(...,
        // {targetId}) calls would let the second attach overwrite `activeSessionId` while the
        // first call's callback is suspended inside its enable batch — so the first call's
        // remaining enables would land on the SECOND session and the first session would
        // silently lose dialog tracking.
        //
        // The new callback receives `(sessionId, targetId)` captured at the activation site so
        // a follow-on attach cannot redirect the bound enable. This test exercises the race by
        // gating the first activation until a second attach has changed `activeSessionId`,
        // then asserts the callback still saw the original explicit sessionId.
        val pendingAttachA = CompletableDeferred<Int>()
        val pendingAttachB = CompletableDeferred<Int>()
        val conn = FakeCdpConnection()
        conn.responder = { req ->
            if (req.method == "Target.attachToTarget") {
                when (req.params["targetId"]!!.jsonPrimitive.content) {
                    "page-A" -> pendingAttachA.complete(req.id)
                    "page-B" -> pendingAttachB.complete(req.id)
                }
                null  // hold the response so the test can interleave attaches by hand
            } else {
                FakeCdpConnection.defaultResponse(req)
            }
        }

        val client = ChromeCdpClient(conn.factory(), commandTimeoutMs = 60_000)
        client.connect("ws://test")

        val gateA = CompletableDeferred<Unit>()
        // Capture both the explicit arg AND the global activeSessionId observed inside the
        // callback so the assertion can prove the race actually occurred (otherwise the test
        // would silently degrade to the non-racy single-coroutine case and pass trivially).
        val observed = java.util.Collections.synchronizedList(
            mutableListOf<Triple<String?, String?, String?>>(),  // explicit sid, explicit tid, active sid at observe-time
        )
        client.onTargetActivated = { sessionId, targetId ->
            if (targetId == "page-A") gateA.await()
            observed += Triple(sessionId, targetId, client.activeSessionId)
        }

        val callA = async(start = CoroutineStart.UNDISPATCHED) {
            client.send("Page.bringToFront", options = CdpOptions(targetId = "page-A"))
        }
        val callB = async(start = CoroutineStart.UNDISPATCHED) {
            client.send("Page.bringToFront", options = CdpOptions(targetId = "page-B"))
        }

        val idA = pendingAttachA.await()
        val idB = pendingAttachB.await()

        // Resolve A first → dispatch T1 so it enters the callback and BLOCKS on gateA.
        // Without runCurrent here, both responses would be injected and gateA completed
        // before any continuation ran, and T1 would breeze through the callback observing
        // its own activeSessionId — the race window we are exercising would never open.
        conn.injectResponse(idA, buildJsonObject { put("sessionId", "session-A") })
        runCurrent()

        // Resolve B → dispatch T2. T2 has no gate; its callback runs to completion and
        // overwrites activeSessionId from session-A to session-B.
        conn.injectResponse(idB, buildJsonObject { put("sessionId", "session-B") })
        runCurrent()

        // Release T1's callback. It now observes activeSessionId == "session-B" while its
        // explicit `sessionId` arg is still the captured "session-A".
        gateA.complete(Unit)
        callA.await()
        callB.await()

        val triples = observed.toList()
        val a = triples.single { it.second == "page-A" }
        val b = triples.single { it.second == "page-B" }

        // Each callback received the EXPLICIT identifiers from its own activation.
        assertThat(a.first).isEqualTo("session-A")
        assertThat(b.first).isEqualTo("session-B")

        // Sanity: the race actually happened — T1's callback observed activeSessionId =
        // session-B at the moment it ran. If this assertion fails, the test no longer
        // proves what it claims and needs new sequencing.
        assertThat(a.third).isEqualTo("session-B")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `callback's bound send lands on explicit session not on globally active session`() = runTest {
        // The complement to the activation-args test: when production wiring uses the
        // explicit sessionId in CdpOptions, the resulting Page.enable on the wire must
        // carry that session — not whatever activeSessionId happens to be at dispatch
        // time. Without this, the production fix would still miss its target even if the
        // callback args were correct.
        val pendingAttachA = CompletableDeferred<Int>()
        val pendingAttachB = CompletableDeferred<Int>()
        val conn = FakeCdpConnection()
        conn.responder = { req ->
            if (req.method == "Target.attachToTarget") {
                when (req.params["targetId"]!!.jsonPrimitive.content) {
                    "page-A" -> pendingAttachA.complete(req.id)
                    "page-B" -> pendingAttachB.complete(req.id)
                }
                null
            } else {
                FakeCdpConnection.defaultResponse(req)
            }
        }

        val client = ChromeCdpClient(conn.factory(), commandTimeoutMs = 60_000)
        client.connect("ws://test")

        val gateA = CompletableDeferred<Unit>()
        client.onTargetActivated = { sessionId, _ ->
            // Mirror BrowserSessionManager.enableCoreDomainsFor: bind to the EXPLICIT
            // session, not the client's mutable activeSessionId.
            client.send("Page.enable", options = CdpOptions(sessionId = sessionId))
            if (sessionId == "session-A") gateA.await()
        }

        val callA = async(start = CoroutineStart.UNDISPATCHED) {
            client.send("Page.bringToFront", options = CdpOptions(targetId = "page-A"))
        }
        val callB = async(start = CoroutineStart.UNDISPATCHED) {
            client.send("Page.bringToFront", options = CdpOptions(targetId = "page-B"))
        }

        val idA = pendingAttachA.await()
        val idB = pendingAttachB.await()

        conn.injectResponse(idA, buildJsonObject { put("sessionId", "session-A") })
        runCurrent()
        conn.injectResponse(idB, buildJsonObject { put("sessionId", "session-B") })
        runCurrent()
        gateA.complete(Unit)

        callA.await()
        callB.await()

        val pageEnables = conn.sent
            .filter { it["method"]!!.jsonPrimitive.content == "Page.enable" }
            .mapNotNull { it["sessionId"]?.jsonPrimitive?.contentOrNull }
        assertThat(pageEnables.toSet()).containsExactly("session-A", "session-B")
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
