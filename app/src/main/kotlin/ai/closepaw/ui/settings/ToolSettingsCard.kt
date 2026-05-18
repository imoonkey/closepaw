package ai.closepaw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.closepaw.ui.theme.closePaw
import ai.closepaw.ui.theme.foldedPaper

internal data class ToolStatusUi(
    val label: String,
    val subtitle: String,
    val tone: ToolStatusTone,
    val showSpinner: Boolean = false,
)

internal enum class ToolStatusTone { Positive, Neutral, Warning }

@Composable
internal fun ToolSettingsCard(
    title: String,
    status: ToolStatusUi,
    switchChecked: Boolean,
    onSwitchChange: (Boolean) -> Unit,
    onRowClick: (() -> Unit)? = null,
    onRowClickLabel: String? = null,
    switchEnabled: Boolean = true,
    switchModifier: Modifier = Modifier,
    trailingInfoIcon: (() -> Unit)? = null,
    trailingInfoIconLabel: String? = null,
    expanded: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val surfaceModifier = Modifier
        .fillMaxWidth()
        .let { base ->
            if (onRowClick != null) {
                val label = requireNotNull(onRowClickLabel) {
                    "onRowClickLabel is required when onRowClick is provided"
                }
                base
                    .semantics {
                        role = Role.Button
                        onClick(label = label) {
                            onRowClick()
                            true
                        }
                    }
                    .clickable(onClick = onRowClick)
            } else {
                base
            }
        }

    Surface(
        modifier = surfaceModifier.foldedPaper(MaterialTheme.shapes.medium),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(horizontal = MaterialTheme.closePaw.spacing.cardPadding, vertical = MaterialTheme.closePaw.spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = status.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = status.tone.labelColor(),
                        )
                        if (status.showSpinner) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    Text(
                        text = status.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                if (trailingInfoIcon != null) {
                    val infoLabel = trailingInfoIconLabel ?: "View $title details"
                    IconButton(onClick = trailingInfoIcon) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = infoLabel,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Switch(
                    checked = switchChecked,
                    onCheckedChange = onSwitchChange,
                    enabled = switchEnabled,
                    modifier = switchModifier,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }

            if (expanded != null) {
                Spacer(modifier = Modifier.height(8.dp))
                expanded()
            }
        }
    }
}

@Composable
private fun ToolStatusTone.labelColor() = when (this) {
    // Warning uses onSurface (not tertiary) — amber on surfaceVariant fails AA contrast.
    // See SettingsWidgets.kt:232-234.
    ToolStatusTone.Positive -> MaterialTheme.colorScheme.secondary
    ToolStatusTone.Neutral -> MaterialTheme.colorScheme.onSurface
    ToolStatusTone.Warning -> MaterialTheme.colorScheme.onSurface
}
