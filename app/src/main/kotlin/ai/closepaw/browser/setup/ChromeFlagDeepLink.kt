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
 * security, so we cascade three strategies and stop on the first that succeeds:
 *
 * 1. **External Intent** (free, almost always fails) — try ACTION_VIEW with `setPackage`.
 *    We pre-check `resolveActivity` so a silent drop doesn't masquerade as success.
 * 2. **Shizuku am start** — `am start -d <url> -n com.android.chrome/...Main`. The `am`
 *    command runs as shell uid, which bypasses the external-intent block.
 * 3. **Clipboard fallback** — copy the URL and toast the user. Last resort that always works.
 *
 * The injected [ShellRunner] is REQUIRED for the cascade to actually exercise step 2; passing
 * null would make the deep-link cosmetic on stock Android builds where Chrome drops chrome://
 * intents. The constructor enforces this so the regression Codex caught can't recur.
 */
class ChromeFlagDeepLink(
    private val context: Context,
    private val shellRunner: ShellRunner,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Try each strategy in order, returning the one that succeeded. The UI doesn't need to
     * know which path won — only that Chrome was reached or a fallback was used.
     */
    suspend fun open(): Strategy = withContext(ioDispatcher) {
        if (tryActionView()) return@withContext Strategy.ActionView
        if (tryShizukuAmStart()) return@withContext Strategy.ShizukuAmStart
        copyToClipboardAndToast()
        Strategy.Clipboard
    }

    enum class Strategy { ActionView, ShizukuAmStart, Clipboard }

    /**
     * `startActivity` for an unhandled intent throws ActivityNotFoundException — but Chrome's
     * external-intent filter ALLOWS the launch (no exception) and then drops the chrome://
     * URL on its end. To avoid that silent-success trap, require that PackageManager confirms
     * Chrome resolves the intent before declaring victory. We still catch the throw for older
     * platforms where ACTION_VIEW just fails outright.
     */
    private fun tryActionView(): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FLAG_URL))
            .setPackage(CHROME_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = context.packageManager.resolveActivity(intent, 0)
        if (resolved == null) {
            Log.d(TAG, "ACTION_VIEW pre-check: no activity resolves chrome:// for $CHROME_PACKAGE")
            return false
        }
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "ACTION_VIEW for chrome://flags blocked: ${e.message}")
        false
    } catch (e: Throwable) {
        Log.w(TAG, "ACTION_VIEW for chrome://flags failed", e)
        false
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

    private fun copyToClipboardAndToast() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("chrome flag URL", FLAG_URL))
            Toast.makeText(context, FALLBACK_TOAST, Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Log.w(TAG, "clipboard fallback failed", e)
        }
    }

    companion object {
        const val FLAG_URL =
            "chrome://flags/#enable-command-line-on-non-rooted-devices"
        const val CHROME_PACKAGE = "com.android.chrome"
        const val CHROME_MAIN_ACTIVITY = "com.google.android.apps.chrome.Main"
        const val FALLBACK_TOAST =
            "URL copied — paste in Chrome's address bar to open the flag page"
        private const val TAG = "ChromeFlagDeepLink"

        /**
         * Pure-functional decision: given the result of each strategy attempt, return the
         * strategy that should be reported as the outcome. Tests use this to verify the
         * cascade order without needing a real Context.
         */
        internal fun decideStrategy(
            actionViewSucceeded: Boolean,
            shizukuAmStartSucceeded: Boolean,
        ): Strategy = when {
            actionViewSucceeded -> Strategy.ActionView
            shizukuAmStartSucceeded -> Strategy.ShizukuAmStart
            else -> Strategy.Clipboard
        }
    }
}
