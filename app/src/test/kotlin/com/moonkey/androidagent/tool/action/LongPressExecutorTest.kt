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
import com.moonkey.androidagent.platform.SemanticTargetHint
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LongPressExecutorTest {

    private val buttonBounds = Bounds(left = 80, top = 160, right = 220, bottom = 260)
    private val buttonHint = SemanticTargetHint(
        resourceId = "button_1",
        text = "Button",
        description = "",
        className = "android.widget.Button",
        bounds = buttonBounds
    )

    @Test
    fun `execute uses gesture long press for coordinate target`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val changedSnapshot = snapshotWithSingleButton(label = "Selected")
        val platform =
            RecordingLongPressPlatform(
                actionResults = listOf(ActionResult.Success()),
                capturedSnapshots = listOf(changedSnapshot)
            )
        val executor = LongPressExecutor()

        val outcome =
            executor.execute(
                target = Target.Coordinate(x = 200, y = 300),
                durationMs = 900L,
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        assertThat(platform.performedActions).containsExactly(
            UIAction.LongPressAt(
                x = 200,
                y = 300,
                durationMs = 900L
            )
        )
    }

    @Test
    fun `execute uses node long click as primary for semantic target`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val changedSnapshot = snapshotWithSingleButton(label = "Selected")
        val platform =
            RecordingLongPressPlatform(
                actionResults = listOf(ActionResult.Success()),
                capturedSnapshots = listOf(changedSnapshot)
            )
        val executor = LongPressExecutor()

        val outcome =
            executor.execute(
                target = Target.ElementIndex(index = 1),
                durationMs = 900L,
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        assertThat(platform.performedActions).containsExactly(
            UIAction.LongClickNodeAt(150, 210, buttonHint)
        )
    }

    @Test
    fun `execute falls back to gesture long press when node long click fails`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val changedSnapshot = snapshotWithSingleButton(label = "Selected")
        val platform =
            RecordingLongPressPlatform(
                actionResults = listOf(
                    ActionResult.Failure("gesture long press failed"),
                    ActionResult.Success()
                ),
                capturedSnapshots = listOf(changedSnapshot)
            )
        val executor = LongPressExecutor()

        val outcome =
            executor.execute(
                target = Target.ElementIndex(index = 1),
                durationMs = 900L,
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        assertThat(platform.performedActions).containsExactly(
            UIAction.LongClickNodeAt(150, 210, buttonHint),
            UIAction.LongPressAt(x = 150, y = 210, durationMs = 900L)
        ).inOrder()
    }

    @Test
    fun `execute marks long press verified when post-action screen changes`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val changedSnapshot = snapshotWithSingleButton(label = "Selected")
        val platform =
            RecordingLongPressPlatform(
                actionResults = listOf(ActionResult.Success()),
                capturedSnapshots = listOf(changedSnapshot)
            )
        val executor = LongPressExecutor()

        val outcome =
            executor.execute(
                target = Target.Coordinate(x = 200, y = 300),
                durationMs = 900L,
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        val success = outcome as ActionOutcome.Success
        assertThat(success.verified).isTrue()
        assertThat(success.message).doesNotContain("unchanged")
    }

    private fun snapshotWithSingleButton(label: String = "Button"): ScreenSnapshot {
        val bounds = Bounds(left = 80, top = 160, right = 220, bottom = 260)
        return ScreenSnapshot(
            timestamp = 1L,
            elements =
                listOf(
                    PerceptionElement(
                        index = 1,
                        text = label,
                        resourceId = "button_1",
                        className = "android.widget.Button",
                        description = "",
                        isClickable = true,
                        isEditable = false,
                        isScrollable = false,
                        isEnabled = true,
                        isFocused = false,
                        isLongClickable = true,
                        bounds = bounds,
                        center = Point(bounds.centerX, bounds.centerY)
                    )
                )
        )
    }
}

private class RecordingLongPressPlatform(
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

    override fun getDisplayInfo(): DisplayInfo =
        DisplayInfo(widthPixels = 1080, heightPixels = 2400, density = 3f)

    override suspend fun getInstalledApps(): List<AppInfo> = emptyList()

    override suspend fun launchApp(packageName: String): ActionResult = ActionResult.Success()
}
