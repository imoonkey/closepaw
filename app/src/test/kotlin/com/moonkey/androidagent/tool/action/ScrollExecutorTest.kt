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
