package ai.closepaw.platform

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class BitmapUtilsTest {

    private fun fakeBitmap(w: Int, h: Int): Bitmap = mockk<Bitmap>().also {
        every { it.width } returns w
        every { it.height } returns h
    }

    @Test
    fun `estimate clamps tiny bitmap to 1KB floor`() {
        val capacity = BitmapUtils.estimateJpegCapacity(fakeBitmap(1, 1))
        assertThat(capacity).isEqualTo(1024)
    }

    @Test
    fun `estimate clamps huge bitmap to 512KB ceiling`() {
        val capacity = BitmapUtils.estimateJpegCapacity(fakeBitmap(4000, 4000))
        assertThat(capacity).isEqualTo(512 * 1024)
    }

    @Test
    fun `estimate scales with pixel count in the common range`() {
        val capacity = BitmapUtils.estimateJpegCapacity(fakeBitmap(500, 500))
        val expected = 500 * 500 * 4 / 10
        assertThat(capacity).isEqualTo(expected)
        assertThat(capacity).isGreaterThan(1024)
        assertThat(capacity).isLessThan(512 * 1024)
    }

    @Test
    fun `estimate handles overflow-safe large dimensions without negative values`() {
        val capacity = BitmapUtils.estimateJpegCapacity(fakeBitmap(100_000, 100_000))
        assertThat(capacity).isEqualTo(512 * 1024)
    }
}
