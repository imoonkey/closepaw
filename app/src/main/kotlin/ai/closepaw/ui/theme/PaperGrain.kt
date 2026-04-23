package ai.closepaw.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import kotlin.random.Random

private const val GrainTileSize = 256
private const val GrainSeed = 42L

private fun bakeNoiseTile(size: Int, ink: Color, alpha: Float): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val rng = Random(GrainSeed)
    val r = (ink.red * 255f).toInt()
    val g = (ink.green * 255f).toInt()
    val b = (ink.blue * 255f).toInt()
    val maxAlpha = (alpha * 255f).toInt().coerceIn(0, 255)
    val pixels = IntArray(size * size) {
        val pa = (rng.nextFloat() * maxAlpha).toInt()
        (pa shl 24) or (r shl 16) or (g shl 8) or b
    }
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap.asImageBitmap()
}

// Light theme: tile a baked noise texture under the modifier's content.
// Dark theme: no-op — Lantern uses [lanternVignette] instead.
fun Modifier.paperGrain(strength: Float = 0.04f): Modifier = composed {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    if (isDark) return@composed this
    val ink = MaterialTheme.colorScheme.onSurface
    val tile = remember(strength, ink) { bakeNoiseTile(GrainTileSize, ink, strength) }
    val brush = remember(tile) {
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
    drawWithCache {
        onDrawWithContent {
            drawContent()
            drawRect(brush = brush, size = size)
        }
    }
}

// Dark theme companion: warm radial vignette in place of [paperGrain].
// No-op in light theme.
fun Modifier.lanternVignette(): Modifier = composed {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    if (!isDark) return@composed this
    val warm = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    drawWithCache {
        val brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, warm),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = maxOf(size.width, size.height) * 0.7f,
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush = brush)
        }
    }
}
