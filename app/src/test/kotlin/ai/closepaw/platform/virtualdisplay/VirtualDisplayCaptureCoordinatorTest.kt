package ai.closepaw.platform.virtualdisplay

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import com.google.common.truth.Truth.assertThat
import ai.closepaw.trace.TraceRecorder
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VirtualDisplayCaptureCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val config = VirtualDisplayConfig(
        width = 100,
        height = 200,
        densityDpi = 320,
        density = 2.0f
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Bitmap::class)
        mockkStatic(Looper::class)
        mockkStatic(PixelCopy::class)
        mockkConstructor(Handler::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        every { Bitmap.createBitmap(any<Int>(), any<Int>(), any()) } returns
            mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
        Dispatchers.resetMain()
    }

    private fun validSurfaceView(): SurfaceView {
        val sv = mockk<SurfaceView>(relaxed = true)
        every { sv.holder.surface.isValid } returns true
        return sv
    }

    private fun makeCoordinator(
        surfaceController: VirtualDisplaySurfaceController,
        switchToImageReader: () -> Unit
    ): VirtualDisplayCaptureCoordinator {
        val trace = mockk<TraceRecorder>(relaxed = true)
        every { trace.enabled } returns false
        return VirtualDisplayCaptureCoordinator(
            config = config,
            windowAccessor = mockk(relaxed = true),
            imageReaderProvider = { null },
            surfaceController = surfaceController,
            switchToImageReader = switchToImageReader,
            screenshotProcessor = mockk(relaxed = true),
            traceRecorder = trace
        )
    }

    @Test
    fun `PixelCopy failure falls back to ImageReader path`() = runTest {
        val surfaceController = mockk<VirtualDisplaySurfaceController>()
        every { surfaceController.mode() } returns VirtualDisplaySurfaceMode.LIVE_PREVIEW
        every { surfaceController.liveSurfaceView() } returns validSurfaceView()

        every {
            PixelCopy.request(any<SurfaceView>(), any<Bitmap>(), any(), any<Handler>())
        } answers {
            val listener = arg<PixelCopy.OnPixelCopyFinishedListener>(2)
            listener.onPixelCopyFinished(PixelCopy.ERROR_UNKNOWN)
        }

        var switchCount = 0
        val coord = makeCoordinator(surfaceController) { switchCount++ }

        val result = coord.captureScreenshot()

        // Fallback took the ImageReader path; provider returns null so result is null.
        assertThat(result).isNull()
        // Single failure is below threshold — no permanent demotion yet.
        assertThat(switchCount).isEqualTo(0)
    }

    @Test
    fun `repeated PixelCopy failures demote permanently to ImageReader`() = runTest {
        val surfaceController = mockk<VirtualDisplaySurfaceController>()
        every { surfaceController.mode() } returns VirtualDisplaySurfaceMode.LIVE_PREVIEW
        every { surfaceController.liveSurfaceView() } returns validSurfaceView()

        every {
            PixelCopy.request(any<SurfaceView>(), any<Bitmap>(), any(), any<Handler>())
        } answers {
            val listener = arg<PixelCopy.OnPixelCopyFinishedListener>(2)
            listener.onPixelCopyFinished(PixelCopy.ERROR_UNKNOWN)
        }

        var switchCount = 0
        val coord = makeCoordinator(surfaceController) { switchCount++ }

        coord.captureScreenshot() // failCount = 1, below threshold
        assertThat(switchCount).isEqualTo(0)

        coord.captureScreenshot() // failCount = 2, reaches threshold → demote
        assertThat(switchCount).isEqualTo(1)
    }
}
