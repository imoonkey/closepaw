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
 * Visibility decision for the completion-handoff CTA. Pure-Kotlin so it can
 * be unit-tested without Compose; the composable below combines it with the
 * Android `PackageManager` lookup that requires a real `Context`.
 *
 * The "View virtual screen" CTA was removed: post-completion the viewer had no
 * working takeover/exit affordance and no edge glow, so the button was a dead
 * end. `CompletionHandoff.virtualDisplayAvailable` is still captured so the
 * affordance can be re-introduced if/when the VD session can outlive task
 * completion (see vd-runtime-boundary).
 */
internal data class CompletionHandoffCtaVisibility(
    val showOpenApp: Boolean,
) {
    val any: Boolean get() = showOpenApp
}

/**
 * Compute whether to render the Open <App> CTA.
 *
 * @param canResolveLauncher returns true when `PackageManager.getLaunchIntentForPackage`
 *   would yield a non-null intent for the given package — injected so this stays
 *   testable. Only invoked when [CompletionHandoff.appPackage] is non-null.
 */
internal fun completionHandoffCtaVisibility(
    handoff: CompletionHandoff?,
    canResolveLauncher: (String) -> Boolean,
): CompletionHandoffCtaVisibility {
    if (handoff == null) return CompletionHandoffCtaVisibility(false)
    val showOpenApp = handoff.appPackage?.let(canResolveLauncher) ?: false
    return CompletionHandoffCtaVisibility(showOpenApp = showOpenApp)
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
    val pkg = handoff.appPackage
    val visibility = remember(pkg) {
        completionHandoffCtaVisibility(
            handoff = handoff,
            canResolveLauncher = { p -> context.packageManager.getLaunchIntentForPackage(p) != null },
        )
    }
    if (!visibility.any) return
    val resolvablePkg = pkg ?: return

    val spacing = MaterialTheme.closePaw.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("qa-handoff-cta-row"),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        val label = handoff.appLabel ?: resolvablePkg
        FilledTonalButton(
            onClick = { onOpenApp(resolvablePkg) },
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
