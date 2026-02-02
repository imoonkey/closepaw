package com.moonkey.androidagent.tool.handlers

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
import com.moonkey.androidagent.tool.ToolExecutionContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TargetInvocationsTest {

    @Test
    fun `click supports coordinates even when snapshot is null`() = runTest {
        val platform = RecordingAndroidPlatform(results = listOf(ActionResult.Success("ok")))
        val context = TestToolExecutionContext(platform = platform, snapshot = null)
        val params = JSONObject().apply {
            put("x", 12)
            put("y", 34)
        }

        val result = ClickTargetInvocation(params = params, description = "click").execute(context)

        assertThat(result).isInstanceOf(com.moonkey.androidagent.tool.ToolExecutionResult.Success::class.java)
        assertThat(platform.performedActions).containsExactly(UIAction.ClickAt(12, 34))
    }

    @Test
    fun `click attempts selectors in fallback order`() = runTest {
        val snapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(index = 0, resourceId = "com.app:id/one", text = "First"),
                element(index = 1, resourceId = "com.app:id/two", text = "Second"),
                element(index = 2, resourceId = "com.app:id/three", text = "Third")
            )
        )

        val platform = RecordingAndroidPlatform(
            results = listOf(
                ActionResult.Failure("fail 1"),
                ActionResult.Failure("fail 2"),
                ActionResult.Failure("fail 3"),
                ActionResult.Failure("fail 4"),
                ActionResult.Failure("fail 5")
            )
        )
        val context = TestToolExecutionContext(platform = platform, snapshot = snapshot)

        val params = JSONObject().apply {
            put("x1", 0)
            put("y1", 10)
            put("x2", 100)
            put("y2", 110)
            put("x", 5)
            put("y", 6)
            put("resource_id", "com.app:id/two")
            put("resource_id_index", 0)
            put("text", "Third")
            put("text_index", 0)
            put("element_index", 0)
        }

        ClickTargetInvocation(params = params, description = "click").execute(context)

        assertThat(platform.performedActions).containsExactly(
            UIAction.ClickAt(50, 60), // bounds center
            UIAction.ClickAt(5, 6),
            UIAction.Click(1), // resource_id -> element 1
            UIAction.Click(2), // text -> element 2
            UIAction.Click(0)  // element_index
        ).inOrder()
    }

    @Test
    fun `type uses target_text when resource_id mismatches`() = runTest {
        val snapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(index = 0, resourceId = "com.app:id/input", text = "Email"),
                element(index = 1, resourceId = "com.app:id/search", text = "Search")
            )
        )

        val platform = RecordingAndroidPlatform(results = listOf(ActionResult.Success("typed")))
        val context = TestToolExecutionContext(platform = platform, snapshot = snapshot)

        val params = JSONObject().apply {
            put("text", "hello")
            put("resource_id", "com.app:id/input")
            put("resource_id_index", 0)
            put("target_text", "Search")
            put("target_text_index", 0)
        }

        val result = TypeTargetInvocation(params = params, description = "type").execute(context)

        assertThat(result).isInstanceOf(com.moonkey.androidagent.tool.ToolExecutionResult.Success::class.java)
        assertThat(platform.performedActions).containsExactly(
            UIAction.Type(text = "hello", elementIndex = 1, clear = false)
        )
    }

    private class TestToolExecutionContext(
        override val platform: AndroidPlatform,
        private val snapshot: ScreenSnapshot?
    ) : ToolExecutionContext {
        override val currentSnapshot: ScreenSnapshot? = snapshot
        override fun isCancelled(): Boolean = false
    }

    private class RecordingAndroidPlatform(
        results: List<ActionResult>
    ) : AndroidPlatform {
        private val remainingResults = ArrayDeque(results)

        val performedActions = mutableListOf<UIAction>()

        override suspend fun captureScreen(): ScreenSnapshot {
            return ScreenSnapshot(timestamp = 0L, elements = emptyList())
        }

        override suspend fun performAction(action: UIAction, snapshot: ScreenSnapshot?): ActionResult {
            performedActions.add(action)
            return if (remainingResults.isEmpty()) {
                ActionResult.Success("ok")
            } else {
                remainingResults.removeFirst()
            }
        }

        override fun hasRequiredPermissions(): Boolean = true

        override fun getCurrentPackageName(): String? = "com.example"

        override fun getDisplayInfo(): DisplayInfo = DisplayInfo(1080, 1920, 2f)

        override suspend fun getInstalledApps(): List<AppInfo> = emptyList()

        override suspend fun launchApp(packageName: String): ActionResult = ActionResult.Success()
    }

    private fun element(
        index: Int,
        resourceId: String,
        text: String = "",
        description: String = ""
    ): PerceptionElement {
        return PerceptionElement(
            index = index,
            text = text,
            resourceId = resourceId,
            className = "View",
            description = description,
            isClickable = true,
            isEditable = true,
            isScrollable = false,
            bounds = Bounds(left = 0, top = 0, right = 10, bottom = 10),
            center = Point(x = 5, y = 5)
        )
    }
}

