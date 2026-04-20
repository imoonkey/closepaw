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
import ai.closepaw.platform.UIAction
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScrollExecutorTest {

    @Test
    fun `scroll fails when all channels have no observable effect`() = runTest {
        val snapshot = scrollableSnapshot("Item 1")
        val platform = RecordingScrollPlatform(
            actionResults = listOf(ActionResult.Success(), ActionResult.Success()),
            capturedSnapshots = listOf(snapshot, snapshot, snapshot, snapshot)
        )

        val outcome = ScrollExecutor().execute(
            target = null,
            direction = "down",
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed::class.java)
        val failed = outcome as ActionOutcome.Failed
        assertThat(failed.reason).contains("no observable effect")
        assertThat(failed.attemptTrail)
            .containsAtLeast(
                "a11y_scroll: Scroll down via a11y_scroll had no observable effect",
                "gesture_swipe: Scroll down via gesture_swipe had no observable effect"
            )
        assertThat(platform.performedActions)
            .containsExactly(
                UIAction.ScrollNodeAt(540, 1200, "down"),
                UIAction.Swipe(540, 1200, 540, 240, 300L)
            )
            .inOrder()
    }

    @Test
    fun `scroll with element_index target returns error when target not found`() = runTest {
        val snapshot = scrollableSnapshot("Item 1")
        val platform = RecordingScrollPlatform(
            actionResults = listOf(ActionResult.Success()),
            capturedSnapshots = listOf(snapshot)
        )

        val outcome = ScrollExecutor().execute(
            target = Target.ElementIndex(999),
            direction = "down",
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed::class.java)
        val failed = outcome as ActionOutcome.Failed
        assertThat(failed.reason).contains("not found")
        // Should NOT have performed any scroll action
        assertThat(platform.performedActions).isEmpty()
    }

    @Test
    fun `scroll with text target returns error when target not found`() = runTest {
        val snapshot = scrollableSnapshot("Item 1")
        val platform = RecordingScrollPlatform(
            actionResults = listOf(ActionResult.Success()),
            capturedSnapshots = listOf(snapshot)
        )

        val outcome = ScrollExecutor().execute(
            target = Target.Text("nonexistent text"),
            direction = "down",
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed::class.java)
        val failed = outcome as ActionOutcome.Failed
        assertThat(failed.reason).contains("not found")
        assertThat(platform.performedActions).isEmpty()
    }

    @Test
    fun `scroll with coordinate target falls back to full screen on failure`() = runTest {
        // Coordinate target should still fall back — only element_index/text are "explicit"
        val snapshot = scrollableSnapshot("Item 1")
        val changedSnapshot = scrollableSnapshot("Item 5")
        val platform = RecordingScrollPlatform(
            actionResults = listOf(ActionResult.Success()),
            capturedSnapshots = listOf(snapshot, changedSnapshot)
        )

        val outcome = ScrollExecutor().execute(
            target = Target.Coordinate(9999, 9999),
            direction = "down",
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        // Coordinate targets don't go through element resolution — they resolve directly
        // So this should proceed and attempt scrolling
        assertThat(platform.performedActions).isNotEmpty()
    }

    @Test
    fun `scroll succeeds when content changes`() = runTest {
        val snapshot = scrollableSnapshot("Item 1")
        val changedSnapshot = scrollableSnapshot("Item 5")
        val platform = RecordingScrollPlatform(
            actionResults = listOf(ActionResult.Success()),
            capturedSnapshots = listOf(snapshot, changedSnapshot)
        )

        val outcome = ScrollExecutor().execute(
            target = null,
            direction = "down",
            snapshot = snapshot,
            platform = platform,
            isCancelled = { false }
        )

        assertThat(outcome).isInstanceOf(ActionOutcome.Success::class.java)
        val success = outcome as ActionOutcome.Success
        assertThat(success.verified).isTrue()
        assertThat(success.message).doesNotContain("No observable")
    }

    private fun scrollableSnapshot(label: String): ScreenSnapshot {
        val bounds = Bounds(left = 0, top = 0, right = 1080, bottom = 2400)
        return ScreenSnapshot(
            timestamp = 1L,
            elements = listOf(
                PerceptionElement(
                    index = 1,
                    text = label,
                    resourceId = "list",
                    className = "androidx.recyclerview.widget.RecyclerView",
                    description = "",
                    isClickable = false,
                    isEditable = false,
                    isScrollable = true,
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

private class RecordingScrollPlatform(
    private val actionResults: List<ActionResult>,
    private val capturedSnapshots: List<ScreenSnapshot>
) : AndroidPlatform {
    override val mode: ai.closepaw.protocol.PlatformMode = ai.closepaw.protocol.PlatformMode.ACCESSIBILITY
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
}
