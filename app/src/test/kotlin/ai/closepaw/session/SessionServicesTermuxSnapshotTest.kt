package ai.closepaw.session

import ai.closepaw.termux.TermuxBridgeStatus
import ai.closepaw.termux.TermuxCapabilitySnapshot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import org.junit.Test

class SessionServicesTermuxSnapshotTest {

    @Test
    fun `captureTermuxSnapshot probes bridge before snapshot`() {
        val bridge = FakeTermuxSessionBridge()

        val snapshot = SessionServices.captureTermuxSnapshot(
            bridge = bridge,
            termuxShellEnabled = true
        )

        assertThat(bridge.healthChecks).isEqualTo(1)
        assertThat(snapshot.available).isTrue()
        assertThat(snapshot.enabled).isTrue()
        assertThat(snapshot.status).isEqualTo(TermuxBridgeStatus.Ready)
    }

    @Test
    fun `captureTermuxSnapshot keeps toggle disabled after successful probe`() {
        val bridge = FakeTermuxSessionBridge()

        val snapshot = SessionServices.captureTermuxSnapshot(
            bridge = bridge,
            termuxShellEnabled = false
        )

        assertThat(bridge.healthChecks).isEqualTo(1)
        assertThat(snapshot.available).isFalse()
        assertThat(snapshot.enabled).isFalse()
        assertThat(snapshot.status).isEqualTo(TermuxBridgeStatus.Ready)
    }

    @Test
    fun `captureTermuxSnapshot falls back to current state when probe times out`() {
        val bridge = FakeTermuxSessionBridge(delayMs = 50)

        val snapshot = SessionServices.captureTermuxSnapshot(
            bridge = bridge,
            termuxShellEnabled = true,
            timeoutMs = 1
        )

        assertThat(bridge.healthChecks).isEqualTo(1)
        assertThat(snapshot.available).isFalse()
        assertThat(snapshot.enabled).isTrue()
        assertThat(snapshot.status).isEqualTo(TermuxBridgeStatus.NotInstalled)
    }

    private class FakeTermuxSessionBridge(
        private val delayMs: Long = 0
    ) : TermuxSessionBridge {
        var healthChecks = 0
            private set
        private var status: TermuxBridgeStatus = TermuxBridgeStatus.NotInstalled

        override suspend fun healthCheck(): TermuxBridgeStatus {
            healthChecks += 1
            if (delayMs > 0) delay(delayMs)
            status = TermuxBridgeStatus.Ready
            return status
        }

        override fun snapshot(enabled: Boolean): TermuxCapabilitySnapshot =
            TermuxCapabilitySnapshot(
                available = enabled && status is TermuxBridgeStatus.Ready,
                enabled = enabled,
                status = status
            )
    }
}
