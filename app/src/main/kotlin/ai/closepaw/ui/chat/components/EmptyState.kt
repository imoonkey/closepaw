package ai.closepaw.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.closepaw.R
import ai.closepaw.ui.theme.Fraunces
import ai.closepaw.ui.theme.closePaw

/**
 * EmptyState — first launch experience with suggestions.
 * Identity-tier subtitle uses the Fraunces serif italic from `ClosePawTokens`.
 */
@Composable
fun EmptyState(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.closePaw.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // D1 §6.2: large paw watermark, Ink at full opacity.
        Icon(
            painter = painterResource(R.drawable.ic_paw),
            contentDescription = null,
            modifier = Modifier.size(160.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(spacing.lg + spacing.xs))

        Text(
            text = "ClosePaw",
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Fraunces),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(spacing.sm))

        // Identity-tier serif italic per Track A / aligned visual spec §6.
        Text(
            text = "What can I help you with?",
            style = MaterialTheme.closePaw.serifItalic,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(spacing.xl + spacing.sm))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            SuggestionChip(
                text = "Check my unread emails",
                onClick = { onSuggestionClick("Check my unread emails") }
            )

            SuggestionChip(
                text = "Turn on Do Not Disturb",
                onClick = { onSuggestionClick("Turn on Do Not Disturb") }
            )

            SuggestionChip(
                text = "Search for nearby restaurants",
                onClick = { onSuggestionClick("Search for nearby restaurants") }
            )
        }
    }
}

@Composable
private fun SuggestionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.closePaw.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(
            text = "\"$text\"",
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md + spacing.xs),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

