package ai.closepaw.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import ai.closepaw.ui.theme.Fraunces
import ai.closepaw.ui.theme.closePaw
import ai.closepaw.ui.theme.foldedPaper

/**
 * Model loading status indicator.
 */
@Composable
internal fun ModelLoadingStatusIndicator(status: ModelLoadingStatus) {
    when (status) {
        is ModelLoadingStatus.Idle -> Unit
        is ModelLoadingStatus.Downloading -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .foldedPaper(MaterialTheme.shapes.medium),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(MaterialTheme.closePaw.spacing.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm)
                    ) {
                        Text(
                            text = "Downloading model...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${(status.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        is ModelLoadingStatus.Loading -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .foldedPaper(MaterialTheme.shapes.medium),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(MaterialTheme.closePaw.spacing.cardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm)
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Loading model...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        is ModelLoadingStatus.Ready -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .foldedPaper(MaterialTheme.shapes.medium),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(MaterialTheme.closePaw.spacing.cardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary)
                    )
                    Text(
                        text = "Model ready",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
        is ModelLoadingStatus.Error -> {
            SettingsAlertCard(
                message = "Error: ${status.message}",
                tone = AlertTone.Error,
            )
        }
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        // D1 §6.3: serif section heads in settings.
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = Fraunces),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = MaterialTheme.closePaw.spacing.md)
        )
        content()
    }
}

@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .foldedPaper(MaterialTheme.shapes.medium),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.closePaw.spacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val dotColor = if (isEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                // Contrast-matrix.md §issues: Amber (tertiary) on PaperInset (surfaceVariant) is 1.77:1 — hard fail.
                // Keep Amber as the dot fill; the label uses an AA-safe foreground (onSurface).
                val labelColor = if (isEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    text = if (isEnabled) "Enabled" else "Required",
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor
                )
            }
        }
    }
}

@Composable
internal fun SettingsNavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .foldedPaper(MaterialTheme.shapes.medium),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.closePaw.spacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.closePaw.inkFaint,
            )
        }
    }
}

@Composable
internal fun PerceptionModeSelector(
    selectedMode: String,
    onModeChange: (String) -> Unit
) {
    data class PerceptionOption(
        val value: String,
        val label: String,
        val subtitle: String,
        val recommended: Boolean = false,
    )

    val options = listOf(
        PerceptionOption(
            value = "accessibility_only",
            label = "Screen Transcript",
            subtitle = "Reads on-screen text and structure. Fastest, lowest cost, and works for most apps.",
            recommended = true,
        ),
        PerceptionOption(
            value = "screenshot_only",
            label = "Screen Image",
            subtitle = "Looks at screenshots only. Slower and higher cost; use for maps, canvas, games, or apps that hide their structure.",
        ),
        PerceptionOption(
            value = "hybrid",
            label = "Transcript + Image",
            subtitle = "Combines both. Most reliable for tricky apps; slowest and highest cost.",
        ),
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .foldedPaper(MaterialTheme.shapes.medium),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(vertical = MaterialTheme.closePaw.spacing.sm)) {
            Row(
                modifier = Modifier.padding(horizontal = MaterialTheme.closePaw.spacing.cardPadding, vertical = MaterialTheme.closePaw.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Perception Mode",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "How the agent sees the screen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { option ->
                    val isSelected = selectedMode == option.value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                onClick = { onModeChange(option.value) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = MaterialTheme.closePaw.spacing.md, vertical = 10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (option.recommended) {
                                Spacer(modifier = Modifier.height(4.dp))
                                RecommendedChip()
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = option.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendedChip() {
    Surface(
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = "Recommended",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = MaterialTheme.closePaw.spacing.sm, vertical = 2.dp),
        )
    }
}
