package com.moonkey.androidagent.debug

import android.content.Context
import android.content.Intent
import android.util.Log
import com.moonkey.androidagent.app.AgentService
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.platform.AccessibilityGestureInjector
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.NodeActionPerformer
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.action.UiChangeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Executes a single action directly and writes structured results to device storage.
 *
 * Composes [NodeActionPerformer] + [AccessibilityGestureInjector] from the live
 * [AgentService], bypassing SessionConfig and the full agent loop.
 * Screenshots are captured host-side via adb; this class handles
 * a11y tree capture, action execution, and change detection only.
 */
class DebugActionExecutor(private val service: AgentService) {

    private val nodePerformer = NodeActionPerformer(rootProvider = { service.rootInActiveWindow })
    private val gestureInjector = AccessibilityGestureInjector(service, visualizer = null)

    suspend fun execute(intent: Intent, context: Context) {
        val dir = prepareOutputDir(context)

        val actionName = intent.getStringExtra("action")
        if (actionName == null) {
            finish(dir, errorJson("Missing 'action' extra"))
            return
        }

        val settleMs = intent.getIntExtra("settle_ms", DEFAULT_SETTLE_MS).toLong()
        val captureTree = intent.getBooleanExtra("capture_tree", true)

        val uiAction = parseAction(actionName, intent)
        if (uiAction == null) {
            finish(dir, errorJson("Unknown or invalid action: $actionName"))
            return
        }

        // Pre-snapshot
        val preSnapshot = if (captureTree) captureSnapshot() else null
        if (preSnapshot != null) writeTree(dir, "pre_tree.json", preSnapshot)

        // Execute
        val startMs = System.currentTimeMillis()
        val result = performAction(uiAction)
        val elapsedMs = System.currentTimeMillis() - startMs

        // Settle
        delay(settleMs)

        // Post-snapshot
        val postSnapshot = if (captureTree) captureSnapshot() else null
        if (postSnapshot != null) writeTree(dir, "post_tree.json", postSnapshot)

        // Compare
        val verdict = if (captureTree) {
            UiChangeDetector.compare(preSnapshot, postSnapshot)
        } else {
            UiChangeDetector.ChangeResult.Unverifiable
        }

        // Result
        val json = buildResultJson(
            actionName, intent, uiAction, result,
            verdict, elapsedMs, settleMs,
            preSnapshot, postSnapshot
        )
        finish(dir, json)

        Log.i(TAG, "action=$actionName status=${result.statusName()} verdict=$verdict elapsed=${elapsedMs}ms")
    }

    // -- Action dispatch --

    private suspend fun performAction(action: UIAction): ActionResult {
        return when (action) {
            is UIAction.ClickNodeAt ->
                nodePerformer.performNodeClickAt(action.x, action.y)
            is UIAction.TapAt ->
                gestureInjector.injectTap(action.x, action.y)
            is UIAction.LongClickNodeAt ->
                nodePerformer.performNodeLongClickAt(action.x, action.y)
            is UIAction.LongPressAt ->
                gestureInjector.injectLongPress(
                    action.x.toFloat(), action.y.toFloat(), action.durationMs
                )
            is UIAction.ScrollNodeAt ->
                nodePerformer.performScrollAt(action.x, action.y, action.direction)
            is UIAction.Swipe ->
                gestureInjector.injectSwipe(
                    action.startX.toFloat(), action.startY.toFloat(),
                    action.endX.toFloat(), action.endY.toFloat(),
                    action.durationMs
                )
            is UIAction.SystemButton ->
                gestureInjector.injectSystemButton(action.button)
            is UIAction.SetTextOnNodeAt ->
                nodePerformer.performSetTextOnNodeAt(
                    action.x, action.y, action.text, action.clear
                )
            is UIAction.SetTextOnFocused ->
                nodePerformer.performSetTextOnFocused(action.text, action.clear)
            is UIAction.Wait -> {
                delay(action.durationMs); ActionResult.Success()
            }
        }
    }

    // -- Intent parsing --

