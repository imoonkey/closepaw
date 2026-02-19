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

class ClickExecutorTest {

    @Test
    fun `execute retries jitter taps after unchanged attempts`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val platform =
            RecordingPlatform(
                actionResults = List(6) { ActionResult.Success() },
                capturedSnapshots = List(6) { snapshot }
            )
        val executor = ClickExecutor()

        val outcome =
            executor.execute(
                target = Target.Coordinate(x = 100, y = 200),
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed::class.java)
        assertThat(platform.performedActions).hasSize(6)
        assertThat(platform.performedActions[0]).isEqualTo(UIAction.ClickNodeAt(100, 200))
        assertThat(platform.performedActions[1]).isEqualTo(UIAction.TapAt(100, 200))
        assertThat(platform.performedActions).contains(UIAction.TapAt(88, 200))
        assertThat(platform.performedActions).contains(UIAction.TapAt(112, 200))
        val failed = outcome as ActionOutcome.Failed
        assertThat(failed.attemptTrail.joinToString("\n")).contains("jitter: scheduling")
    }

    @Test
    fun `execute returns cancelled when platform cancels action`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Cancelled("platform cancelled")),
                capturedSnapshots = emptyList()
            )
        val executor = ClickExecutor()

        val outcome =
            executor.execute(
                target = Target.Coordinate(x = 50, y = 50),
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isEqualTo(ActionOutcome.Cancelled("platform cancelled"))
    }

    @Test
    fun `execute does not report success when capture fails`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val platform =
            RecordingPlatform(
                actionResults = List(6) { ActionResult.Success() },
                capturedSnapshots = emptyList()
            )
        val executor = ClickExecutor()

        val outcome =
            executor.execute(
                target = Target.Coordinate(x = 100, y = 200),
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed::class.java)
        val failed = outcome as ActionOutcome.Failed
        assertThat(failed.attemptTrail.joinToString("\n")).contains("capture failed")
    }

    @Test
    fun `execute fails fast for out of bounds coordinate target`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Success()),
                capturedSnapshots = listOf(snapshot)
            )
        val executor = ClickExecutor()

        val outcome =
            executor.execute(
                target = Target.Coordinate(x = -1, y = 2401),
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed::class.java)
        assertThat(platform.performedActions).isEmpty()
    }

    private fun snapshotWithSingleButton(): ScreenSnapshot {
        val bounds = Bounds(left = 80, top = 160, right = 220, bottom = 260)
        return ScreenSnapshot(
            timestamp = 1L,
            elements =
                listOf(
                    PerceptionElement(
                        index = 1,
                        text = "Button",
                        resourceId = "button_1",
                        className = "android.widget.Button",
                        description = "",
                        isClickable = true,
                        isEditable = false,
                        isScrollable = false,
                        isEnabled = true,
                        isFocused = false,
                        isLongClickable = false,
                        bounds = bounds,
                        center = Point(bounds.centerX, bounds.centerY)
                    )
                )
        )
    }
}

private class RecordingPlatform(
    private val actionResults: List<ActionResult>,
    private val capturedSnapshots: List<ScreenSnapshot>
) : AndroidPlatform {
    val performedActions = mutableListOf<UIAction>()
    private var actionIndex = 0
    private var captureIndex = 0

    override suspend fun captureScreen(): ScreenSnapshot {
        if (capturedSnapshots.isEmpty()) {
            error("No snapshots configured")
        }
        val snapshot = capturedSnapshots.getOrNull(captureIndex) ?: capturedSnapshots.last()
        captureIndex += 1
        return snapshot
    }

    override suspend fun performAction(action: UIAction): ActionResult {
        performedActions.add(action)
        val result = actionResults.getOrNull(actionIndex) ?: actionResults.lastOrNull()
        actionIndex += 1
        return result ?: ActionResult.Failure("No action results configured")
    }

    override fun hasRequiredPermissions(): Boolean = true

    override fun getCurrentPackageName(): String? = "com.example"

    override fun getDisplayInfo(): DisplayInfo = DisplayInfo(widthPixels = 1080, heightPixels = 2400, density = 3f)

    override suspend fun getInstalledApps(): List<AppInfo> = emptyList()

    override suspend fun launchApp(packageName: String): ActionResult = ActionResult.Success()
}
