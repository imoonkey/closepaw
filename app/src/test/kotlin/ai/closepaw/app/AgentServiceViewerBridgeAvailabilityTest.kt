package ai.closepaw.app

import ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Regression coverage for stale-handoff issue: once a VD task completes, the chat
 * row still says `virtualDisplayAvailable = true` even after the idle timeout tears
 * down the platform. The click path must re-ask the bridge, not trust the row, so
 * tapping View virtual screen on a stale row no-ops instead of opening a dead viewer.
 */
class AgentServiceViewerBridgeAvailabilityTest {

    @Test
    fun `isViewerAvailable is false when no VD platform is active`() {
        val bridge = AgentServiceViewerBridge(
            logTag = "test",
            overlayControllerProvider = { null },
            platformProvider = { null },
            openViewerActivity = { error("must not open") },
            finishViewerActivity = { error("must not finish") },
        )
        assertThat(bridge.isViewerAvailable()).isFalse()
    }

    @Test
    fun `isViewerAvailable forwards Running platform state`() {
        val platform = mockk<VirtualDisplayPlatform>()
        every { platform.isViewerAvailable() } returns true
        val bridge = AgentServiceViewerBridge(
            logTag = "test",
            overlayControllerProvider = { null },
            platformProvider = { platform },
            openViewerActivity = { error("must not open") },
            finishViewerActivity = { error("must not finish") },
        )
        assertThat(bridge.isViewerAvailable()).isTrue()
    }

    @Test
    fun `isViewerAvailable is false once platform reports not Running`() {
        val platform = mockk<VirtualDisplayPlatform>()
        every { platform.isViewerAvailable() } returns false
        val bridge = AgentServiceViewerBridge(
            logTag = "test",
            overlayControllerProvider = { null },
            platformProvider = { platform },
            openViewerActivity = { error("must not open") },
            finishViewerActivity = { error("must not finish") },
        )
        assertThat(bridge.isViewerAvailable()).isFalse()
    }
}
