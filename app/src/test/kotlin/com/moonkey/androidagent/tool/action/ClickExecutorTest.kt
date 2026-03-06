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

class ClickExecutorTest {

    private val buttonBounds = Bounds(left = 80, top = 160, right = 220, bottom = 260)
    private val buttonHint = SemanticTargetHint(
        resourceId = "button_1",
        text = "Button",
        description = "",
        className = "android.widget.Button",
        bounds = buttonBounds
    )

    @Test
    fun `execute dispatches a single gesture tap on success`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val changedSnapshot = snapshotWithSingleButton(label = "Tapped")
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Success()),
                capturedSnapshots = listOf(changedSnapshot)
            )
        val executor = ClickExecutor()

        val outcome =
            executor.execute(
                target = Target.Coordinate(x = 100, y = 200),
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        assertThat(platform.performedActions).containsExactly(UIAction.TapAt(100, 200))
    }

    @Test
    fun `execute uses node click as primary for semantic target`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val changedSnapshot = snapshotWithSingleButton(label = "Pressed")
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Success()),
                capturedSnapshots = listOf(changedSnapshot)
            )
        val executor = ClickExecutor()

        val outcome =
            executor.execute(
                target = Target.ElementIndex(index = 1),
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        assertThat(platform.performedActions)
            .containsExactly(UIAction.ClickNodeAt(150, 210, buttonHint))
            .inOrder()
    }

    @Test
    fun `execute falls back to gesture tap when node action click fails`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val changedSnapshot = snapshotWithSingleButton(label = "Fallback")
        val platform =
            RecordingPlatform(
                actionResults = listOf(
                    ActionResult.Failure("gesture tap failed"),
                    ActionResult.Success()
                ),
                capturedSnapshots = listOf(changedSnapshot)
            )
        val executor = ClickExecutor()

        val outcome =
            executor.execute(
                target = Target.ElementIndex(index = 1),
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        assertThat(platform.performedActions)
            .containsExactly(
                UIAction.ClickNodeAt(150, 210, buttonHint),
                UIAction.TapAt(150, 210)
            )
            .inOrder()
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
    fun `execute still reports success when capture fails`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Success()),
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

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        val success = outcome as ActionOutcome.Success
        assertThat(success.message).contains("Warning: Post-action capture failed")
        assertThat(success.verified).isFalse()
        assertThat(platform.performedActions).containsExactly(UIAction.TapAt(100, 200))
    }

    @Test
    fun `execute fails when all click channels have no observable effect`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Success(), ActionResult.Success()),
                capturedSnapshots = listOf(snapshot, snapshot, snapshot, snapshot)
            )
        val executor = ClickExecutor()

        val outcome =
            executor.execute(
                target = Target.ElementIndex(index = 1),
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed::class.java)
        val failed = outcome as ActionOutcome.Failed
        assertThat(failed.reason).contains("had no observable effect after all channels")
        assertThat(failed.attemptTrail)
            .containsAtLeast(
                "node_action_click: click via node_action_click had no observable effect",
                "gesture_tap: click via gesture_tap had no observable effect"
            )
        assertThat(platform.performedActions)
            .containsExactly(
                UIAction.ClickNodeAt(150, 210, buttonHint),
                UIAction.TapAt(150, 210)
            )
            .inOrder()
    }

    @Test
    fun `execute allows delayed post-action change without warning`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val changedSnapshot = snapshotWithSingleButton(label = "Pressed")
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Success()),
                capturedSnapshots = listOf(snapshot, changedSnapshot)
            )
        val executor = ClickExecutor()

        val outcome =
            executor.execute(
                target = Target.Coordinate(x = 100, y = 200),
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        val success = outcome as ActionOutcome.Success
        assertThat(success.verified).isTrue()
        assertThat(success.message).doesNotContain("No observable")
    }

    @Test
    fun `execute promotes text target to containing clickable row`() = runTest {
        val snapshot = snapshotWithClickableRow()
        val changedSnapshot = snapshotWithClickableRow(rowLabel = "Opened")
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Success()),
                capturedSnapshots = listOf(changedSnapshot)
            )
        val executor = ClickExecutor()

        val outcome =
            executor.execute(
                target = Target.Text(text = "task.html", textIndex = 0),
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        assertThat(platform.performedActions)
            .containsExactly(
                UIAction.ClickNodeAt(
                    540,
                    763,
                    SemanticTargetHint(
                        resourceId = "item_root",
                        text = "task.html | 03:33, 2.23 kB, HTML document",
                        description = "",
                        className = "android.widget.LinearLayout",
                        bounds = Bounds(0, 667, 1080, 859)
                    )
                )
            )
            .inOrder()
    }

    @Test
    fun `execute falls back when node click succeeds but has no observable effect`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val changedSnapshot = snapshotWithSingleButton(label = "Tapped")
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Success(), ActionResult.Success()),
                capturedSnapshots = listOf(snapshot, snapshot, snapshot, changedSnapshot)
            )
        val executor = ClickExecutor()

        val outcome =
            executor.execute(
                target = Target.ElementIndex(index = 1),
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        assertThat(platform.performedActions)
            .containsExactly(
                UIAction.ClickNodeAt(150, 210, buttonHint),
                UIAction.TapAt(150, 210)
            )
            .inOrder()
    }

    @Test
    fun `execute promotes element index target to containing clickable row`() = runTest {
        val snapshot = snapshotWithClickableRow()
        val changedSnapshot = snapshotWithClickableRow(rowLabel = "Opened")
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Success()),
                capturedSnapshots = listOf(changedSnapshot)
            )
        val executor = ClickExecutor()

        val outcome =
            executor.execute(
                target = Target.ElementIndex(index = 18),
                snapshot = snapshot,
                platform = platform,
                isCancelled = { false }
            )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        assertThat(platform.performedActions)
            .containsExactly(
                UIAction.ClickNodeAt(
                    540,
                    763,
                    SemanticTargetHint(
                        resourceId = "item_root",
                        text = "task.html | 03:33, 2.23 kB, HTML document",
                        description = "",
                        className = "android.widget.LinearLayout",
                        bounds = Bounds(0, 667, 1080, 859)
                    )
                )
            )
            .inOrder()
    }

    @Test
    fun `execute fails when gesture tap fails for coordinate target`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Failure("dispatch failed")),
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
        assertThat(failed.reason).contains("dispatch failed")
        assertThat(platform.performedActions).containsExactly(UIAction.TapAt(100, 200))
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
                        isLongClickable = false,
                        bounds = bounds,
                        center = Point(bounds.centerX, bounds.centerY)
                    )
                )
        )
    }

    private fun snapshotWithClickableRow(
        rowLabel: String = "task.html | 03:33, 2.23 kB, HTML document"
    ): ScreenSnapshot {
        val rowBounds = Bounds(left = 0, top = 667, right = 1080, bottom = 859)
        val titleBounds = Bounds(left = 189, top = 706, right = 364, bottom = 763)
        return ScreenSnapshot(
            timestamp = 1L,
            elements = listOf(
                PerceptionElement(
                    index = 17,
                    text = rowLabel,
                    resourceId = "item_root",
                    className = "android.widget.LinearLayout",
                    description = "",
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isEnabled = true,
                    isFocused = false,
                    isLongClickable = false,
                    bounds = rowBounds,
                    center = Point(rowBounds.centerX, rowBounds.centerY)
                ),
                PerceptionElement(
                    index = 18,
                    text = "task.html",
                    resourceId = "title",
                    className = "android.widget.TextView",
                    description = "",
                    isClickable = false,
                    isEditable = false,
                    isScrollable = false,
                    isEnabled = true,
                    isFocused = false,
                    isLongClickable = false,
                    bounds = titleBounds,
                    center = Point(titleBounds.centerX, titleBounds.centerY)
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
