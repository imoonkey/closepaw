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
 * 2. **Shizuku am start** — `am start -d <url> -n com.android.chrome/...Main`. The `am`
 *    command runs as shell uid, which bypasses the external-intent block.
 * 3. **Clipboard fallback** — copy the URL and toast the user. Last resort that always works.
 *
 * The actual launch is delegated; this class only decides which step to take. The decision
 * function ([decideStrategy]) is pure-functional for unit testing — it inspects an injected
 * environment and returns the strategy to attempt next.
 */
class ChromeFlagDeepLink(
    private val context: Context,
    private val shellRunner: CommandLineWriter.ShellRunner? = null,
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

    private fun tryActionView(): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FLAG_URL))
            .setPackage(CHROME_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private suspend fun tryShizukuAmStart(): Boolean {
        val runner = shellRunner ?: return false
        return runCatching {
            val result = runner.run(arrayOf("am", "start", "-a", "android.intent.action.VIEW",
                "-d", FLAG_URL, "-n", "$CHROME_PACKAGE/$CHROME_MAIN_ACTIVITY"))
            result.exitCode == 0
        }.getOrElse {
            Log.w(TAG, "Shizuku am start failed", it)
            false
        }
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
