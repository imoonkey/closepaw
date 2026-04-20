package ai.closepaw.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.closepaw.onboarding.PermissionStateMonitor.PermissionRepairModel
import ai.closepaw.ui.theme.closePaw

/**
 * In-chat repair card shown when a permission is revoked after onboarding completes.
 *
 * Not a full wizard replay — just a targeted fix for the specific revoked permission.
 */
@Composable
fun PermissionRepairCard(
    model: PermissionRepairModel,
    onFixAccessibility: () -> Unit,
    onFixOverlay: () -> Unit,
    onFixBattery: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SETUP ISSUE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (model.accessibilityMissing) {
                RepairRow(
                    label = "Accessibility service is disabled. The agent cannot automate tasks.",
                    buttonLabel = "Fix",
                    onClick = onFixAccessibility
                )
            }

            if (model.overlayMissing) {
                RepairRow(
                    label = "Overlay permission is revoked. You won't see controls while the agent works.",
                    buttonLabel = "Fix",
                    onClick = onFixOverlay
                )
            }

            if (model.batteryMissing) {
                RepairRow(
                    label = "Battery optimization re-enabled. Long tasks may stop.",
                    buttonLabel = "Fix",
                    onClick = onFixBattery
                )
            }
        }
    }
}

@Composable
private fun RepairRow(
    label: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.closePaw.monoBody,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onClick) {
            Text(buttonLabel)
        }
    }
}
