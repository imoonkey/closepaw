package ai.closepaw.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.closepaw.protocol.CompletionHandoff
import ai.closepaw.ui.theme.closePaw

/**
 * Pure-Kotlin guard for the "Open <App>" CTA. Returns true when [handoff] has a
 * non-null `appPackage` AND [canResolveLauncher] reports the package resolves to
 * a launcher intent. Extracted so the visibility decision can be unit-tested
 * without Compose; the composable below wraps `PackageManager.getLaunchIntentForPackage`.
 */
internal fun shouldShowOpenAppCta(
    handoff: CompletionHandoff?,
    canResolveLauncher: (String) -> Boolean,
): Boolean {
    val pkg = handoff?.appPackage ?: return false
    return canResolveLauncher(pkg)
}

/**
 * Renders the "Open <App>" CTA under a finished VD row. Hidden when the package
 * is null or PackageManager cannot resolve a launcher intent.
 */
@Composable
internal fun CompletionHandoffCtaRow(
    handoff: CompletionHandoff,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pkg = handoff.appPackage ?: return
    val canOpen = remember(pkg) {
        shouldShowOpenAppCta(handoff) { p -> context.packageManager.getLaunchIntentForPackage(p) != null }
    }
    if (!canOpen) return

    val spacing = MaterialTheme.closePaw.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("qa-handoff-cta-row"),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        val label = handoff.appLabel ?: pkg
        FilledTonalButton(
            onClick = { onOpenApp(pkg) },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier
                .height(32.dp)
                .testTag("qa-handoff-open-app"),
        ) {
            Text(
                text = "Open $label",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
