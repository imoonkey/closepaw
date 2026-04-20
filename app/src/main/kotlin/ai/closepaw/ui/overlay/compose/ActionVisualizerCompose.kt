package ai.closepaw.ui.overlay.compose

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

internal sealed interface VisualizationItem {
    val id: Long
    val createdAtMs: Long
    val durationMs: Long

    data class Click(
        override val id: Long,
        override val createdAtMs: Long,
        override val durationMs: Long,
        val x: Float,
        val y: Float,
        val longPress: Boolean,
    ) : VisualizationItem

    data class Swipe(
        override val id: Long,
        override val createdAtMs: Long,
        override val durationMs: Long,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val scroll: Boolean,
    ) : VisualizationItem
}

@Composable
internal fun ActionVisualizerCompose(
    items: List<VisualizationItem>,
    modifier: Modifier = Modifier,
) {
    var frameTimeMs by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val tapColor = MaterialTheme.colorScheme.primary
    val longPressColor = MaterialTheme.colorScheme.tertiary
    val swipeColor = MaterialTheme.colorScheme.primary
    val scrollColor = MaterialTheme.colorScheme.secondary

    LaunchedEffect(items.isNotEmpty()) {
        if (!items.isNotEmpty()) return@LaunchedEffect
        while (true) {
            withFrameMillis { frameTimeMs = it }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val lineWidth = 4.dp.toPx()
        val startDotRadius = 8.dp.toPx()
        val endDotRadius = 6.dp.toPx()
        val initialClickRadius = 8.dp.toPx()
        val maxClickRadius = 48.dp.toPx()

        items.forEach { item ->
            val elapsed = (frameTimeMs - item.createdAtMs).coerceAtLeast(0L)
            val progress = (elapsed.toFloat() / item.durationMs.coerceAtLeast(1L).toFloat())
                .coerceIn(0f, 1f)

            when (item) {
                is VisualizationItem.Click -> {
                    val radius = lerp(initialClickRadius, maxClickRadius, progress)
                    val alpha = (0.6f * (1f - progress * 0.7f)).coerceIn(0f, 0.6f)
                    val color = if (item.longPress) longPressColor else tapColor
                    drawCircle(
                        color = color.copy(alpha = alpha),
                        radius = radius,
                        center = androidx.compose.ui.geometry.Offset(item.x, item.y)
                    )
                }

                is VisualizationItem.Swipe -> {
                    val color = if (item.scroll) scrollColor else swipeColor
                    val currentX = lerp(item.startX, item.endX, progress)
                    val currentY = lerp(item.startY, item.endY, progress)
                    val lineAlpha = (0.5f * (1f - progress * 0.4f)).coerceIn(0f, 0.5f)
                    val dotAlpha = (0.6f * (1f - progress * 0.4f)).coerceIn(0f, 0.6f)

                    drawCircle(
                        color = color.copy(alpha = dotAlpha),
                        radius = startDotRadius,
                        center = androidx.compose.ui.geometry.Offset(item.startX, item.startY)
                    )
                    if (progress > 0.01f) {
                        drawLine(
                            color = color.copy(alpha = lineAlpha),
                            start = androidx.compose.ui.geometry.Offset(item.startX, item.startY),
                            end = androidx.compose.ui.geometry.Offset(currentX, currentY),
                            strokeWidth = lineWidth,
                            cap = StrokeCap.Round
                        )
                    }
                    if (progress > 0.1f) {
                        drawCircle(
                            color = color.copy(alpha = dotAlpha),
                            radius = endDotRadius,
                            center = androidx.compose.ui.geometry.Offset(currentX, currentY),
                        )
                    }
                }
            }
        }
    }
}
