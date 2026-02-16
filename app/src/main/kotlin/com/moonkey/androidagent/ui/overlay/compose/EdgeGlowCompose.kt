package com.moonkey.androidagent.ui.overlay.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moonkey.androidagent.ui.overlay.model.GlowState

@Composable
fun EdgeGlowCompose(
    state: GlowState,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val glowWidth = 40.dp.toPx()
        val glowColor = Color(state.colorHex).copy(alpha = alpha)
        val transparent = Color.Transparent

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(glowColor, transparent),
                start = Offset(0f, 0f),
                end = Offset(0f, glowWidth)
            ),
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(size.width, glowWidth)
        )

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(glowColor, transparent),
                start = Offset(0f, size.height),
                end = Offset(0f, size.height - glowWidth)
            ),
            topLeft = Offset(0f, size.height - glowWidth),
            size = androidx.compose.ui.geometry.Size(size.width, glowWidth)
        )

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(glowColor, transparent),
                start = Offset(0f, 0f),
                end = Offset(glowWidth, 0f)
            ),
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(glowWidth, size.height)
        )

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(glowColor, transparent),
                start = Offset(size.width, 0f),
                end = Offset(size.width - glowWidth, 0f)
            ),
            topLeft = Offset(size.width - glowWidth, 0f),
            size = androidx.compose.ui.geometry.Size(glowWidth, size.height)
        )
    }
}
