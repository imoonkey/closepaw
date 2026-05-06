package ai.closepaw.browser.setup

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens Chrome on the `chrome://flags#enable-command-line-on-non-rooted-devices` page so the
 * user can flip the unlock toggle. Chrome blocks `chrome://` URLs from external intents for
 * security, so the cascade is:
 *
 * 1. **External intent + clipboard hint** — when Chrome resolves the chrome:// intent, fire
 *    [Intent.ACTION_VIEW] AND copy the URL to the clipboard with a hint toast. Lossless: even
 *    when Chrome silently drops the URL on its end (a real, observed behaviour), the user has
 *    a one-paste manual recovery. The previous "return success right after `startActivity`"
 *    design caused a silent-success loop where the user kept tapping the CTA.
 * 2. **Shizuku am start** — `am start -d <url> -n com.android.chrome/...Main`. Reserved for
 *    when ACTION_VIEW genuinely cannot dispatch (no Chrome handler resolves, or it threw).
 *    The `am` command runs as shell uid, which bypasses the external-intent block, so Chrome
 *    treats the URL as same-origin and renders it.
 * 3. **Clipboard-only fallback** — copy the URL with a generic toast. Last resort for
 *    locked-down devices where neither path works.
 *
 * The injected [ShellRunner] is REQUIRED so step 2 actually runs in production. Passing null
 * would make the cascade two-step and re-open the silent-success class of bug.
 */
class ChromeFlagDeepLink(
    private val context: Context,
    private val shellRunner: ShellRunner,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Run the cascade and report the strategy that was attempted as the primary action. The
     * clipboard hint is ADDITIVE on the ActionView path — the strategy still reports
     * [Strategy.ActionView] when both fired, because that's the user-visible launch attempt.
     */
    suspend fun open(): Strategy = withContext(ioDispatcher) {
        when (tryActionView()) {
            ActionViewOutcome.Launched -> {
                // Additive fallback: even though we just launched the intent, Chrome can drop
                // the chrome:// URL after handing the user to its homepage. Copy + hint so the
                // user has a one-paste recovery without re-tapping the CTA.
                copyUrlToClipboard()
                showToast(LAUNCH_HINT_TOAST)
                Strategy.ActionView
            }
            ActionViewOutcome.NoHandler, ActionViewOutcome.Threw -> {
                if (tryShizukuAmStart()) {
                    Strategy.ShizukuAmStart
                } else {
                    copyUrlToClipboard()
                    showToast(FALLBACK_TOAST)
                    Strategy.Clipboard
                }
            }
        }
    }

    enum class Strategy { ActionView, ShizukuAmStart, Clipboard }

    /**
     * Three-state outcome to drive the cascade. We can't tell whether Chrome internally
     * accepted or dropped the URL after launch — the OS reports success either way — so when
     * the launch dispatched at all we treat it as [Launched] AND let the clipboard hint cover
     * the "Chrome dropped it" case.
     */
    private enum class ActionViewOutcome { Launched, NoHandler, Threw }

    private fun tryActionView(): ActionViewOutcome = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FLAG_URL))
            .setPackage(CHROME_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = context.packageManager.resolveActivity(intent, 0)
        if (resolved == null) {
            Log.d(TAG, "ACTION_VIEW pre-check: no activity resolves chrome:// for $CHROME_PACKAGE")
            return ActionViewOutcome.NoHandler
        }
        context.startActivity(intent)
        ActionViewOutcome.Launched
    } catch (_: ActivityNotFoundException) {
        ActionViewOutcome.NoHandler
    } catch (e: SecurityException) {
        Log.w(TAG, "ACTION_VIEW for chrome://flags blocked: ${e.message}")
        ActionViewOutcome.Threw
    } catch (e: Throwable) {
        Log.w(TAG, "ACTION_VIEW for chrome://flags failed", e)
        ActionViewOutcome.Threw
    }

    private suspend fun tryShizukuAmStart(): Boolean = runCatching {
        val result = shellRunner.run(arrayOf(
            "am", "start",
            "-a", "android.intent.action.VIEW",
            "-d", FLAG_URL,
            "-n", "$CHROME_PACKAGE/$CHROME_MAIN_ACTIVITY",
        ))
        // `am start` exits 0 on dispatch even when the activity later refuses, but a
        // shell-uid dispatch is what bypasses the external-intent block — at this point
        // Chrome's internal nav handler treats the URL as same-origin and renders it. Exit
        // non-zero genuinely means the dispatch itself failed (Shizuku unavailable, am not
        // found, etc.) which is what we care about for the cascade decision.
        result.exitCode == 0
    }.getOrElse {
        Log.w(TAG, "Shizuku am start failed", it)
        false
    }

    private fun copyUrlToClipboard() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("chrome flag URL", FLAG_URL))
        } catch (e: Throwable) {
            Log.w(TAG, "clipboard copy failed", e)
        }
    }

    private fun showToast(message: String) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Log.w(TAG, "toast failed", e)
        }
    }

    companion object {
        const val FLAG_URL =
            "chrome://flags/#enable-command-line-on-non-rooted-devices"
        const val CHROME_PACKAGE = "com.android.chrome"
        const val CHROME_MAIN_ACTIVITY = "com.google.android.apps.chrome.Main"
        const val LAUNCH_HINT_TOAST =
            "If Chrome didn't open chrome://flags, paste the URL from your clipboard into the address bar."
        const val FALLBACK_TOAST =
            "URL copied — paste in Chrome's address bar to open the flag page"
        private const val TAG = "ChromeFlagDeepLink"

        /**
         * Pure-functional decision: given the result of each cascade attempt, return the
         * strategy that should be reported as the outcome. Tests use this to verify the
         * cascade order without needing a real Context.
         */
        internal fun decideStrategy(
            actionViewLaunched: Boolean,
            shizukuAmStartSucceeded: Boolean,
        ): Strategy = when {
            actionViewLaunched -> Strategy.ActionView
            shizukuAmStartSucceeded -> Strategy.ShizukuAmStart
            else -> Strategy.Clipboard
        }
    }
}
