package ai.closepaw.tool.action

import com.google.common.truth.Truth.assertThat
import ai.closepaw.model.Bounds
import ai.closepaw.model.PerceptionElement
import ai.closepaw.model.Point
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.AppInfo
import ai.closepaw.platform.DisplayInfo
import ai.closepaw.platform.SemanticTargetHint
import ai.closepaw.platform.UIAction
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
    fun `execute succeeds unverified when all click channels have no observable effect`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Success()),
                capturedSnapshots = listOf(snapshot, snapshot, snapshot)
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
        val success = outcome as ActionOutcome.Success
        assertThat(success.verified).isFalse()
        assertThat(success.message).contains("No observable")
        // Only first channel tried — no fallback on unchanged
        assertThat(platform.performedActions)
            .containsExactly(
                UIAction.ClickNodeAt(150, 210, buttonHint)
            )
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
    fun `execute picks single actionable child hotspot instead of container center`() = runTest {
        val snapshot = snapshotWithRowAndSingleClickableChild()
        val changedSnapshot = snapshotWithRowAndSingleClickableChild(childLabel = "Opened")
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
        // Should click the icon child (94, 763) not the container center (540, 763)
        val clickAction = platform.performedActions.first() as UIAction.ClickNodeAt
        assertThat(clickAction.x).isEqualTo(94)
        assertThat(clickAction.y).isEqualTo(763)
    }

    @Test
    fun `execute falls back to container when two children are ambiguously close`() = runTest {
        val snapshot = snapshotWithRowAndTwoCloseChildren()
        val changedSnapshot = snapshotWithRowAndTwoCloseChildren(titleLabel = "Opened")
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
        // Should fall back to container center (540, 763), not either child
        val clickAction = platform.performedActions.first() as UIAction.ClickNodeAt
        assertThat(clickAction.x).isEqualTo(540)
        assertThat(clickAction.y).isEqualTo(763)
    }

    @Test
    fun `execute succeeds unverified on first channel when unchanged — no fallback`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val platform =
            RecordingPlatform(
                actionResults = listOf(ActionResult.Success()),
                capturedSnapshots = listOf(snapshot, snapshot, snapshot)
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
        val success = outcome as ActionOutcome.Success
        assertThat(success.verified).isFalse()
        // Only node_click — no fallback to gesture_tap
        assertThat(platform.performedActions)
            .containsExactly(
                UIAction.ClickNodeAt(150, 210, buttonHint)
            )
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
    fun `retargeting to container adds note in success message`() = runTest {
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
        val success = outcome as ActionOutcome.Success
        assertThat(success.message).contains("Retargeted")
    }

    @Test
    fun `retargeting to child adds note in success message`() = runTest {
        val snapshot = snapshotWithRowAndSingleClickableChild()
        val changedSnapshot = snapshotWithRowAndSingleClickableChild(childLabel = "Opened")
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
        val success = outcome as ActionOutcome.Success
        assertThat(success.message).contains("Retargeted")
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

    // ---------- Coordinate-hint normalization (Codex dual target) ----------

    @Test
    fun `execute semantic plus hint inside bounds uses node click first`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val changedSnapshot = snapshotWithSingleButton(label = "Pressed")
        val platform = RecordingPlatform(
            actionResults = listOf(ActionResult.Success()),
            capturedSnapshots = listOf(changedSnapshot)
        )

        val outcome = ClickExecutor().execute(
            target = Target.ElementIndex(index = 1, coordinateHint = Target.Coordinate(150, 210)),
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        assertThat(platform.performedActions)
            .containsExactly(UIAction.ClickNodeAt(150, 210, buttonHint))
            .inOrder()
        val success = outcome as ActionOutcome.Success
        assertThat(success.message).doesNotContain("coordinate fallback")
    }

    @Test
    fun `execute coordinate fallback after semantic miss skips node click and warns`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val changedSnapshot = snapshotWithSingleButton(label = "Tapped")
        val platform = RecordingPlatform(
            actionResults = listOf(ActionResult.Success()),
            capturedSnapshots = listOf(changedSnapshot)
        )

        val outcome = ClickExecutor().execute(
            target = Target.ElementIndex(index = 999, coordinateHint = Target.Coordinate(100, 200)),
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        // ClickNodeAt skipped — semantic miss, no resolved node. Only TapAt.
        assertThat(platform.performedActions).containsExactly(UIAction.TapAt(100, 200))
        val success = outcome as ActionOutcome.Success
        assertThat(success.message).contains("coordinate fallback")
    }

    @Test
    fun `execute semantic plus hint outside bounds fails ambiguous without dispatch`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val platform = RecordingPlatform(
            actionResults = listOf(ActionResult.Success()),
            capturedSnapshots = listOf(snapshot)
        )

        val outcome = ClickExecutor().execute(
            target = Target.ElementIndex(index = 1, coordinateHint = Target.Coordinate(900, 900)),
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed::class.java)
        val failed = outcome as ActionOutcome.Failed
        assertThat(failed.reason).contains("Ambiguous")
        assertThat(platform.performedActions).isEmpty()
    }

    @Test
    fun `execute pure coordinate target is unchanged`() = runTest {
        val snapshot = snapshotWithSingleButton()
        val changedSnapshot = snapshotWithSingleButton(label = "Tapped")
        val platform = RecordingPlatform(
            actionResults = listOf(ActionResult.Success()),
            capturedSnapshots = listOf(changedSnapshot)
        )

        val outcome = ClickExecutor().execute(
            target = Target.Coordinate(x = 100, y = 200),
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        assertThat(platform.performedActions).containsExactly(UIAction.TapAt(100, 200))
        val success = outcome as ActionOutcome.Success
        assertThat(success.message).doesNotContain("coordinate fallback")
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

    /** Row with one clickable icon child — child hotspot should be selected. */
    private fun snapshotWithRowAndSingleClickableChild(
        childLabel: String = "file_icon"
    ): ScreenSnapshot {
        val rowBounds = Bounds(left = 0, top = 667, right = 1080, bottom = 859)
        val titleBounds = Bounds(left = 189, top = 706, right = 364, bottom = 763)
        val iconBounds = Bounds(left = 24, top = 697, right = 164, bottom = 829)
        return ScreenSnapshot(
            timestamp = 1L,
            elements = listOf(
                PerceptionElement(
                    index = 17, text = "task.html | 03:33", resourceId = "item_root",
                    className = "android.widget.LinearLayout", description = "",
                    isClickable = true, isEditable = false, isScrollable = false,
                    isEnabled = true, isFocused = false, isLongClickable = false,
                    bounds = rowBounds, center = Point(rowBounds.centerX, rowBounds.centerY)
                ),
                PerceptionElement(
                    index = 18, text = "task.html", resourceId = "title",
                    className = "android.widget.TextView", description = "",
                    isClickable = false, isEditable = false, isScrollable = false,
                    isEnabled = true, isFocused = false, isLongClickable = false,
                    bounds = titleBounds, center = Point(titleBounds.centerX, titleBounds.centerY)
                ),
                PerceptionElement(
                    index = 19, text = childLabel, resourceId = "icon",
                    className = "android.widget.ImageView", description = "File icon",
                    isClickable = true, isEditable = false, isScrollable = false,
                    isEnabled = true, isFocused = false, isLongClickable = false,
                    bounds = iconBounds, center = Point(iconBounds.centerX, iconBounds.centerY)
                )
            )
        )
    }

    /**
     * Row with two clickable children at similar distance from the title text.
     * Ambiguity guard should fall back to container.
     */
    private fun snapshotWithRowAndTwoCloseChildren(
        titleLabel: String = "task.html"
    ): ScreenSnapshot {
        val rowBounds = Bounds(left = 0, top = 667, right = 1080, bottom = 859)
        val titleBounds = Bounds(left = 350, top = 706, right = 550, bottom = 763)
        // Left child: center ~(94, 763), distance from title center (450, 734) ≈ 358
        val leftChildBounds = Bounds(left = 24, top = 697, right = 164, bottom = 829)
        // Right child: center ~(900, 763), distance from title center (450, 734) ≈ 452
        val rightChildBounds = Bounds(left = 830, top = 697, right = 970, bottom = 829)
        return ScreenSnapshot(
            timestamp = 1L,
            elements = listOf(
                PerceptionElement(
                    index = 17, text = "task.html | 03:33", resourceId = "item_root",
                    className = "android.widget.LinearLayout", description = "",
                    isClickable = true, isEditable = false, isScrollable = false,
                    isEnabled = true, isFocused = false, isLongClickable = false,
                    bounds = rowBounds, center = Point(rowBounds.centerX, rowBounds.centerY)
                ),
                PerceptionElement(
                    index = 18, text = titleLabel, resourceId = "title",
                    className = "android.widget.TextView", description = "",
                    isClickable = false, isEditable = false, isScrollable = false,
                    isEnabled = true, isFocused = false, isLongClickable = false,
                    bounds = titleBounds, center = Point(titleBounds.centerX, titleBounds.centerY)
                ),
                PerceptionElement(
                    index = 19, text = "icon", resourceId = "icon",
                    className = "android.widget.ImageView", description = "File icon",
                    isClickable = true, isEditable = false, isScrollable = false,
                    isEnabled = true, isFocused = false, isLongClickable = false,
                    bounds = leftChildBounds, center = Point(leftChildBounds.centerX, leftChildBounds.centerY)
                ),
                PerceptionElement(
                    index = 20, text = "more", resourceId = "overflow",
                    className = "android.widget.ImageButton", description = "More options",
                    isClickable = true, isEditable = false, isScrollable = false,
                    isEnabled = true, isFocused = false, isLongClickable = false,
                    bounds = rightChildBounds, center = Point(rightChildBounds.centerX, rightChildBounds.centerY)
                )
            )
        )
    }
}

private class RecordingPlatform(
    private val actionResults: List<ActionResult>,
    private val capturedSnapshots: List<ScreenSnapshot>
) : AndroidPlatform {
    override val mode: ai.closepaw.protocol.PlatformMode = ai.closepaw.protocol.PlatformMode.ACCESSIBILITY
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
