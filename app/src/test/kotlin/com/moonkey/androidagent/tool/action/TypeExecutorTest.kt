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
import org.junit.Test

class TypeExecutorTest {

    private val editBounds = Bounds(left = 50, top = 100, right = 500, bottom = 160)

    @Test
    fun `direct-set cancellation maps to Cancelled`() = runTest {
        val snapshot = editableSnapshot()
        val platform = TypeRecordingPlatform(
            actionResults = listOf(ActionResult.Cancelled("direct-set interrupted"))
        )

        val outcome = TypeExecutor().execute(
            target = Target.ElementIndex(1),
            inputText = "hello",
            clear = false,
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Cancelled::class.java)
    }

    @Test
    fun `tap-to-focus cancellation maps to Cancelled`() = runTest {
        val snapshot = editableSnapshot()
        // First: direct-set fails. Second: tap cancelled.
        val platform = TypeRecordingPlatform(
            actionResults = listOf(
                ActionResult.Failure("no node"),
                ActionResult.Cancelled("tap interrupted")
            )
        )

        val outcome = TypeExecutor().execute(
            target = Target.ElementIndex(1),
            inputText = "hello",
            clear = false,
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Cancelled::class.java)
    }

    @Test
    fun `focused-set after tap cancellation maps to Cancelled`() = runTest {
        val snapshot = editableSnapshot()
        // First: direct-set fails. Second: tap succeeds. Third: focused-set cancelled.
        val platform = TypeRecordingPlatform(
            actionResults = listOf(
                ActionResult.Failure("no node"),
                ActionResult.Success(),
                ActionResult.Cancelled("focused-set interrupted")
            )
        )

        val outcome = TypeExecutor().execute(
            target = Target.ElementIndex(1),
            inputText = "hello",
            clear = false,
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Cancelled::class.java)
    }

    @Test
    fun `typeOnFocused cancellation maps to Cancelled`() = runTest {
        val snapshot = editableSnapshot()
        val platform = TypeRecordingPlatform(
            actionResults = listOf(ActionResult.Cancelled("focused interrupted"))
        )

        val outcome = TypeExecutor().execute(
            target = null,
            inputText = "hello",
            clear = false,
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Cancelled::class.java)
    }

    private fun editableSnapshot(): ScreenSnapshot {
        return ScreenSnapshot(
            timestamp = 1L,
            elements = listOf(
                PerceptionElement(
                    index = 1, text = "", resourceId = "input",
                    className = "android.widget.EditText", description = "",
                    isClickable = true, isEditable = true, isScrollable = false,
                    isEnabled = true, isFocused = false, isLongClickable = false,
                    bounds = editBounds, center = Point(editBounds.centerX, editBounds.centerY)
                )
            )
        )
    }
}

private class TypeRecordingPlatform(
    private val actionResults: List<ActionResult>,
    private val capturedSnapshots: List<ScreenSnapshot> = emptyList()
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