    private fun parseAction(name: String, intent: Intent): UIAction? {
        return when (name) {
            "click" -> {
                val x = intent.getIntExtra("x", -1)
                val y = intent.getIntExtra("y", -1)
                if (x < 0 || y < 0) return null
                if (intent.getBooleanExtra("use_node", true))
                    UIAction.ClickNodeAt(x, y)
                else
                    UIAction.TapAt(x, y)
            }
            "tap" -> {
                val x = intent.getIntExtra("x", -1)
                val y = intent.getIntExtra("y", -1)
                if (x < 0 || y < 0) return null
                UIAction.TapAt(x, y)
            }
            "long_press" -> {
                val x = intent.getIntExtra("x", -1)
                val y = intent.getIntExtra("y", -1)
                if (x < 0 || y < 0) return null
                UIAction.LongPressAt(x, y, intent.getIntExtra("duration_ms", 1000).toLong())
            }
            "scroll" -> {
                val direction = intent.getStringExtra("direction") ?: return null
                if (direction !in SCROLL_DIRECTIONS) return null
                val dm = service.resources.displayMetrics
                val x = intent.getIntExtra("x", dm.widthPixels / 2)
                val y = intent.getIntExtra("y", dm.heightPixels / 2)
                UIAction.ScrollNodeAt(x, y, direction)
            }
            "swipe" -> {
                val sx = intent.getIntExtra("start_x", -1)
                val sy = intent.getIntExtra("start_y", -1)
                val ex = intent.getIntExtra("end_x", -1)
                val ey = intent.getIntExtra("end_y", -1)
                if (sx < 0 || sy < 0 || ex < 0 || ey < 0) return null
                UIAction.Swipe(sx, sy, ex, ey, intent.getIntExtra("duration_ms", 400).toLong())
            }
            else -> null
        }
    }

    // -- Snapshot --

    private suspend fun captureSnapshot(): ScreenSnapshot? {
        return try {
            withContext(Dispatchers.Main) {
                val root = service.rootInActiveWindow
                val dm = service.resources.displayMetrics
                Perceptor.snapshot(root, dm.widthPixels, dm.heightPixels)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture snapshot", e)
            null
        }
    }

    // -- Result JSON --

    private fun buildResultJson(
        actionName: String,
        intent: Intent,
        uiAction: UIAction,
        result: ActionResult,
        verdict: UiChangeDetector.ChangeResult,
        elapsedMs: Long,
        settleMs: Long,
        pre: ScreenSnapshot?,
        post: ScreenSnapshot?
    ): JSONObject {
        return JSONObject().apply {
            put("version", 1)
            put("action", actionName)
            put("layer", "platform")
            put("params", paramsJson(uiAction, intent))
            put("action_accepted", JSONObject().apply {
                put("status", result.statusName())
                put("message", result.message())
            })
            put("ui_changed", JSONObject().apply {
                put("verdict", verdict.name.lowercase())
                put("element_count_before", pre?.elements?.size ?: -1)
                put("element_count_after", post?.elements?.size ?: -1)
            })
            put("elapsed_ms", elapsedMs)
            put("settle_ms", settleMs)
            put("timestamp", isoTimestamp())
            put("device", android.os.Build.MODEL)
            put("files", JSONObject().apply {
                if (pre != null) put("pre_tree", "pre_tree.json")
                if (post != null) put("post_tree", "post_tree.json")
            })
        }
    }

    private fun paramsJson(action: UIAction, intent: Intent): JSONObject {
        return JSONObject().apply {
            when (action) {
                is UIAction.ClickNodeAt -> {
                    put("x", action.x); put("y", action.y); put("use_node", true)
                }
                is UIAction.TapAt -> {
                    put("x", action.x); put("y", action.y)
                }
                is UIAction.LongPressAt -> {
                    put("x", action.x); put("y", action.y)
                    put("duration_ms", action.durationMs)
                }
                is UIAction.ScrollNodeAt -> {
                    put("x", action.x); put("y", action.y)
                    put("direction", action.direction)
                }
                is UIAction.Swipe -> {
                    put("start_x", action.startX); put("start_y", action.startY)
                    put("end_x", action.endX); put("end_y", action.endY)
                    put("duration_ms", action.durationMs)
                }
                else -> {}
            }
        }
    }

    // -- File I/O --

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
        private const val TAG = "DebugActionExecutor"
        private const val DEFAULT_SETTLE_MS = 350
        private const val OUTPUT_DIR = "action-debug/latest"
        private val SCROLL_DIRECTIONS = setOf("up", "down", "left", "right")

        fun writeErrorResult(context: Context, error: String) {
            try {
                val dir = File(context.applicationContext.getExternalFilesDir(null), OUTPUT_DIR)
                if (dir.exists()) dir.deleteRecursively()
                dir.mkdirs()
                File(dir, "result.json").writeText(errorJson(error).toString(2))
                File(dir, ".done").createNewFile()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write error result", e)
            }
        }

        private fun errorJson(error: String): JSONObject {
            return JSONObject().apply {
                put("version", 1)
                put("status", "error")
                put("error", error)
                put("timestamp", isoTimestamp())
            }
        }

        private fun isoTimestamp(): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(Date())
        }
    }
}

private fun ActionResult.statusName(): String = when (this) {
    is ActionResult.Success -> "success"
    is ActionResult.Failure -> "failure"
    is ActionResult.Cancelled -> "cancelled"
}

private fun ActionResult.message(): String = when (this) {
    is ActionResult.Success -> message
    is ActionResult.Failure -> reason
    is ActionResult.Cancelled -> reason
}
