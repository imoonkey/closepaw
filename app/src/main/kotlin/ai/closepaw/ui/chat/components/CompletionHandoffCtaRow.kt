package ai.closepaw.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * Visibility decision for the two completion-handoff CTAs. Pure-Kotlin so it can
 * be unit-tested without Compose; the composable below combines it with the
 * Android `PackageManager` lookup that requires a real `Context`.
 */
internal data class CompletionHandoffCtaVisibility(
    val showOpenApp: Boolean,
    val showOpenViewer: Boolean,
) {
    val any: Boolean get() = showOpenApp || showOpenViewer
}

/**
 * Compute which handoff CTAs to render.
 *
 * @param canResolveLauncher returns true when [PackageManager.getLaunchIntentForPackage]
 *   would yield a non-null intent for the given package — injected so this stays
 *   testable. Only invoked when [CompletionHandoff.appPackage] is non-null.
 */
internal fun completionHandoffCtaVisibility(
    handoff: CompletionHandoff?,
    canResolveLauncher: (String) -> Boolean,
): CompletionHandoffCtaVisibility {
    if (handoff == null) return CompletionHandoffCtaVisibility(false, false)
    val showOpenApp = handoff.appPackage?.let(canResolveLauncher) ?: false
    return CompletionHandoffCtaVisibility(
        showOpenApp = showOpenApp,
        showOpenViewer = handoff.virtualDisplayAvailable,
    )
}

/**
 * Renders the two explicit completion handoff buttons under a finished VD row:
 * "Open <App>" launches the package on the real display, "View virtual screen"
 * opens the existing virtual display viewer. Each button is hidden when its
 * guard fails (per design_codex.md): an unresolvable / null package suppresses
 * Open, and `!virtualDisplayAvailable` suppresses the viewer button. Returns
 * nothing visible if neither guard passes.
 */
@Composable
internal fun CompletionHandoffCtaRow(
    handoff: CompletionHandoff,
    onOpenApp: (String) -> Unit,
    onOpenViewer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pkg = handoff.appPackage
    val launcherResolves = remember(pkg) {
        pkg != null && context.packageManager.getLaunchIntentForPackage(pkg) != null
    }
    val resolvablePkg: String? = if (launcherResolves) pkg else null
    val showOpenViewer = handoff.virtualDisplayAvailable
    if (resolvablePkg == null && !showOpenViewer) return

    val spacing = MaterialTheme.closePaw.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("qa-handoff-cta-row"),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (resolvablePkg != null) {
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
        if (showOpenViewer) {
            OutlinedButton(
                onClick = onOpenViewer,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("qa-handoff-open-viewer"),
            ) {
                Text(
                    text = "View virtual screen",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
