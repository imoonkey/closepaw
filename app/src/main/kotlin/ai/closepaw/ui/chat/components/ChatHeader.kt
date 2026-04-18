package ai.closepaw.ui.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * ChatHeader - Header with navigation menu and new chat button.
 * 
 * Layout: [Menu] --- Title --- [New Chat]
 * - Menu button (left): Opens navigation drawer with history + settings
 * - New Chat button (right): Quick new conversation
 * 
 * Uses background color (not surface) for seamless visual integration
 * with content area below.
 */
@Composable
fun ChatHeader(
    onMenuClick: () -> Unit,
    onNewChatClick: (() -> Unit)? = null,
    showNewChatButton: Boolean = true,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
        color = MaterialTheme.colorScheme.background  // Use background for seamless look
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Menu button (left)
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Open menu",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Title (center) - slightly lighter weight for elegance
            Text(
                text = "ClosePaw",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,  // Lighter than SemiBold
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // New chat button (right)
            if (showNewChatButton && onNewChatClick != null) {
                IconButton(
                    onClick = onNewChatClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "New conversation",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Placeholder for symmetry
                Spacer(modifier = Modifier.size(48.dp))
            }
        }
    }
}
