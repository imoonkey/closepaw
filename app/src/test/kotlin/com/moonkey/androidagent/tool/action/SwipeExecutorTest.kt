package com.moonkey.androidagent.tool.action

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.AppInfo
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class SwipeExecutorTest {

    @Test
    fun `platform cancellation maps to ActionOutcome Cancelled`() = runTest {
        val snapshot = simpleSnapshot()
        val platform = SwipeRecordingPlatform(
            actionResults = listOf(ActionResult.Cancelled("gesture interrupted")),
            capturedSnapshots = listOf(snapshot)
        )
        val params = swipeParams(0, 100, 0, 500)

        val outcome = SwipeExecutor().execute(
            params = params,
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Cancelled::class.java)
        val cancelled = outcome as ActionOutcome.Cancelled
        assertThat(cancelled.reason).contains("cancelled")
    }

    @Test
    fun `swipe success returns Success with observation`() = runTest {
        val snapshot = simpleSnapshot()
        val changedSnapshot = simpleSnapshot(label = "Changed")
        val platform = SwipeRecordingPlatform(
            actionResults = listOf(ActionResult.Success()),
            capturedSnapshots = listOf(snapshot, changedSnapshot)
        )
        val params = swipeParams(100, 200, 300, 400)

        val outcome = SwipeExecutor().execute(
            params = params,
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
    }

    @Test
    fun `swipe failure returns Failed`() = runTest {
        val snapshot = simpleSnapshot()
        val platform = SwipeRecordingPlatform(
            actionResults = listOf(ActionResult.Failure("dispatch error")),
            capturedSnapshots = listOf(snapshot)
        )
        val params = swipeParams(0, 0, 100, 100)

        val outcome = SwipeExecutor().execute(
            params = params,
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed::class.java)
        assertThat((outcome as ActionOutcome.Failed).reason).contains("dispatch error")
    }

    private fun swipeParams(sx: Int, sy: Int, ex: Int, ey: Int): JSONObject {
        return JSONObject().apply {
            put("start", JSONArray().apply { put(sx); put(sy) })
            put("end", JSONArray().apply { put(ex); put(ey) })
        }
    }

    private fun simpleSnapshot(label: String = "Item"): ScreenSnapshot {
        val bounds = Bounds(left = 0, top = 0, right = 1080, bottom = 2400)
        return ScreenSnapshot(
            timestamp = 1L,
            elements = listOf(
                PerceptionElement(
                    index = 1, text = label, resourceId = "item",
                    className = "android.widget.TextView", description = "",
                    isClickable = false, isEditable = false, isScrollable = false,
                    isEnabled = true, isFocused = false, isLongClickable = false,
                    bounds = bounds, center = Point(bounds.centerX, bounds.centerY)
                )
            )
        )
    }
}

private class SwipeRecordingPlatform(
    private val actionResults: List<ActionResult>,
    private val capturedSnapshots: List<ScreenSnapshot>
) : AndroidPlatform {
    val performedActions = mutableListOf<UIAction>()
    private var actionIndex = 0
    private var captureIndex = 0

    override suspend fun captureScreen(): ScreenSnapshot {
        if (capturedSnapshots.isEmpty()) error("No snapshots configured")
        val snapshot = capturedSnapshots.getOrNull(captureIndex) ?: capturedSnapshots.last()
        captureIndex += 1
        return snapshot
    }

    override suspend fun performAction(action: UIAction): ActionResult {
        performedActions += action
        val result = actionResults.getOrNull(actionIndex) ?: actionResults.lastOrNull()
        actionIndex += 1
        return result ?: ActionResult.Failure("No action results configured")
    }

    override fun hasRequiredPermissions(): Boolean = true
    override fun getCurrentPackageName(): String? = "com.example"
    override fun getDisplayInfo(): DisplayInfo =
        DisplayInfo(widthPixels = 1080, heightPixels = 2400, density = 3f)
    override suspend fun getInstalledApps(): List<AppInfo> = emptyList()
    override suspend fun launchApp(packageName: String): ActionResult = ActionResult.Success()
    override fun allowTapToFocus(): Boolean = true
}
