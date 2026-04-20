package ai.closepaw.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ai.closepaw.R
import ai.closepaw.ui.theme.Fraunces
import ai.closepaw.ui.theme.closePaw

/**
 * Shared scaffold for every onboarding step.
 *
 * Provides: back arrow (except step 1), step count, linear progress bar,
 * title, and content slot.
 */
@Composable
fun OnboardingShell(
    stepIndex: Int,
    totalSteps: Int,
    title: String,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        val spacing = MaterialTheme.closePaw.spacing
        // Two layout modes via a height heuristic so step bodies' Spacer(weight)
        // pattern keeps pinning CTAs on tall screens, while short landscape
        // heights fall back to a scrollable column (finding #4).
        // Threshold: typical landscape phones are <480dp tall; portrait phones
        // and tablets exceed it comfortably.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            val needsScroll = maxHeight < 480.dp
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (needsScroll) Modifier.verticalScroll(rememberScrollState())
                        else Modifier.fillMaxHeight()
                    )
                    .padding(horizontal = spacing.lg)
            ) {
            Spacer(modifier = Modifier.height(spacing.md))

            // Back arrow + step counter
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(spacing.xs))
                }
                Text(
                    text = "Step $stepIndex of $totalSteps",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(spacing.sm))

            // D1 §6.4: five-paw progress treatment — filled paws for completed
            // steps, ghosted for upcoming. Reuses ic_paw at small size.
            FivePawProgress(stepIndex = stepIndex, totalSteps = totalSteps)

            Spacer(modifier = Modifier.height(spacing.xl))

            // Step title — D1 §4.2 identity surface (Fraunces).
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = Fraunces),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Step content
            content()
            }
        }
    }
}

@Composable
private fun FivePawProgress(stepIndex: Int, totalSteps: Int) {
    val spacing = MaterialTheme.closePaw.spacing
    val pawCount = 5
    // Map progress onto five paws regardless of totalSteps (spec is fixed at 5).
    val filled = ((stepIndex.toFloat() / totalSteps) * pawCount)
        .toInt()
        .coerceIn(0, pawCount)
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        repeat(pawCount) { i ->
            Icon(
                painter = painterResource(R.drawable.ic_paw),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (i < filled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
