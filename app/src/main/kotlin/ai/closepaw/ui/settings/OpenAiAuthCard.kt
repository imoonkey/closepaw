package ai.closepaw.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ai.closepaw.ui.theme.ChatSuccess

sealed interface OpenAiAuthUiState {
    data object SignedOut : OpenAiAuthUiState
    data object InProgress : OpenAiAuthUiState
    data object Finishing : OpenAiAuthUiState
    data class SignedIn(val email: String?) : OpenAiAuthUiState
    data class Error(val message: String) : OpenAiAuthUiState
}

@Composable
internal fun OpenAiAuthCard(
    state: OpenAiAuthUiState,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (state) {
                is OpenAiAuthUiState.SignedOut -> SignedOutContent(onStartOAuth)
                is OpenAiAuthUiState.InProgress -> InProgressContent(onCancelOAuth)
                is OpenAiAuthUiState.Finishing -> FinishingContent()
                is OpenAiAuthUiState.SignedIn -> SignedInContent(state.email, onSignOut)
                is OpenAiAuthUiState.Error -> ErrorContent(state.message, onStartOAuth)
            }
        }
    }
}

@Composable
private fun SignedOutContent(onStartOAuth: () -> Unit) {
    Text(
        text = "Not signed in",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(12.dp))
    FilledTonalButton(
        onClick = onStartOAuth,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Sign in with OpenAI")
    }
}

@Composable
private fun InProgressContent(onCancelOAuth: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            text = "Signing in...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    TextButton(onClick = onCancelOAuth) {
        Text("Cancel")
    }
}

@Composable
private fun FinishingContent() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            text = "Finishing up with OpenAI...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SignedInContent(email: String?, onSignOut: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(ChatSuccess)
        )
        Text(
            text = "Signed in",
            style = MaterialTheme.typography.bodyMedium,
            color = ChatSuccess
        )
    }
    if (!email.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = email,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = onSignOut,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        )
    ) {
        Text("Sign Out")
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(modifier = Modifier.height(12.dp))
    FilledTonalButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text("Try Again")
    }
}
