package ai.closepaw.ui.settings

import ai.closepaw.platform.virtualdisplay.ShizukuClient
import ai.closepaw.protocol.PlatformMode
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
private const val SHIZUKU_HELP_URL = "https://shizuku.rikka.app"

@Composable
internal fun DisplayModeSection(
    persistedMode: PlatformMode,
    effectiveMode: PlatformMode?,
    onModeChange: (PlatformMode) -> Unit,
) {
    val context = LocalContext.current
    val client = remember { ShizukuClient() }
    val statusState = rememberShizukuStatus(client)
    DisplayModeSection(
        persistedMode = persistedMode,
        effectiveMode = effectiveMode,
        status = statusState.value,
        onModeChange = onModeChange,
        onLearnMore = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_HELP_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
        onGrant = { client.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE) },
    )
}

@Composable
internal fun DisplayModeSection(
    persistedMode: PlatformMode,
    effectiveMode: PlatformMode?,
    status: ShizukuStatus,
    onModeChange: (PlatformMode) -> Unit,
    onLearnMore: () -> Unit,
    onGrant: () -> Unit,
) {
    val displayedMode = effectiveMode ?: persistedMode
    val virtualDisplayEnabled = status == ShizukuStatus.Ready

    SettingsSection(title = "Display Mode") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModeOption(
                    label = "Accessibility",
                    selected = displayedMode == PlatformMode.ACCESSIBILITY,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeChange(PlatformMode.ACCESSIBILITY) }
                )
                ModeOption(
                    label = "Virtual Display",
                    selected = displayedMode == PlatformMode.VIRTUAL_DISPLAY,
                    enabled = virtualDisplayEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeChange(PlatformMode.VIRTUAL_DISPLAY) }
                )
            }

            ShizukuStatusRow(
                status = status,
                onLearnMore = onLearnMore,
                onGrant = onGrant,
            )
        }
    }
}

@Composable
private fun ModeOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
        }
    }
}

@Composable
private fun ShizukuStatusRow(
    status: ShizukuStatus,
    onLearnMore: () -> Unit,
    onGrant: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (status) {
                ShizukuStatus.Unavailable -> {
                    Text(
                        text = "Shizuku not running",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onLearnMore) { Text("Learn more") }
                }
                ShizukuStatus.NeedsPermission -> {
                    Text(
                        text = "Shizuku running, permission needed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onGrant) { Text("Grant") }
                }
                ShizukuStatus.Ready -> {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Shizuku ready",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
