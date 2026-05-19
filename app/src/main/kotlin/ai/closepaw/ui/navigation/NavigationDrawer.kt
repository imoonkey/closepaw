package ai.closepaw.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.closepaw.history.model.SessionInfo
import ai.closepaw.ui.session.TimeUtils
import ai.closepaw.ui.theme.AppWindowInsets
import ai.closepaw.ui.theme.PageMastheadIdentity
import ai.closepaw.ui.theme.closePaw

/**
 * NavigationDrawer - Side drawer containing session history and settings access.
 * 
 * Structure:
 * - Header with close button
 * - "New Conversation" button
 * - Scrollable session list
 * - Settings entry at bottom
 */
@Composable
fun NavigationDrawerContent(
    sessions: List<SessionInfo>,
    currentModel: String,
    onSessionSelect: (SessionInfo) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (SessionInfo) -> Unit,
    onSettingsClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Use ModalDrawerSheet's built-in windowInsets parameter for proper system bar handling
    // This ensures consistent inset behavior at the component level
    // Width: 85% of screen width, max 320dp (responsive design)
    ModalDrawerSheet(
        modifier = modifier.fillMaxWidth(0.85f).widthIn(max = 320.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        windowInsets = AppWindowInsets.statusBars  // Handle status bar at container level
    ) {
        val spacing = MaterialTheme.closePaw.spacing
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            DrawerHeader(onClose = onClose)
            
            // New session button
            NewSessionButton(
                onClick = onNewSession,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Recent sessions header
            if (sessions.isNotEmpty()) {
                Text(
                    text = "Recent",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            
            // Session list (scrollable, takes remaining space)
            Box(modifier = Modifier.weight(1f)) {
                if (sessions.isEmpty()) {
                    EmptySessionsMessage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        items(
                            items = sessions,
                            key = { it.id }
                        ) { session ->
                            DrawerSessionItem(
                                session = session,
                                onClick = { onSessionSelect(session) },
                                onDelete = { onDeleteSession(session) }
                            )
                        }
                    }
                }
            }
            
            // Settings entry at bottom - no divider, uses surface background to distinguish
            SettingsEntry(
                currentModel = currentModel,
                onClick = onSettingsClick,
                modifier = Modifier
                    .padding(bottom = spacing.sm)  // Consistent bottom spacing
                    .windowInsetsPadding(WindowInsets.navigationBars)
            )
        }
    }
}

/**
 * Drawer header — Bound Edition running head + close affordance.
 */
@Composable
private fun DrawerHeader(
    onClose: () -> Unit
) {
    PageMastheadIdentity(
        title = "Sessions",
        onClose = onClose,
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
    )
}

/**
 * New session button — D1 §6.6: the one Claw-accented new-entry affordance.
 */
@Composable
private fun NewSessionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("New Conversation")
    }
}

/**
 * Individual session item in drawer.
 */
@Composable
private fun DrawerSessionItem(
    session: SessionInfo,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val rowShape = RoundedCornerShape(8.dp)
    val isCorrupted = session.isCorrupted
    val rowColor = when {
        isCorrupted -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f)
        session.isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val rowBorder = when {
        isCorrupted -> BorderStroke(1.dp, MaterialTheme.colorScheme.error)
        session.isActive -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        else -> null
    }
    val rowStateDescription = when {
        isCorrupted -> "Unreadable session file. Cannot open."
        session.isActive -> "Active session"
        else -> null
    }
    val statusText = when {
        isCorrupted -> "Unreadable session file"
        session.isActive -> "Active session"
        else -> null
    }
    val statusColor = when {
        isCorrupted -> MaterialTheme.colorScheme.error
        session.isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Session") },
            text = { Text("This session and its messages will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(rowShape)
            .then(
                if (isCorrupted) {
                    Modifier
                } else {
                    Modifier.clickable(
                        onClickLabel = "Open session",
                        role = Role.Button,
                        onClick = onClick
                    )
                }
            )
            .semantics {
                if (rowStateDescription != null) {
                    stateDescription = rowStateDescription
                }
            },
        color = rowColor,
        shape = rowShape,
        border = rowBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (statusText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = TimeUtils.formatMessageCount(session.messageCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Separator dot
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant)
                    )

                    // D1 §6.6: ledger treatment — mono dates.
                    Text(
                        text = TimeUtils.formatRelativeTime(session.lastUpdated),
                        style = MaterialTheme.closePaw.monoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Delete button — ghosted; visible but de-emphasised so the row
            // reads as a passive ledger entry, not a destructive surface.
            Box(
                modifier = Modifier.minimumInteractiveComponentSize(),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete session",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.closePaw.inkFaint
                    )
                }
            }
        }
    }
}

/**
 * Empty state message.
 */
@Composable
private fun EmptySessionsMessage(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No saved sessions",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Your chat history will appear here",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Settings entry at bottom of drawer.
 * Uses surface color (no fill) to distinguish from session items above.
 */
@Composable
private fun SettingsEntry(
    currentModel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // D1 §6.6: machine-text ledger — current model in mono.
            Text(
                text = currentModel,
                style = MaterialTheme.closePaw.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
