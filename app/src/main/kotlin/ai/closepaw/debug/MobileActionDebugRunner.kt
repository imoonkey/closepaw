package ai.closepaw.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.closepaw.app.AgentService
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.perception.Perceptor
import ai.closepaw.platform.AccessibilityPlatform
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.impl.MobileActionTool
import ai.closepaw.trace.NoopTraceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Debug receiver for direct MobileActionTool invocation with raw JSON args.
 *
 * Intent: `ai.closepaw.ACTION_DEBUG_MOBILE_ACTION`
 * Extras:
 *   --es args '<JSON>'  // mobile_action params object
 *
 * Writes result to /sdcard/Android/data/ai.closepaw/files/mobile-action-debug/latest/.
 * Registered dynamically in [AgentService.onServiceConnected], gated by BuildConfig.DEBUG.
 *
 * Used by `scripts/mobile-action-test.sh` to QA targeting-normalization scenarios on device.
 */
class MobileActionDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val service = AgentService.instance
        if (service == null) {
            Log.e(TAG, "Accessibility service not running")
            writeErrorResult(context, "Accessibility service not running")
            return
        }

        if (service.getActiveSession() != null) {
            Log.e(TAG, "Agent session active — rejecting debug exec")
            writeErrorResult(context, "Agent session active — stop agent first")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    MobileActionDebugRunner(service).run(intent, context)
                } ?: run {
                    Log.e(TAG, "Debug mobile_action timed out after ${TIMEOUT_MS}ms")
                    writeErrorResult(context, "Timed out after ${TIMEOUT_MS}ms")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Debug mobile_action failed", e)
                writeErrorResult(context, "Exception: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MobileActionDebugRx"
        private const val TIMEOUT_MS = 15_000L
        const val ACTION = "ai.closepaw.ACTION_DEBUG_MOBILE_ACTION"
    }
}

/**
 * Builds platform + snapshot from a live [AgentService], validates a raw mobile_action
 * params object, runs the tool through its normal validate→createInvocation→execute path,
 * and persists the result for the test harness.
 */
private class MobileActionDebugRunner(private val service: AgentService) {

    suspend fun run(intent: Intent, context: Context) {
        val dir = prepareOutputDir(context)

        val argsJson = intent.getStringExtra("args")
        if (argsJson.isNullOrBlank()) {
            finish(dir, errorJson("Missing 'args' extra (JSON mobile_action params)"))
            return
        }

        val params = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            finish(dir, errorJson("Invalid JSON in 'args': ${e.message}"))
            return
        }

        val tool = MobileActionTool()

        val validation = tool.validate(params)
        if (validation is ValidationResult.Invalid) {
            finish(
                dir,
                resultJson(
                    params = params,
                    phase = "validation",
                    status = "failure",
                    message = validation.errors.joinToString("; "),
                    elapsedMs = 0
                )
            )
            return
        }

        // Snapshot first so we can record element count + serve as currentSnapshot to executors.
        val preSnapshot = captureSnapshot()
        if (preSnapshot != null) writeTree(dir, "pre_tree.json", preSnapshot)

        val appClassifier = try {
            AppClassifier.fromAssets(service.assets)
        } catch (e: Exception) {
            Log.w(TAG, "AppClassifier load failed; using null", e)
            null
        }

        // Minimal config — defaults are fine for direct dispatch.
        val sessionConfig = SessionConfig()

        val platform: AndroidPlatform = AccessibilityPlatform(
            service = service,
            config = sessionConfig,
            visualizer = service.getActionVisualizer(),
            traceRecorder = NoopTraceRecorder,
            overlayTouchGate = service.getOverlayTouchGate(),
            isPackageBlocked = { false }
        )

        val ctx = object : ToolExecutionContext {
            override val platform: AndroidPlatform = platform
            override val currentSnapshot: ScreenSnapshot? = preSnapshot
            override val appClassifier: AppClassifier? = appClassifier
            override fun isCancelled(): Boolean = false
        }

        val invocation = tool.createInvocation(params)

        val startMs = System.currentTimeMillis()
        val toolResult = invocation.execute(ctx)
        val elapsedMs = System.currentTimeMillis() - startMs

        val postSnapshot = captureSnapshot()
        if (postSnapshot != null) writeTree(dir, "post_tree.json", postSnapshot)

        val (status, message) = when (toolResult) {
            is ToolExecutionResult.Success -> "success" to toolResult.output
            is ToolExecutionResult.Failure -> "failure" to toolResult.error
            is ToolExecutionResult.Cancelled -> "cancelled" to toolResult.reason
        }

        finish(
            dir,
            resultJson(
                params = params,
                phase = "execute",
                status = status,
                message = message,
                elapsedMs = elapsedMs,
                preElementCount = preSnapshot?.elements?.size ?: -1,
                postElementCount = postSnapshot?.elements?.size ?: -1
            )
        )

        Log.i(TAG, "mobile_action status=$status elapsed=${elapsedMs}ms msg=${message.take(200)}")
    }

    private suspend fun captureSnapshot(): ScreenSnapshot? {
        return try {
            withContext(Dispatchers.Main) {
                val root = service.rootInActiveWindow
                val dm = service.resources.displayMetrics
                Perceptor.snapshot(root, dm.widthPixels, dm.heightPixels)
            }
        } catch (e: Exception) {
            Log.w(TAG, "snapshot failed", e)
            null
        }
    }

    private fun resultJson(
        params: JSONObject,
        phase: String,
        status: String,
        message: String,
        elapsedMs: Long,
        preElementCount: Int = -1,
        postElementCount: Int = -1
    ): JSONObject {
        return JSONObject().apply {
            put("version", 1)
            put("tool", "mobile_action")
            put("phase", phase)
            put("params", params)
            put("result", JSONObject().apply {
                put("status", status)
                put("message", message)
            })
            put("elements_before", preElementCount)
            put("elements_after", postElementCount)
            put("elapsed_ms", elapsedMs)
            put("timestamp", isoTimestamp())
            put("device", android.os.Build.MODEL)
        }
    }

    private fun prepareOutputDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), OUTPUT_DIR)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun writeTree(dir: File, filename: String, snapshot: ScreenSnapshot) {
        try {
            File(dir, filename).writeText(Perceptor.toPromptJson(snapshot))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write $filename", e)
        }
    }

    private fun finish(dir: File, json: JSONObject) {
        try {
            File(dir, "result.json").writeText(json.toString(2))
            File(dir, ".done").createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write result", e)
        }
    }

    companion object {
        private const val TAG = "MobileActionDebugRun"
        private const val OUTPUT_DIR = "mobile-action-debug/latest"
    }
}

private fun writeErrorResult(context: Context, error: String) {
    try {
        val dir = File(context.applicationContext.getExternalFilesDir(null), "mobile-action-debug/latest")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "result.json").writeText(errorJson(error).toString(2))
        File(dir, ".done").createNewFile()
    } catch (e: Exception) {
        Log.e("MobileActionDebugRx", "Failed to write error result", e)
    }
}

private fun errorJson(error: String): JSONObject {
    return JSONObject().apply {
        put("version", 1)
        put("tool", "mobile_action")
        put("phase", "init")
        put("result", JSONObject().apply {
            put("status", "error")
            put("message", error)
        })
        put("timestamp", isoTimestamp())
    }
}

private fun isoTimestamp(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date())
}
